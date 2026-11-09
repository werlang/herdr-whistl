package io.github.werlang.whistl.core.rpc

/**
 * One frame on a framed connection (SPEC §5.1). The bytes after the framed `hello` reply are frames back to back, each
 * `channel u32 LE | kind u8 | length u32 LE | payload[length]`.
 *
 * @property channel the channel number as an unsigned 32-bit value; channel 0 carries the JSON-RPC messages.
 * @property kind the kind number as sent. A kind this client does not know is kept as sent, so the stream stays in step.
 * @property payload the payload bytes. [toString] leaves them out, so terminal content never reaches a log.
 */
class Frame(val channel: Long, val kind: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Frame && other.channel == channel && other.kind == kind && other.payload.contentEquals(payload)

    override fun hashCode(): Int = (channel.hashCode() * 31 + kind) * 31 + payload.contentHashCode()

    override fun toString(): String = "Frame(channel=$channel, kind=$kind, length=${payload.size})"
}

/** The frame kinds (SPEC §5.1). The numbers are the wire values, measured on hc 0.1.14. */
object FrameKind {
    /** Bytes of a channel: keys from the client; the first paint and live output from the core. On channel 0, one JSON-RPC message. */
    const val DATA = 0

    /** Client to core: the number of DATA payload bytes the client has handled, as u32 LE. */
    const val CREDIT = 1

    /** Client to core: detach, with an empty payload. Core to client: the channel ended; the payload is the exit facts as JSON. */
    const val CLOSE = 2

    /** Client to core: the viewer's size, columns then rows, each u16 LE. */
    const val RESIZE = 3

    /** Client to core: one byte, 0 for focus out and 1 for focus in. */
    const val FOCUS = 4

    /** Client to core: the viewer's colours and cell size as JSON, the shape of the `display` of `terminal.attach`. */
    const val FACTS = 5
}

/** Bytes in a frame header: channel (u32), kind (u8) and length (u32). */
const val FRAME_HEADER_BYTES: Int = 9

/** The largest payload a frame may declare: 16 MiB. A header that declares more is refused before its payload is read. */
const val MAX_FRAME_PAYLOAD: Long = 16L * 1024 * 1024

/**
 * The colours and cell size of the viewer's own terminal (SPEC §5.1). Sent with `terminal.attach` and with a FACTS frame.
 * A `null` field is left out, so the core keeps its default for it.
 *
 * @property fg the default foreground as `#rrggbb`.
 * @property bg the default background as `#rrggbb`.
 * @property cursor the cursor colour as `#rrggbb`.
 * @property cell the cell size in pixels, as width and height.
 */
data class ViewerDisplay(
    val fg: String? = null,
    val bg: String? = null,
    val cursor: String? = null,
    val cell: Pair<Int, Int>? = null,
)
