package io.github.werlang.whistl.guards

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Guard (phase 4b r1 §9): every golden under `src/test/screenshots/` is a real frame, and no two goldens anywhere in the
 * directory share a hash. A screen that renders the same as another, or a golden copied under a second name, is a bug in the
 * suite, not a pass.
 */
class GoldenUniquenessTest {

    /** Every golden is more than 4 KiB, and no two goldens have the same SHA-256. */
    @Test
    fun everyGoldenIsARealFrameAndNoTwoShareAHash() {
        val directory = File("src/test/screenshots")
        assertTrue("the golden directory is missing: ${directory.absolutePath}", directory.isDirectory)
        val goldens = directory.walkTopDown().filter { it.isFile && it.extension == "png" }.sortedBy { it.name }.toList()
        assertTrue("no goldens were found", goldens.isNotEmpty())

        val small = goldens.filter { it.length() <= MIN_BYTES }.map { "${it.name} (${it.length()} bytes)" }
        assertTrue("goldens that are not real frames: $small", small.isEmpty())

        val byHash = goldens.groupBy { sha256(it.readBytes()) }
        val shared = byHash.values.filter { it.size > 1 }.map { group -> group.map { it.name } }
        assertTrue("goldens that share a hash: $shared", shared.isEmpty())
    }

    /**
     * The SHA-256 of some bytes, in hex.
     *
     * @param bytes the bytes.
     * @return the digest as lowercase hex.
     */
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** The size floor of a real frame (phase 4 brief: more than a few KiB). */
        const val MIN_BYTES: Long = 4L * 1024L
    }
}
