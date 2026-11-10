package io.github.werlang.whistl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version parsing and ordering (phase 9, SPEC §2.7). */
class AppVersionTest {

    /** The tag the feed publishes and the version the app carries parse to the same value. */
    @Test
    fun theFeedTagAndTheAppVersionAgree() {
        assertEquals(AppVersion(0, 3, 5), AppVersion.parse("v0.3.5"))
        assertEquals(AppVersion(0, 3, 5), AppVersion.parse("0.3.5"))
        assertEquals(AppVersion(0, 3, 5), AppVersion.parse("  0.3.5 \n"))
    }

    /** Comparison is numeric per field, never lexicographic. */
    @Test
    fun comparisonIsNumericPerField() {
        assertTrue(AppVersion.parse("0.3.10")!! > AppVersion.parse("0.3.9")!!)
        assertTrue(AppVersion.parse("0.10.0")!! > AppVersion.parse("0.9.9")!!)
        assertTrue(AppVersion.parse("1.0.0")!! > AppVersion.parse("0.99.99")!!)
        assertEquals(0, AppVersion.parse("0.3.5")!!.compareTo(AppVersion.parse("v0.3.5")!!))
    }

    /** A pre-release sorts before its release, so an rc is never offered over the final build. */
    @Test
    fun aPreReleaseSortsBeforeItsRelease() {
        assertTrue(AppVersion.parse("1.0.0-rc1")!! < AppVersion.parse("1.0.0")!!)
        assertTrue(AppVersion.parse("1.0.0+build9")!! == AppVersion.parse("1.0.0")!!)
    }

    /** Anything that is not a version is refused, so the caller cannot mistake it for an update. */
    @Test
    fun rubbishIsRefused() {
        listOf("", "latest", "0.3", "0.3.5.1", "v", "0.3.x", "release-0.3.5").forEach {
            assertNull(it, AppVersion.parse(it))
        }
    }
}
