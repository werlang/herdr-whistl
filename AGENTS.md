# Herdr Whistl

Android app (Kotlin, Jetpack Compose) to coordinate Herdr agents on your own
machines over SSH. The product and technical spec is `docs/SPEC.md`; read it
before changing behaviour, and keep it in sync with the code.

## Build

Always build through `scripts/gradle`:

```sh
scripts/gradle check assembleDebug     # unit tests + lint + debug APK
scripts/gradle verifyRoborazziDebug     # compares screenshot goldens in app/src/test/screenshots/ (check does the same)
scripts/gradle recordRoborazziDebug     # rewrites those goldens; run it on macOS only
```

It uses a native JDK 17 + Android SDK when it finds one (Homebrew:
`brew install openjdk@17 && brew install --cask android-commandlinetools`,
then `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`).
Otherwise, or with `WHISTL_DOCKER=1`, it runs in the pinned container from
`docker/android-build/` (needs ~4 GB free in Docker; on Apple silicon it runs
under amd64 emulation, because Android's aapt2 has no linux/arm64 build).

## Rules

- All user-visible text lives in `strings.xml` (`values/` = English,
  `values-pt-rBR/` = Portuguese). No hard-coded UI strings.
- Never build shell command strings from user input; pass `argv` arrays.
- Never log terminal content, hosts or credentials.
- `docs/protocol-fixtures/` are recorded herdr-core responses used by tests;
  re-record them rather than hand-editing.
