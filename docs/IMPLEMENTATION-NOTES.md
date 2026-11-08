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

## Ed25519 below API 33

Android only gains Ed25519 in its platform crypto at API 33. To keep `minSdk 26`, BouncyCastle
(`bcprov` + `bcpkix`) is bundled and registered as sshj's provider on API < 33 only. The provider
is reached from release code paths after R8: `dexdump` on the release APK shows it, and a unit test
asserts the API-26 registration path.

## `terminal.interrupt` fixture

`docs/protocol-fixtures/` holds responses recorded from herdr-core 0.1.18, except
`interrupt_synthetic.json`: `hc` does not expose the raw JSON-RPC response for `terminal.interrupt`
in a way that can be captured from the CLI, so that fixture was written from the method's shape and
its test is named `…Synthetic…`. Re-record it before trusting it as protocol truth:

1. Start a terminal through the app (or `hc run --bg --name probe sleep 300`).
2. Call `terminal.interrupt` over the app's own connection with the JSON-RPC logging wrapper on.
3. Save the exact response line as `docs/protocol-fixtures/interrupt.json`, delete the synthetic
   file, and rename the test.

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
