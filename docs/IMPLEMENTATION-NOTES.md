# Implementation notes (as built)

Where the implementation had to choose something the spec does not pin down. Read this with
`SPEC.md`.

## Tilde and relative folders in spaces

The `whistl` document stores the folder exactly as typed (`~`, `~/code/web-app`, an absolute path).
herdr-core does not expand `~`, so the app resolves it once per machine: it starts a throwaway
terminal with `cwd` set to the folder and `argv = ["pwd"]`, reads the absolute path from its
output, and removes the terminal. The resolved path is cached locally, never written to the
document, and is what agent `cwd`s are matched against. A folder that cannot be resolved shows as
an error on that space row instead of silently landing under "No space".

## SSH crypto provider: bundled BouncyCastle on every API level

Android registers its own provider named `BC`, a stripped copy with no X25519. sshj's key exchange
(`curve25519-sha256`) asks the provider named `BC` for X25519 before it reads the server's host key.
On API 35 that lookup failed (phase 7 r1c, device diagnostic, release-signed debug build):

    NoSuchAlgorithmException: no such algorithm: X25519 for provider BC
      at SecurityUtils.getKeyPairGenerator <- DHBase.<init> <- Curve25519SHA256 <- KeyExchanger.gotKexInit

So the connection stopped before the host-key sheet, and the app showed "could not reach the machine".

The earlier decision (phase 4 decision 2) bundled BouncyCastle only below API 33, because the platform
had Ed25519 from there. That reasoning covered Ed25519 signatures. It missed that sshj also needs X25519
key agreement, which the platform's `BC` lacks at every level. `CryptoProviders.install` now replaces the
platform's `BC` with the bundled BouncyCastle (`bcprov`) at position 1 on every API level and points sshj
at it. `minSdk` stays 26. `bcpkix` stays for the EST code path (see `app/build.gradle.kts`).

Two tests cover this. `CryptoProvidersBehaviourTest` puts a decoy `BC` with no X25519 in front, installs
through the production path, and runs sshj's X25519 lookup and an Ed25519 sign and verify. It fails on the
old code with the message above. R8 is not the cause: the failure reproduces on the non-minified debug build.

A release build cannot diagnose this class of failure, because logging is a no-op there. The failure stage
the user sees (unreachable, refused, login, host key, unsupported core, or failed inside the app) is the
only signal a release user gets. To see the exception, use a debug build.

## `terminal.interrupt` fixture

`docs/protocol-fixtures/interrupt.json` is **recorded** from hc 0.1.18 (herdr-core), not hand-written. The
supervisor recorded it from the live core before phase 4 round 1 and re-recorded it on 2026-11-08 with an
identical result: `{"key":"ctrl+c","outcome":"exited"}`. Its test is the conformance row
`terminal.interrupt | interrupt.json` in `ConformanceCases.kt`, which `ConformanceTest` runs.

To re-record it:

1. Start a probe terminal: `hc run --bg --name probe sleep 300`.
2. Call `terminal.interrupt` on it through the app's connection, with the JSON-RPC logging wrapper on.
3. Save the exact response line over `docs/protocol-fixtures/interrupt.json`, then run
   `scripts/gradle testDebugUnitTest`; the `terminal.interrupt | interrupt.json` row must still pass.

The one synthetic fixture is `event_missed_synthetic.json`: hc 0.1.18 could not be made to emit `missed` on
demand, so both shapes SPEC §5 allows are written by hand. The other event fixtures are recorded. See
`docs/event-shapes.md`.

## Screenshot goldens are macOS-recorded

Robolectric rasterises with the host's fonts, so goldens recorded on macOS do not compare equal on
Linux. CI therefore runs unit tests, lint and both assemblies, and the Roborazzi comparison
(`scripts/gradle check`, which includes `verifyRoborazziDebug`) is a local gate on the machine the
goldens were recorded on. If CI ever needs the screenshots, record them on the CI image and commit
those.

## Release signing

`assembleRelease` produces `app-release-unsigned.apk`. No signing configuration is committed;
`*.jks`, `*.keystore` and `keystore.properties` are git-ignored. Wire your own key to publish.

## FakeCore

`FakeCore` and the seeded demo data live in `src/testDebug` / `src/debug`, are reachable only from
debug builds, and are asserted absent from the release dex in CI and in the phase reports.
