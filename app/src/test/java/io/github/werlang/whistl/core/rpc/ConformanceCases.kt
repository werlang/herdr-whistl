package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.domain.KnownAgents
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.support.FakeLinePipe
import io.github.werlang.whistl.support.Fixtures
import io.github.werlang.whistl.support.assertThrowsSuspend
import io.github.werlang.whistl.support.clientWith
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * One row of the conformance table: a client method, the fixture it is replayed from, and the assertions on
 * parsed values.
 *
 * @property label the row name shown by the test runner.
 * @property method the hc method this row exercises.
 * @property fixture the fixture file the row replays.
 * @property check runs the method through a fake pipe and asserts on parsed values.
 */
class ConformanceCase(
    val label: String,
    val method: String,
    val fixture: String,
    val check: suspend TestScope.() -> Unit,
) {
    override fun toString(): String = label
}

/**
 * The conformance table (phase brief §2): one row per [HcClient] method, plus the error and gap fixtures.
 *
 * Every row replays its fixture: the request must equal the recorded request (ignoring `id`), and the
 * assertions run on parsed values, never on "no exception".
 */
object ConformanceCases {
    /** Every row, in table order. */
    val all: List<ConformanceCase> = listOf(
        ConformanceCase("hello | hello.json", "hello", "hello.json") {
            val hello = clientWith(command = FakeLinePipe.replaying(Fixtures.load("hello.json"))).hello()
            assertEquals(1, hello.generation)
            assertEquals("0.1.18", hello.version)
            assertEquals("laptop", hello.coreName)
            assertTrue("events" in hello.features)
        },

        ConformanceCase("terminal.list | list_by_label.json", "terminal.list", "list_by_label.json") {
            val list = clientWith(command = FakeLinePipe.replaying(Fixtures.load("list_by_label.json")))
                .terminalList(labels = mapOf("whistl.fixture" to "1"))
            val terminal = list.terminals.single()
            assertEquals("t15s", terminal.address)
            assertEquals(TerminalState.Live, terminal.state)
            assertNull(terminal.status)
            assertEquals("sp-demo", terminal.labels["whistl.space"])
            assertEquals("homelab", list.machines.single().name)
            assertEquals("connected", list.machines.single().state)
            assertEquals(0, list.hiddenFinished)
        },

        ConformanceCase("terminal.run | run.json", "terminal.run", "run.json") {
            val terminal = clientWith(command = FakeLinePipe.replaying(Fixtures.load("run.json"))).terminalRun(
                argv = listOf("sh", "-c", "echo whistl-fixture ready; sleep 60"),
                cwd = "/tmp",
                labels = mapOf("whistl.fixture" to "1", "whistl.space" to "sp-demo"),
                name = "whistl-fixture",
            )
            assertEquals("t15s", terminal.address)
            assertEquals(TerminalState.Live, terminal.state)
            assertNull(terminal.status)
            assertEquals("whistl-fixture", terminal.name)
        },

        ConformanceCase("terminal.read | read.json", "terminal.read", "read.json") {
            val read = clientWith(command = FakeLinePipe.replaying(Fixtures.load("read.json")))
                .terminalRead("t15s", lines = 20)
            assertEquals("whistl-fixture ready\necho hello-from-whistl\n^C", read.text)
            assertTrue(read.complete)
            assertEquals("history", read.how)
        },

        ConformanceCase("terminal.read error | unknown_terminal.json", "terminal.read", "unknown_terminal.json") {
            val error = assertThrowsSuspend(NotFound::class.java) {
                clientWith(command = FakeLinePipe.replaying(Fixtures.load("unknown_terminal.json")))
                    .terminalRead("t-does-not-exist")
            }
            assertEquals("see `hc ls --all`", error.hint)
        },

        ConformanceCase("terminal.send | send.json", "terminal.send", "send.json") {
            val sent = clientWith(command = FakeLinePipe.replaying(Fixtures.load("send.json")))
                .terminalSend("t15s", "echo hello-from-whistl")
            assertNull(sent.started)
            assertNull(sent.status)
            assertEquals(0L, sent.turn)
        },

        ConformanceCase("terminal.keys | keys.json", "terminal.keys", "keys.json") {
            val keys = clientWith(command = FakeLinePipe.replaying(Fixtures.load("keys.json")))
                .terminalKeys("t15s", listOf("ctrl+c"))
            assertNull(keys.status)
            assertEquals(0L, keys.turn)
        },

        ConformanceCase("terminal.interrupt | interrupt.json", "terminal.interrupt", "interrupt.json") {
            val sent = clientWith(command = FakeLinePipe.replaying(Fixtures.load("interrupt.json")))
                .terminalInterrupt("t161")
            assertEquals("ctrl+c", sent.key)
            assertEquals("exited", sent.outcome)
        },

        ConformanceCase(
            "terminal.run spawn_failed | run_spawn_failed.json",
            "terminal.run",
            "run_spawn_failed.json",
        ) {
            val error = assertThrowsSuspend(SpawnFailed::class.java) {
                clientWith(command = FakeLinePipe.replaying(Fixtures.load("run_spawn_failed.json"))).terminalRun(
                    argv = listOf("pwd"),
                    cwd = "~/whistl-no-such-folder",
                    labels = mapOf("whistl.fixture" to "1"),
                )
            }
            assertEquals("check the program name and cwd", error.hint)
        },

        ConformanceCase("terminal.kill | kill.json", "terminal.kill", "kill.json") {
            val terminal = clientWith(command = FakeLinePipe.replaying(Fixtures.load("kill.json")))
                .terminalKill("t15s")
            assertEquals(TerminalState.Finished, terminal.state)
            assertEquals(1794006623711L, terminal.finishedMs)
            assertEquals(2, terminal.signal)
            assertEquals("exited", terminal.reason)
            assertNull(terminal.exitCode)
        },

        ConformanceCase("terminal.rm | rm.json", "terminal.rm", "rm.json") {
            val removed = clientWith(command = FakeLinePipe.replaying(Fixtures.load("rm.json")))
                .terminalRm("t15s")
            assertEquals("t15s", removed)
        },

        ConformanceCase("terminal.label | label.json", "terminal.label", "label.json") {
            val terminal = clientWith(command = FakeLinePipe.replaying(Fixtures.load("label.json")))
                .terminalLabel("t15s", mapOf("whistl.space" to "sp-other"))
            assertEquals("sp-other", terminal.labels["whistl.space"])
            assertEquals(TerminalState.Finished, terminal.state)
        },

        ConformanceCase("machine.exec | exec_installed.json", "machine.exec", "exec_installed.json") {
            val exec = clientWith(command = FakeLinePipe.replaying(Fixtures.load("exec_installed.json")))
                .machineExec(KnownAgents.detectionArgv)
            assertEquals(0, exec.code)
            assertEquals("claude\nopencode\n", exec.stdout)
            assertEquals("", exec.stderr)
            assertEquals(listOf("claude", "opencode"), KnownAgents.parseInstalled(exec.stdout).map { it.id })
        },

        ConformanceCase("doc.get | doc_get.json", "doc.get", "doc_get.json") {
            val doc = clientWith(command = FakeLinePipe.replaying(Fixtures.load("doc_get.json")))
                .docGet("whistl-fixture")
            assertEquals(Json.parseToJsonElement("""{"spaces":[],"v":1}"""), doc.value)
            assertEquals(1L, doc.version)
        },

        ConformanceCase("doc.get missing | doc_get_missing.json", "doc.get", "doc_get_missing.json") {
            val doc = clientWith(command = FakeLinePipe.replaying(Fixtures.load("doc_get_missing.json")))
                .docGet("whistl-fixture")
            assertNull(doc.value)
            assertEquals(0L, doc.version)
        },

        ConformanceCase("doc.set | doc_set.json", "doc.set", "doc_set.json") {
            val newVersion = clientWith(command = FakeLinePipe.replaying(Fixtures.load("doc_set.json")))
                .docSet("whistl-fixture", Json.parseToJsonElement("""{"v":1,"spaces":[]}"""), version = 0L)
            assertEquals(1L, newVersion)
        },

        ConformanceCase("doc.set conflict | doc_set_conflict.json", "doc.set", "doc_set_conflict.json") {
            val error = assertThrowsSuspend(Conflict::class.java) {
                clientWith(command = FakeLinePipe.replaying(Fixtures.load("doc_set_conflict.json")))
                    .docSet("whistl-fixture", Json.parseToJsonElement("""{"v":1,"spaces":[]}"""), version = 0L)
            }
            assertEquals("read doc.get and retry with its version", error.hint)
        },

        ConformanceCase("events.subscribe | subscribe.json", "events.subscribe", "subscribe.json") {
            val sub = clientWith(events = FakeLinePipe.replaying(Fixtures.load("subscribe.json")))
                .eventsSubscribe(mapOf("whistl.fixture" to "1"))
            assertEquals(9504L, sub.seq)
            val terminal = sub.terminals.single()
            assertEquals("t15s", terminal.address)
            assertEquals(TerminalState.Live, terminal.state)
            assertEquals("sp-demo", terminal.labels["whistl.space"])
        },
    )
}
