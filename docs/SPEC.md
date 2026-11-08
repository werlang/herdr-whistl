# Herdr Whistl — product and technical spec

A minimalist Android app to coordinate coding agents running in
[Herdr](https://herdr.dev)'s herdr-core (`hc`) on your own machines. Community
app, not affiliated with Herdr. No server, no account, no telemetry: the phone
talks straight to each machine over SSH.

Interactive prototype (source of truth for layout and flows):
https://claude.ai/artifact/GErDLM34YhtZjk5vTV9j3a

## 1. Concepts

| Concept | What it is | Where it lives |
|---|---|---|
| **Machine** | A computer reachable over SSH that runs herdr-core. | App storage (connection settings, pinned host key, credentials encrypted). |
| **Space** | A named folder on one machine where agents work. Pure app abstraction. | A JSON document named `whistl` in **that machine's** core (`doc.get` / `doc.set`), cached locally. |
| **Agent** | A terminal on a machine's core, started in the space's folder. | The core. Membership is the terminal label `whistl.space=<space id>`. |

Agents started elsewhere (e.g. from Herdr desktop) with no `whistl.space`
label are matched to a space on the same machine whose folder equals the
terminal's `cwd`; otherwise they show under "No space" in the Agents tab.

## 2. Screens (see prototype)

Bottom navigation, 4 destinations: **Machines, Spaces, Agents, Settings**.
The Agents destination shows a badge with the count of agents in `blocked`.

1. **Machines** — cards: name, status pill (connected / connecting / offline /
   error), `user@host[:port]`, `N agents · hc <version>`, installed agents.
   FAB/+ opens **Add machine**: host, user, port (default 22), display name
   (default: first host label), auth = SSH key (default) | password.
   SSH key mode shows this device's public key with a Copy button and the
   hint "add it to ~/.ssh/authorized_keys". Connect → first-connection
   host-key confirmation (algorithm + SHA256 fingerprint) → Trust and connect →
   detection of installed agents. Card menu: Edit machine, Reconnect, Remove
   (confirm; spaces of that machine leave the app, agents keep running there).
   Reconnect restarts the session: a connected machine's session is closed and
   connected again (connecting → connected); an offline machine makes one
   immediate attempt (phase 7 r5).
   **Edit machine** (phase 7 P2) opens the Add machine sheet prefilled with the
   stored name, host, user, port and login method, titled "Edit machine", with
   Save in place of Connect. The saved password is never shown: a blank password
   field keeps the saved one. Save is enabled only when a field differs from the
   stored machine, and disabled again when it is typed back. Four rules, as
   implemented in `MachinesRepository.edit`:
   - **R1, name only:** stored in place. The pinned host key and the live session
     are not touched.
   - **R2, user, port, login method or a new password:** the pinned host key is
     kept (same host, same key). The session closes and reconnects with the new
     settings when the machine is held. A machine waiting for a host-key decision,
     or blocked by a changed key, keeps that state until the user decides. A port
     change is the exception: the new port presents its own key, so the connection
     starts again and that key is compared with the pin. A pending key is asked
     again, a changed key is blocked again, and nothing is re-pinned (phase 7 r8).
   - **R3, host:** the pinned host key is forgotten and the session closes. The
     next connection goes through first-connection confirmation again, with the
     new host's fingerprint. The old host's fingerprint is never compared with, or
     saved for, the new host. A changed-key block is cleared, since its key was the
     old host's. Cancelling the confirmation leaves the new host with no pin and no
     session, in the host-key state until the user trusts the key or removes the
     machine.
   - **R4, identity:** the machine keeps its id and the machine count is unchanged,
     so its spaces (keyed by machine id) stay listed under it.
2. **Spaces** — grouped by machine, pinned first, then user order. Row: name,
   status dots per agent, "waiting" badge if any agent is blocked,
   `N agents · folder`. Offline machine → group subtitle "offline", rows dimmed.
   + opens **New space**: pick an added machine, folder (default `~`), name
   (default: last folder segment). Row menu: Pin/Unpin, Move up, Move down,
   Rename, Close space (confirm; kills its agents).
3. **Space detail** — header `name` + `machine:folder`; agent rows (name, id,
   status pill on its own line); FAB **New agent** (disabled when offline) →
   **picker only**: the agents installed on that machine + "Terminal" (the
   machine's shell). No task text box. Tapping an option starts it and opens it.
4. **Agents** — every agent on connected machines; filter chips All / Needs
   you / Working / Idle with counts; sorted blocked → working → done → idle.
5. **Agent** — a tmux-like **terminal only**, no chat UI: full-height monospace
   screen, a one-line status bar `[machine] space · agent    status`, then a
   horizontally scrolling **shortcut key bar**, then a text field + Send.
   Typed text is sent to the terminal (Send = text + Enter). `Ctrl` is a
   sticky modifier: armed, the next bar key is sent with Ctrl (`ctrl+<key>`).
   Typing only inserts and keeps Ctrl armed. Send never applies Ctrl: the text
   goes out plainly and clears the latch (phase 7 r5 amendment U3). Menu:
   Interrupt (Ctrl+C), Stop and remove. While the machine has no connection
   (offline or connecting), the screen keeps the agent's last known status and
   space and its status bar says `offline`; it says `finished` only when the
   core reported the program finished (phase 7 r7).
6. **Settings** — General: Language (System / English / Português), terminal
   font size (S/M/L), theme (System / Dark / Light). Shortcut keys: list in bar
   order with move up / move down / remove, "Add key" sheet listing the keys
   not in the bar, "Restore defaults". About: name, version, one-line privacy
   statement, open-source licenses.

Available shortcut keys: Esc, Tab, Ctrl (modifier), ↑ ↓ ← →, Enter, ^C, ^D,
Home, End, PgUp, PgDn, `/`, `|`, `~`, `-`. Defaults: Esc, Tab, Ctrl, ↑, ↓, ←, →, Enter.

## 3. Design system

Native Material 3 + Compose, adapted to the prototype. Calm, dense, dark-first,
terminal-native. System sans for UI (Roboto); **JetBrains Mono** (bundled in
`app/src/main/res/font/`, OFL) for addresses, paths, keys and the terminal.

Dark tokens (default):

| Token | Hex | Use |
|---|---|---|
| background | `#121417` | screens |
| surface | `#1B1E23` | cards, sheets |
| outline | `#262A30` | card borders, dividers |
| field | `#0D0F11` | inputs, segmented tracks |
| terminal | `#0A0B0C` | terminal screen (always dark, both themes) |
| onSurface | `#E8EAED` | primary text |
| muted | `#A5ABB3` | secondary text |
| dim | `#8A9099` | tertiary text (≥4.5:1 on background) |
| accent | `#5EC2B7` | primary actions, selection, tmux bar |
| onAccent | `#0B1F1D` | text on accent |
| working | `#7FB2FF` | status |
| blocked | `#F5B860` | status, badges |
| done | `#7BD88F` | status, connected |
| idle | `#A5ABB3` | status, offline |
| danger | `#FF8A80` | destructive actions |

Light theme: same roles, values chosen for ≥4.5:1 text contrast on `#F7F8F9`
backgrounds (e.g. accent `#16786F`, blocked `#8F5600`, working `#2459B8`,
done `#24733D`, danger `#B3261E`). The terminal stays dark.

Shape: cards 14dp radius, sheets 22dp top radius, chips/pills fully rounded.
Spacing on a 4dp grid; screen gutter 16dp. Touch targets ≥48dp. Status is
never color-only: always a text label next to the dot. Every icon-only button
has a content description. Dynamic type: layouts must survive 1.3× font scale.
Bottom sheets (ModalBottomSheet) for every create/menu/confirm flow, as in the
prototype. Motion: Material defaults; respect "remove animations".

## 4. Architecture

Single `:app` module, package `io.github.werlang.whistl`, Kotlin, Jetpack
Compose, Material 3, coroutines + Flow, unidirectional data flow.

```
ui/          screens, components, theme, navigation (Navigation Compose)
ui/*/        one ViewModel per screen exposing StateFlow<UiState>; events in, state out
domain/      models (Machine, Space, Agent, AgentStatus, ShortcutKey…) and pure logic
data/        repositories: MachinesRepository, SpacesRepository, AgentsRepository, SettingsRepository
core/rpc/    HcClient: JSON-RPC 2.0 over a line transport
core/ssh/    SshTransport (sshj), HostKeyStore, KeyManager
core/secure/ Android Keystore AES-GCM wrapper for secrets at rest
```

Manual constructor injection through one `AppContainer` (no DI framework).
Every repository depends on interfaces so tests use fakes. A `FakeCore`
(in-memory implementation of the same interfaces, seeded like the prototype)
backs Compose previews, screenshot tests and a debug-only "demo mode".

## 5. Protocol facts (measured against hc 0.1.18; fixtures in `docs/protocol-fixtures/`)

- Transport: SSH exec channel running `hc connect`; JSON-RPC 2.0, **one JSON
  message per line** each way. First call `hello` (result: `core{id,name}`,
  `features`, `generation`, `methods`, `version`). Require `generation == 1`.
- **`events.subscribe` takes over its connection**: after it, that connection
  answers no further calls. Use one SSH session with **two exec channels**: a
  command channel and an events channel.
- `events.subscribe {labels?}` → result `{seq, terminals:[Terminal]}` snapshot,
  then notifications `{"method":"event","params":{type, terminal, seq, data}}`
  with `type` ∈ created, lost, restarted, keep, finished, removed, status,
  turn, title, labels, size, doc; `heartbeat` every 15 s; `missed` + new
  snapshot if the subscriber fell behind. No heartbeat for 40 s ⇒ connection dead.
  A failed subscription on a connected machine is retried on the same connection,
  with a backoff from 1 s doubling to 30 s; the backoff resets only after the
  subscription has stayed up for 30 s. When the machine leaves `Connected`, its
  agents leave the list and a pending retry is cancelled (phase 7 r8).
- `terminal.list {labels?, all?}` → `{terminals, machines, hidden_finished}`.
- `terminal.run {argv, cwd, labels, keep?, no_parent:true, no_notify:true}` →
  Terminal (has `address`). The app always passes `no_parent` and `no_notify`.
- `terminal.read {terminal, lines}` → `{text, complete, how}`: plain text, no
  colour and no cell attributes. Colour and live resize are **not supported in
  v0.1.x**: they need `terminal.attach` (a raw framed stream with cols/rows and
  resize notifications) and a VT emulator, a phase of its own (report F1).
- `terminal.run` has no size parameter in hc 0.1.14 (`rpc.discover`); every
  terminal is created at the core's 200×120. The app cannot size it.
- `terminal.list` and `events.subscribe` decode each terminal entry on its own:
  an entry that does not decode is skipped and counted (class name in the log),
  the others are kept. `status` is a string, or for an agent the object
  `{"app":"claude-code","state":"idle"}`; the app reads its `state` (phase 7
  r5 U4). Fixtures: `events_snapshot_agent_status.json`,
  `terminal_list_agent_status.json`.
- `terminal.send {terminal, text}` pastes text and presses Enter.
- `terminal.keys {terminal, keys:["ctrl+c","up","enter","esc","tab",…]}`.
- `terminal.interrupt`, `terminal.kill`, `terminal.rm`, `terminal.label`.
- `machine.exec {argv}` → `{code, stdout, stderr}` where **stdout/stderr are base64**.
- `doc.get {name}` → `{value, version}` (version 0 = never written).
  `doc.set {name, value, version}` is compare-and-set: a stale version fails
  with `error.data.kind == "conflict"`. Always read–modify–write with retry.
- Errors: JSON-RPC `error{code,message,data{kind,hint}}`; `kind` is stable
  (`not_found`, `conflict`, …). Map kinds to typed exceptions.
- Terminal `status` ∈ idle, working, done, blocked, error, or null for plain
  programs; `state` ∈ live, finished.

Installed agents: herdr-core has no API for it (Herdr desktop does it itself).
Run `machine.exec ["sh","-lc", <script>]` with a known list (claude, codex,
gemini, opencode, aider, …), each checked with `command -v`, at most once a
minute per machine. The list lives in one Kotlin table (`KnownAgents`) with
display name and argv.

The `whistl` document on each machine:

```json
{ "v": 1, "spaces": [ { "id": "sp_<16 hex>", "name": "web-app", "folder": "~/code/web-app", "pinned": true, "order": 0 } ] }
```

Unknown fields are preserved on write (round-trip `JsonObject`).

## 6. Security (non-negotiable)

- SSH via **sshj** with strict host-key checking: trust on first use only
  after the user confirms the fingerprint; a changed key blocks the connection
  with a clear warning (never auto-accept). Pinned keys stored per machine.
- Client key: Ed25519 generated on device. Private key and any saved password
  encrypted at rest with an AES-256-GCM key in the **Android Keystore**
  (non-exportable). Never logged, never in backups
  (`android:allowBackup="false"`, `dataExtractionRules` excluding everything).
- No `INTERNET` traffic except SSH to user-configured hosts. No analytics, no
  crash reporters, no third-party network SDKs.
- Shell injection: never build shell strings from user input. `argv` arrays
  only; the one `sh -lc` script for detection is a constant.
- Validate inputs: host (hostname / IPv4 / IPv6), port 1–65535, user
  (POSIX name chars), space folder (no NUL / newlines), names length-capped.
- Release build: R8 minify + resource shrinking, `debuggable=false`,
  cleartext disabled by network security config, `FLAG_SECURE` optional
  setting is out of scope.
- SSH crypto provider: sshj uses the bundled BouncyCastle, under the name `BC`,
  on every API level (`minSdk` stays 26). It is inserted at position 1, which
  replaces the platform's own `BC`: a stripped copy with no X25519, so sshj's
  key exchange (`curve25519-sha256`) cannot run on it (phase 7 r1c).
- Connection failures are classified by their cause, not their message (see
  `core/ssh/FailureStage.kt`). Only a network failure is "could not reach the
  machine", and it goes offline with a retry. A refused port, a login refusal,
  an unsupported core and a failure inside the app are errors that do not retry
  on their own. A failure inside the app is never shown as "could not reach".
- A missing `hc` on the login (the `hc` channel exits with status 127, "command
  not found") is `CoreMissing`: "herdr-core not found", an error with no automatic
  retry, and the install hint (phase 7 r3). The status is read after a bounded
  wait of 2 s, since it can arrive after the end of the stream (phase 7 r8).
- A failed `printenv HOME` probe for a space is `HomeUnreadable` ("Could not read
  the machine’s home folder just now. It is tried again the next time the space
  is read."): it is not remembered for the connection, so the next read tries again. Only a real empty HOME is "no home", which is kept for the
  connection (phase 7 r4).
- Logs: a `Log` wrapper that is a no-op in release; never log terminal text,
  hosts or credentials. Because release logs nothing, a release build cannot
  diagnose a failure inside the SSH stack: the failure stage the user sees is the
  only signal there. The internal-failure log line holds class names only, in
  debug builds.

## 7. i18n

All user-visible text in `res/values/strings.xml` (English, default) and
`res/values-pt-rBR/strings.xml`; positional args (`%1$s`); `<plurals>` for
counts. Per-app language via `AppCompatDelegate.setApplicationLocales` and
`res/xml/locales_config.xml`. No hard-coded user-visible strings in Kotlin
(lint `HardcodedText` / `SetTextI18n` as errors). Product names (Claude Code,
Codex…) and key glyphs (Esc, ↑) are not translated.

## 8. Quality gates

- `scripts/gradle check assembleDebug` passes (unit tests + Android lint, lint
  warnings as errors for i18n, a11y and security categories).
- Unit tests: domain logic, ViewModels (with fakes), HcClient against the
  recorded fixtures, KnownAgents parsing, input validation, doc
  read–modify–write conflict retry, shortcut key editing.
- Screenshot tests with **Roborazzi** (Robolectric, JVM) for every screen in
  dark and light, en and pt-BR. Goldens in `app/src/test/screenshots/` are
  recorded with `scripts/gradle recordRoborazziDebug` and compared by
  `scripts/gradle check` (and `verifyRoborazziDebug`); a differing frame fails
  the build. Goldens are recorded on macOS and fonts rasterise per platform, so
  the comparison runs on macOS only (CI: the macOS `screenshots` job).
- KDoc on public types and non-obvious functions.

## 9. As built

Implementation choices that this spec does not pin down (tilde resolution, the bundled BouncyCastle
as the SSH provider on every API level, the recorded `terminal.interrupt` fixture, screenshot platform, release signing) are recorded
in `docs/IMPLEMENTATION-NOTES.md`. Contributions follow `CONTRIBUTING.md`.
