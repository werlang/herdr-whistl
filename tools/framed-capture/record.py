# Records the framed-protocol fixtures in docs/protocol-fixtures/ from captures made with capture.py and pty_strace.py
# (SPEC §5, phase 8 P0). Every value written comes from a capture; nothing is typed in by hand.
#
#   python3 tools/framed-capture/record.py <capture-dir> docs/protocol-fixtures
#
# <capture-dir> holds one capture file per run, named by mode (see the header of capture.py):
#   fixture.jsonl  hello_framed.json, attach_result.json, attach_frames.bin
#   live.jsonl     attach_resize.json, and the client's detach (CLOSE) in attach_close.json
#   flood.jsonl    attach_window.json (the CREDIT window, granted by the client)
#   paste.jsonl    attach_window.json (keys as DATA; no CREDIT is sent back)
#   exit.jsonl     attach_close.json (the program exits while attached)
#   kill.jsonl     attach_close.json (`hc kill` while attached)
#   focus.jsonl    frame_kinds.json (what the core does with FOCUS)
#   facts.jsonl    frame_kinds.json (what the core answers to a colour query before and after a FACTS frame)
#   line.jsonl     attach_needs_framed.json (terminal.attach on a line-mode connection)
#   strace.txt     frame_kinds.json (the frames hc attach sends; output of pty_strace.py)
import json
import os
import re
import struct
import sys

KIND_NAMES = {0: "DATA", 1: "CREDIT", 2: "CLOSE", 3: "RESIZE", 4: "FOCUS", 5: "FACTS"}
HEADER = struct.Struct("<IBI")


def load(path):
    with open(path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh]


def stream_in(entries):
    """Every byte the core sent, in order."""
    return b"".join(bytes.fromhex(e["hex"]) for e in entries if e.get("dir") == "in")


def split_frames(raw):
    """Complete frames in `raw`, as (channel, kind, payload). A partial tail is an error: captures are whole."""
    out, off = [], 0
    while off < len(raw):
        if off + HEADER.size > len(raw):
            raise ValueError("partial frame header at the end of the capture")
        channel, kind, length = HEADER.unpack_from(raw, off)
        end = off + HEADER.size + length
        if end > len(raw):
            raise ValueError("partial frame payload at the end of the capture")
        out.append((channel, kind, raw[off + HEADER.size:end]))
        off = end
    return out


def sent_frames(entries):
    """The frames the client sent, as (channel, kind, payload) in order; a plain JSON line is returned as (None, None, line)."""
    out = []
    for entry in entries:
        if entry.get("dir") != "out":
            continue
        raw = bytes.fromhex(entry["hex"])
        if raw[:1] == b"{":
            out.append((None, None, raw.rstrip(b"\n")))
        else:
            out.append(split_frames(raw)[0])
    return out


def describe(channel, kind, payload):
    """A readable form of one frame: the name, and the fields that kind carries (SPEC §5)."""
    record = {"channel": channel, "kind": kind, "name": KIND_NAMES.get(kind, "unknown"), "payload_hex": payload.hex()}
    if kind == 1:
        record["consumed"] = struct.unpack("<I", payload)[0]
    elif kind == 3:
        cols, rows = struct.unpack("<HH", payload)
        record["cols"], record["rows"] = cols, rows
    elif kind == 4:
        record["focus"] = {0: "out", 1: "in"}[payload[0]]
    elif kind == 5 or (kind == 2 and payload):
        record["json"] = json.loads(payload)
    elif kind == 0:
        record["text"] = payload.decode("utf-8", "replace")
    return record


def write(directory, name, obj):
    with open(os.path.join(directory, name), "w", encoding="utf-8") as fh:
        json.dump(obj, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    print("wrote", name)


def record_hello_and_attach(capture, out_dir):
    entries = load(capture)
    sent = sent_frames(entries)
    hello_request = json.loads(sent[0][2])
    raw = stream_in(entries)
    newline = raw.index(b"\n")
    hello_response = json.loads(raw[:newline])
    write(out_dir, "hello_framed.json", {"request": hello_request, "response": hello_response})

    rest = raw[newline + 1:]
    frames = split_frames(rest)
    attach_request = json.loads(sent[1][2])
    attach_reply = next(json.loads(p) for ch, kind, p in frames if ch == 0 and kind == 0)
    write(out_dir, "attach_result.json", {"request": attach_request, "response": attach_reply})
    with open(os.path.join(out_dir, "attach_frames.bin"), "wb") as fh:
        fh.write(rest)
    print("wrote attach_frames.bin:", len(rest), "bytes,", len(frames), "frames")
    return frames


def record_needs_framed(out_dir, line_capture):
    """terminal.attach on a plain line-mode connection: the core refuses it with needs_framed (SPEC §5.1)."""
    entries = load(line_capture)
    request = json.loads(bytes.fromhex(next(e["hex"] for e in entries if e.get("dir") == "out")))
    replies = [json.loads(bytes.fromhex(e["hex"])) for e in entries if e.get("dir") == "in"]
    write(out_dir, "attach_needs_framed.json", {"request": request, "response": replies[-1]})


def record_resize(out_dir, capture):
    """The RESIZE the client sent and what came back on the channel and the events stream while it was in force."""
    entries = load(capture)
    marks = [i for i, e in enumerate(entries) if e.get("note", "").startswith("resize 100x30")]
    end_mark = next(i for i, e in enumerate(entries) if e.get("note", "").startswith("resize 80x24"))
    start = marks[0]
    sent = next(e for e in entries[start:] if e.get("dir") == "out" and bytes.fromhex(e["hex"])[4] == 3)
    channel, kind, payload = split_frames(bytes.fromhex(sent["hex"]))[0]
    request = describe(channel, kind, payload)
    frames_back = []
    events = []
    for e in entries[start:end_mark]:
        if "frame" in e and e["frame"]["channel"] == 1:
            frames_back.append(e["frame"])
        if "event" in e and '"type":"size"' in e["event"]:
            data = json.loads(e["event"])["params"]
            events.append({"type": "size", "terminal": data["terminal"], "data": data["data"]})
    write(out_dir, "attach_resize.json", {
        "request": request,
        "response": {"channel_frames": frames_back, "events": events, "window_s": round(entries[end_mark]["t"] - entries[start]["t"], 1)},
    })
    return request


def record_close(out_dir, live, exit_capture, kill_capture):
    entries = load(live)
    last_out = [e for e in entries if e.get("dir") == "out"][-1]
    detach = describe(*split_frames(bytes.fromhex(last_out["hex"]))[0])
    ack = [e["frame"] for e in entries if "frame" in e and e["frame"]["kind"] == 2][-1]
    exit_frame = [e["frame"] for e in load(exit_capture) if "frame" in e and e["frame"]["kind"] == 2][-1]
    kill_frame = [e["frame"] for e in load(kill_capture) if "frame" in e and e["frame"]["kind"] == 2][-1]
    write(out_dir, "attach_close.json", {
        "request": {"detach": detach},
        "response": {
            "detach_ack": describe(1, 2, bytes.fromhex(ack["hex"])),
            "program_exit": describe(1, 2, bytes.fromhex(exit_frame["hex"])),
            "program_killed": describe(1, 2, bytes.fromhex(kill_frame["hex"])),
        },
    })


def record_window(out_dir, flood, paste):
    entries = load(flood)
    windows, grants = [0], []
    for e in entries:
        if e.get("dir") == "out" and bytes.fromhex(e["hex"])[:1] != b"{":
            channel, kind, payload = split_frames(bytes.fromhex(e["hex"]))[0]
            if channel == 1 and kind == 1:
                grants.append(struct.unpack("<I", payload)[0])
                windows.append(0)
        elif "frame" in e and e["frame"]["channel"] == 1 and e["frame"]["kind"] == 0:
            windows[-1] += e["frame"]["length"]
    paste_entries = load(paste)
    keys_sent = sum(len(bytes.fromhex(e["hex"])) - HEADER.size for e in paste_entries
                    if e.get("dir") == "out" and bytes.fromhex(e["hex"])[4] == 0 and bytes.fromhex(e["hex"])[0] == 1)
    credit_back = sum(1 for e in paste_entries if "frame" in e and e["frame"]["kind"] == 1)
    data_back = sum(e["frame"]["length"] for e in paste_entries if "frame" in e and e["frame"]["channel"] == 1
                    and e["frame"]["kind"] == 0)
    credited_first_paint = sum(struct.unpack("<I", bytes.fromhex(e["hex"])[9:13])[0] for e in paste_entries
                               if e.get("dir") == "out" and bytes.fromhex(e["hex"])[4] == 1 and bytes.fromhex(e["hex"])[0] == 1)
    write(out_dir, "attach_window.json", {
        "request": {
            "flood": {"no_credit_until_first_grant": True, "grants": grants},
            "paste": {"keys_as_data_bytes": keys_sent},
        },
        "response": {
            "flood": {
                "data_payload_before_each_grant": windows[:-1],
                "data_payload_after_last_grant": windows[-1],
                "data_payload_total": sum(windows),
            },
            "paste": {"core_credit_frames": credit_back, "credited_by_client": credited_first_paint,
                      "data_payload_total": data_back},
        },
    })


def record_frame_kinds(out_dir, strace_text, focus_capture, facts_capture):
    text = open(strace_text, encoding="utf-8", errors="replace").read()
    text = text.split("-----BEGIN STRACE-----", 1)[1].split("-----END STRACE-----", 1)[0]
    frames = []
    for m in re.finditer(r'^\d+ sendto\(3, "((?:[^"\\]|\\.)*)"', text, re.M):
        raw = unescape(m.group(1))
        if raw[:1] == b"{":
            continue
        for channel, kind, payload in split_frames(raw):
            if channel == 0:
                continue
            frames.append(describe(channel, kind, payload))
    focus_entries = load(focus_capture)
    reactions = []
    for i, e in enumerate(focus_entries):
        if e.get("dir") == "out" and bytes.fromhex(e["hex"])[4:5] == b"\x04":
            focus = describe(*split_frames(bytes.fromhex(e["hex"]))[0])["focus"]
            back = []
            for x in focus_entries[i + 1:]:
                if x.get("dir") == "out":
                    break
                if "frame" in x and x["frame"]["channel"] == 1 and x["frame"]["kind"] == 0:
                    back.append(bytes.fromhex(x["frame"]["hex"]).decode("utf-8", "replace"))
            reactions.append({"focus": focus, "data_back": back})
    write(out_dir, "frame_kinds.json", {
        "request": {"source": "hc attach (official client) on a pty, strace of its socket writes", "frames": frames},
        "response": {
            "source": "hc connect captures: what the core sent back",
            "focus_reactions": reactions,
            "facts_reaction": facts_reaction(facts_capture),
        },
    })


def facts_reaction(capture):
    """The colour the core answers OSC 10 with, before and after a FACTS frame that sets fg to #112233."""
    text, marked = "", False
    before = after = None
    for e in load(capture):
        if "note" in e and "facts sent" in e["note"]:
            marked = True
        if "frame" in e and e["frame"]["channel"] == 1 and e["frame"]["kind"] == 0:
            text += bytes.fromhex(e["frame"]["hex"]).decode("utf-8", "replace")
            answers = re.findall(r"10;rgb:([0-9a-f]{4}/[0-9a-f]{4}/[0-9a-f]{4})", text)
            if answers and before is None:
                before = answers[0]
            if marked and len(answers) >= 2 and after is None:
                after = answers[-1]
    return {"fg_sent": "#112233", "answer_before": before, "answer_after": after}


ESC = re.compile(r'\\(x[0-9a-fA-F]{2}|[0-7]{1,3}|.)')


def unescape(text):
    """Bytes from a C string as strace prints it (\\xHH, octal, and the usual single-character escapes)."""
    out, i = bytearray(), 0
    while i < len(text):
        if text[i] == "\\":
            m = ESC.match(text, i)
            tok = m.group(1)
            if tok[0] == "x":
                out.append(int(tok[1:], 16))
            elif tok[0] in "01234567":
                out.append(int(tok, 8) & 0xFF)
            else:
                out += {"n": b"\n", "t": b"\t", "r": b"\r", '"': b'"', "\\": b"\\"}.get(tok, tok.encode())
            i = m.end()
        else:
            out += text[i].encode("latin-1")
            i += 1
    return bytes(out)


if __name__ == "__main__":
    capture_dir, out_dir_global = sys.argv[1], sys.argv[2]
    def cap(name):
        return os.path.join(capture_dir, name)
    record_hello_and_attach(cap("fixture.jsonl"), out_dir_global)
    record_needs_framed(out_dir_global, cap("line.jsonl"))
    record_resize(out_dir_global, cap("live.jsonl"))
    record_close(out_dir_global, cap("live.jsonl"), cap("exit.jsonl"), cap("kill.jsonl"))
    record_window(out_dir_global, cap("flood.jsonl"), cap("paste.jsonl"))
    record_frame_kinds(out_dir_global, cap("strace.txt"), cap("focus.jsonl"), cap("facts.jsonl"))
