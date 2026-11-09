package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.support.Fixtures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The frame codec on the recorded frames (`attach_frames.bin`, `frame_kinds.json`) and on the frame errors: a split read,
 * two frames in one read, a stream that ends inside a frame, an oversized length and an unknown kind (SPEC §5.1).
 */
class FrameCodecTest {

    /** The recorded layout of `attach_frames.bin` (docs/protocol-fixtures/README.md), decoded in one read. */
    @Test
    fun attachFramesDecodeToTheRecordedFrames() {
        val frames = decode(Fixtures.bytes("attach_frames.bin"))

        assertEquals(listOf(0L to 0, 1L to 0, 1L to 0, 1L to 0, 1L to 2, 1L to 2), frames.map { it.channel to it.kind })
        assertEquals(listOf(670, 330, 4, 15, 0, 0), frames.map { it.payload.size })
    }

    /** The first paint is the recorded colour output; the live bytes are the typed line and its colour echo. */
    @Test
    fun theFirstPaintAndTheLiveBytesCarryTheRecordedColour() {
        val frames = decode(Fixtures.bytes("attach_frames.bin"))

        val firstPaint = frames[1].payload.toString(Charsets.UTF_8)
        assertTrue(firstPaint.contains("\u001b[38;5;1mred"))
        assertTrue(firstPaint.contains("\u001b[38;2;255;100;0mtrue"))
        assertEquals("hi\r\n", frames[2].payload.toString(Charsets.UTF_8))
        assertEquals("\u001b[1;35mhi\u001b[0m\r\n", frames[3].payload.toString(Charsets.UTF_8))
    }

    /** A frame split across reads is held until it is whole: feeding one byte at a time gives the same frames. */
    @Test
    fun splitReadsGiveTheSameFramesAsOneRead() {
        val bytes = Fixtures.bytes("attach_frames.bin")
        val decoder = FrameDecoder()
        val frames = ArrayList<Frame>()
        for (at in bytes.indices) decoder.feed(bytes, at, 1) { frames += it }

        assertEquals(decode(bytes), frames)
        decoder.finish()
    }

    /**
     * Two frames in one read are both passed on, in order. Revert proof: the decoder was changed to stop after the first
     * frame of a read, and this test failed with one frame instead of two (phase 8 P0).
     */
    @Test
    fun twoFramesInOneReadArePassedOnInOrder() {
        val bytes = Fixtures.bytes("attach_frames.bin")
        val twoFrames = bytes.copyOfRange(679, 1031)

        val frames = decode(twoFrames)

        assertEquals(listOf(330, 4), frames.map { it.payload.size })
    }

    /** A stream that ends inside a frame is an error at [FrameDecoder.finish]; the frames before the cut are kept. */
    @Test
    fun aStreamThatEndsInsideAFrameIsAnError() {
        val bytes = Fixtures.bytes("attach_frames.bin")
        val decoder = FrameDecoder()
        val frames = ArrayList<Frame>()
        decoder.feed(bytes, 0, bytes.size - 3) { frames += it }

        assertEquals(5, frames.size)
        assertTrue(decoder.pendingBytes > 0)
        assertTransportLost { decoder.finish() }
    }

    /** A header cut short at the end of the stream is the same error. */
    @Test
    fun aHeaderCutShortAtTheEndIsAnError() {
        val decoder = FrameDecoder()
        decoder.feed(byteArrayOf(0, 0, 0, 0, 0)) { fail("no frame is complete") }

        assertTransportLost { decoder.finish() }
    }

    /** A stream that ends between frames is fine. */
    @Test
    fun aStreamThatEndsBetweenFramesIsFine() {
        val decoder = FrameDecoder()
        decoder.feed(Fixtures.bytes("attach_frames.bin")) {}

        decoder.finish()
        assertEquals(0, decoder.pendingBytes)
    }

    /**
     * A header that declares more than the limit is refused from the header alone: the decoder never waits for its payload,
     * so no buffer is allocated for it.
     */
    @Test
    fun aLengthAboveTheLimitIsRefusedFromTheHeaderAlone() {
        val header = byteArrayOf(1, 0, 0, 0, 0) + littleEndian32(MAX_FRAME_PAYLOAD + 1)
        try {
            FrameDecoder().feed(header) { fail("no frame can be complete") }
            fail("a length above the limit must be refused")
        } catch (e: FrameTooLarge) {
            assertEquals(MAX_FRAME_PAYLOAD + 1, e.length)
        }
    }

    /** A length at the limit is accepted: the decoder waits for the payload. */
    @Test
    fun aLengthAtTheLimitWaitsForItsPayload() {
        val header = byteArrayOf(1, 0, 0, 0, 0) + littleEndian32(MAX_FRAME_PAYLOAD)
        val decoder = FrameDecoder()

        decoder.feed(header) { fail("the payload has not arrived") }

        assertEquals(FRAME_HEADER_BYTES, decoder.pendingBytes)
    }

    /** A frame of a kind this client does not know is passed on as sent, and the frames after it still decode. */
    @Test
    fun anUnknownKindIsKeptAndTheStreamStaysInStep() {
        val bytes = FrameCodec.encode(1, 9, "abc".toByteArray()) + FrameCodec.encode(1, 0, "ok".toByteArray())

        val frames = decode(bytes)

        assertEquals(listOf(9, 0), frames.map { it.kind })
        assertEquals("abc", frames[0].payload.toString(Charsets.UTF_8))
        assertEquals("ok", frames[1].payload.toString(Charsets.UTF_8))
    }

    /**
     * The encoders produce the frames the official client sent: each recorded frame of `frame_kinds.json`, in order, decodes
     * to the same channel, kind and payload as the encoder's frame.
     */
    @Test
    fun encodersMatchTheFramesTheOfficialClientSent() {
        val recorded = Fixtures.load("frame_kinds.json").request.getValue("frames").jsonArray.map { it.jsonObject }
        val encoded = listOf(
            FrameCodec.credit(1, 335),
            FrameCodec.resize(1, 100, 30),
            FrameCodec.resize(1, 80, 24),
            FrameCodec.data(1, "x".toByteArray()),
            FrameCodec.credit(1, 1),
            FrameCodec.focus(1, false),
            FrameCodec.focus(1, true),
            FrameCodec.facts(1, ViewerDisplay(fg = "#12569a", bg = "#223344", cursor = "#feba76", cell = 10 to 20)),
            FrameCodec.close(1),
        ).map { decode(it).single() }

        assertEquals(recorded.size, encoded.size)
        recorded.zip(encoded).forEach { (fixture, frame) ->
            assertEquals(fixture.getValue("channel").jsonPrimitive.long, frame.channel)
            assertEquals(fixture.getValue("kind").jsonPrimitive.int, frame.kind)
            assertEquals(fixture.getValue("payload_hex").jsonPrimitive.content, frame.payload.toHex())
        }
    }

    /** A FACTS frame changes the colour the core answers colour queries with (`frame_kinds.json`, `facts_reaction`). */
    @Test
    fun aFactsFrameChangesTheColourTheCoreAnswers() {
        val reaction = Fixtures.load("frame_kinds.json").response.getValue("facts_reaction").jsonObject

        assertEquals("ffff/ffff/ffff", reaction.getValue("answer_before").jsonPrimitive.content)
        assertEquals("1111/2222/3333", reaction.getValue("answer_after").jsonPrimitive.content)
    }

    /** RESIZE 100x30 and CREDIT 335 are byte for byte the frames the capture recorded on the wire. */
    @Test
    fun resizeAndCreditFramesMatchTheCapturedBytes() {
        assertEquals("01000000030400000064001e00", FrameCodec.resize(1, 100, 30).toHex())
        assertEquals("0100000001040000004f010000", FrameCodec.credit(1, 335).toHex())
    }

    /** Sizes and credits outside their wire types are refused, not truncated. */
    @Test
    fun outOfRangeFieldsAreRefused() {
        assertIllegalArgument { FrameCodec.resize(1, 65536, 1) }
        assertIllegalArgument { FrameCodec.credit(1, -1) }
        assertIllegalArgument { FrameCodec.encode(1, 256) }
    }

    private fun decode(bytes: ByteArray): List<Frame> {
        val frames = ArrayList<Frame>()
        FrameDecoder().feed(bytes) { frames += it }
        return frames
    }

    private fun littleEndian32(value: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun assertTransportLost(block: () -> Unit) {
        try {
            block()
        } catch (e: TransportLost) {
            return
        }
        fail("expected TransportLost")
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("expected IllegalArgumentException")
    }
}
