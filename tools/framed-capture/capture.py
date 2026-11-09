# Captures framed-mode traffic from herdr-core on the rpi5 (SPEC §5, phase 8 P0). The app's own transport is
# `ssh <host> hc connect`, so this drives `hc connect` over stdin/stdout and logs every byte both ways.
#
# Run it from the WSL box, with the terminal name of a terminal you created:
#   ssh <root@rpi5-host> python3 - <mode> <name> create < tools/framed-capture/capture.py > <dir>/<mode>.jsonl
# (`create` makes the terminal first, with the program for <mode>; drop it to attach to an existing terminal.)
# Modes: palette (256 colours and a truecolor gradient), altscreen (htop, quit with q), line (terminal.attach without a framed hello: needs_framed), live (colour, live key, resize, focus, detach), fixture (colour first paint and a typed line), exit (the program
# exits while attached), kill (`hc kill` while attached), modes (title, alternate screen, mouse, focus, cursor query),
# focus (focus frames while DECSET 1004 is on), flood (CREDIT window), paste (300 KB of keys as DATA).
# Output: one JSON object per line on stdout: {"t", "dir": "in"|"out", "hex"} for each byte chunk, {"t", "frame": {...}} for
# each frame parsed, {"t", "note"} for marks, {"t", "event": <hc events line>} for the events of that terminal.
# Nothing is written to disk on the rpi5. record.py turns the captures into docs/protocol-fixtures/.
import json, select, struct, subprocess, sys, threading, time

MODE, TERMINAL = sys.argv[1], sys.argv[2]
SCRIPTS = {
    "exit": "sleep 2; printf 'bye\\n'; sleep 3; exit 7",
    "modes": ("sleep 1; printf '\\033]0;p8r1-title\\007'; sleep 1; printf '\\033[?1049h'; sleep 1; "
              "printf '\\033[?1000h\\033[?1004h'; sleep 1; printf '\\033[6n'; sleep 1; "
              "printf '\\033[?1049l\\033[?1004l'; exec cat"),
    "focus": "printf '\\033[?1004h'; exec cat",
    "flood": "sleep 1; yes 0123456789abcdef | head -n 75000; exec cat",
    "paste": "exec cat > /dev/null",
    "kill": "exec sleep 60",
    # 16 x 16 palette cells (indices 0-255), then a 32-cell truecolor gradient; the program then waits for input.
    "palette": r"""i=0; while [ $i -lt 256 ]; do printf '\033[48;5;%dm  ' $i; i=$((i+1)); if [ $((i % 16)) -eq 0 ]; then printf '\033[0m\n'; fi; done; i=0; while [ $i -lt 32 ]; do printf '\033[48;2;%d;%d;%dm  ' $((i*8)) $((255-i*8)) 128; i=$((i+1)); done; printf '\033[0m\n'; exec cat""",
    # Main-screen text, then htop (alternate screen: ESC[?1049h), which the capture quits with `q` (ESC[?1049l).
    "altscreen": "printf 'main screen before\\n'; sleep 1; htop; printf 'main screen after\\n'; exec cat",
    # Asks for the foreground colour twice: before and after a FACTS frame; the core's answers are echoed as DATA.
    "facts": "sleep 1; printf '\\033]10;?\\033\\\\'; sleep 3; printf '\\033]10;?\\033\\\\'; exec cat",
    # Colour first paint, then a line typed in and echoed back in colour (raw bytes as received).
    "fixture": ("printf '\\033[31mred\\033[0m \\033[1;32mbold green\\033[0m\\n'; "
                "printf '\\033[38;2;255;100;0mtrue\\033[0m\\n'; "
                "while IFS= read -r line; do printf '\\033[1;35m%s\\033[0m\\n' \"$line\"; done"),
}
if MODE == "line":
    # Plain line mode, no framed hello: terminal.attach must be refused with needs_framed (SPEC §5.1).
    conn = subprocess.Popen(["hc", "connect"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    conn.stdin.write(b'{"jsonrpc":"2.0","id":1,"method":"hello","params":{}}\n')
    conn.stdin.flush()
    print(json.dumps({"dir": "in", "hex": conn.stdout.readline().hex()}), flush=True)
    attach = {"jsonrpc": "2.0", "id": 2, "method": "terminal.attach",
              "params": {"terminal": TERMINAL, "cols": 80, "rows": 24}}
    line = json.dumps(attach, separators=(",", ":")).encode() + b"\n"
    print(json.dumps({"dir": "out", "hex": line.hex()}), flush=True)
    conn.stdin.write(line)
    conn.stdin.flush()
    print(json.dumps({"dir": "in", "hex": conn.stdout.readline().hex()}), flush=True)
    conn.stdin.close()
    conn.wait(timeout=5)
    sys.exit(0)
if len(sys.argv) > 3 and sys.argv[3] == "create":
    made = subprocess.run(["hc", "run", "--bg", "--name", TERMINAL, "--no-parent", "--no-notify", "--cwd", "/root", "--",
                           "sh", "-c", SCRIPTS[MODE]], capture_output=True, text=True, timeout=60)
    if made.returncode != 0:
        sys.exit("create failed")

T0 = time.time()
DISPLAY = {"bg": None, "cell": None, "cursor": None, "fg": None}


def now():
    return round(time.time() - T0, 3)


def emit(obj):
    print(json.dumps(obj, separators=(",", ":")), flush=True)


def frame(channel, kind, payload=b""):
    return struct.pack("<IBI", channel, kind, len(payload)) + payload


conn = subprocess.Popen(["hc", "connect"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
ev = subprocess.Popen(["hc", "events", "--terminal", TERMINAL], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)


def events_reader():
    for line in ev.stdout:
        emit({"t": now(), "event": line.decode("utf-8", "replace").rstrip("\n")})


threading.Thread(target=events_reader, daemon=True).start()

buf = bytearray()
channel = None


def send(data):
    emit({"t": now(), "dir": "out", "hex": data.hex()})
    conn.stdin.write(data)
    conn.stdin.flush()


def read_into_buf(seconds):
    """Reads what arrives for `seconds`, logging each chunk. Returns False when hc closed the stream."""
    end = time.time() + seconds
    while True:
        left = end - time.time()
        if left <= 0:
            return True
        ready, _, _ = select.select([conn.stdout], [], [], left)
        if not ready:
            continue
        chunk = conn.stdout.read1(65536)
        if not chunk:
            emit({"t": now(), "note": "hc connect stdout closed"})
            return False
        emit({"t": now(), "dir": "in", "hex": chunk.hex()})
        buf.extend(chunk)


def take_line():
    while b"\n" not in buf:
        if not read_into_buf(0.05):
            return None
    line, _, rest = bytes(buf).partition(b"\n")
    buf[:] = rest
    return line


def take_frames():
    out = []
    while len(buf) >= 9:
        ch, kind, ln = struct.unpack_from("<IBI", buf, 0)
        if len(buf) < 9 + ln:
            break
        out.append((ch, kind, bytes(buf[9:9 + ln])))
        del buf[:9 + ln]
    return out


def pump(seconds, credit=True):
    """Reads for `seconds` and reports each frame. Answers DATA with CREDIT (the consumed payload length) when `credit`.
    Returns the number of DATA payload bytes parsed in this window."""
    global channel
    read_into_buf(seconds)
    data_bytes = 0
    for ch, kind, payload in take_frames():
        emit({"t": now(), "frame": {"channel": ch, "kind": kind, "length": len(payload), "hex": payload.hex()}})
        if ch == 0 and kind == 0 and channel is None:
            msg = json.loads(payload)
            if msg.get("id") == 2 and "result" in msg:
                channel = msg["result"]["channel"]
                emit({"t": now(), "note": f"attach channel {channel}"})
        elif ch == channel and kind == 0:
            data_bytes += len(payload)
            if credit:
                send(frame(channel, 1, struct.pack("<I", len(payload))))
    return data_bytes


emit({"t": now(), "note": f"mode {MODE} terminal {TERMINAL}"})
send(b'{"jsonrpc":"2.0","id":1,"method":"hello","params":{"framed":true}}\n')
hello = take_line()
emit({"t": now(), "note": "hello reply line", "line": hello.decode()})

attach = {"jsonrpc": "2.0", "id": 2, "method": "terminal.attach",
          "params": {"terminal": TERMINAL, "cols": 80, "rows": 24, "display": DISPLAY, "history": 0}}
send(frame(0, 0, json.dumps(attach, separators=(",", ":")).encode()))
first = pump(2.0, credit=(MODE != "flood"))

if MODE == "live":
    send(frame(channel, 0, b"x"))
    pump(1.5)
    emit({"t": now(), "note": "resize 100x30 sent"})
    send(frame(channel, 3, struct.pack("<HH", 100, 30)))
    pump(2.0)
    emit({"t": now(), "note": "resize 80x24 sent"})
    send(frame(channel, 3, struct.pack("<HH", 80, 24)))
    pump(1.5)
    send(frame(channel, 2))
    pump(1.5)
elif MODE == "focus":
    pump(1.0)
    emit({"t": now(), "note": "focus out sent"})
    send(frame(channel, 4, b"\x00"))
    pump(1.0)
    emit({"t": now(), "note": "focus in sent"})
    send(frame(channel, 4, b"\x01"))
    pump(1.0)
    send(frame(channel, 2))
    pump(1.0)
elif MODE == "facts":
    pump(1.5)
    emit({"t": now(), "note": "facts sent: fg #112233"})
    send(frame(channel, 5, b'{"fg":"#112233"}'))
    pump(4.0)
    send(frame(channel, 2))
    pump(0.5, credit=False)
elif MODE == "palette":
    pump(2.0)
    send(frame(channel, 0, b"x"))
    pump(1.0)
    send(frame(channel, 2))
    pump(0.5, credit=False)
elif MODE == "altscreen":
    pump(3.0)
    emit({"t": now(), "note": "quit htop"})
    send(frame(channel, 0, b"q"))
    pump(3.0)
    send(frame(channel, 2))
    pump(0.5, credit=False)
elif MODE == "fixture":
    pump(2.0)
    emit({"t": now(), "note": "typed line sent"})
    send(frame(channel, 0, b"hi\r"))
    pump(1.5)
    send(frame(channel, 2))
    pump(0.5, credit=False)
elif MODE == "kill":
    pump(1.0)
    killed = subprocess.run(["hc", "kill", TERMINAL], capture_output=True, text=True, timeout=30)
    emit({"t": now(), "note": f"hc kill rc={killed.returncode}"})
    pump(2.0)
elif MODE == "paste":
    # 300 KB of keys as DATA on the channel, in 4 KB frames: does the core answer any of it with CREDIT (kind 1)?
    sent = 0
    for _ in range(75):
        send(frame(channel, 0, b"p" * 4096))
        sent += 4096
    emit({"t": now(), "note": f"keys sent as DATA: {sent} bytes"})
    got = pump(3.0, credit=False)
    emit({"t": now(), "note": f"data payload back from core: {got}"})
    send(frame(channel, 2))
    pump(0.5, credit=False)
elif MODE == "modes":
    pump(6.0)
elif MODE == "exit":
    pump(6.0)
elif MODE == "flood":
    # No CREDIT is sent: the core stops at its window; then CREDIT n lets it send n more (see docs/protocol-fixtures/README).
    emit({"t": now(), "note": f"initial window (no credit): data payload bytes {first}"})
    stalled = pump(2.0, credit=False)
    emit({"t": now(), "note": f"2 s more without credit: data payload bytes {stalled}"})
    parsed = first
    for step, grant in enumerate((100000, 1000000, 1000000)):
        send(frame(channel, 1, struct.pack("<I", grant)))
        got = pump(3.0, credit=False)
        parsed += got
        emit({"t": now(), "note": f"granted {grant}: data payload bytes {got} (parsed total {parsed})"})
    send(frame(channel, 2))
    pump(0.5, credit=False)

if MODE != "exit" and channel is not None:
    try:
        send(frame(channel, 2))
    except BrokenPipeError:
        pass
read_into_buf(0.5)
conn.stdin.close()
try:
    conn.wait(timeout=5)
except subprocess.TimeoutExpired:
    conn.kill()
ev.kill()
emit({"t": now(), "note": "done"})
