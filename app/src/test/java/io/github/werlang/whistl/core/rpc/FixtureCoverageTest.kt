package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Keeps the fixture set and the tests in step (phase brief §2).
 *
 * The directory must hold exactly [KNOWN_FIXTURES]. Each of them must be named in at least one test source
 * file, so no recorded fixture sits unused. This file is excluded from that search, so its own list cannot
 * count as a reference.
 */
class FixtureCoverageTest {

    /** The fixture directory lists exactly the known fixtures: a new or removed file fails here. */
    @Test
    fun directoryHoldsExactlyTheKnownFixtures() {
        assertEquals(KNOWN_FIXTURES.size, Fixtures.names().size)
        assertEquals(KNOWN_FIXTURES.sorted(), Fixtures.names())
    }

    /** Every known fixture is named by at least one test (as a quoted file name). */
    @Test
    fun everyFixtureIsReferencedByATest() {
        val sources = testSources()
        val unreferenced = KNOWN_FIXTURES.filter { name -> sources.none { it.contains("\"$name\"") } }
        assertTrue("fixtures not referenced by any test: $unreferenced", unreferenced.isEmpty())
    }

    /** A missing fixture fails with a clear message. It never skips or returns empty. */
    @Test
    fun missingFixtureFailsLoudly() {
        try {
            Fixtures.load("no_such_fixture.json")
            fail("loading a missing fixture must fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("fixture missing"))
        }
    }

    /** The Kotlin source of every test under `app/src/test`, except this file. */
    private fun testSources(): List<String> {
        val root = File(Fixtures.repoRoot(), "app/src/test/java")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "FixtureCoverageTest.kt" }
            .map { it.readText() }
            .toList()
    }

    private companion object {
        /** All 21 recorded fixtures in `docs/protocol-fixtures/`, sorted. */
        val KNOWN_FIXTURES: List<String> = listOf(
            "doc_get.json",
            "doc_get_missing.json",
            "doc_set.json",
            "doc_set_conflict.json",
            "event_doc.json",
            "event_heartbeat.json",
            "event_missed_synthetic.json",
            "events_seen.json",
            "exec_installed.json",
            "hello.json",
            "interrupt.json",
            "keys.json",
            "kill.json",
            "label.json",
            "list_by_label.json",
            "machine_list.json",
            "read.json",
            "rm.json",
            "rm_live.json",
            "run.json",
            "run_spawn_failed.json",
            "send.json",
            "subscribe.json",
            "unknown_terminal.json",
        )
    }
}
