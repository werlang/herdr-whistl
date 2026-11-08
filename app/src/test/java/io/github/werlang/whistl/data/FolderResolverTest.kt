package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.support.FakeCoreDoc
import io.github.werlang.whistl.support.FakeLinePipe
import io.github.werlang.whistl.support.InMemoryResolvedFolderCache
import io.github.werlang.whistl.support.assertThrowsSuspend
import io.github.werlang.whistl.support.clientWith
import io.github.werlang.whistl.support.requestIdOf
import io.github.werlang.whistl.support.withId
import io.github.werlang.whistl.support.withoutId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FolderResolver] on virtual time, against [FakeCoreDoc] behind a recording pipe (phase 4 decision 1). Each case asserts the
 * resolved result, the requests hc received, and that the probe was removed.
 */
class FolderResolverTest {

    /** A command pipe answered by [core], which also keeps every request it receives, in order. */
    private class Recorded(val core: FakeCoreDoc) {
        val requests: MutableList<JsonObject> = mutableListOf()

        val pipe: FakeLinePipe = FakeLinePipe { line ->
            val request = Json.parseToJsonElement(line).jsonObject
            requests += request
            core.handle(request.withoutId())?.let { push(withId(it, requestIdOf(line))) }
        }

        /** The methods received, in order. */
        fun methods(): List<String> = requests.map { it.getValue("method").jsonPrimitive.content }
    }

    private fun TestScope.resolverFor(recorded: Recorded, cache: InMemoryResolvedFolderCache): FolderResolver =
        FolderResolver("m1", clientWith(command = recorded.pipe), cache)

    /** The probe request: `terminal.run` of `pwd` in [cwd], with the probe label and both fixed flags. */
    private fun assertProbeRequest(recorded: Recorded, cwd: String) {
        val run = recorded.requests.filter { it.getValue("method").jsonPrimitive.content == "terminal.run" }
        assertEquals("one probe request for $cwd", 1, run.size)
        val params = run.single().getValue("params").jsonObject
        assertEquals(listOf("pwd"), params.getValue("argv").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(cwd, params.getValue("cwd").jsonPrimitive.content)
        assertEquals(JsonPrimitive("1"), params.getValue("labels").jsonObject.getValue(PROBE_LABEL))
        assertEquals(JsonPrimitive(true), params.getValue("no_parent"))
        assertEquals(JsonPrimitive(true), params.getValue("no_notify"))
    }

    /** `~` is the home directory, read with `printenv HOME`, and the probe runs there. */
    @Test
    fun tildeResolvesToTheHomeDirectory() = runTest {
        val core = FakeCoreDoc()
        val recorded = Recorded(core)
        val cache = InMemoryResolvedFolderCache()

        val resolution = resolverFor(recorded, cache).resolve("~")

        assertEquals(FolderResolution.Resolved("/home/u"), resolution)
        assertEquals(listOf("machine.exec", "terminal.run", "terminal.list", "terminal.read", "terminal.rm"), recorded.methods())
        assertProbeRequest(recorded, cwd = "/home/u")
        val exec = recorded.requests.single { it.getValue("method").jsonPrimitive.content == "machine.exec" }
        assertEquals(PRINTENV_HOME, exec.getValue("params").jsonObject.getValue("argv").jsonArray.map { it.jsonPrimitive.content })
    }

    /** `~/x` is the home directory plus `/x`. The probe runs there, and the result is kept in the cache. */
    @Test
    fun tildeSlashExpandsUnderTheHome() = runTest {
        val core = FakeCoreDoc()
        val recorded = Recorded(core)
        val cache = InMemoryResolvedFolderCache()

        val resolution = resolverFor(recorded, cache).resolve("~/code/web-app")

        assertEquals(FolderResolution.Resolved("/home/u/code/web-app"), resolution)
        assertProbeRequest(recorded, cwd = "/home/u/code/web-app")
        assertEquals("/home/u/code/web-app", cache.get("m1", "~/code/web-app"))
    }

    /** `~user` is not supported: the answer is typed, and hc is not asked anything. */
    @Test
    fun tildeUserIsUnsupportedAndCallsNothing() = runTest {
        val core = FakeCoreDoc()
        val recorded = Recorded(core)

        val resolution = resolverFor(recorded, InMemoryResolvedFolderCache()).resolve("~bob/code")

        assertEquals(FolderResolution.Unresolvable(UnresolvableReason.UnsupportedTilde), resolution)
        assertEquals(emptyList<String>(), recorded.methods())
    }

    /** An absolute folder is probed as it is typed, and resolves to the path pwd printed. */
    @Test
    fun absoluteFolderIsProbedAsGiven() = runTest {
        val core = FakeCoreDoc()
        val recorded = Recorded(core)

        val resolution = resolverFor(recorded, InMemoryResolvedFolderCache()).resolve("/srv/app")

        assertEquals(FolderResolution.Resolved("/srv/app"), resolution)
        assertEquals(listOf("terminal.run", "terminal.list", "terminal.read", "terminal.rm"), recorded.methods())
        assertProbeRequest(recorded, cwd = "/srv/app")
    }

    /** A folder hc cannot start in is NotFound (spawn_failed). Nothing was started, so there is nothing to remove. */
    @Test
    fun spawnFailedIsNotFound() = runTest {
        val core = FakeCoreDoc().apply { missingFolders += "/gone" }
        val recorded = Recorded(core)
        val cache = InMemoryResolvedFolderCache()

        val resolution = resolverFor(recorded, cache).resolve("/gone")

        assertEquals(FolderResolution.Unresolvable(UnresolvableReason.NotFound), resolution)
        assertEquals(emptyList<String>(), core.removedProbes)
        assertNull(cache.get("m1", "/gone"))
    }

    /**
     * A probe that never finishes is a Timeout after 5 s of virtual time. It may still be running, so it is killed first and
     * then removed: hc refuses to remove a running terminal (`rm_live.json`).
     */
    @Test
    fun aProbeThatNeverFinishesTimesOutAndIsKilledThenRemoved() = runTest {
        val core = FakeCoreDoc().apply { probeFinishesAfterPolls = Int.MAX_VALUE }
        val recorded = Recorded(core)

        val resolution = resolverFor(recorded, InMemoryResolvedFolderCache()).resolve("/srv/app")

        assertEquals(FolderResolution.Unresolvable(UnresolvableReason.Timeout), resolution)
        val methods = recorded.methods()
        val kill = methods.indexOf("terminal.kill")
        val rm = methods.indexOf("terminal.rm")
        assertTrue("kill (at $kill) must come before rm (at $rm): $methods", kill >= 0 && kill < rm)
        assertEquals(1, core.removedProbes.size)
        assertEquals(emptyMap<String, FakeCoreDoc.Probe>(), core.probes)
    }

    /** A probe seen finished is removed without a kill. */
    @Test
    fun aFinishedProbeIsRemovedWithoutAKill() = runTest {
        val core = FakeCoreDoc()
        val recorded = Recorded(core)

        resolverFor(recorded, InMemoryResolvedFolderCache()).resolve("/srv/app")

        assertFalse(recorded.methods().contains("terminal.kill"))
        assertEquals(1, core.removedProbes.size)
    }

    /** A probe that finishes on a later poll is waited for: the state is read again every 100 ms. */
    @Test
    fun aProbeThatFinishesLaterIsPolledUntilFinished() = runTest {
        val core = FakeCoreDoc().apply { probeFinishesAfterPolls = 3 }
        val recorded = Recorded(core)

        val resolution = resolverFor(recorded, InMemoryResolvedFolderCache()).resolve("/srv/app")

        assertEquals(FolderResolution.Resolved("/srv/app"), resolution)
        assertEquals(3, recorded.methods().count { it == "terminal.list" })
    }

    /** Output that is not an absolute path is BadOutput, and the probe is still removed. */
    @Test
    fun badOutputIsBadOutputAndTheProbeIsRemoved() = runTest {
        val core = FakeCoreDoc().apply { probeOutput = { "not a path" } }
        val recorded = Recorded(core)

        val resolution = resolverFor(recorded, InMemoryResolvedFolderCache()).resolve("/srv/app")

        assertEquals(FolderResolution.Unresolvable(UnresolvableReason.BadOutput), resolution)
        assertEquals(1, core.removedProbes.size)
    }

    /** The answer is the last non-blank line: earlier lines and the terminal's trailing padding do not count. */
    @Test
    fun theLastNonBlankLineIsTheAnswer() = runTest {
        val core = FakeCoreDoc().apply { probeOutput = { "warning: slow disk\n$it\n\n" } }

        val resolution = resolverFor(Recorded(core), InMemoryResolvedFolderCache()).resolve("/srv/app")

        assertEquals(FolderResolution.Resolved("/srv/app"), resolution)
    }

    /** One folder is probed once per connection, however often it is asked for. */
    @Test
    fun aFolderIsProbedOncePerConnection() = runTest {
        val core = FakeCoreDoc()
        val recorded = Recorded(core)
        val resolver = resolverFor(recorded, InMemoryResolvedFolderCache())

        resolver.resolve("/srv/app")
        resolver.resolve("/srv/app")

        assertEquals(1, core.probeFolders.count { it == "/srv/app" })
    }

    /** The home directory is read once per connection, however many `~` folders there are. */
    @Test
    fun theHomeDirectoryIsReadOncePerConnection() = runTest {
        val core = FakeCoreDoc()
        val recorded = Recorded(core)
        val resolver = resolverFor(recorded, InMemoryResolvedFolderCache())

        resolver.resolve("~/a")
        resolver.resolve("~/b")

        assertEquals(1, recorded.methods().count { it == "machine.exec" })
        assertEquals(listOf("/home/u/a", "/home/u/b"), core.probeFolders)
    }

    /** A home directory that cannot be read is NoHome for a `~` folder; an absolute folder does not need it. */
    @Test
    fun anUnreadableHomeIsNoHomeForTildeOnly() = runTest {
        val core = FakeCoreDoc().apply { home = "" }
        val resolver = resolverFor(Recorded(core), InMemoryResolvedFolderCache())

        assertEquals(FolderResolution.Unresolvable(UnresolvableReason.NoHome), resolver.resolve("~/a"))
        assertEquals(FolderResolution.Resolved("/srv/app"), resolver.resolve("/srv/app"))
    }

    /** A lost connection is not a verdict: it is thrown, and nothing is kept for the folder. */
    @Test
    fun aLostConnectionIsNotAVerdict() = runTest {
        val core = FakeCoreDoc()
        val recorded = Recorded(core)
        val cache = InMemoryResolvedFolderCache()
        val resolver = resolverFor(recorded, cache)
        recorded.pipe.end()
        runCurrent()

        assertThrowsSuspend(TransportLost::class.java) { resolver.resolve("/srv/app") }
        assertNull(cache.get("m1", "/srv/app"))
    }

    private companion object {
        /** The home-directory read's argv, as the resolver sends it. */
        val PRINTENV_HOME: List<String> = listOf("printenv", "HOME")
    }
}
