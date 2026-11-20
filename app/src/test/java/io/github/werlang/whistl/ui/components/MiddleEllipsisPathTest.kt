package io.github.werlang.whistl.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure middle truncation of a path (proposal 15): a path that fits stays whole, and a long one loses its intermediate
 * segments before it loses the last segment that names the folder.
 */
class MiddleEllipsisPathTest {

    /** A [middleEllipsizePath] predicate that accepts exactly the given candidates. */
    private fun accepting(vararg candidates: String): (String) -> Boolean = { it in candidates }

    /** A path that already fits is returned untouched. */
    @Test
    fun aPathThatFitsIsReturnedUnchanged() {
        val path = "/home/demo/work"

        assertEquals(path, middleEllipsizePath(path) { true })
        assertEquals(path, middleEllipsizePath(path) { it.length >= path.length })
        assertEquals("", middleEllipsizePath("") { true })
    }

    /** An absolute path drops one intermediate segment at a time from the right, then the root, then the leading slash. */
    @Test
    fun anAbsolutePathCollapsesFromTheRight() {
        val path = "/home/demo/work/nightly-build"

        assertEquals("/home/demo/…/nightly-build", middleEllipsizePath(path, accepting("/home/demo/…/nightly-build")))
        assertEquals("/home/…/nightly-build", middleEllipsizePath(path, accepting("/home/…/nightly-build")))
        assertEquals("/…/nightly-build", middleEllipsizePath(path, accepting("/…/nightly-build")))
        assertEquals("…/nightly-build", middleEllipsizePath(path, accepting("…/nightly-build")))
    }

    /** A home path keeps its `~` for as long as it can, then falls back to `…/last`. */
    @Test
    fun aHomePathCollapsesFromTheRight() {
        val path = "~/a/b/c"

        assertEquals("~/a/…/c", middleEllipsizePath(path, accepting("~/a/…/c")))
        assertEquals("~/…/c", middleEllipsizePath(path, accepting("~/…/c")))
        assertEquals("…/c", middleEllipsizePath(path, accepting("…/c")))
    }

    /** A trailing slash is ignored when the last segment is found, and is not kept in the shortened path. */
    @Test
    fun aTrailingSlashDoesNotHideTheLastSegment() {
        val path = "/home/demo/work/"

        assertEquals(path, middleEllipsizePath(path) { true })
        assertEquals("/home/…/work", middleEllipsizePath(path, accepting("/home/…/work")))
        assertEquals("…/work", middleEllipsizePath(path, accepting("…/work")))
    }

    /** A path with only separators has no last segment to preserve. */
    @Test
    fun aRootPathHasNoSegmentToKeep() {
        assertEquals("/", middleEllipsizePath("/") { true })
        assertEquals("…", middleEllipsizePath("/") { false })
        assertEquals("…", middleEllipsizePath("////") { false })
    }

    /** With no separator, the only segment is shortened from the left until its suffix fits. */
    @Test
    fun aSingleLongSegmentKeepsItsEnd() {
        val path = "a".repeat(120)
        val expected = "…" + "a".repeat(10)

        assertEquals(expected, middleEllipsizePath(path, accepting(expected)))
        assertEquals("…", middleEllipsizePath(path) { false })
    }

    /** A single long segment below the root is shortened after its slash until it fits. */
    @Test
    fun aSingleLongSegmentBelowTheRootKeepsItsEnd() {
        val path = "/nightly-build"

        assertEquals("…/build", middleEllipsizePath(path, accepting("…/build")))
        assertTrue(middleEllipsizePath(path) { it == "…/build" }.endsWith("/build"))
    }

    /** At the smallest width the ellipsis is the last answer, and the function does not loop or throw. */
    @Test
    fun theSmallestWidthReturnsTheEllipsis() {
        val path = "/home/demo/work/nightly-build"

        assertEquals("…", middleEllipsizePath(path) { false })
        assertEquals("…", middleEllipsizePath(path, accepting("…")))
        assertEquals("…", middleEllipsizePath("", accepting("…")))
    }

    /** The answer never ends before the last segment while the shortest structured candidate fits. */
    @Test
    fun theLastSegmentIsPreservedWhenItFits() {
        val path = "/srv/platform/current/release-2026-11"
        val shortened = middleEllipsizePath(path, accepting("…/release-2026-11"))

        assertEquals("…/release-2026-11", shortened)
        assertTrue(shortened.endsWith("/release-2026-11"))
    }
}
