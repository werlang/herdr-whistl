package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.Rejection
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.support.Exchange
import io.github.werlang.whistl.support.Fixture
import io.github.werlang.whistl.support.Fixtures
import io.github.werlang.whistl.support.RepoStack
import io.github.werlang.whistl.support.scriptedCommands
import io.github.werlang.whistl.support.spaceJson
import io.github.werlang.whistl.support.spacesDocJson
import io.github.werlang.whistl.support.terminalJson
import io.github.werlang.whistl.support.versionReply
import io.github.werlang.whistl.support.withParam
import io.github.werlang.whistl.support.withResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [DefaultSpacesRepository] on virtual time, against the in-memory core ([RepoStack.core]) and, for the concurrent
 * writer, an exact scripted conversation (round p3r2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpacesRepositoryTest {

    /** A stack whose machine is connected, with [document] stored on the fake core. */
    private suspend fun TestScope.connectedWith(document: JsonObject?): Pair<RepoStack, String> {
        val h = RepoStack(this)
        h.core.value = document
        val id = h.addMachine()
        runCurrent()
        return h to id
    }

    private fun RepoStack.storedSpaces(): List<JsonObject> =
        core.value!!.getValue("spaces").jsonArray.map { it.jsonObject }

    private fun conflictReply(fixture: Fixture, currentVersion: Long, sent: Long): JsonObject {
        val error = fixture.response.getValue("error").jsonObject
        val message = "document \"whistl\" is at version $currentVersion, not $sent"
        return JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "error" to JsonObject(error + ("message" to JsonPrimitive(message))),
            ),
        )
    }

    /** Groups come out pinned first, then by order, with ties in document order. */
    @Test
    fun groupsAreOrderedPinnedFirstThenByOrderWithTiesInDocumentOrder() = runTest {
        val (h, _) = connectedWith(
            spacesDocJson(
                listOf(
                    spaceJson("sp_a", "a", "/a", order = 2),
                    spaceJson("sp_c", "c", "/c", order = 0),
                    spaceJson("sp_b", "b", "/b", pinned = true, order = 0),
                    spaceJson("sp_e", "e", "/e", order = 0),
                    spaceJson("sp_d", "d", "/d", pinned = true, order = 1),
                ),
            ),
        )

        val group = h.spaces.groups().value.single()

        assertEquals(listOf("sp_b", "sp_d", "sp_c", "sp_e", "sp_a"), group.spaces.map { it.id })
        assertFalse(group.offline)
    }

    /** When the machine drops, its spaces stay on screen from the cache, flagged offline. */
    @Test
    fun anOfflineMachineRendersFromTheCacheWithTheOfflineFlag() = runTest {
        val (h, id) = connectedWith(spacesDocJson(listOf(spaceJson("sp_a", "a", "/a"))))
        assertFalse(h.spaces.groups().value.single().offline)

        h.connector.lastSession!!.kill()
        runCurrent()

        val group = h.spaces.groups().value.single()
        assertTrue(group.offline)
        assertEquals(listOf("sp_a"), group.spaces.map { it.id })
        assertEquals("sp_a", h.cache.get(id)!!.spaces.single().id)
    }

    /** create: a new id, the given folder, and a default name from the folder's last segment. */
    @Test
    fun createWritesANewSpaceWithDefaults() = runTest {
        val (h, id) = connectedWith(spacesDocJson(emptyList()))

        val result = h.spaces.create(id, folder = "~/code/web-app")

        val spaceId = (result as SpaceResult.Done).spaceId
        assertTrue(Regex("sp_[0-9a-f]{16}").matches(spaceId))
        val written = h.storedSpaces().single()
        assertEquals(spaceId, written.getValue("id").jsonPrimitive.content)
        assertEquals("web-app", written.getValue("name").jsonPrimitive.content)
        assertEquals("~/code/web-app", written.getValue("folder").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), written.getValue("pinned"))
        assertEquals(JsonPrimitive(0), written.getValue("order"))
    }

    /** create with no folder uses `~`, and the default name is `~`. */
    @Test
    fun createDefaultsToTheHomeFolder() = runTest {
        val (h, id) = connectedWith(spacesDocJson(emptyList()))

        h.spaces.create(id)

        val written = h.storedSpaces().single()
        assertEquals("~", written.getValue("folder").jsonPrimitive.content)
        assertEquals("~", written.getValue("name").jsonPrimitive.content)
    }

    /** A folder with a NUL byte is rejected with its typed reason, and nothing is written. */
    @Test
    fun createRejectsABadFolderWithItsTypedReason() = runTest {
        val (h, id) = connectedWith(spacesDocJson(emptyList()))
        val before = h.core.value

        val result = h.spaces.create(id, folder = "a\u0000b")

        assertEquals(SpaceResult.Rejected(SpaceField.Folder, Rejection.NUL_OR_LINE_BREAK), result)
        assertEquals(before, h.core.value)
    }

    /** A name longer than 64 characters is rejected with its typed reason. */
    @Test
    fun createRejectsALongNameWithItsTypedReason() = runTest {
        val (h, id) = connectedWith(spacesDocJson(emptyList()))

        val result = h.spaces.create(id, folder = "/tmp", name = "a".repeat(65))

        assertEquals(SpaceResult.Rejected(SpaceField.Name, Rejection.TOO_LONG), result)
    }

    /**
     * Rename, move and pin all write through the document, and every unknown field — top-level and inside a space —
     * is still there afterwards.
     */
    @Test
    fun renameMoveAndPinKeepEveryUnknownField() = runTest {
        val (h, id) = connectedWith(
            spacesDocJson(
                listOf(
                    spaceJson("sp_a", "a", "/a", order = 0, extra = mapOf("x_owner" to JsonPrimitive("someone"))),
                    spaceJson("sp_b", "b", "/b", order = 1),
                ),
                extra = mapOf("future_top" to JsonPrimitive("keep-me")),
            ),
        )

        assertEquals(SpaceResult.Done("sp_a"), h.spaces.rename(id, "sp_a", "alpha"))
        assertEquals(SpaceResult.Done("sp_a"), h.spaces.moveDown(id, "sp_a"))
        assertEquals(SpaceResult.Done("sp_b"), h.spaces.setPinned(id, "sp_b", true))

        val stored = h.core.value!!
        assertEquals(JsonPrimitive("keep-me"), stored.getValue("future_top"))
        val byId = h.storedSpaces().associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals("alpha", byId.getValue("sp_a").getValue("name").jsonPrimitive.content)
        assertEquals(JsonPrimitive("someone"), byId.getValue("sp_a").getValue("x_owner"))
        assertEquals(JsonPrimitive(true), byId.getValue("sp_b").getValue("pinned"))
        assertEquals(listOf("sp_b", "sp_a"), h.spaces.groups().value.single().spaces.map { it.id })
    }

    /**
     * Close kills the live agents that match the space (by label or by folder), skips a finished terminal and one that
     * matches another space, then removes the space.
     */
    @Test
    fun closeKillsTheLiveMatchingAgentsThenRemovesTheSpace() = runTest {
        val (h, id) = connectedWith(
            spacesDocJson(
                listOf(
                    spaceJson("sp_a", "web", "/home/u/web"),
                    spaceJson("sp_b", "api", "/home/u/api"),
                ),
            ),
        )
        h.core.terminals += terminalJson("t-cwd", cwd = "/home/u/web")
        h.core.terminals += terminalJson("t-label", cwd = "/tmp", labels = mapOf("whistl.space" to "sp_a"))
        h.core.terminals += terminalJson("t-other", cwd = "/home/u/api")
        h.core.terminals += terminalJson("t-finished", state = "finished", cwd = "/home/u/web")

        val result = h.spaces.close(id, "sp_a")

        assertEquals(SpaceResult.Done("sp_a"), result)
        assertEquals(listOf("t-cwd", "t-label"), h.core.killed)
        assertEquals(listOf("sp_b"), h.storedSpaces().map { it.getValue("id").jsonPrimitive.content })
    }

    /** An edit to a space that is not in the document is reported, not guessed. */
    @Test
    fun editingAnUnknownSpaceIsReported() = runTest {
        val (h, id) = connectedWith(spacesDocJson(listOf(spaceJson("sp_a", "a", "/a"))))

        assertEquals(SpaceResult.UnknownSpace("sp_missing"), h.spaces.rename(id, "sp_missing", "x"))
        assertEquals(SpaceResult.UnknownSpace("sp_missing"), h.spaces.close(id, "sp_missing"))
    }

    /** A `doc` event for a connected machine makes the repository read the document again. */
    @Test
    fun aDocumentChangeRereadsTheDocument() = runTest {
        val (h, id) = connectedWith(spacesDocJson(listOf(spaceJson("sp_a", "a", "/a"))))
        h.core.value = spacesDocJson(listOf(spaceJson("sp_a", "a", "/a"), spaceJson("sp_new", "new", "/new")))

        h.docChanges.notifyChanged(id)
        runCurrent()

        assertEquals(listOf("sp_a", "sp_new"), h.spaces.groups().value.single().spaces.map { it.id })
    }

    /** While the machine is not connected, a write is refused as MachineOffline and nothing is sent. */
    @Test
    fun aWriteWhileOfflineIsRefused() = runTest {
        val (h, id) = connectedWith(spacesDocJson(emptyList()))
        h.connector.lastSession!!.kill()
        runCurrent()

        assertEquals(SpaceResult.MachineOffline, h.spaces.create(id, folder = "/tmp"))
        assertTrue(h.storedSpaces().isEmpty())
    }

    /**
     * The compare-and-set retry, as one exact conversation through HcClient. A concurrent writer bumps the version
     * after this write has read the document. The repository re-reads, keeps the concurrent writer's space and the
     * unknown top-level field, adds its own space after it, and writes at the new version.
     *
     * Every request is derived in memory: the fixtures name their document `whistl-fixture`, and this repository writes
     * `whistl`. The fixture replies are used as they are, except where a field changes.
     */
    @Test
    fun casRetryKeepsTheConcurrentWritersSpaceAndUnknownFields() = runTest {
        val read = Fixtures.load("doc_get.json")
        val write = Fixtures.load("doc_set.json")
        val conflict = Fixtures.load("doc_set_conflict.json")
        val ourId = spaceIdsFrom { Random(7).nextBytes(it) }.next()
        val ourFirst = spaceJson(ourId, "web-app", "~/code/web-app", order = 0)
        val ourSecond = spaceJson(ourId, "web-app", "~/code/web-app", order = 1)
        val concurrent = spaceJson("sp_c", "other", "/other", order = 0, extra = mapOf("x_owner" to JsonPrimitive("other-app")))
        val topExtra = mapOf("future_top" to JsonPrimitive("keep-me"))
        fun whistl(request: JsonObject) = request.withParam("name", JsonPrimitive("whistl"))

        val h = RepoStack(this)
        h.connector.commandReply = scriptedCommands(
            Exchange(
                "derived in memory from doc_get.json (refresh on connect)",
                whistl(read.request),
                read.response,
            ),
            Exchange(
                "derived in memory from doc_get.json (read before the write)",
                whistl(read.request),
                read.response,
            ),
            Exchange(
                "derived in memory from doc_set_conflict.json: first write at version 1",
                whistl(conflict.request)
                    .withParam("version", JsonPrimitive(1))
                    .withParam("value", spacesDocJson(listOf(ourFirst))),
                conflictReply(conflict, currentVersion = 2, sent = 1),
            ),
            Exchange(
                "derived in memory from doc_get.json: a concurrent writer bumped the version to 2",
                whistl(read.request),
                read.response.withResult(
                    JsonObject(
                        mapOf(
                            "value" to spacesDocJson(listOf(concurrent), topExtra),
                            "version" to JsonPrimitive(2),
                        ),
                    ),
                ),
            ),
            Exchange(
                "derived in memory from doc_set.json: retry at version 2 with both spaces, reply version 3",
                whistl(write.request)
                    .withParam("value", spacesDocJson(listOf(concurrent, ourSecond), topExtra))
                    .withParam("version", JsonPrimitive(2)),
                versionReply(3),
            ),
        )
        val id = h.addMachine()
        runCurrent()

        val result = h.spaces.create(id, folder = "~/code/web-app")

        assertEquals(SpaceResult.Done(ourId), result)
        val group = h.spaces.groups().value.single()
        assertEquals(listOf("sp_c", ourId), group.spaces.map { it.id })
        assertEquals(JsonPrimitive("other-app"), group.spaces.first().unknownFields.getValue("x_owner"))
        assertEquals(JsonPrimitive("keep-me"), h.cache.get(id)!!.unknownFields.getValue("future_top"))
    }
}
