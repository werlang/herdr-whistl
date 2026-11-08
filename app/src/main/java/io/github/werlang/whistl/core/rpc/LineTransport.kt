package io.github.werlang.whistl.core.rpc

/**
 * The seam between the RPC core and a real connection (SPEC §5).
 *
 * The production implementation runs `hc connect` over SSH exec channels (phase 2 round 2). Tests use a fake.
 * Each [exec] call opens a new channel: the command channel and the events channel are separate, because
 * `events.subscribe` takes over its connection.
 */
interface LineTransport {
    /** Opens the transport (for SSH: connect and authenticate). Calling it again is a no-op. */
    suspend fun open()

    /**
     * Starts [command] on a new channel and returns its line pipe.
     *
     * The command is a constant (`hc connect`), never built from user input.
     *
     * @param command the remote command to run.
     * @return the pipe for that channel.
     */
    suspend fun exec(command: String): LinePipe
}

/**
 * One channel of newline-delimited JSON (SPEC §5: one JSON message per line, each way).
 *
 * Contract: [readLine] returns `null` once the far end has closed the channel. [writeLine] throws
 * `HcException.TransportLost` when the channel is already gone.
 */
interface LinePipe {
    /**
     * Writes one line. The caller passes a single JSON object with no line break in it.
     *
     * @param line one message, without a trailing newline (the pipe adds it).
     */
    suspend fun writeLine(line: String)

    /**
     * Reads the next line.
     *
     * @return the line without its newline, or `null` when the channel is closed.
     */
    suspend fun readLine(): String?

    /** Closes the channel. Safe to call more than once. */
    suspend fun close()
}
