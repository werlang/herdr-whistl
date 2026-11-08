package io.github.werlang.whistl.core.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
     * hc dropped events because this subscriber fell behind. A fresh snapshot follows.
     *
     * @property data hc's `data` object, kept raw.
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
