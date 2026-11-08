# hc event shapes

The shapes the events channel (`events.subscribe`) carries, as the app reads them, and how sure each one is. The decoder
(`app/src/main/java/io/github/werlang/whistl/core/rpc/HcEvent.kt`), the reducer
(`app/src/main/java/io/github/werlang/whistl/data/AgentEvents.kt`) and the test support cite this file. The statuses below are
the ones the code and its KDoc use.

## Status

- **observed**: captured from hc 0.1.18 with `hc events --json --label …` by the supervisor on 2026-11-08, while a throwaway
  plain terminal was created, labelled, kept and unkept, retitled, killed and removed.
- **recorded**: a fixture in `docs/protocol-fixtures/`, recorded from hc 0.1.18 on 2026-11-08 on an unfiltered subscription.
- **synthetic**: written by hand from SPEC §5, because hc 0.1.18 could not be made to emit it on demand. Re-record it when hc
  is seen to send it.
- **assumed**: not observed in hc 0.1.18. The shape is a guess by analogy with the observed ones. A test of an assumed shape
  says so in its name or KDoc.

## `event` notifications

An `event` notification is `{"jsonrpc":"2.0","method":"event","params":{"type":…,"terminal":…,"seq":…,"data":{…}}}`.

| type | data | status |
|---|---|---|
| created | the full Terminal object, with the keys of the `run` result: address, argv, cols, created_ms, cwd, die_with_parent, exit_code, finished_ms, identity, keep, labels, name, notify, parent, pid, reason, reported, rm, rows, screen, signal, state, status, status_ms, template, title, turn | observed |
| labels | `{"labels": {"whistl.capture": "1", "whistl.space": "sp-cap"}}`: the full new label map | observed |
| keep | `{"keep": true}` or `{"keep": false}` | observed |
| title | `{"title": "whistl-title"}` | observed |
| foreground | `{"argv": […], "cwd": "/private/tmp", "name": "bash", "pid": 16854}`. Not in SPEC §5. It parses as unknown and is ignored; it must not crash | observed |
| finished | `{"exit_code": null, "finished_ms": 1794114200934, "reason": "killed", "signal": 1}`, or `{"exit_code": 0, …, "reason": "exited", "signal": null}` | observed |
| removed | `{}` | observed |
| status | `{"status": "working", "status_ms": …}` | assumed |
| turn | `{"turn": 3}` | assumed |
| size | `{"cols": 120, "rows": 40}`. Nothing the app models changes | assumed |
| lost | `{}`. The state follows from the type: finished, with reason `lost` | assumed |
| restarted | `{}`. The state follows from the type: live again, exit code, finish time and reason cleared | assumed |

## Top-level notifications

These sit on the same channel, beside the `event` notifications. The watchdog (40 s) resets on any line, so each one counts as
liveness.

**heartbeat** (recorded, `event_heartbeat.json`). `{"jsonrpc":"2.0","method":"heartbeat","params":{"seq":10640}}`. It is a
top-level notification, not an `event` of type `heartbeat`. hc sends one every 15 s. It decodes to `HcEvent.Heartbeat` and
changes no agent.

**doc** (recorded, `event_doc.json`). `{"jsonrpc":"2.0","method":"event","params":{"type":"doc","seq":10643,"data":{"name":"whistl.fixture","version":2}}}`.
It has no `terminal`. Any `doc` event re-reads the `whistl` document, whatever the name in `data` says (phase 6 P0-5).

**missed** (synthetic, `event_missed_synthetic.json`). hc drops events for a subscriber that falls behind and follows them with
a snapshot. SPEC §5 allows two shapes, and the app decodes both:

- `{"jsonrpc":"2.0","method":"missed","params":{"seq":10700,"terminals":[]}}`
- `{"jsonrpc":"2.0","method":"event","params":{"type":"missed","seq":10700,"data":{"seq":10700,"terminals":[]}}}`

The snapshot is decoded with the same wire type as the `events.subscribe` result. It replaces the machine's agent list, and
folder probes are left out. A `missed` without a `terminals` array changes nothing.

## Rules the app applies

- Decoding is total. A line whose params do not decode is dropped and counted, and its content is never logged (phase 6 P0-2).
- An unknown `type` decodes to `Unknown` and changes nothing.
- An event for an address the machine does not list changes nothing.
- A `doc` event, of any name, triggers a re-read of the `whistl` document.
