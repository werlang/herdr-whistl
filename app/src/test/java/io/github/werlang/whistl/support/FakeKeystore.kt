package io.github.werlang.whistl.support

import io.github.werlang.whistl.core.secure.BlobStore
import io.github.werlang.whistl.core.secure.WrappingCipher

/** A blob map that counts removals, so a test can see that nothing was touched. */
internal class MapBlobStore : BlobStore {
    private val values = LinkedHashMap<String, String>()

    /** How many times [remove] was called. */
    var removals: Int = 0
        private set

    override fun read(alias: String): String? = values[alias]

    override fun write(alias: String, encoded: String) {
        values[alias] = encoded
    }

    override fun remove(alias: String) {
        removals++
        values.remove(alias)
    }
}

/**
 * A cipher whose [openFailure] is thrown by [open]. Discarding the key clears the failure, as a new key opens new blobs. The
 * layout is the real one: a 12-byte IV, the value, and a 16-byte tag.
 */
internal class FakeWrappingCipher(var openFailure: Throwable? = null) : WrappingCipher {
    /** How many times [open] was called. */
    var opens: Int = 0
        private set

    /** How many times [discardKey] was called. */
    var discards: Int = 0
        private set

    override fun seal(value: ByteArray): ByteArray = ByteArray(IV) + value + ByteArray(TAG)

    override fun open(blob: ByteArray): ByteArray {
        opens++
        openFailure?.let { throw it }
        return blob.copyOfRange(IV, blob.size - TAG)
    }

    override fun discardKey() {
        discards++
        openFailure = null
    }

    private companion object {
        const val IV = 12
        const val TAG = 16
    }
}
