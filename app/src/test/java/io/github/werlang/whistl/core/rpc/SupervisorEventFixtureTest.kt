package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.support.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The three event fixtures the supervisor recorded or derived for phase 6 (`docs/protocol-fixtures/event_*.json`). They are not
 * request/response pairs, so they are read as they are: this test checks the shape each one claims, so a later edit that changes
 * a recorded shape fails. Decoding them is part of phase 6 round 2 (item 5).
 */
class SupervisorEventFixtureTest {

    /** Reads a fixture file as JSON. */
    private fun read(name: String): JsonObject {
        val file = File(Fixtures.repoRoot(), "docs/protocol-fixtures/$name")
        assertTrue("fixture missing: $name", file.isFile)
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    /** `event_doc.json`, recorded from hc 0.1.18: a `doc` event is an `event` notification with no terminal. */
    @Test
    fun eventDocIsAnEventNotificationWithoutATerminal() {
        val notification = read("event_doc.json").getValue("notification").jsonObject
        val params = notification.getValue("params").jsonObject

        assertEquals("event", notification.getValue("method").jsonPrimitive.content)
        assertEquals("doc", params.getValue("type").jsonPrimitive.content)
        assertFalse("a doc event names no terminal", params.containsKey("terminal"))
        assertEquals("whistl.fixture", params.getValue("data").jsonObject.getValue("name").jsonPrimitive.content)
    }

    /** `event_heartbeat.json`, recorded: a heartbeat is a top-level notification, not an `event` of type heartbeat. */
    @Test
    fun heartbeatIsATopLevelNotificationNotAnEvent() {
        val notification = read("event_heartbeat.json").getValue("notification").jsonObject

        assertEquals("heartbeat", notification.getValue("method").jsonPrimitive.content)
        assertNotNull("a heartbeat carries a seq", notification.getValue("params").jsonObject["seq"])
    }

    /**
     * `event_missed_synthetic.json`, synthetic: both shapes SPEC §5 allows for `missed`. The file says it is synthetic; the test
     * checks that it says so and that it holds both shapes.
     */
    @Test
    fun missedIsSyntheticAndHoldsBothSpecShapes() {
        val fixture = read("event_missed_synthetic.json")
        val notifications: JsonArray = fixture.getValue("notifications").jsonArray
        val methods = notifications.map { it.jsonObject.getValue("method").jsonPrimitive.content }

        assertTrue(
            "the file says hc did not emit it",
            fixture.getValue("synthetic").jsonPrimitive.content.contains("could not be made to emit missed"),
        )
        assertEquals(listOf("missed", "event"), methods)
        val typedMissed = notifications[1].jsonObject.getValue("params").jsonObject.getValue("type")
        assertEquals("missed", (typedMissed as JsonPrimitive).content)
    }
}
