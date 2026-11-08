package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.Blocked
import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.HcException
import io.github.werlang.whistl.core.rpc.NotFound
import io.github.werlang.whistl.core.rpc.StillLive
import io.github.werlang.whistl.core.rpc.TransportLost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether the Agent screen can reach its terminal (SPEC §2.5). */
enum class TerminalReach {
    /** The machine is connected: the output is read every [DefaultTerminalRepository.POLL_INTERVAL_MS]. */
    Connected,

    /** The machine is not connected: the output is the last one read, and nothing can be sent. */
    Offline,

    /** The terminal no longer exists on the machine: it was removed or it finished and was removed. */
    Gone,
}

/**
 * What the Agent screen shows of its terminal.
 *
 * Terminal text is data: it is never logged, and this type carries no log helper.
 *
 * @property text the last output read, the most recent [DefaultTerminalRepository.READ_LINES] lines.
 * @property reach whether the output is current.
 */
data class TerminalScreen(
    val text: String,
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
 * One open terminal, held for as long as its screen is open. It keeps the machine's lease, so the session stays up.
 */
interface TerminalHandle {
    /** The output and its reach, updated while the handle is open. */
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
     * Stops polling and gives the machine's lease back, in the repository's scope, so it can be called from a cleared
     * screen. Calling it again does nothing.
     */
    fun close()
}

/** Opens terminals on machines (SPEC §2.5). */
interface TerminalRepository {
    /**
     * Opens a terminal and starts reading its output.
     *
     * @param machineId the machine the terminal runs on.
     * @param address the terminal address.
     * @return the handle; close it when the screen closes.
     * @throws NoSuchElementException when no machine has that id.
     */
    suspend fun open(machineId: String, address: String): TerminalHandle
}

/**
 * The default [TerminalRepository], over the machine's live client. Output is read with `terminal.read` every
 * [POLL_INTERVAL_MS] of time while the handle is open; the read stops at [close]. A lost connection reports the loss to the
 * session and shows the terminal as [TerminalReach.Offline] until the machine connects again, and polling goes on.
 *
 * @param scope the scope for the poll.
 * @param machines the machines and their leases.
 */
class DefaultTerminalRepository(
    private val scope: CoroutineScope,
    private val machines: MachinesRepository,
) : TerminalRepository {
    override suspend fun open(machineId: String, address: String): TerminalHandle {
        val lease = machines.acquire(machineId)
        return DefaultTerminalHandle(scope, lease, address)
    }

    /** The handle over one lease and one terminal address. */
    private class DefaultTerminalHandle(
        private val scope: CoroutineScope,
        private val lease: MachineLease,
        private val address: String,
    ) : TerminalHandle {
        private val output = MutableStateFlow(TerminalScreen(text = "", reach = TerminalReach.Offline))
        private var closed = false
        private val poller: Job = scope.launch { poll() }

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

        override fun close() {
            if (closed) return
            closed = true
            poller.cancel()
            scope.launch(NonCancellable) { lease.release() }
        }

        /** Reads the output until the terminal is gone or the handle is closed; a lost connection is reported. */
        private suspend fun poll() {
            while (true) {
                val client = lease.client()
                if (client == null) {
                    setReach(TerminalReach.Offline)
                } else {
                    try {
                        val read = client.terminalRead(address, lines = READ_LINES)
                        output.value = TerminalScreen(text = read.text, reach = TerminalReach.Connected)
                    } catch (e: TransportLost) {
                        lease.reportLost(client, e)
                        setReach(TerminalReach.Offline)
                    } catch (e: NotFound) {
                        setReach(TerminalReach.Gone)
                        return
                    } catch (e: HcException) {
                        // Another failure: keep the last output and try again on the next tick.
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }

        private fun setReach(reach: TerminalReach) {
            output.value = output.value.copy(reach = reach)
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
        /** How often the output is read while the screen is open (phase 4 brief: every 500 ms). */
        const val POLL_INTERVAL_MS: Long = 500L

        /** How many lines of history each read asks for (phase 4 brief: 200). */
        const val READ_LINES: Int = 200

        /** How many removal attempts are made while hc reports the terminal as running. */
        const val REMOVE_ATTEMPTS: Int = 20

        /** The wait between removal attempts (about 2 s in all). */
        const val REMOVE_RETRY_MS: Long = 100L
    }
}
