package io.github.werlang.whistl.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two checks that decide whether a downloaded APK may be installed (phase 9, SPEC §2.7). */
class UpdateTrustTest {

    /** The published asset is read whether it is a bare hash or `sha256sum` output. */
    @Test
    fun theChecksumAssetIsReadInBothShapes() {
        val hash = "42724a427b3b6ec206f6c85021deb68a36840325596297c941edaebe3d99ae7c"
        assertEquals(hash, UpdateTrust.parseChecksum(hash))
        assertEquals(hash, UpdateTrust.parseChecksum("$hash  herdr-whistl-0.3.5.apk\n"))
        assertEquals(hash, UpdateTrust.parseChecksum("${hash.uppercase()}\n"))
    }

    /** Something that is not a SHA-256 is not a checksum: an empty or truncated asset must refuse the download. */
    @Test
    fun rubbishIsNotAChecksum() {
        listOf("", "\n", "d41d8cd9", "not-a-hash", "42724a427b3b6ec206f6c85021deb68a36840325596297c941edaebe3d99ae7").forEach {
            assertNull(it, UpdateTrust.parseChecksum(it))
        }
    }

    /** The same signer on both sides is the only thing that lets the file through. */
    @Test
    fun onlyTheSameSignerIsTrusted() {
        val mine = setOf("aee6ad8e55c637a82bc0b1c9ef6924fe8f8775d759d4a09c74b7b15cb643ec9e")
        assertTrue(UpdateTrust.signersMatch(mine, mine))
        assertFalse(UpdateTrust.signersMatch(mine, setOf("deadbeef")))
        assertFalse(UpdateTrust.signersMatch(mine, mine + "extra"))
        assertFalse(UpdateTrust.signersMatch(emptySet(), emptySet()))
        assertFalse(UpdateTrust.signersMatch(mine, emptySet()))
    }
}
