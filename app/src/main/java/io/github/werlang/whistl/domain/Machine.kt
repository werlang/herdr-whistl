package io.github.werlang.whistl.domain

/**
 * A machine the user added to the app: SSH connection settings (SPEC §1).
 *
 * Secrets (private key, saved password) never live here; they go through `core/secure`.
 *
 * @property id the app's own id for this machine.
 * @property name display name; defaults to the first host label.
 * @property host hostname, IPv4 or IPv6 literal.
 * @property port SSH port, 1 to 65535 (default 22).
 * @property user SSH user name.
 */
data class Machine(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
)
