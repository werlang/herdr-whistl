# Runs `hc attach <terminal>` on the rpi5 in a pty under strace, drives it (two sizes, typed key, focus out and in,
# detach with ctrl+\), answers the outer terminal's colour and cell-size queries, and prints the strace of its socket
# writes and reads between BEGIN and END STRACE markers. Used for the frame kinds the client sends (SPEC §5).
#   ssh <root@rpi5-host> python3 - <terminal> < tools/framed-capture/pty_strace.py > <dir>/strace.txt
# The strace file lives in /tmp on the rpi5 only while the run lasts and is removed at the end.
import fcntl, os, pty, select, signal, struct, sys, termios, time

TERMINAL = sys.argv[1]
OUT = "/tmp/p8r1-strace.txt"
TIOCSWINSZ = termios.TIOCSWINSZ

master, slave = pty.openpty()
fcntl.ioctl(slave, TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
pid = os.fork()
if pid == 0:
    os.setsid()
    fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
    for fd in (0, 1, 2):
        os.dup2(slave, fd)
    os.close(master)
    os.close(slave)
    os.execvp("strace", ["strace", "-f", "-e", "trace=sendto,recvfrom", "-s", "300", "-x", "-o", OUT,
                         "hc", "attach", TERMINAL])
os.close(slave)

seen = bytearray()
names = []
# Each query is answered twice: the second round (after the RESIZE) gives different values, to see whether the client reports a change.
QUERIES = [
    (b"\x1b]10;?", [b"\x1b]10;rgb:eeee/eeee/eeee\x1b\\", b"\x1b]10;rgb:1234/5678/9abc\x1b\\"]),
    (b"\x1b]11;?", [b"\x1b]11;rgb:1111/1111/1111\x1b\\", b"\x1b]11;rgb:2222/3333/4444\x1b\\"]),
    (b"\x1b]12;?", [b"\x1b]12;rgb:ffff/ffff/ffff\x1b\\", b"\x1b]12;rgb:fedc/ba98/7654\x1b\\"]),
    (b"\x1b[16t", [b"\x1b[6;18;9t", b"\x1b[6;20;10t"]),
]
answered = {}


def pump(seconds):
    end = time.time() + seconds
    while True:
        left = end - time.time()
        if left <= 0:
            return
        ready, _, _ = select.select([master], [], [], left)
        if ready:
            try:
                chunk = os.read(master, 65536)
            except OSError:
                return
            if not chunk:
                return
            seen.extend(chunk)
            # Answer the outer terminal's queries (colours, cell size, cursor) so the client has real values to report.
            for query, answers in QUERIES:
                if query in chunk:
                    n = answered.get(query, 0)
                    answered[query] = n + 1
                    names.append(query.decode())
                    os.write(master, answers[min(n, len(answers) - 1)])


def mark(label):
    print(f"[{label}] pty bytes so far: {len(seen)}")


pump(2.0)
mark("attached")
fcntl.ioctl(master, TIOCSWINSZ, struct.pack("HHHH", 30, 100, 0, 0))
pump(1.0)
mark("resized 100x30")
fcntl.ioctl(master, TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
pump(1.0)
mark("resized 80x24")
os.write(master, b"x")
pump(1.0)
mark("typed x")
os.write(master, b"\x1b[O")
pump(0.7)
mark("focus out")
os.write(master, b"\x1b[I")
pump(0.7)
mark("focus in")
print("focus reporting requested by hc (DECSET 1004):", b"\x1b[?1004h" in seen)
os.write(master, b"\x1c")
pump(1.5)
mark("detach ctrl+backslash")
done, status = os.waitpid(pid, os.WNOHANG)
if done == 0:
    time.sleep(1)
    done, status = os.waitpid(pid, os.WNOHANG)
print("hc attach strace child exited:", done == pid, "status:", status)
print("queries seen on the pty:", names)
try:
    with open(OUT, "r") as fh:
        text = fh.read()
    os.remove(OUT)
except FileNotFoundError:
    text = ""
print("-----BEGIN STRACE-----")
print(text, end="")
print("-----END STRACE-----")
