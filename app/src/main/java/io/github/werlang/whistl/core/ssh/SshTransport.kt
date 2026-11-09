package io.github.werlang.whistl.core.ssh

import io.github.werlang.whistl.core.rpc.BytePipe
import io.github.werlang.whistl.core.rpc.LineTransport

/**
 * Where one machine's core is reached and who logs in (SPEC §1, §6). Host, port and user are validated by the caller
 * (see `domain/Validation.kt`).
 *
 * [toString] shows only the machine id, so a host name never reaches a log or an exception message.
 *
 * @property machineId the app's machine id.
 * @property host the validated host (see `validateHost`).
 * @property port the validated SSH port.
 * @property user the validated SSH user name.
 */
class SshTarget(
    val machineId: String,
    val host: String,
    val port: Int,
    val user: String,
) {
    override fun toString(): String = "SshTarget(machineId=$machineId)"
}

/**
 * How to authenticate. Secrets are copied in and never shown: [toString] redacts them.
 */
sealed interface SshAuth {
    /**
     * Public-key authentication with the app's Ed25519 client key.
     *
     * @param seed the 32-byte seed, from `KeyManager.loadSeed`. A copy is kept.
     */
    class Key(seed: ByteArray) : SshAuth {
        private val seed: ByteArray = seed.copyOf()

        /** A copy of the seed, for the transport's key provider only. */
        internal fun seedBytes(): ByteArray = seed.copyOf()

        override fun toString(): String = "SshAuth.Key(<redacted>)"
    }

    /**
     * Password authentication with a saved password.
     *
     * @param password the password characters. A copy is kept.
     */
    class Password(password: CharArray) : SshAuth {
        private val password: CharArray = password.copyOf()

        /** A copy of the password, for the transport's login step only. */
        internal fun passwordChars(): CharArray = password.copyOf()

        override fun toString(): String = "SshAuth.Password(<redacted>)"
    }
}

/**
 * The [LineTransport] for one machine (SPEC §5): it opens one SSH session, then runs `hc connect` on two exec
 * channels, one for commands and one for events.
 *
 * [exec] accepts only [HC_CONNECT_COMMAND]. No other command string can be run through this seam.
 */
interface SshTransport : LineTransport {
    /** The machine this transport talks to. */
    val target: SshTarget

    /**
     * Starts [command] on a new channel and returns its raw byte pipe, for a framed connection (SPEC §5.1). Nothing is read ahead
     * of the caller, so the bytes after a line are the caller's.
     *
     * @param command must be [HC_CONNECT_COMMAND].
     * @return the byte pipe for that channel.
     */
    suspend fun execBytes(command: String): BytePipe

    /** Closes the SSH session and every channel on it. Safe to call more than once. */
    suspend fun close()

    companion object {
        /** The one command this transport runs on an exec channel. A constant, never built from input. */
        const val HC_CONNECT_COMMAND: String = "hc connect"
    }
}
