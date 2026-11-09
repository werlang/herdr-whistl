package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.log.Log
import io.github.werlang.whistl.core.rpc.Blocked
import io.github.werlang.whistl.core.rpc.ChannelEvent
import io.github.werlang.whistl.core.rpc.FramedChannel
import io.github.werlang.whistl.core.rpc.FramedSession
import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.HcException
import io.github.werlang.whistl.core.rpc.NotFound
import io.github.werlang.whistl.core.rpc.StillLive
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.core.rpc.ViewerDisplay
import io.github.werlang.whistl.core.vt.JohnanEngine
import io.github.werlang.whistl.core.vt.VtScreen
import io.github.werlang.whistl.domain.TerminalFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Whether the Agent screen can reach its terminal (SPEC §2.5). */
enum class TerminalReach {
    /** The stream is attached: the screen follows the program's output. */
    Connected,

    /** The machine is not connected, or the stream dropped: the screen is the last one drawn, and nothing can be sent. */
    Offline,

    /** The terminal no longer exists on the machine: it was removed or it finished and was removed. */
    Gone,
}

/**
 * What the Agent screen shows of its terminal.
 *
 * Terminal content is data: it is never logged, and this type carries no log helper.
 *
 * @property frame the screen as the emulator has it: history, then the screen rows, with the cursor (SPEC §5.1).
 * @property reach whether the output is current.
 */
data class TerminalScreen(
    val frame: TerminalFrame,
    val reach: TerminalReach,
)

/** The outcome of an action on a terminal. Refusals are values, not exceptions. */
sealed interface TerminalAction {
    /** The action reached hc and was accepted. */
    data object Done : TerminalAction

    /** The machine is not connected, so nothing was sent. */
    data object Offline : TerminalAction

    /** The terminal no longer exists on the machine. */
    data object Gone : TerminalAction

    /** hc refused a send to a blocked program, because the request did not force it. */
    data object Blocked : TerminalAction

    /** hc refused the action for another reason (for example a running terminal that cannot be removed yet). */
    data object Refused : TerminalAction
}

/**
 * One open terminal, held for as long as its screen is open. It keeps the machine's lease, so the session stays up, and it holds
 * one framed stream of the terminal's output (SPEC §5.1).
 */
interface TerminalHandle {
    /** The screen and its reach, updated while the handle is open. */
    val screen: StateFlow<TerminalScreen>

    /**
     * Sends text and presses Enter (`terminal.send`). [force] is set only for a blocked program: hc then accepts the text.
     *
     * @param text the text to send.
     * @param force `true` to send to a blocked program.
     * @return the outcome.
     */
    suspend fun send(text: String, force: Boolean): TerminalAction

    /**
     * Sends key names at once (`terminal.keys`).
     *
     * @param names key names hc accepts, for example `ctrl+c`, `up`.
     * @return the outcome.
     */
    suspend fun keys(names: List<String>): TerminalAction

    /**
     * Sends Ctrl+C (`terminal.interrupt`).
     *
     * @return the outcome.
     */
    suspend fun interrupt(): TerminalAction

    /**
     * Stops the terminal and removes it: `terminal.kill`, then `terminal.rm`. The removal is retried for a short time while hc
     * still reports the terminal as running.
     *
     * @return the outcome; [TerminalAction.Done] once the terminal is removed.
     */
    suspend fun stopAndRemove(): TerminalAction

    /**
     * Tells the terminal the size of the pane that shows it, in cells. The emulator follows it at once, and the core gets a RESIZE
     * frame on the stream (SPEC §5.1). The size is kept across a dropped stream, so the re-attach uses it.
     *
     * @param cols the width in cells.
     * @param rows the height in cells.
     */
    fun resize(cols: Int, rows: Int)

    /**
     * Stops the stream and gives the machine's lease back, in the repository's scope, so it can be called from a cleared screen.
     * Calling it again does nothing.
     */
    fun close()
}

/** Opens terminals on machines (SPEC §2.5). */
interface TerminalRepository {
    /**
     * Opens a terminal and starts its output stream.
     *
     * @param machineId the machine the terminal runs on.
     * @param address the terminal address.
     * @return the handle; close it when the screen closes.
     * @throws NoSuchElementException when no machine has that id.
     */
    suspend fun open(machineId: String, address: String): TerminalHandle
}

/**
 * The default [TerminalRepository], over the machine's live session. Each open handle attaches one framed stream of its terminal
 * (`terminal.attach`, SPEC §5.1): the program's bytes go to a [VtScreen], and the screen shows what the emulator holds, redrawn at
 * most every [FRAME_INTERVAL_MS]. Each DATA frame is credited once it is handled. A dropped stream re-attaches with `resumed`, so
 * the screen is kept; while the machine is not connected the screen is [TerminalReach.Offline] and the stream waits for it.
 *
 * @param scope the scope for the stream and the frame loop.
 * @param machines the machines and their leases.
 * @param display the viewer's colours, sent with `terminal.attach` so the program's colour queries get the screen's own colours.
 * @param newScreen makes the screen for one terminal, at a size. The default is the device's emulator; the JVM tests pass a scripted one.
 */
class DefaultTerminalRepository(
    private val scope: CoroutineScope,
    private val machines: MachinesRepository,
    private val display: ViewerDisplay? = null,
    private val newScreen: (cols: Int, rows: Int) -> VtScreen = { cols, rows -> emulatorScreen(cols, rows) },
) : TerminalRepository {
    override suspend fun open(machineId: String, address: String): TerminalHandle {
        val lease = machines.acquire(machineId)
        return DefaultTerminalHandle(scope, lease, address, display, newScreen)
    }

    /** The viewer's size in cells: what the screen is, and what the core is told. */
    private data class ViewerSize(val cols: Int, val rows: Int)

    /** The handle over one lease, one terminal address and one stream. */
    private class DefaultTerminalHandle(
        private val scope: CoroutineScope,
        private val lease: MachineLease,
        private val address: String,
        private val display: ViewerDisplay?,
        newScreen: (cols: Int, rows: Int) -> VtScreen,
    ) : TerminalHandle {
        private val vt = newScreen(DEFAULT_COLS, DEFAULT_ROWS)
        private val output = MutableStateFlow(TerminalScreen(TerminalFrame.empty(DEFAULT_COLS, DEFAULT_ROWS), TerminalReach.Offline))
        private val viewer = MutableStateFlow(ViewerSize(DEFAULT_COLS, DEFAULT_ROWS))
        private val changed = AtomicBoolean(false)

        /** The live channel of the stream, while it is attached: a resize goes out on it. */
        @Volatile
        private var channel: FramedChannel? = null

        @Volatile
        private var closed = false

        private val streamJob: Job = scope.launch { streamUntilClosed() }
        private val publishJob: Job = scope.launch { publishFrames() }

        override val screen: StateFlow<TerminalScreen> = output.asStateFlow()

        override suspend fun send(text: String, force: Boolean): TerminalAction = withClient { client ->
            client.terminalSend(address, text, force = if (force) true else null)
            TerminalAction.Done
        }

        override suspend fun keys(names: List<String>): TerminalAction = withClient { client ->
            client.terminalKeys(address, names)
            TerminalAction.Done
        }

        override suspend fun interrupt(): TerminalAction = withClient { client ->
            client.terminalInterrupt(address)
            TerminalAction.Done
        }

        override suspend fun stopAndRemove(): TerminalAction = withClient { client ->
            client.terminalKill(address)
            removeWhenStopped(client)
        }

        override fun resize(cols: Int, rows: Int) {
            if (closed || cols < 1 || rows < 1) return
            val current = viewer.value
            if (current.cols == cols && current.rows == rows) return
            viewer.value = ViewerSize(cols, rows)
            vt.resize(cols, rows)
            changed.set(true)
            val live = channel ?: return
            scope.launch {
                // A stream that has dropped re-attaches at the size kept above, so a failed send is not an error here.
                try {
                    Log.d(TAG, "resize sent: $cols x $rows")
                    live.resize(cols, rows)
                } catch (e: HcException) {
                    // The stream is gone; the next attach takes the size kept above.
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            streamJob.cancel()
            publishJob.cancel()
            scope.launch(NonCancellable) { lease.release() }
        }

        /**
         * Attaches the stream and follows it until the program ends or the handle closes. A dropped stream waits and attaches
         * again, with `resumed` after the first attach. A terminal that is gone ends the loop.
         */
        private suspend fun streamUntilClosed() {
            var attachedBefore = false
            var resumeRefused = false
            while (!closed) {
                val client = lease.client()
                if (client == null) {
                    setReach(TerminalReach.Offline)
                    delay(RECONNECT_DELAY_MS)
                    continue
                }
                val resume = attachedBefore && !resumeRefused
                var framed: FramedSession? = null
                try {
                    val opened = lease.openStream()
                    framed = opened
                    val size = viewer.value
                    val attach = HcClient(opened.rpc, { error("events do not run on a terminal stream") }, scope).terminalAttach(
                        terminal = address,
                        cols = size.cols,
                        rows = size.rows,
                        display = display,
                        resumed = if (resume) true else null,
                    )
                    val live = opened.channel(attach.channel)
                    channel = live
                    attachedBefore = true
                    setReach(TerminalReach.Connected)
                    Log.d(TAG, "stream attached: channel ${attach.channel}, ${size.cols} x ${size.rows}, resumed=$resume")
                    // The stream ends with the machine's session: when the lease has no client, the stream is closed here, so
                    // the screen shows offline without waiting for the next stream read to fail.
                    val watcher = scope.launch {
                        while (true) {
                            delay(LEASE_WATCH_MS)
                            if (lease.client() == null) {
                                Log.d(TAG, "stream closed: the machine has no connection")
                                opened.close()
                                return@launch
                            }
                        }
                    }
                    try {
                        if (followChannel(live)) {
                            Log.d(TAG, "stream ended: the program finished")
                            return
                        }
                    } finally {
                        watcher.cancel()
                    }
                } catch (e: TransportLost) {
                    Log.d(TAG, "stream lost: attaching again")
                    if (!closed) lease.reportLost(client, e)
                    setReach(TerminalReach.Offline)
                    delay(RECONNECT_DELAY_MS)
                } catch (e: NotFound) {
                    setReach(TerminalReach.Gone)
                    return
                } catch (e: HcException) {
                    if (resume) {
                        // The core refused the re-attach: attach plainly next time. The emulator keeps the screen either way.
                        resumeRefused = true
                    } else {
                        setReach(TerminalReach.Offline)
                        delay(RECONNECT_DELAY_MS)
                    }
                } finally {
                    channel = null
                    withContext(NonCancellable) { runCatching { framed?.close() } }
                }
            }
        }

        /**
         * Feeds the channel's bytes to the emulator in order, and credits each one once handled.
         *
         * @return `true` when the program ended (the channel sent its CLOSE), `false` when the stream was cut or the handle closed.
         */
        private suspend fun followChannel(live: FramedChannel): Boolean {
            var ended = false
            live.events.transformWhile { event ->
                emit(event)
                event !is ChannelEvent.Closed
            }.collect { event ->
                when (event) {
                    is ChannelEvent.Data -> {
                        vt.write(event.bytes)
                        changed.set(true)
                        live.credit(event.bytes.size)
                    }

                    is ChannelEvent.Closed -> ended = true
                }
            }
            return ended
        }

        /**
         * Draws the emulator's state at most every [FRAME_INTERVAL_MS], and only when it changed. Each frame is taken under the
         * emulator's lock, so it is one state of the screen and never part of an update.
         */
        private suspend fun publishFrames() {
            while (!closed) {
                delay(FRAME_INTERVAL_MS)
                if (changed.getAndSet(false)) {
                    val frame = vt.frame()
                    output.update { it.copy(frame = frame) }
                }
            }
        }

        private fun setReach(reach: TerminalReach) {
            output.update { it.copy(reach = reach) }
        }

        /** Runs [action] on the live client, and maps every failure to its outcome. */
        private suspend fun withClient(action: suspend (HcClient) -> TerminalAction): TerminalAction {
            val client = lease.client() ?: return TerminalAction.Offline
            return try {
                action(client)
            } catch (e: TransportLost) {
                lease.reportLost(client, e)
                TerminalAction.Offline
            } catch (e: NotFound) {
                TerminalAction.Gone
            } catch (e: Blocked) {
                TerminalAction.Blocked
            } catch (e: HcException) {
                TerminalAction.Refused
            }
        }

        /** Removes a stopped terminal. hc refuses a removal while the process is still exiting, so it is retried briefly. */
        private suspend fun removeWhenStopped(client: HcClient): TerminalAction {
            repeat(REMOVE_ATTEMPTS) { attempt ->
                try {
                    client.terminalRm(address)
                    return TerminalAction.Done
                } catch (e: StillLive) {
                    if (attempt < REMOVE_ATTEMPTS - 1) delay(REMOVE_RETRY_MS)
                }
            }
            return TerminalAction.Refused
        }
    }

    companion object {
        /** The size a new screen starts at, before the pane has been measured. */
        const val DEFAULT_COLS: Int = 80

        /** The size a new screen starts at, before the pane has been measured. */
        const val DEFAULT_ROWS: Int = 24

        /** The fewest milliseconds between two redraws of the screen: about 30 frames a second. */
        const val FRAME_INTERVAL_MS: Long = 33L

        /** How long a stream waits before it attaches again, while the machine is not connected or the attach failed. */
        const val RECONNECT_DELAY_MS: Long = 500L

        /** The tag of the stream's log lines: attach, resize and loss, with channel numbers and sizes only. */
        private const val TAG: String = "Terminal"

        /** How often an attached stream checks that its machine still has a connection. */
        const val LEASE_WATCH_MS: Long = 100L

        /** The screen of a terminal on the device: the emulator behind [VtScreen], with the history it keeps. */
        private fun emulatorScreen(cols: Int, rows: Int): VtScreen =
            VtScreen(cols, rows, JohnanEngine(cols, rows, VtScreen.MAX_SCROLLBACK))

        /** How many removal attempts are made while hc reports the terminal as running. */
        const val REMOVE_ATTEMPTS: Int = 20

        /** The wait between removal attempts (about 2 s in all). */
        const val REMOVE_RETRY_MS: Long = 100L

        /**
         * The viewer's colours for `terminal.attach`: the screen's text and background (SPEC §3, the terminal stays dark in both
         * themes). Kept equal to `WhistlTerminalText` and `WhistlTerminal` in ui/theme.
         */
        val VIEWER_DISPLAY: ViewerDisplay = ViewerDisplay(fg = "#e8eaed", bg = "#0a0b0c", cursor = "#e8eaed")
    }
}
