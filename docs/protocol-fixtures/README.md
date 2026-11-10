# Protocol fixtures

Recorded from herdr-core, never hand-edited. The `.json` files are request/response pairs (or, where a
request is a frame, `request`/`response` objects describing the frames). `FixtureCoverageTest` checks that every
file here is named by a test.

One exception, and it is only the request: in `exec_installed.json` the `argv[2]` script is **authored by the app**
(it is the probe list from `KnownAgents`), so it is kept in step with that table by hand when the list changes; the
recorded `response` is untouched. The 0.3.5 update added the agents the app now detects.

Framed mode (SPEC §5.1, measured on hc 0.1.14 on the rpi5):

- `hello_framed.json`: `hello` with `{"framed": true}` and its reply (`"framed": true`). The reply is the last
  plain JSON line on the connection; frames start right after its newline.
- `attach_result.json`: `terminal.attach` (sent as a channel-0 frame) and its reply: `{"channel": 1, "terminal": …}`.
- `attach_frames.bin`: the bytes the core sent after the hello reply's newline, exactly as received. It is a
  sequence of frames, each `channel u32 LE | kind u8 | length u32 LE | payload[length]`, with nothing between them.
  Its six frames, by offset: `0` channel 0 kind 0, 670 bytes (the attach reply); `679` channel 1 kind 0, 330 bytes
  (the first paint: `ESC[38;5;1m` red, `ESC[38;5;2m` green, `ESC[38;2;255;100;0m` truecolor, then the cursor
  placed); `1018` channel 1 kind 0, `hi\r\n` (the typed line echoed by the tty); `1031` channel 1 kind 0, 15 bytes
  (`ESC[1;35mhi ESC[0m\r\n`, the line printed in colour by the terminal's program); `1055` and `1064` channel 1
  kind 2 with an empty payload (the core's answer to the two CLOSE frames the capture sent).
- `attach_resize.json`: the RESIZE frame (100×30) and the core's reaction: no frame on the channel, and one `size`
  event on the events stream.
- `attach_window.json`: the CREDIT window. The core sent 262144 bytes of DATA payload before any CREDIT; each CREDIT
  `n` then released `n` more bytes (100000 released 100000); keys sent as DATA were accepted with no CREDIT back.
- `attach_close.json`: the client's detach (CLOSE, empty payload) and the core's empty CLOSE answer; the core's
  CLOSE when the program exits (`reason: "exited"`, `exit_code`) and when it is killed (`reason: "killed"`, `signal`).
- `attach_needs_framed.json`: `terminal.attach` on a line-mode connection, refused with `kind: needs_framed`.
- `frame_kinds.json`: the frames the official client (`hc attach`) sends, read from strace of its socket writes on a
  pty; what the core does with FOCUS; and the colour the core answers OSC 10 with before and after a FACTS frame.

`attach_frames_256.bin` and `attach_frames_altscreen.bin` (phase 8 P1, for the emulator spike) use the same layout as
`attach_frames.bin`: the bytes after the hello reply's newline, as received. `attach_frames_256.bin` is a program that
prints a 16 × 16 grid of background cells in palette indices 0 to 255, then a 32-cell truecolor gradient (`ESC[48;2;…m`),
then waits for input; the first paint and the live `x` echo carry it. `attach_frames_altscreen.bin` is main-screen text,
then htop (ESC[?1049h … ESC[?1049l, the capture quits it with `q`), then more main-screen text. Its 29 frames include both
alternate-screen switches.

Re-record with `tools/framed-capture/` (see the header of each script): run `capture.py` once per mode into a
directory, `pty_strace.py` into `strace.txt`, then `python3 tools/framed-capture/record.py <dir> docs/protocol-fixtures`.
