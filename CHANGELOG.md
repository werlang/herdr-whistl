# Changelog

All notable changes to Herdr Whistl. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

Nothing yet.

## [0.2.0] - 2026-11-09

### Added

- **Edit machine**: change a machine's name, host, user or port from its card menu. Changing the
  name touches nothing; changing user or port keeps the pinned host key and forces a reconnect;
  changing the host invalidates the pin and asks for the fingerprint again.
- The machine card says **why** a connection failed: unreachable, connection refused, login
  refused, host key rejected, or herdr-core missing for that login.
- Agent terminal output is exposed to screen readers line by line.
- New screenshot goldens for the states the matrix added: empty and missing space detail, an
  unresolved `~` folder, machine issues, and settings at 1.3× in Portuguese.

### Fixed

- **The app could not connect on Android 13 and newer.** sshj resolves its crypto through a
  provider named `BC`; from API 33 the app relied on the platform's stripped BouncyCastle, which has
  no X25519 key-pair generator, so key exchange failed before authentication. The bundled provider
  is now registered on every API level; `minSdk` stays 26.
- **Agents disappeared after reopening the app.** The events snapshot decoded `status` as a string
  while herdr-core sends `{app, state}`, so the whole snapshot was dropped.
- A superseded connection attempt no longer applies its result to the card.
- A failed `~` resolution is no longer remembered as "no home" for the life of the session.
- Ctrl latched on the shortcut bar no longer leaks into `Send`; with an empty field and Ctrl armed,
  the next typed character is sent as Ctrl+`<char>`.
- An agent on a machine that drops is reported as offline, not finished.
- New space and Rename say why they did nothing when the machine dropped.
- Removed dead code from the phase-6 list and corrected stale comments on shipped screens.

## [0.1.0] - 2026-11-08

### Added

- First public build. Machines over SSH (key or password) with trust-on-first-use host keys,
  Spaces grouped per machine with pinning and ordering, a live Agents list with filters and a
  blocked badge, a tmux-style agent terminal with a sticky-Ctrl shortcut bar, and Settings for
  language (System/English/Português), terminal font size, theme and shortcut keys.
- herdr-core 0.1.18 protocol client with recorded conformance fixtures, Ed25519 keys held in the
  Android Keystore, no telemetry and no network traffic other than SSH to hosts you add.

[Unreleased]: https://github.com/werlang/herdr-whistl/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/werlang/herdr-whistl/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/werlang/herdr-whistl/releases/tag/v0.1.0
