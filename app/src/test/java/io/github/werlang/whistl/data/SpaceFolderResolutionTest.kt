package io.github.werlang.whistl.data

import io.github.werlang.whistl.support.FakeMachineConnector
import io.github.werlang.whistl.support.RepoStack
import io.github.werlang.whistl.support.eventLine
import io.github.werlang.whistl.support.eventsSnapshot
import io.github.werlang.whistl.support.snapshotPipe
import io.github.werlang.whistl.support.spaceJson
import io.github.werlang.whistl.support.spacesDocJson
import io.github.werlang.whistl.support.terminalJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folder resolution through the spaces and agents repositories (phase 4 decision 1), on virtual time against [RepoStack].
 * The resolver's own cases are in [FolderResolverTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpaceFolderResolutionTest {

    /** A stack whose machine is connected, with [document] on the fake core and [events] in its snapshot. */
    private suspend fun TestScope.connectedWith(
        document: JsonObject,
        events: List<JsonObject> = emptyList(),
    ): Pair<RepoStack, String> {
        val h = RepoStack(this)
        h.core.value = document
        h.connector.eventsPipeFactory = { snapshotPipe(eventsSnapshot(1, events)) }
        val id = h.addMachine()
        runCurrent()
        return h to id
    }

    /** On connect every folder is resolved: `~` by the home directory, an absolute folder as it is. A shared folder is probed once. */
    @Test
    fun connectingResolvesEachSpaceFolderOnce() = runTest {
        val (h, _) = connectedWith(
            spacesDocJson(
                listOf(
                    spaceJson("sp_a", "web", "~/code/web"),
                    spaceJson("sp_b", "app", "/srv/app"),
                    spaceJson("sp_c", "web-copy", "~/code/web"),
                ),
            ),
        )

        assertEquals(listOf("/home/u/code/web", "/srv/app"), h.core.probeFolders)
        val resolutions = h.spaces.groups().value.single().resolutions
        assertEquals(FolderResolution.Resolved("/home/u/code/web"), resolutions["sp_a"])
        assertEquals(FolderResolution.Resolved("/home/u/code/web"), resolutions["sp_c"])
        assertEquals(FolderResolution.Resolved("/srv/app"), resolutions["sp_b"])
    }

    /** The `whistl` document keeps the folder exactly as typed, after resolution and after an edit. */
    @Test
    fun theDocumentKeepsTheFolderAsTyped() = runTest {
        val (h, id) = connectedWith(spacesDocJson(listOf(spaceJson("sp_a", "web", "~/code/web"))))
        assertEquals(FolderResolution.Resolved("/home/u/code/web"), h.spaces.groups().value.single().resolutions["sp_a"])

        assertEquals(SpaceResult.Done("sp_a"), h.spaces.rename(id, "sp_a", "web-2"))

        val folder = h.core.value!!.getValue("spaces").jsonArray.single().jsonObject.getValue("folder").jsonPrimitive.content
        assertEquals("~/code/web", folder)
    }

    /** A `doc` change resolves the new folder only; the folder already resolved on this connection is not probed again. */
    @Test
    fun aDocumentChangeResolvesOnlyTheNewFolder() = runTest {
        val (h, id) = connectedWith(spacesDocJson(listOf(spaceJson("sp_a", "web", "~/code/web"))))
        h.core.value = spacesDocJson(
            listOf(spaceJson("sp_a", "web", "~/code/web"), spaceJson("sp_new", "new", "/srv/new")),
        )

        h.docChanges.notifyChanged(id)
        runCurrent()

        assertEquals(listOf("/home/u/code/web", "/srv/new"), h.core.probeFolders)
        assertEquals(FolderResolution.Resolved("/srv/new"), h.spaces.groups().value.single().resolutions["sp_new"])
    }

    /**
     * A machine that never connected shows the path kept on this device for its folder, flagged offline. No hc call is made:
     * the connection is refused.
     */
    @Test
    fun aCachedPathIsShownOfflineWithoutAnyProbe() = runTest {
        val h = RepoStack(this)
        h.connector.script(FakeMachineConnector.Attempt.Unreachable)
        h.cache.put("m1", SpacesDocument.fromJson(spacesDocJson(listOf(spaceJson("sp_a", "web", "~/code/web")))))
        h.folderCache.put("m1", "~/code/web", "/home/u/code/web")

        h.addMachine()
        runCurrent()

        val group = h.spaces.groups().value.single()
        assertTrue(group.offline)
        assertEquals(FolderResolution.Resolved("/home/u/code/web"), group.resolutions["sp_a"])
        assertEquals(emptyList<String>(), h.core.methods)
    }

    /**
     * A folder from the document that holds a line break or a NUL is never probed: no `terminal.run` reaches the core for it, and
     * its row is [UnresolvableReason.Invalid]. A `~` folder with a break is refused before the home directory is read (phase 6 P0-9).
     */
    @Test
    fun aDocumentFolderWithALineBreakOrNulIsInvalidAndNeverProbed() = runTest {
        val (h, _) = connectedWith(
            spacesDocJson(
                listOf(
                    spaceJson("sp_break", "break", "/srv/app\n/evil"),
                    spaceJson("sp_nul", "nul", "/srv/\u0000app"),
                    spaceJson("sp_tilde", "tilde", "~/code\rweb"),
                    spaceJson("sp_ok", "ok", "/srv/app"),
                ),
            ),
        )

        assertEquals(listOf("/srv/app"), h.core.probeFolders)
        val resolutions = h.spaces.groups().value.single().resolutions
        assertEquals(FolderResolution.Unresolvable(UnresolvableReason.Invalid), resolutions["sp_break"])
        assertEquals(FolderResolution.Unresolvable(UnresolvableReason.Invalid), resolutions["sp_nul"])
        assertEquals(FolderResolution.Unresolvable(UnresolvableReason.Invalid), resolutions["sp_tilde"])
        assertEquals(FolderResolution.Resolved("/srv/app"), resolutions["sp_ok"])
    }

    /** A new space's folder resolves right after the create, on the same connection, without any `doc` event (F4). */
    @Test
    fun aNewSpaceResolvesRightAwayWithoutADocEvent() = runTest {
        val (h, id) = connectedWith(spacesDocJson(emptyList()))

        val result = h.spaces.create(id, folder = "/srv/new") as SpaceResult.Done
        runCurrent()

        assertEquals(
            FolderResolution.Resolved("/srv/new"),
            h.spaces.groups().value.single().resolutions[result.spaceId],
        )
        assertTrue(h.core.probeFolders.contains("/srv/new"))
    }

    /** Removing a machine: its spaces leave the app, so its cached document and its resolved folders go too (F3, SPEC §2). */
    @Test
    fun removingAMachineClearsItsSpacesAndItsResolvedFolders() = runTest {
        val (h, id) = connectedWith(spacesDocJson(listOf(spaceJson("sp_a", "web", "~/code/web"))))
        assertEquals("/home/u/code/web", h.folderCache.get(id, "~/code/web"))
        assertTrue(h.cache.get(id) != null)

        h.machines.remove(id)
        runCurrent()

        assertNull(h.cache.get(id))
        assertNull(h.folderCache.get(id, "~/code/web"))
        assertTrue(h.spaces.groups().value.isEmpty())
    }

    /** A folder hc cannot start in is a typed error on its row. It never matches by cwd; a label still matches it. */
    @Test
    fun anUnresolvableFolderNeverMatchesByCwdButTheLabelStillWins() = runTest {
        val h = RepoStack(this)
        h.core.missingFolders += "/gone"
        h.core.value = spacesDocJson(listOf(spaceJson("sp_gone", "gone", "/gone"), spaceJson("sp_ok", "ok", "/srv/ok")))
        h.connector.eventsPipeFactory = {
            snapshotPipe(
                eventsSnapshot(
                    1,
                    listOf(
                        terminalJson("t-cwd", cwd = "/gone"),
                        terminalJson("t-label", cwd = "/gone", labels = mapOf("whistl.space" to "sp_gone")),
                    ),
                ),
            )
        }
        h.addMachine()
        runCurrent()

        assertEquals(
            FolderResolution.Unresolvable(UnresolvableReason.NotFound),
            h.spaces.groups().value.single().resolutions["sp_gone"],
        )
        val spaceOf = h.agents.agents.value.associate { it.agent.terminal.address to it.space?.id }
        assertEquals(mapOf("t-cwd" to null, "t-label" to "sp_gone"), spaceOf)
    }

    /** An agent started in a folder that resolved matches the space by its resolved cwd. */
    @Test
    fun anAgentInAResolvedFolderMatchesTheSpaceByCwd() = runTest {
        val (h, _) = connectedWith(
            spacesDocJson(listOf(spaceJson("sp_web", "web", "~/code/web"))),
            events = listOf(terminalJson("t-cwd", cwd = "/home/u/code/web")),
        )

        assertEquals(listOf("sp_web"), h.agents.agents.value.mapNotNull { it.space?.id })
    }

    /** A folder probe is never an agent: not in the snapshot, not on a `created` event, and not counted for the badge. */
    @Test
    fun aFolderProbeIsHiddenFromTheAgents() = runTest {
        val probe = mapOf(PROBE_LABEL to "1")
        val (h, _) = connectedWith(
            spacesDocJson(emptyList()),
            events = listOf(
                terminalJson("t-agent", status = "blocked"),
                terminalJson("tp-snapshot", status = "blocked", labels = probe),
            ),
        )

        h.pushEvent(eventLine("created", "tp-new", 3, terminalJson("tp-new", status = "blocked", labels = probe)))
        runCurrent()

        assertEquals(listOf("t-agent"), h.agents.agents.value.map { it.agent.terminal.address })
        assertEquals(1, h.agents.blockedCount.value)
    }
}
