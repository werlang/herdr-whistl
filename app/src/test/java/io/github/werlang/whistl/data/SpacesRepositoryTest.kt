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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
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

    /**
     * R4 (phase 7 P2): a space stays under its machine after the machine is edited. Spaces are keyed by machine id, and an
     * edit keeps the id, so the same space is listed under the same machine after a rename and after a user change.
     */
    @Test
    fun spacesStayUnderTheSameMachineAfterAnEdit() = runTest {
        val (h, id) = connectedWith(spacesDocJson(emptyList()))
        val spaceId = (h.spaces.create(id, folder = "~/code/web-app") as SpaceResult.Done).spaceId
        runCurrent()

        h.machines.edit(id, NewMachine("renamed", "mac.local", 22, "root"), AuthMethod.Key)
        runCurrent()
        h.machines.edit(id, NewMachine("renamed", "mac.local", 22, "admin"), AuthMethod.Key)
        runCurrent()

        assertEquals(listOf(id), h.machines.machines().value.map { it.id })
        val group = h.spaces.groups().value.single()
        assertEquals(id, group.machineId)
        assertEquals(listOf(spaceId), group.spaces.map { it.id })
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

    /**
     * The desktop may keep several spaces on one folder. Adoption keeps them all, by id only; the tab map read from the
     * same document is kept for the machine (0.7.3).
     */
    @Test
    fun desktopSpacesThatShareAFolderAreAllAdoptedAndTheirTabsAreKept() = runTest {
        val h = RepoStack(this)
        h.core.value = spacesDocJson(emptyList())
        h.core.desktop = JsonObject(
            mapOf(
                "spaces" to JsonArray(
                    listOf(
                        desktopSpaceJson("sp74d8", "one", "/p/shared", tabs = listOf("tb1")),
                        desktopSpaceJson("sp75x", "two", "/p/shared", tabs = listOf("tb2")),
                    ),
                ),
            ),
        )

        val id = h.addMachine()
        runCurrent()

        val group = h.spaces.groups().value.single()
        assertEquals(id, group.machineId)
        assertEquals(listOf("sp74d8", "sp75x"), group.spaces.map { it.id })
        assertEquals(listOf("/p/shared", "/p/shared"), group.spaces.map { it.folder })
        assertEquals(mapOf("tb1" to "sp74d8", "tb2" to "sp75x"), group.tabSpaces)
    }

    /**
     * The rpi5 shape: the desktop's spaces have `folder: ""` and a name of their own. They are adopted with that name, the
     * empty folder is not probed, and the tabs keep the names the desktop gave them.
     */
    @Test
    fun desktopSpacesWithBlankFoldersAreAdoptedWithTheirNamesAndNotProbed() = runTest {
        val h = RepoStack(this)
        h.core.value = spacesDocJson(emptyList())
        h.core.desktop = JsonObject(
            mapOf(
                "spaces" to JsonArray(
                    listOf(
                        desktopSpaceJson(
                            "sp9aed68",
                            "gladcode2",
                            "",
                            tabs = listOf("tb987745"),
                            tabNames = mapOf("tb987745" to "Claude Code"),
                        ),
                        desktopSpaceJson("sp646103", "cv-mapa", ""),
                        desktopSpaceJson("sp820ec2", "node-aec", "/root"),
                    ),
                ),
            ),
        )

        h.addMachine()
        runCurrent()

        val group = h.spaces.groups().value.single()
        assertEquals(listOf("sp9aed68", "sp646103", "sp820ec2"), group.spaces.map { it.id })
        assertEquals(listOf("gladcode2", "cv-mapa", "node-aec"), group.spaces.map { it.name })
        assertEquals(listOf("", "", "/root"), group.spaces.map { it.folder })
        assertEquals(mapOf("tb987745" to "sp9aed68"), group.tabSpaces)
        assertEquals(mapOf("tb987745" to "Claude Code"), group.tabNames)
        assertEquals("only the real folder is probed", listOf("/root"), h.core.probeFolders)
    }

    /**
     * The adopted name follows the desktop document until the user renames the space here. A refresh with the same document
     * writes nothing; a desktop rename is written once; an explicit Whistl rename survives later desktop changes.
     */
    @Test
    fun adoptedNamesFollowTheDesktopUntilAWhistlRenameWins() = runTest {
        val h = RepoStack(this)
        h.core.value = spacesDocJson(emptyList())
        h.core.desktop = desktopDoc("sp9aed68", "gladcode2")
        val machineId = h.addMachine()
        runCurrent()
        assertEquals("gladcode2", h.spaces.groups().value.single().spaces.single().name)

        h.core.methods.clear()
        h.docChanges.notifyChanged(machineId)
        runCurrent()
        assertFalse("an unchanged name is not rewritten", h.core.methods.contains("doc.set"))

        h.core.desktop = desktopDoc("sp9aed68", "gladcode2 production")
        h.docChanges.notifyChanged(machineId)
        runCurrent()
        assertEquals("gladcode2 production", h.spaces.groups().value.single().spaces.single().name)

        assertEquals(SpaceResult.Done("sp9aed68"), h.spaces.rename(machineId, "sp9aed68", "mine"))
        runCurrent()
        h.core.desktop = desktopDoc("sp9aed68", "something else")
        h.docChanges.notifyChanged(machineId)
        runCurrent()
        assertEquals("mine", h.spaces.groups().value.single().spaces.single().name)
    }

    /**
     * A space adopted before the override marker existed is aligned to the desktop on the first refresh, even when its stored
     * name came from the old import. The folder follows the desktop too, while pin, order and a Rename made after the upgrade
     * stay the user's.
     */
    @Test
    fun aLegacyAdoptedSpaceAlignsOnUpgradeAndKeepsItsRenameLater() = runTest {
        val h = RepoStack(this)
        h.core.value = spacesDocJson(
            listOf(spaceJson("sp9aed68", "old imported", "/old", pinned = true, order = 7)),
        )
        h.core.desktop = desktopDoc("sp9aed68", "gladcode2")
        val machineId = h.addMachine()
        runCurrent()

        val aligned = h.spaces.groups().value.single().spaces.single()
        assertEquals("gladcode2", aligned.name)
        assertEquals("/root", aligned.folder)
        assertEquals(true, aligned.pinned)
        assertEquals(7, aligned.order)

        assertEquals(SpaceResult.Done("sp9aed68"), h.spaces.rename(machineId, "sp9aed68", "mine"))
        h.core.desktop = JsonObject(
            mapOf(
                "spaces" to JsonArray(
                    listOf(desktopSpaceJson("sp9aed68", "later desktop", "")),
                ),
            ),
        )
        h.docChanges.notifyChanged(machineId)
        runCurrent()

        val after = h.spaces.groups().value.single().spaces.single()
        assertEquals("mine", after.name)
        assertEquals("the desktop still owns the folder", "", after.folder)
        assertEquals(true, after.pinned)
        assertEquals(7, after.order)
    }

    /**
     * A desktop folder can become blank after adoption. The record takes the new folder, so it no longer matches by a stale
     * cwd and no folder line is invented; its name, pin and order stay as they were.
     */
    @Test
    fun anAdoptedSpaceFollowsAFolderThatBecomesBlank() = runTest {
        val h = RepoStack(this)
        h.core.value = spacesDocJson(emptyList())
        h.core.desktop = desktopDoc("sp9aed68", "gladcode2")
        val machineId = h.addMachine()
        runCurrent()
        assertEquals("/root", h.spaces.groups().value.single().spaces.single().folder)

        h.core.desktop = JsonObject(
            mapOf(
                "spaces" to JsonArray(
                    listOf(desktopSpaceJson("sp9aed68", "gladcode2", "")),
                ),
            ),
        )
        h.docChanges.notifyChanged(machineId)
        runCurrent()

        val space = h.spaces.groups().value.single().spaces.single()
        assertEquals("", space.folder)
        assertEquals("gladcode2", space.name)
        assertEquals(false, space.pinned)
        assertEquals(0, space.order)
    }

    /**
     * A compare-and-set conflict re-reads the desktop document as well: the retry must not write a name from a revision that
     * the newer desktop read already replaced.
     */
    @Test
    fun aConflictingWriteRereadsTheDesktopBeforeRetrying() = runTest {
        val h = RepoStack(this)
        h.core.value = spacesDocJson(emptyList())
        h.core.desktop = desktopDoc("sp9aed68", "first")
        var changed = false
        h.core.beforeSet = { core ->
            if (!changed) {
                changed = true
                core.desktop = desktopDoc("sp9aed68", "newest")
                core.version += 1
            }
        }

        h.addMachine()
        runCurrent()

        assertEquals("newest", h.spaces.groups().value.single().spaces.single().name)
    }

    /**
     * A desktop document that cannot be read keeps the last tab map and names, so a refresh does not make a named terminal
     * anonymous again; the written spaces stay as they were.
     */
    @Test
    fun anUnavailableDesktopDocumentKeepsTheLastTabNames() = runTest {
        val h = RepoStack(this)
        h.core.value = spacesDocJson(emptyList())
        h.core.desktop = JsonObject(
            mapOf(
                "spaces" to JsonArray(
                    listOf(
                        desktopSpaceJson(
                            "sp9aed68",
                            "gladcode2",
                            "/root",
                            tabs = listOf("tb1"),
                            tabNames = mapOf("tb1" to "Claude Code"),
                        ),
                    ),
                ),
            ),
        )
        val machineId = h.addMachine()
        runCurrent()
        assertEquals(mapOf("tb1" to "Claude Code"), h.spaces.groups().value.single().tabNames)

        h.core.desktopReadFails = true
        h.docChanges.notifyChanged(machineId)
        runCurrent()

        val group = h.spaces.groups().value.single()
        assertEquals(mapOf("tb1" to "sp9aed68"), group.tabSpaces)
        assertEquals(mapOf("tb1" to "Claude Code"), group.tabNames)
        assertEquals(listOf("gladcode2"), group.spaces.map { it.name })
    }

    /**
     * Close kills an agent that reaches the space through the desktop's tab map, even when its cwd does not match and its
     * `herdr.space` is unknown (0.7.3).
     */
    @Test
    fun closeKillsAnAgentThatReachesTheSpaceThroughItsDesktopTab() = runTest {
        val h = RepoStack(this)
        h.core.value = spacesDocJson(listOf(spaceJson("sp_a", "web", "/home/u/web")))
        h.core.desktop = JsonObject(
            mapOf(
                "spaces" to JsonArray(
                    listOf(desktopSpaceJson("sp_a", "web", "/home/u/web", tabs = listOf("tb1"))),
                ),
            ),
        )
        h.core.terminals += terminalJson(
            "t-tab",
            cwd = "/tmp",
            labels = mapOf("herdr.tab" to "tb1", "herdr.space" to "sp_unknown"),
        )
        h.addMachine()
        runCurrent()

        val result = h.spaces.close(h.machines.machines().value.single().id, "sp_a")

        assertEquals(SpaceResult.Done("sp_a"), result)
        assertEquals(listOf("t-tab"), h.core.killed)
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
        fun doc(name: String, request: JsonObject) = request.withParam("name", JsonPrimitive(name))
        fun whistl(request: JsonObject) = doc("whistl", request)

        // Every refresh also reads the Herdr desktop's document, to adopt the spaces made there (0.7.3). Empty here.
        fun desktop(read: JsonObject, response: JsonObject) = Exchange(
            "derived in memory from doc_get.json: the desktop keeps no spaces",
            doc("herdr-app", read),
            response.withResult(
                JsonObject(mapOf("value" to JsonNull, "version" to JsonPrimitive(1))),
            ),
        )

        val h = RepoStack(this)
        h.connector.commandReply = scriptedCommands(
            Exchange(
                "derived in memory from doc_get.json (refresh on connect)",
                whistl(read.request),
                read.response,
            ),
            desktop(read.request, read.response),
            Exchange(
                "derived in memory from doc_get.json (read before the write)",
                whistl(read.request),
                read.response,
            ),
            // The create path does not refresh, so no desktop read here.
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
            // F4: after the write, every folder of the document not yet resolved on this connection is resolved, in document
            // order: the concurrent writer's /other first, then the new ~/code/web-app (HOME first, then its probe).
            *probeExchanges("/other", "tp1").toTypedArray(),
            Exchange(
                "derived in memory: machine.exec printenv HOME for ~ in ~/code/web-app",
                rpc("machine.exec", "argv" to JsonArray(listOf(JsonPrimitive("printenv"), JsonPrimitive("HOME")))),
                ok(
                    JsonObject(
                        mapOf(
                            "code" to JsonPrimitive(0),
                            "ms" to JsonPrimitive(1),
                            "signal" to JsonNull,
                            "stderr" to JsonPrimitive(""),
                            "stdout" to JsonPrimitive(Base64.getEncoder().encodeToString("/home/u\n".toByteArray())),
                            "timed_out" to JsonPrimitive(false),
                            "truncated" to JsonPrimitive(false),
                        ),
                    ),
                ),
            ),
            *probeExchanges("/home/u/code/web-app", "tp2").toTypedArray(),
        )
        val id = h.addMachine()
        runCurrent()

        val result = h.spaces.create(id, folder = "~/code/web-app")
        // The new folder is resolved right after the write (phase 4 r2, F4): the probe conversation follows the write.
        runCurrent()

        assertEquals(SpaceResult.Done(ourId), result)
        val group = h.spaces.groups().value.single()
        assertEquals(listOf("sp_c", ourId), group.spaces.map { it.id })
        assertEquals(JsonPrimitive("other-app"), group.spaces.first().unknownFields.getValue("x_owner"))
        assertEquals(JsonPrimitive("keep-me"), h.cache.get(id)!!.unknownFields.getValue("future_top"))
    }

    /**
     * The four exchanges of one folder probe, derived in memory from the recorded `run.json`, `unknown_terminal.json` and
     * `rm.json` shapes: `pwd` in [folder], the poll that finds it finished, its answer, and its removal.
     *
     * @param folder the folder the probe runs in.
     * @param address the terminal address hc gives the probe.
     * @return the exchanges, in order.
     */
    private fun probeExchanges(folder: String, address: String): List<Exchange> {
        val probeLabels = JsonObject(mapOf("whistl.probe" to JsonPrimitive("1")))
        return listOf(
            Exchange(
                "derived in memory: pwd probe in $folder",
                rpc(
                    "terminal.run",
                    "argv" to JsonArray(listOf(JsonPrimitive("pwd"))),
                    "cwd" to JsonPrimitive(folder),
                    "labels" to probeLabels,
                    "no_parent" to JsonPrimitive(true),
                    "no_notify" to JsonPrimitive(true),
                ),
                ok(terminalJson(address, state = "live", labels = mapOf("whistl.probe" to "1"), cwd = folder)),
            ),
            Exchange(
                "derived in memory: the probe is finished when listed",
                rpc("terminal.list", "labels" to probeLabels, "all" to JsonPrimitive(true)),
                ok(
                    JsonObject(
                        mapOf(
                            "terminals" to JsonArray(
                                listOf(terminalJson(address, state = "finished", labels = mapOf("whistl.probe" to "1"))),
                            ),
                            "machines" to JsonArray(emptyList()),
                            "hidden_finished" to JsonPrimitive(0),
                        ),
                    ),
                ),
            ),
            Exchange(
                "derived in memory: the probe's answer",
                rpc("terminal.read", "terminal" to JsonPrimitive(address)),
                ok(
                    JsonObject(
                        mapOf(
                            "text" to JsonPrimitive("$folder\n"),
                            "complete" to JsonPrimitive(true),
                            "how" to JsonPrimitive("history"),
                        ),
                    ),
                ),
            ),
            Exchange(
                "derived in memory: the probe is removed",
                rpc("terminal.rm", "terminal" to JsonPrimitive(address)),
                ok(JsonObject(mapOf("removed" to JsonPrimitive(address)))),
            ),
        )
    }

    /** A desktop space object for [HERDR_DOCUMENT], with the tabs it owns and their names (0.7.3). */
    private fun desktopSpaceJson(
        id: String,
        name: String,
        folder: String,
        tabs: List<String> = emptyList(),
        tabNames: Map<String, String> = emptyMap(),
    ): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "name" to JsonPrimitive(name),
            "folder" to JsonPrimitive(folder),
            "tabs" to JsonArray(
                tabs.map { tab ->
                    JsonObject(
                        mapOf<String, JsonElement>("id" to JsonPrimitive(tab)) +
                            (tabNames[tab]?.let { mapOf("name" to JsonPrimitive(it)) } ?: emptyMap()),
                    )
                },
            ),
        ),
    )

    /** A desktop document with one space and no tabs. */
    private fun desktopDoc(id: String, name: String): JsonObject = JsonObject(
        mapOf("spaces" to JsonArray(listOf(desktopSpaceJson(id, name, "/root")))),
    )

    /** A request object for [method] with the given params (no id; [Exchange] compares without it). */
    private fun rpc(method: String, vararg params: Pair<String, JsonElement>): JsonObject = JsonObject(
        mapOf(
            "jsonrpc" to JsonPrimitive("2.0"),
            "method" to JsonPrimitive(method),
            "params" to JsonObject(mapOf(*params)),
        ),
    )

    /** A success reply carrying [result]. */
    private fun ok(result: JsonObject): JsonObject = JsonObject(
        mapOf("jsonrpc" to JsonPrimitive("2.0"), "result" to result),
    )
}
