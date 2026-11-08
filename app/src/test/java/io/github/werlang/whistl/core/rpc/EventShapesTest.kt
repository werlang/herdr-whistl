package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.support.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event notifications hc sends (phase 6 P0-5), decoded from the supervisor's fixtures in `docs/protocol-fixtures/`. The shapes
 * and their status (recorded, synthetic, assumed) are in `docs/event-shapes.md`. Tests of the synthetic `missed` say `Synthetic`
 * in their names.
 */
class EventShapesTest {

    /** The `notification` object of a single-notification fixture. */
    private fun notification(name: String): JsonObject =
        Json.parseToJsonElement(Fixtures.text(name)).jsonObject.getValue("notification").jsonObject

    /** The `notifications` array of the synthetic fixture: the top-level `missed` shape, then the `event` shape. */
    private fun notifications(name: String): List<JsonObject> =
        Json.parseToJsonElement(Fixtures.text(name)).jsonObject.getValue("notifications").jsonArray.map { it.jsonObject }

    private fun method(n: JsonObject): String = n.getValue("method").jsonPrimitive.content

    /** The recorded heartbeat is a top-level `heartbeat` notification, not an `event`; it decodes to [HcEvent.Heartbeat]. */
    @Test
    fun theRecordedHeartbeatDecodesToHeartbeat() {
        val n = notification("event_heartbeat.json")

        assertEquals(HcEvent.Heartbeat(10640L), HcEvent.fromNotificationOrNull(method(n), n.getValue("params")))
    }

    /** The recorded doc event has a `data` object and no terminal; it decodes to [HcEvent.DocChanged] whatever the document is named. */
    @Test
    fun theRecordedDocEventDecodesToDocChangedWithoutATerminal() {
        val n = notification("event_doc.json")

        val doc = HcEvent.fromNotificationOrNull(method(n), n.getValue("params")) as HcEvent.DocChanged
        assertEquals(10643L, doc.seq)
        assertEquals("whistl.fixture", doc.data.getValue("name").jsonPrimitive.content)
    }

    /** The synthetic `missed` in its top-level shape keeps its snapshot: `terminals` is there for the reducer to decode. */
    @Test
    fun missedSyntheticTopLevelShapeKeepsItsSnapshot() {
        val n = notifications("event_missed_synthetic.json")[0]

        val missed = HcEvent.fromNotificationOrNull(method(n), n.getValue("params")) as HcEvent.Missed
        assertEquals(10700L, missed.seq)
        assertTrue(missed.data.getValue("terminals") is JsonArray)
    }

    /** The synthetic `missed` in its `event` shape decodes the same way: the snapshot sits in `data`. */
    @Test
    fun missedSyntheticEventShapeKeepsItsSnapshot() {
        val params = notifications("event_missed_synthetic.json")[1].getValue("params")

        val missed = HcEvent.fromParamsOrNull(params) as HcEvent.Missed
        assertEquals(10700L, missed.seq)
        assertTrue(missed.data.getValue("terminals") is JsonArray)
    }

    /** A notification whose method is not an event is not an event: it decodes to null. */
    @Test
    fun aNotificationThatIsNotAnEventDecodesToNull() {
        assertNull(HcEvent.fromNotificationOrNull("log", Json.parseToJsonElement("""{"seq":1}""")))
    }
}
