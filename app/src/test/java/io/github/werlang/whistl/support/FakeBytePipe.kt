package io.github.werlang.whistl.support

import io.github.werlang.whistl.core.rpc.BytePipe
import io.github.werlang.whistl.core.rpc.TransportLost
import kotlinx.coroutines.channels.Channel

/**
 * An in-memory [BytePipe] for tests: no socket, no real time.
 *
 * Every write is recorded in [written] and passed to [onWrite], which may [push] replies. Bytes queued with [push] are what
 * the client reads, in the pieces they were pushed in. [end] simulates hc closing the channel.
 *
 * @param onWrite called for each write; the default does nothing.
 */
class FakeBytePipe(private val onWrite: suspend FakeBytePipe.(ByteArray) -> Unit = {}) : BytePipe {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private var closed = false
    private var leftover = ByteArray(0)

    /** Every write the client made, in order. */
    val written: MutableList<ByteArray> = mutableListOf()

    override suspend fun write(bytes: ByteArray) {
        if (closed) throw TransportLost("fake pipe is closed")
        written += bytes
        onWrite(bytes)
    }

    override suspend fun read(buffer: ByteArray): Int {
        if (leftover.isEmpty()) {
            leftover = incoming.receiveCatching().getOrNull() ?: return -1
        }
        val count = minOf(buffer.size, leftover.size)
        leftover.copyInto(buffer, 0, 0, count)
        leftover = leftover.copyOfRange(count, leftover.size)
        return count
    }

    override suspend fun close() {
        closed = true
        incoming.close()
    }

    /**
     * Queues bytes for the client to read, cut into pieces of [pieceSize] bytes so a test can split frames across reads.
     *
     * @param bytes what the far end sends.
     * @param pieceSize the size of each piece; the whole block by default.
     */
    fun push(bytes: ByteArray, pieceSize: Int = bytes.size.coerceAtLeast(1)) {
        for (start in bytes.indices step pieceSize) {
            incoming.trySend(bytes.copyOfRange(start, minOf(bytes.size, start + pieceSize)))
        }
    }

    /** Simulates hc closing the channel: after the queued bytes, the next read returns `-1`. */
    fun end() {
        incoming.close()
    }
}
