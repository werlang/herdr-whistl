package io.github.werlang.whistl.core.ssh

/**
 * A host key the user has confirmed for one machine: its algorithm and public-key blob.
 *
 * Equality compares the blob by content. The key is public, so [toString] shows only the fingerprint.
 *
 * @property algorithm the key type, for example `ssh-ed25519`.
 * @property blob the SSH public-key blob.
 */
class PinnedHostKey(val algorithm: String, blob: ByteArray) {
    /** The blob, copied so the pinned key cannot change after it is stored. */
    val blob: ByteArray = blob.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PinnedHostKey && other.algorithm == algorithm && other.blob.contentEquals(blob)

    override fun hashCode(): Int = 31 * algorithm.hashCode() + blob.contentHashCode()

    override fun toString(): String = "PinnedHostKey($algorithm, ${SshFingerprint.of(blob)})"
}

/**
 * Where confirmed host keys are kept, per machine (SPEC §6). Implementations persist the pins; tests use an in-memory
 * fake.
 */
interface HostKeyStore {
    /**
     * The key pinned for [machineId].
     *
     * @param machineId the app's machine id.
     * @return the pinned key, or `null` when none is pinned.
     */
    fun pinned(machineId: String): PinnedHostKey?

    /**
     * Pins [key] for [machineId]. Only [HostKeyPolicy.trust] calls this, after the user confirmed the key.
     *
     * @param machineId the app's machine id.
     * @param key the key to pin.
     */
    fun pin(machineId: String, key: PinnedHostKey)
}

/**
 * What the host-key check decided for one presented key (SPEC §6, trust on first use).
 */
sealed interface HostKeyDecision {
    /** The presented key is the pinned key. The connection may go on. */
    data object Trusted : HostKeyDecision

    /**
     * No key is pinned for this machine yet. The caller must show the fingerprint and get the user's confirmation
     * before pinning with [HostKeyPolicy.trust].
     *
     * @property algorithm the presented key type.
     * @property fingerprint the presented key's SHA-256 fingerprint.
     */
    data class Unknown(val algorithm: String, val fingerprint: String) : HostKeyDecision

    /**
     * The machine presented a key other than the pinned one. This is the typed `HostKeyChanged` result: the
     * connection is refused and the UI shows a blocking warning. A changed key is never accepted silently and is
     * never re-pinned here.
     *
     * @property algorithm the presented key type.
     * @property expectedFingerprint the fingerprint of the pinned key.
     * @property actualFingerprint the fingerprint of the presented key.
     */
    data class Changed(
        val algorithm: String,
        val expectedFingerprint: String,
        val actualFingerprint: String,
    ) : HostKeyDecision
}

/**
 * Decides, for a presented host key, whether to connect (SPEC §6). Pure apart from the [HostKeyStore] it reads.
 *
 * - No pin: [HostKeyDecision.Unknown].
 * - Same algorithm and same blob as the pin: [HostKeyDecision.Trusted].
 * - Anything else: [HostKeyDecision.Changed]. Algorithm changes count as changed keys.
 *
 * @param store where pins are read and written.
 */
class HostKeyPolicy(private val store: HostKeyStore) {
    /**
     * Checks a presented key. Never pins anything.
     *
     * @param machineId the app's machine id.
     * @param algorithm the presented key type.
     * @param blob the presented SSH public-key blob.
     * @return the decision.
     */
    fun check(machineId: String, algorithm: String, blob: ByteArray): HostKeyDecision {
        val presentedFingerprint = SshFingerprint.of(blob)
        val pinned = store.pinned(machineId)
            ?: return HostKeyDecision.Unknown(algorithm, presentedFingerprint)
        return if (pinned.algorithm == algorithm && pinned.blob.contentEquals(blob)) {
            HostKeyDecision.Trusted
        } else {
            HostKeyDecision.Changed(algorithm, SshFingerprint.of(pinned.blob), presentedFingerprint)
        }
    }

    /**
     * Pins a presented key after the user confirmed it. Only the [HostKeyDecision.Unknown] case pins.
     *
     * A changed key is refused and the existing pin is kept. Trusting a key that is already pinned changes nothing.
     *
     * @param machineId the app's machine id.
     * @param algorithm the key type the user confirmed.
     * @param blob the key blob the user confirmed.
     * @return [HostKeyDecision.Trusted] after pinning, or the existing [HostKeyDecision.Changed] when refused.
     */
    fun trust(machineId: String, algorithm: String, blob: ByteArray): HostKeyDecision =
        when (val decision = check(machineId, algorithm, blob)) {
            is HostKeyDecision.Unknown -> {
                store.pin(machineId, PinnedHostKey(algorithm, blob))
                HostKeyDecision.Trusted
            }

            else -> decision
        }

    /**
     * The algorithm pinned for [machineId], used to answer SSH's "which key types do you have for this host" query.
     *
     * @param machineId the app's machine id.
     * @return the pinned algorithm, or `null` when none is pinned.
     */
    fun pinnedAlgorithm(machineId: String): String? = store.pinned(machineId)?.algorithm
}

/**
 * Thrown by the SSH transport when the host key was not trusted. Carries the typed decision, so the UI can ask for
 * confirmation ([HostKeyDecision.Unknown]) or show the blocking warning ([HostKeyDecision.Changed]). Its message names
 * neither the host nor the key.
 *
 * @property decision the refused decision; never [HostKeyDecision.Trusted].
 */
class HostKeyRefused(val decision: HostKeyDecision) :
    RuntimeException("host key was not trusted: ${decision::class.java.simpleName}")

/**
 * The verifier-side view of [HostKeyPolicy] for one machine: admits a presented key only when it is trusted, and
 * records the decision so the transport can say why it refused. Never pins.
 *
 * @param policy the policy to ask.
 * @param machineId the machine this connection is for.
 */
class HostKeyGate(
    private val policy: HostKeyPolicy,
    private val machineId: String,
) {
    /** The decision for the most recent presented key, or `null` before any key was seen. */
    var lastDecision: HostKeyDecision? = null
        private set

    /**
     * Admits a presented key.
     *
     * @param algorithm the presented key type.
     * @param blob the presented SSH public-key blob.
     * @return `true` only for [HostKeyDecision.Trusted].
     */
    fun admit(algorithm: String, blob: ByteArray): Boolean {
        val decision = policy.check(machineId, algorithm, blob)
        lastDecision = decision
        return decision is HostKeyDecision.Trusted
    }

    /**
     * The algorithms this machine already has a pin for, offered to SSH when it picks a host key.
     *
     * @return zero or one algorithm name.
     */
    fun pinnedAlgorithms(): List<String> = listOfNotNull(policy.pinnedAlgorithm(machineId))
}
