# Herdr Whistl

A minimalist Android app to coordinate coding agents running in
[Herdr](https://herdr.dev)'s herdr-core on your own machines.

- Add machines you reach over SSH (a server, a Raspberry Pi, your Mac).
- Group work into spaces: a folder on a machine.
- Start any agent installed there, or a plain terminal, and drive it from a
  tmux-style terminal with shortcut keys.
- No server, no account, no telemetry. The phone talks straight to each
  machine's herdr-core over SSH.

Community project, not affiliated with Herdr.

## Requirements

On each machine: SSH access and herdr-core (`hc`) installed.

## Build

See `AGENTS.md`. Short version: `scripts/gradle check assembleDebug` (needs Docker).

## License

Terminal font: JetBrains Mono, SIL Open Font License 1.1 (`docs/JetBrainsMono-OFL.txt`).
