# Contributing

`SPEC.md` is the product and technical spec, `AGENTS.md` and `docs/IMPLEMENTATION-NOTES.md` are the
working rules, and the code follows them — not the other way round. If behaviour has to change,
change the spec in the same pull request.

## Build and gates

```sh
scripts/gradle check assembleDebug        # unit tests + lint + debug APK
scripts/gradle check --rerun-tasks        # same, with the build cache out of the way
scripts/gradle recordRoborazziDebug       # re-record screenshot goldens (the only writer of them)
scripts/gradle assembleRelease            # R8 + resource shrinking
```

`scripts/gradle` uses a native JDK 17 and Android SDK when it finds them and falls back to the
pinned container otherwise. CI runs unit tests, lint and both assemblies on Linux.

## Rules that are not negotiable

- Every user-visible string lives in `res/values/strings.xml` and `res/values-pt-rBR/strings.xml`.
  No literals in Kotlin or layouts. Keep both files key-for-key in sync.
- Never build a shell command string from user input. Pass `argv` arrays. When a value has to be
  resolved on the far machine (a `~` folder), start a process with it as `cwd` and read the output.
- Never log terminal content, hosts or credentials. Use the `Log` wrapper, which is a no-op in
  release.
- Debug-only code must not reach the release artifact: `dexdump` the release APK and check.
- Status is never colour-only; every icon-only button has a content description; touch targets are
  at least 48 dp; layouts must survive a 1.3× font scale.

## Screenshots

Goldens are committed under `app/src/test/screenshots/` as
`<screen>_<theme>_<locale>[_fs13].png`, and the matrix is per screen **and** per state that matters
(empty, offline, host-key unknown/changed, blocked agents…). Record them on macOS — Robolectric
rasterises differently on Linux.

Every test run **compares** against the goldens: `testDebugUnitTest` and `check` set
`roborazzi.test.verify` (app/build.gradle.kts), so a golden that differs fails the run and nothing is
rewritten. Only `recordRoborazziDebug` writes goldens. Each screenshot test also checks that its
frame shows the screen's own text and is not a flat tint (at least 64 distinct colours and a
luminance spread of 5).

CI skips the screenshot classes on Linux (`-Pwhistl.screenshots=skip`, comment in
`.github/workflows/ci.yml`); the macOS `screenshots` job runs `verifyRoborazziDebug`. Adding or
changing a screen means re-recording and committing the goldens in the same pull request.

## Cutting a release

1. Bump `version.properties` (`versionName` only — `versionCode` is derived: MAJOR*10000 +
   MINOR*100 + PATCH).
2. Move the `Unreleased` entries in `CHANGELOG.md` into a new `## [x.y.z] - YYYY-MM-DD` section.
3. Commit, open the PR, wait for `build` and `screenshots`.
4. Tag and push: `git tag -a vX.Y.Z -m "..." && git push origin vX.Y.Z`. The `Release` workflow
   then runs the tests and lint, builds the release APK on Linux, verifies the goldens on macOS,
   and publishes a GitHub release with the **unsigned** APK and its SHA-256.
5. Sign locally and upload, because the keystore never enters GitHub:

   ```sh
   scripts/sign-release.sh dist/app-release-unsigned.apk herdr-whistl-X.Y.Z.apk
   gh release upload vX.Y.Z herdr-whistl-X.Y.Z.apk --repo werlang/herdr-whistl
   ```

Bumping `versionName` changes the string the About panel renders, so the goldens that contain it
must be re-recorded in the same commit, on macOS.

## Commits and pull requests

Conventional Commits, one logical change per commit. Branch from `main` as `<type>/<slug>`. A pull
request states what changed on the user-visible surface, the gates you ran with their exit codes,
and what you did not verify. Protocol fixtures are recorded, never hand-edited.
