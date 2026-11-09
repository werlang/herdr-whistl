package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.support.Fixtures
import io.github.werlang.whistl.support.FakeBytePipe
import io.github.werlang.whistl.support.FakeLinePipe
import io.github.werlang.whistl.support.assertRequestMatches
import io.github.werlang.whistl.support.assertThrowsSuspend
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framed session on the recorded hello and attach (`hello_framed.json`, `attach_result.json`, `attach_frames.bin`):
 * the reply, the channel's events, what the viewer sends, the exit facts, and the failures of a broken stream
 * (SPEC §5.1). Time is virtual; no test touches the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FramedSessionTest {

    /** The framed `hello` is the recorded request, and its reply names the core and keeps the framed connection. */
    @Test
    fun helloSendsTheRecordedRequestAndReadsTheFramedReply() = runTest {
        val pipe = recordedCore()

        val session = FramedSession.open(pipe, backgroundScope)

        assertRequestMatches(
            Fixtures.load("hello_framed.json").request,
            String(pipe.written.first()).trimEnd('\n'),
            "hello_framed.json",
        )
        assertEquals("rpi5", session.hello.coreName)
        assertEquals(1, session.hello.generation)
    }

    /** `terminal.attach` returns the recorded channel and terminal. */
    @Test
    fun attachReturnsTheRecordedChannelAndTerminal() = runTest {
        val session = FramedSession.open(recordedCore(), backgroundScope)

        val attached = clientFor(session, backgroundScope).terminalAttach(terminal = "p8r1-fix1", cols = 80, rows = 24)

        val recorded = Fixtures.load("attach_result.json").response.getValue("result").jsonObject
        assertEquals(recorded.getValue("channel").jsonPrimitive.long, attached.channel)
        assertEquals(
            recorded.getValue("terminal").jsonObject.getValue("address").jsonPrimitive.content,
            attached.terminal.address,
        )
    }

    /**
     * The first paint and the live bytes come out as DATA in order, then the two CLOSE answers. The same holds however the
     * reads are cut: one byte at a time, seven, or whole.
     */
    @Test
    fun theFirstPaintAndTheLiveBytesComeOutAsDataInOrder() = runTest {
        for (piece in listOf(1, 7, 4096)) {
            val session = FramedSession.open(recordedCore(piece = piece), backgroundScope)
            clientFor(session, backgroundScope).terminalAttach(terminal = "p8r1-fix1", cols = 80, rows = 24)

            val events = session.channel(1).events.take(5).toList()

            val firstPaint = (events[0] as ChannelEvent.Data).bytes.toString(Charsets.UTF_8)
            assertTrue(firstPaint.contains("\u001b[38;5;1mred"))
            assertTrue(firstPaint.contains("\u001b[38;2;255;100;0mtrue"))
            assertEquals("hi\r\n", data(events[1]))
            assertEquals("\u001b[1;35mhi\u001b[0m\r\n", data(events[2]))
            assertNull((events[3] as ChannelEvent.Closed).exit)
            assertNull((events[4] as ChannelEvent.Closed).exit)
        }
    }

    /**
     * What the viewer sends is the recorded frame bytes: RESIZE 100x30 as `attach_resize.json` records it, CREDIT for what was
     * handled, focus out, and the detach.
     */
    @Test
    fun theViewersFramesAreTheRecordedBytes() = runTest {
        val pipe = recordedCore()
        val session = FramedSession.open(pipe, backgroundScope)
        clientFor(session, backgroundScope).terminalAttach(terminal = "p8r1-fix1", cols = 80, rows = 24)
        val channel = session.channel(1)

        channel.resize(100, 30)
        channel.credit(335)
        channel.focus(false)
        channel.detach()

        val resize = pipe.written[2]
        val recorded = Fixtures.load("attach_resize.json").request
        assertEquals("01000000030400000064001e00", resize.toHex())
        assertEquals(recorded.getValue("payload_hex").jsonPrimitive.content, resize.copyOfRange(9, resize.size).toHex())
        assertEquals("0100000001040000004f010000", pipe.written[3].toHex())
        assertEquals("01000000040100000000", pipe.written[4].toHex())
        assertEquals("010000000200000000", pipe.written[5].toHex())
    }

    /** The core's CLOSE when the program exits carries the exit facts (`attach_close.json`, `program_exit`). */
    @Test
    fun aProgramsExitArrivesAsAClosedEventWithItsFacts() = runTest {
        val exitPayload = hex(
            Fixtures.load("attach_close.json").response.getValue("program_exit").jsonObject
                .getValue("payload_hex").jsonPrimitive.content,
        )
        val attachReply = Fixtures.bytes("attach_frames.bin").copyOfRange(0, 679)
        val session = FramedSession.open(
            recordedCore(attach = attachReply + FrameCodec.encode(1, FrameKind.CLOSE, exitPayload)),
            backgroundScope,
        )
        clientFor(session, backgroundScope).terminalAttach(terminal = "p8r1-fix1", cols = 80, rows = 24)

        val closed = session.channel(1).events.first() as ChannelEvent.Closed

        val exit = closed.exit!!
        assertEquals(7, exit.getValue("exit_code").jsonPrimitive.int)
        assertEquals("exited", exit.getValue("reason").jsonPrimitive.content)
    }

    /** A stream that ends inside a frame fails the channel: the events before the cut arrive, then the flow fails. */
    @Test
    fun aStreamThatEndsInsideAFrameFailsTheChannel() = runTest {
        val bytes = Fixtures.bytes("attach_frames.bin")
        val session = FramedSession.open(recordedCore(attach = bytes.copyOfRange(0, bytes.size - 3), ended = true), backgroundScope)
        clientFor(session, backgroundScope).terminalAttach(terminal = "p8r1-fix1", cols = 80, rows = 24)

        val failure = assertThrowsSuspend(TransportLost::class.java) { session.channel(1).events.toList() }

        assertTrue(failure.message.orEmpty().contains("inside a frame"))
    }

    /** A frame of a kind the core does not send is skipped and counted; the data after it still arrives. */
    @Test
    fun anUnknownKindIsSkippedAndCounted() = runTest {
        val attach = Fixtures.bytes("attach_frames.bin").copyOfRange(0, 679) +
            FrameCodec.encode(1, 9, "??".toByteArray()) +
            FrameCodec.data(1, "ok".toByteArray())
        val session = FramedSession.open(recordedCore(attach = attach), backgroundScope)
        clientFor(session, backgroundScope).terminalAttach(terminal = "p8r1-fix1", cols = 80, rows = 24)

        val first = session.channel(1).events.first()

        assertEquals("ok", data(first))
        assertEquals(1L, session.skippedFrames)
    }

    /**
     * A header from the core that declares more than the limit fails the session. The attach reply before it, in the same
     * read, has already been answered.
     */
    @Test
    fun anOversizedFrameFromTheCoreFailsTheSession() = runTest {
        val header = byteArrayOf(1, 0, 0, 0, 0) + littleEndian32(MAX_FRAME_PAYLOAD + 1)
        val attach = Fixtures.bytes("attach_frames.bin").copyOfRange(0, 679) + header
        val session = FramedSession.open(recordedCore(attach = attach), backgroundScope)
        clientFor(session, backgroundScope).terminalAttach(terminal = "p8r1-fix1", cols = 80, rows = 24)

        val failure = assertThrowsSuspend(FrameTooLarge::class.java) { session.channel(1).events.toList() }

        assertEquals(MAX_FRAME_PAYLOAD + 1, failure.length)
    }

    /** `terminal.attach` on a plain line-mode connection is refused with `needs_framed` (`attach_needs_framed.json`). */
    @Test
    fun attachOnALineConnectionIsRefusedWithNeedsFramed() = runTest {
        val fixture = Fixtures.load("attach_needs_framed.json")
        val client = HcClient(JsonRpcConnection(FakeLinePipe.replaying(fixture), backgroundScope), { error("unused") }, backgroundScope)

        val error = assertThrowsSuspend(NeedsFramed::class.java) {
            client.terminalAttach(terminal = "p8r1-fix1", cols = 80, rows = 24)
        }

        assertEquals("send hello with framed: true first, or use `hc attach`", error.hint)
    }

    /** A hello reply that does not switch the connection to framed mode is refused. */
    @Test
    fun aHelloReplyThatDoesNotFrameTheConnectionIsNeedsFramed() = runTest {
        val response = Fixtures.load("hello_framed.json").response
        val result = response.getValue("result").jsonObject
        val patched = JsonObject(response + ("result" to JsonObject(result + ("framed" to JsonPrimitive(false)))))
        val pipe = FakeBytePipe { if (it[0] == '{'.code.toByte()) push((patched.toString() + "\n").toByteArray()) }

        assertThrowsSuspend(NeedsFramed::class.java) { FramedSession.open(pipe, backgroundScope) }
    }

    /**
     * The CREDIT window as recorded (`attach_window.json`): 262144 bytes of DATA before any CREDIT; then each CREDIT released
     * exactly its own count, and the core sent no more than it was given.
     */
    @Test
    fun theCreditWindowIsTheRecordedOne() {
        val recorded = Fixtures.load("attach_window.json")
        val grants = recorded.request.getValue("flood").jsonObject.getValue("grants").jsonArray.map { it.jsonPrimitive.long }
        val windows = recorded.response.getValue("flood").jsonObject
            .getValue("data_payload_before_each_grant").jsonArray.map { it.jsonPrimitive.long }

        assertEquals(262144L, windows[0])
        assertEquals(grants[0], windows[1])
        assertTrue(windows[2] <= grants[1])
    }

    /** The core's answer to the hello, then the recorded frames once the attach request is written. */
    private fun recordedCore(
        attach: ByteArray = Fixtures.bytes("attach_frames.bin"),
        piece: Int = 4096,
        ended: Boolean = false,
    ): FakeBytePipe {
        val reply = (Fixtures.load("hello_framed.json").response.toString() + "\n").toByteArray()
        return FakeBytePipe { bytes ->
            if (bytes[0] == '{'.code.toByte()) {
                push(reply, piece)
            } else if (String(bytes, FRAME_HEADER_BYTES, bytes.size - FRAME_HEADER_BYTES, Charsets.UTF_8).contains("terminal.attach")) {
                push(attach, piece)
                if (ended) end()
            }
        }
    }

    private fun clientFor(session: FramedSession, scope: CoroutineScope): HcClient =
        HcClient(session.rpc, { error("events are not framed yet") }, scope)

    private fun littleEndian32(value: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()

    private fun data(event: ChannelEvent): String = (event as ChannelEvent.Data).bytes.toString(Charsets.UTF_8)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
