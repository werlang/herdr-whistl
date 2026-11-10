package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Reading the release feed (phase 9, SPEC §2.7).
 *
 * `release_latest.json` is the real answer GitHub gave for `releases/latest` on v0.3.5, kept as is: the asset names it
 * contains are the app's contract with its own release page, so a change in them must show up here.
 */
class ReleaseFeedTest {
    private val recorded: String by lazy {
        File("src/test/resources/release_latest.json").readText()
    }

    /** The recorded release is read into a release with the signed APK and its checksum. */
    @Test
    fun theRecordedReleaseIsRead() {
        val release = ReleaseFeed.parse(recorded)!!
        assertEquals(AppVersion(0, 3, 5), release.version)
        assertEquals("https://github.com/werlang/herdr-whistl/releases/download/v0.3.5/herdr-whistl-0.3.5.apk", release.apkUrl)
        assertEquals("https://github.com/werlang/herdr-whistl/releases/download/v0.3.5/herdr-whistl-0.3.5.apk.sha256", release.sha256Url)
        assertEquals(3_728_563L, release.bytes)
    }

    /** A release that only carries the unsigned build is not an update: nothing there can be verified against the key. */
    @Test
    fun aReleaseWithoutTheSignedApkIsNotAnUpdate() {
        val onlyUnsigned = recorded.replace("herdr-whistl-0.3.5.apk.sha256", "herdr-whistl-0.3.5.apk.txt")
        assertNull(ReleaseFeed.parse(onlyUnsigned))
        assertNull(ReleaseFeed.parse(recorded.replace("\"herdr-whistl-0.3.5.apk.sha256\"", "\"other\"")))
    }

    /** A link that is not HTTPS is not followed, whatever the feed says. */
    @Test
    fun plainHttpIsRefused() {
        assertNull(ReleaseFeed.parse(recorded.replace("https://github.com", "http://github.com")))
    }

    /** Anything that is not the feed's shape is refused rather than guessed at. */
    @Test
    fun rubbishIsRefused() {
        listOf("", "{}", "[]", "not json", """{"tag_name":"latest"}""").forEach {
            assertNull(it, ReleaseFeed.parse(it))
        }
    }
}
