package io.github.werlang.whistl.core.rpc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Encodes frames to wire bytes (SPEC §5.1). Each function returns one complete frame, header included.
 */
object FrameCodec {
    private const val U8_MAX = 0xFF
    private const val U16_MAX = 0xFFFF
    private const val U32_MAX = 0xFFFFFFFFL

    /**
     * Encodes one frame.
     *
     * @param channel the channel number, 0 to 2^32 - 1.
     * @param kind the kind number, 0 to 255.
     * @param payload the payload bytes.
     * @return the header and the payload.
     * @throws IllegalArgumentException when a field is out of range, or the payload is above [MAX_FRAME_PAYLOAD].
     */
    fun encode(channel: Long, kind: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(channel in 0..U32_MAX) { "channel is not a u32" }
        require(kind in 0..U8_MAX) { "kind is not a u8" }
        require(payload.size <= MAX_FRAME_PAYLOAD) { "payload is above the frame limit" }
        return ByteBuffer.allocate(FRAME_HEADER_BYTES + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(channel.toInt())
            .put(kind.toByte())
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    /** A DATA frame: keys or pasted bytes for the program. */
    fun data(channel: Long, bytes: ByteArray): ByteArray = encode(channel, FrameKind.DATA, bytes)

    /**
     * A RESIZE frame: the viewer's size in cells.
     *
     * @throws IllegalArgumentException when a size is not a u16.
     */
    fun resize(channel: Long, cols: Int, rows: Int): ByteArray {
        require(cols in 0..U16_MAX && rows in 0..U16_MAX) { "size is not a u16" }
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(cols.toShort())
            .putShort(rows.toShort())
            .array()
        return encode(channel, FrameKind.RESIZE, payload)
    }

    /**
     * A CREDIT frame: the client has handled [consumed] DATA payload bytes from the core.
     *
     * @throws IllegalArgumentException when [consumed] is not a u32.
     */
    fun credit(channel: Long, consumed: Long): ByteArray {
        require(consumed in 0..U32_MAX) { "credit is not a u32" }
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(consumed.toInt()).array()
        return encode(channel, FrameKind.CREDIT, payload)
    }

    /** A FOCUS frame: 0 for focus out, 1 for focus in. */
    fun focus(channel: Long, focused: Boolean): ByteArray =
        encode(channel, FrameKind.FOCUS, byteArrayOf(if (focused) 1 else 0))

    /** A CLOSE frame sent by the client: detach from the channel. */
    fun close(channel: Long): ByteArray = encode(channel, FrameKind.CLOSE)

    /** A FACTS frame: the viewer's colours and cell size, as JSON. */
    fun facts(channel: Long, display: ViewerDisplay): ByteArray {
        val json = RequestJson.encodeToString(DisplayWire.serializer(), display.toWire())
        return encode(channel, FrameKind.FACTS, json.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Splits a framed byte stream into frames (SPEC §5.1). Feed it the bytes as they arrive, in chunks of any size: a frame
 * may be split across chunks, and one chunk may hold several frames.
 *
 * A header that declares more than [maxPayload] bytes is refused as soon as the header is complete, before any payload is
 * buffered. The frames before it in the same chunk have already been passed on. After [FrameTooLarge] or [TransportLost]
 * the decoder must not be used again.
 *
 * @param maxPayload the largest payload accepted; [MAX_FRAME_PAYLOAD] by default.
 */
class FrameDecoder(private val maxPayload: Long = MAX_FRAME_PAYLOAD) {
    private var pending = ByteArray(FRAME_HEADER_BYTES)
    private var size = 0

    /** Bytes received that do not yet make a complete frame. */
    val pendingBytes: Int
        get() = size

    /**
     * Appends bytes and passes on each frame they complete, in order.
     *
     * @param chunk the bytes received.
     * @param offset where the new bytes start in [chunk].
     * @param length how many bytes to take from [chunk].
     * @param onFrame called once for each complete frame, as soon as it is complete.
     * @throws FrameTooLarge when a header declares a payload above the limit; the frames before it were passed on.
     */
    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset, onFrame: (Frame) -> Unit) {
        append(chunk, offset, length)
        var at = 0
        while (size - at >= FRAME_HEADER_BYTES) {
            val channel = unsigned32(at)
            val kind = pending[at + 4].toInt() and 0xFF
            val declared = unsigned32(at + 5)
            if (declared > maxPayload) {
                consume(at)
                throw FrameTooLarge(declared)
            }
            val end = at + FRAME_HEADER_BYTES + declared.toInt()
            if (end > size) break
            onFrame(Frame(channel, kind, pending.copyOfRange(at + FRAME_HEADER_BYTES, end)))
            at = end
        }
        consume(at)
    }

    /**
     * Ends the stream. A stream that ends between frames is fine; one that ends inside a frame is not.
     *
     * @throws TransportLost when the bytes of an unfinished frame are still pending.
     */
    fun finish() {
        if (size > 0) throw TransportLost("the channel ended inside a frame")
    }

    private fun append(chunk: ByteArray, offset: Int, length: Int) {
        if (size + length > pending.size) pending = pending.copyOf(max(size + length, pending.size * 2))
        chunk.copyInto(pending, size, offset, offset + length)
        size += length
    }

    private fun consume(count: Int) {
        if (count == 0) return
        pending.copyInto(pending, 0, count, size)
        size -= count
    }

    private fun unsigned32(at: Int): Long =
        (pending[at].toLong() and 0xFF) or
            ((pending[at + 1].toLong() and 0xFF) shl 8) or
            ((pending[at + 2].toLong() and 0xFF) shl 16) or
            ((pending[at + 3].toLong() and 0xFF) shl 24)
}
