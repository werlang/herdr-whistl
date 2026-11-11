# Changelog

All notable changes to Herdr Whistl. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

Nothing yet.

## [0.5.8] - 2026-11-11

### Fixed

- **Backspace now erases text that was not typed on the phone.** The invisible keyboard field was empty, and a text field
  with nothing in it gives the IME nothing to delete: pressing backspace reported nothing, so the key was lost. The field
  now always keeps one zero-width character, so every backspace deletes something and reaches the terminal — which is what
  a prompt, a paste or anything already on the line needed.
- **A drag never fires a key.** An arrow sent on touch-down, so sliding a thumb along the bar pressed whatever it crossed.
  A tap now sends its key on release, and a drag sends nothing.
- **A single tap is never a repeat.** An arrow held for 400 ms starts repeating every 120 ms; shorter than that it is one
  key.

### Changed

- **The message line is gone.** What was sent, or why input is off, now shows for two seconds in the status bar, in place
  of the status, which comes back on its own; a line of chrome above the keys cost a row of terminal all the time.
- **Paste is a bar key.** It scrolls with the other keys and can be added or removed in Settings, like any of them.
- **Narrower arrows.** Each arrow cell is 40 dp wide instead of 48 dp, so the bundle takes less of the bar.

## [0.5.7] - 2026-11-11

### Added

- **An arrow keeps sending while it is held.** One tap is one key, as before; holding walks the cursor back through a
  long line instead of needing one tap per column.
- **A drag pages a full-screen program.** With no lines above its screen there is nothing to scroll, so the pane has
  nothing to move — a drag now sends `pageup`/`pagedown` to the program, which scrolls its own view.

### Fixed

- **Ctrl works on the phone's keyboard.** The bar's Ctrl latch only reached bar keys, so Ctrl then `c` typed on the
  soft keyboard sent a plain `c`. The latch now combines with the next typed letter: `ctrl+c`, measured as SIGINT on
  the machine.
- **Shift produces uppercase.** The core lowercases a bare uppercase letter, so `A` arrived as `a`; it now travels as
  `shift+a`. Measured on hc 0.1.19: `shift+a` reaches the program as `A`.
- **A freshly opened terminal scrolls.** The core keeps the scrollback, but the app never asked for it, so a terminal
  opened now had nothing above its screen; the stream now attaches with the core's history.

## [0.5.6] - 2026-11-11

### Fixed

- **Every key arrived as if Enter had been pressed with it.** The core's `terminal.send` pastes text **and then submits it
  with Enter** — its own schema says so — so sending one character per keystroke pressed Enter once per character. Keys
  now travel as `terminal.keys` names (`a`, `enter`, `tab`, `space`, `backspace`, `esc`), which press the key and nothing
  else. Measured on hc 0.1.19: `keys a` + `keys b` + `keys enter` reaches the program as `ab\n`; `send a` + `send b`
  reaches it as `a\nb\n`.
- **A character the core cannot press is reported, not sent.** `terminal.keys` takes named keys and a single printable
  ASCII character; an accent or an emoji has no key name, and sending it as text would submit the line, so it is dropped
  with a notice instead of corrupting what was typed.

## [0.5.5] - 2026-11-11

### Changed

- **Every key goes to the terminal as it is pressed**, the way a keyboard does on a computer: no buffer, no waiting for
  Enter, no timer. The terminal's own line editor — or the TUI — decides what a character means and echoes it.

### Fixed

- **The terminal scrolls, and stays where you put it.** The view jumped to the bottom on every frame, and a TUI emits
  frames constantly (cursor, spinner), so scrolling up was undone immediately. Following the output now happens only
  while the view is already at the bottom.

## [0.5.4] - 2026-11-11

### Changed

- **A line is sent by the user, not by a timer.** Typing collects the line and nothing reaches the terminal until Enter
  is pressed; the line being typed shows above the keys, with "Enter sends" beside it. Backspace edits that line first
  and only then deletes on the terminal, a key from the bar sends the line before the key, and a paste lands at once.
  The 40 ms flush of 0.5.3 is gone: guessing when a person is done typing is not a rule that survives contact with a
  TUI.

## [0.5.3] - 2026-11-11

### Fixed

- **Typing sent one command per key.** Every character was its own `terminal.send`, and a TUI redrew between them: in
  opencode the line stuttered and the agent answered keystroke by keystroke. A burst of typing now goes as one command
  after a 40 ms pause; Enter sends what came before it and the carriage return, in order, without waiting.

## [0.5.2] - 2026-11-11

### Fixed

- **The folder picker could not read the home folder.** `cd -- "$1"` with the path `~` looks for a directory literally
  named `~`, because a shell does not expand a tilde that is already inside a variable — the picker opened on "~" and
  said it could not be read. The home comes from `printf %s "$HOME"` now, and a typed `~/…` path is expanded the same
  way before it is listed. Reproduced and verified against the machine: `cd -- "~"` fails, `printf %s "$HOME"` does not.
- **The keyboard never came up in the agent.** The invisible field was enabled only while there was text to send, which
  is never, so it could not take focus; the paste button had the same gate. Both follow the terminal's ability to take
  typing now, and the IME is asked for one frame after the focus lands instead of in the same frame.
- **The terminal did not scroll.** A tap handler added for the keyboard sat in front of the scroll: it ate the drag.
  It is gone; the keyboard has its own button.
- **"Choose a folder" could do nothing** when the New space sheet had not emitted its state yet: the machine now comes
  from the machines themselves, and a picker with no machine says why instead of staying silent.

## [0.5.1] - 2026-11-11

### Added

- **Browse the machine's folders when creating a space.** The folder field has a browse button: the picker opens at the
  machine's home, walks down into folders and up again, and "Use this folder" fills the field. The path still can be
  typed. Reading a folder is `machine.exec` with a constant argv and the path as a positional argument, so a folder
  name is never part of a command.
- **A pinned agent looks pinned**: a pin next to its name, in the list and inside its space.

### Changed

- **The order of the agents list** is now: what needs you first (blocked or errored), then by the last change, with
  finished agents last — inside the pinned group and inside the rest, which are still two separate groups.
- **The keyboard's row**: show/hide the keyboard is the first icon, paste is the second, then the shortcut keys. Tapping
  the terminal takes the keyboard too.
- **Closing an agent asks in the app's standard confirmation sheet** — a title naming the agent, one line saying what
  happens, Cancel and an error-coloured button — instead of a second line inside the action sheet.

### Fixed

- "No spaces yet" sits under the machines now, not above them.
- The goldens' uniqueness guard ignores the `._` files a Mac copy brings along.

## [0.5.0] - 2026-11-11

### Added

- **The terminal takes the keyboard directly.** The text field and the Send button are gone: what you type goes to the
  agent as you type it, the way a terminal works. Deletions travel as DELETE, Enter as a carriage return, and the
  terminal draws the line. A **paste button** sends the phone's clipboard at the cursor.
- **Pin an agent** from its row menu: a pinned agent sorts first in the list and in its space. It is a device
  preference, nothing is sent to the core.

### Changed

- **One set of actions everywhere.** Every agent row — in the Agents list and inside a space — has the same menu:
  Interrupt, Close & Remove, Rename, Pin. "Stop" is gone: closing already stops, and two ways to end a process invited
  the wrong tap. Removing asks once, inside the sheet. Long press is gone; the overflow button is the only way in.
- **The app mark sits in every header**, as a small rounded square to the left of the title.

### Fixed

- The footer no longer disappears on a space's detail.
- A space filtered from a machine shows "No spaces yet" under that machine's name, where it belongs.
- The agent card's odd top padding, caused by the menu button being bottom-aligned inside the name row.
- CI now renders the goldens only when a push touches UI files.

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

[Unreleased]: https://github.com/werlang/herdr-whistl/compare/v0.5.8...HEAD
[0.5.8]: https://github.com/werlang/herdr-whistl/compare/v0.5.7...v0.5.8
[0.5.7]: https://github.com/werlang/herdr-whistl/compare/v0.5.6...v0.5.7
[0.5.6]: https://github.com/werlang/herdr-whistl/compare/v0.5.5...v0.5.6
[0.5.5]: https://github.com/werlang/herdr-whistl/compare/v0.5.4...v0.5.5
[0.5.4]: https://github.com/werlang/herdr-whistl/compare/v0.5.3...v0.5.4
[0.5.3]: https://github.com/werlang/herdr-whistl/compare/v0.5.2...v0.5.3
[0.5.2]: https://github.com/werlang/herdr-whistl/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/werlang/herdr-whistl/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/werlang/herdr-whistl/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/werlang/herdr-whistl/compare/v0.3.5...v0.4.0
[0.3.4]: https://github.com/werlang/herdr-whistl/compare/v0.3.3...v0.3.4
[0.3.3]: https://github.com/werlang/herdr-whistl/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/werlang/herdr-whistl/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/werlang/herdr-whistl/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/werlang/herdr-whistl/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/werlang/herdr-whistl/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/werlang/herdr-whistl/releases/tag/v0.1.0
