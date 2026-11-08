package io.github.werlang.whistl.core.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The terminal event kinds that carry a terminal address and change one terminal (SPEC §5).
 *
 * @property wire the `type` string hc sends.
 */
enum class TerminalEventKind(val wire: String) {
    /** A terminal was started. */
    Created("created"),

    /** A terminal was lost (its core process went away). */
    Lost("lost"),

    /** A terminal was restarted. */
    Restarted("restarted"),

    /** The keep flag changed. */
    Keep("keep"),

    /** The process exited. */
    Finished("finished"),

    /** The terminal was removed from the core. */
    Removed("removed"),

    /** Agent status changed. */
    Status("status"),

    /** Turn counter changed. */
    Turn("turn"),

    /** Title changed. */
    Title("title"),

    /** Terminal size changed. */
    Size("size"),
}

/**
 * An `event` notification from `events.subscribe`, parsed into a sealed type (SPEC §5).
 *
 * Choice for unknown `type` values: they parse to [Unknown] and never throw, so a newer hc cannot crash the app.
 *
 * @property seq hc's sequence number, or `null` when the notification has none.
 */
sealed class HcEvent {
    abstract val seq: Long?

    /**
     * A change to one terminal. [data] is kept raw: its shape depends on [kind].
     *
     * @property terminal the terminal address.
     * @property kind which change.
     * @property data hc's `data` object for the event.
     */
    data class TerminalChanged(
        override val seq: Long?,
        val terminal: String,
        val kind: TerminalEventKind,
        val data: JsonObject,
    ) : HcEvent()

    /**
     * A terminal's labels changed. [labels] is the full label set after the change.
     *
     * @property terminal the terminal address.
     * @property labels the complete label map after the change.
     */
    data class LabelsChanged(
        override val seq: Long?,
        val terminal: String,
        val labels: Map<String, String>,
    ) : HcEvent()

    /**
     * The `whistl` document changed on this core.
     *
     * @property data hc's `data` object, kept raw.
     */
    data class DocChanged(
        override val seq: Long?,
        val data: JsonObject,
    ) : HcEvent()

    /** Keep-alive from hc, sent every 15 s. It resets the watchdog and changes no state. */
    data class Heartbeat(override val seq: Long?) : HcEvent()

    /**
     * hc dropped events because this subscriber fell behind. A fresh snapshot follows. The snapshot is the `terminals` array of
     * [data] (the `event` shape keeps it in `data`, the top-level shape in the params); the reducer decodes it with the subscribe
     * result's wire type (phase 6 P0-5, `docs/event-shapes.md`).
     *
     * @property data hc's `data` object, or the top-level params, kept raw.
     */
    data class Missed(
        override val seq: Long?,
        val data: JsonObject,
    ) : HcEvent()

    /**
     * An event type this client does not know. Kept so the caller can log its count, not its content.
     *
     * @property type the `type` string as sent.
     * @property terminal the terminal address, if the event named one.
     * @property data hc's `data` object, kept raw.
     */
    data class Unknown(
        override val seq: Long?,
        val type: String,
        val terminal: String?,
        val data: JsonObject,
    ) : HcEvent()

    companion object {
        /** The method of an `event` notification (SPEC §5). */
        internal const val EVENT_METHOD: String = "event"

        /** The top-level keep-alive notification hc sends every 15 s (recorded, `event_heartbeat.json`). */
        internal const val HEARTBEAT_METHOD: String = "heartbeat"

        /** The top-level notification that replaces dropped events with a snapshot (synthetic, `event_missed_synthetic.json`). */
        internal const val MISSED_METHOD: String = "missed"

        /**
         * Parses one notification of the events channel (phase 6 P0-5). Besides `event`, hc sends two top-level notifications:
         * `heartbeat`, which decodes to [Heartbeat], and `missed`, whose params carry the snapshot. Any other method is not an event
         * and gives `null`; so do `event` params that do not decode.
         *
         * @param method the notification's method.
         * @param params the raw `params`.
         * @return the event, or `null`.
         */
        fun fromNotificationOrNull(method: String, params: JsonElement): HcEvent? = when (method) {
            EVENT_METHOD -> fromParamsOrNull(params)
            HEARTBEAT_METHOD -> Heartbeat(seqOf(params))
            MISSED_METHOD -> (params as? JsonObject)?.let { Missed(seqOf(params), it) }
            else -> null
        }

        /** The `seq` of a params object, or `null` when it has none. */
        private fun seqOf(params: JsonElement): Long? =
            (params as? JsonObject)?.get("seq")?.let { (it as? JsonPrimitive)?.longOrNull }

        /**
         * Parses the `params` of an `event` notification.
         *
         * @param params the raw `params` element.
         * @return the parsed event; never throws for an unknown type.
         */
        fun fromParams(params: JsonElement): HcEvent {
            val wire = ResponseJson.decodeFromJsonElement(EventWire.serializer(), params)
            val data = wire.data ?: JsonObject(emptyMap())
            val terminal = wire.terminal
            val kind = TerminalEventKind.entries.firstOrNull { it.wire == wire.type }
            return when {
                kind != null && terminal != null -> TerminalChanged(wire.seq, terminal, kind, data)
                wire.type == "labels" && terminal != null -> LabelsChanged(wire.seq, terminal, labelsOf(data))
                wire.type == "doc" -> DocChanged(wire.seq, data)
                wire.type == "heartbeat" -> Heartbeat(wire.seq)
                wire.type == "missed" -> Missed(wire.seq, data)
                else -> Unknown(wire.seq, wire.type, terminal, data)
            }
        }

        /**
         * Like [fromParams], but returns `null` for params that are not an event object: a `null`, an array, a wrong field type.
         * The events pipeline uses this, so one bad line from the core drops that line and nothing else (phase 6 P0-2).
         *
         * @param params the raw `params` element.
         * @return the parsed event, or `null` when the params do not decode.
         */
        fun fromParamsOrNull(params: JsonElement): HcEvent? = try {
            fromParams(params)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        /** Reads `data.labels` as a string map. Non-string values are skipped. */
        private fun labelsOf(data: JsonObject): Map<String, String> {
            val labels = data["labels"] as? JsonObject ?: return emptyMap()
            return labels.entries.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
            }.toMap()
        }
    }
}
