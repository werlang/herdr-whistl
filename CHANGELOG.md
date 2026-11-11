# Changelog

All notable changes to Herdr Whistl. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

Nothing yet.

## [0.4.0] - 2026-11-10

### Added

- **A menu on every agent row.** Overflow button or long press opens Interrupt, Stop, Close and remove, and Rename —
  the same actions the agent's own screen offers, without opening the terminal. Acting from the list does not attach
  the terminal's stream: it takes the machine's lease, sends one command and releases it. Rename is a `whistl.name`
  label, because the core cannot rename a live terminal, and the list shows it over the original name.
- **Tapping a machine opens its spaces.** The Spaces list filters to that machine, with a banner naming it and one tap
  to show every machine again.
- **The app icon**, reproduced as an adaptive vector from the delivered art (IoU 0.947 against the source).

- **Check for updates, and install the new version from the app.** Settings now has an Updates card: it reads the
  newest release, offers the download, and hands the file to the installer. Nothing is installed on trust — the APK
  must hash to the published `sha256` *and* carry exactly the signers of the app that downloaded it, so a tampered
  release page cannot produce an installable file. Any mismatch deletes the download and says which check refused it.
  The file travels to the installer through a `FileProvider` restricted to `cache/updates`, and the system still asks
  the user to allow installs from this app.

## [0.3.5] - 2026-11-10

### Changed

- **The terminal font steps down while the keyboard is open.** With the IME up the pane is roughly half its height and
  a TUI kept drawing the same rows into it, so the text you were reading scrolled off the top. The font is now 85% of
  the chosen size while the keyboard is visible, the cell metrics derive from it, and the pane reports the larger
  column/row count to the core — the TUI reflows into the space instead of the window panning.
- **The arrow bundle reads as one control.** The four segments keep their 48 dp targets; the glyph drops one type
  scale and the separators thin from 1 dp to 0.5 dp.

## [0.3.4] - 2026-11-10

### Added

- **Every installed agent is detected, not just two.** The probe reported `codex`, `gemini` and `aider` and the parser
  threw them away because they were not in a two-entry table. Detection now keeps every name the probe prints
  (labelled and started by its own command), the table names fourteen agents (claude, codex, gemini, agy, opencode,
  cursor-agent, aider, goose, amp, crush, qwen, copilot, droid, cline), and the probe script is generated from that
  table so the two cannot drift. Results are prefixed with `AGENT:`, so a login banner is never read as an agent.

### Fixed

- **The arrow bundle does not collapse after one press.** Collapsing meant reopening it for every line you stepped
  back; it is now a segmented control — four 48 dp segments in one rounded container, separated by hairlines, always
  available.


## [0.3.3] - 2026-11-10

### Fixed

- **The keyboard resizes the terminal.** Opening the keyboard only panned the window: the terminal kept its rows and
  columns and slid up, so a TUI never reflowed. The activity now resizes for the IME and the pane reports the smaller
  size, so the core redraws to fit the space above the keyboard.
- **The arrow bundle is one key in the bar.** It was drawn as four cells at once, which reads as four separate keys.
  Collapsed it shows the bundle's notation; tapping reveals the four arrows, and sending one collapses it again.


## [0.3.2] - 2026-11-10

### Added

- **The arrow keys are one bundle.** Enabling or disabling any of the four arrows enables or disables all of
  them, moving one moves the whole bundle, and Settings shows a single "Arrow keys" entry. In the terminal bar
  the four share one compact cluster instead of four separate buttons.
- Block elements and box drawing are drawn as shapes instead of through a font fallback, so a TUI banner and a
  box border render at the right size: `█ ▀ ▄`, all shades, every light/heavy/double line, rounded corners,
  diagonals and the partial strokes.

### Fixed

- The `opencode` banner no longer renders as thick misaligned boxes, and text that follows a block character in
  the same run is no longer offset by the fallback advance.


## [0.3.1] - 2026-11-10

### Fixed

- **Sending text to an agent no longer crashes.** herdr-core answers `terminal.send` with
  `"started": true`, while the client expected a timestamp (`"started": null`, as the recorded fixture
  has it), so every send that returned a flag died in the decoder with `Failed to parse literal 'true'
  as a long value at path: $.started`. The field now accepts both shapes; only a numeric value is a
  start time.


## [0.3.0] - 2026-11-09

### Added

- **The agent screen is a real terminal.** The framed `terminal.attach` stream is rendered through a VT
  emulator: ANSI 16/256/truecolour, bold/underline/inverse, the cursor, and the alternate screen, so
  `vim` and `htop` work instead of arriving as plain text.
- The pane size follows the window: the size you see is the size herdr-core gets (a `RESIZE` frame on
  the attached channel), never a 1x1.
- Recorded 256-colour and alternate-screen protocol fixtures, plus screenshot coverage for a coloured
  agent screen and one in the alternate buffer.

### Changed

- The terminal is drawn from cells on a Canvas; the text view is gone.

### Known limitations

- `terminal-core` 1.0.8 drops the line above the cursor when the pane shrinks.
- `vim`'s `:q!` sent as a line does not reach herdr-core's command line (`hc send` of a command does).


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

[Unreleased]: https://github.com/werlang/herdr-whistl/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/werlang/herdr-whistl/compare/v0.3.5...v0.4.0
[0.3.4]: https://github.com/werlang/herdr-whistl/compare/v0.3.3...v0.3.4
[0.3.3]: https://github.com/werlang/herdr-whistl/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/werlang/herdr-whistl/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/werlang/herdr-whistl/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/werlang/herdr-whistl/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/werlang/herdr-whistl/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/werlang/herdr-whistl/releases/tag/v0.1.0
