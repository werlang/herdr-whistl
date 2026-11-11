package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.support.Exchange
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.clientWith
import io.github.werlang.whistl.support.scriptedPipe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Browsing a machine's folders (phase 9, SPEC 2.3): the exact argv it sends, and that a folder name never becomes part
 * of a command.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderBrowserTest {

    private fun request(vararg argv: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 0)
        put("method", "machine.exec")
        putJsonObject("params") { putJsonArray("argv") { argv.forEach { add(JsonPrimitive(it)) } } }
    }

    private fun reply(code: Int, stdout: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 0)
        putJsonObject("result") {
            put("code", code)
            put("stdout", Base64.getEncoder().encodeToString(stdout.toByteArray()))
            put("stderr", Base64.getEncoder().encodeToString(ByteArray(0)))
            put("timed_out", false)
            put("truncated", false)
        }
    }

    private fun TestScope.browser(pipe: io.github.werlang.whistl.support.FakeLinePipe): ExecFolderBrowser {
        val machines = ScriptedMachines()
        machines.put(Machine(id = MACHINE, name = MACHINE, host = "$MACHINE.lan", port = 22, user = "ci"), ConnectionState.Offline)
        machines.leaseClient = clientWith(command = pipe)
        return ExecFolderBrowser(machines)
    }

    /** Home resolves `~` with a fixed script and lists it with `ls -1Ap`, keeping only the directories. */
    @Test
    fun homeResolvesAndListsOnlyDirectories() = runTest {
        val pipe = scriptedPipe(
            Exchange("resolve home", request("sh", "-lc", "cd -- \"$1\" 2>/dev/null && pwd", "sh", "~"), reply(0, "/home/pablo\n")),
            Exchange(
                "list home",
                request("ls", "-1Ap", "--", "/home/pablo"),
                reply(0, "code/\ndocs/\nnotes.txt\n.git/\n"),
            ),
        )

        val listing = browser(pipe).home(MACHINE)

        assertEquals("/home/pablo", listing.path)
        assertEquals("/home", listing.parent)
        assertEquals(listOf(".git", "code", "docs"), listing.folders)
        assertNull(listing.problem)
    }

    /** A folder name is one positional argument: nothing in it is interpreted by the shell. */
    @Test
    fun aFolderNameIsNeverAShellFragment() = runTest {
        val nasty = "/tmp/\$(rm -rf ~)"
        val pipe = scriptedPipe(
            Exchange("list nasty", request("ls", "-1Ap", "--", nasty), reply(0, "")),
        )

        val listing = browser(pipe).list(MACHINE, nasty)

        assertEquals(nasty, listing.path)
        assertEquals(emptyList<String>(), listing.folders)
        // The written request carries the name verbatim, as one argv entry and nothing else.
        val written = Json.parseToJsonElement(pipe.written.single()).jsonObject
        val argv = written["params"]!!.jsonObject["argv"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("ls", "-1Ap", "--", nasty), argv)
    }

    /** A folder the machine cannot read is a problem the sheet can name, not an empty list that looks empty. */
    @Test
    fun anUnreadableFolderIsReported() = runTest {
        val pipe = scriptedPipe(
            Exchange("list missing", request("ls", "-1Ap", "--", "/nope"), reply(2, "")),
        )

        val listing = browser(pipe).list(MACHINE, "/nope")

        assertEquals(FolderProblem.Unreadable, listing.problem)
        assertTrue(listing.folders.isEmpty())
    }

    private companion object {
        const val MACHINE = "rpi5"
    }
}
