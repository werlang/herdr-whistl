package io.github.werlang.whistl.data

/**
 * This device's public SSH key as the line the user adds to `~/.ssh/authorized_keys` (SPEC §2.1, §6). The line is public; the
 * private key never leaves the Keystore. The live implementation generates the key on first use; the demo shows a fixed line.
 */
interface ClientPublicKey {
    /**
     * The OpenSSH public-key line, `ssh-ed25519 <base64> whistl`, without a trailing newline.
     *
     * @return the line.
     */
    fun publicKeyLine(): String
}
