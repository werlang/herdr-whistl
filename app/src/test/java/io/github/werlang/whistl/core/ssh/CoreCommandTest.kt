package io.github.werlang.whistl.core.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision that tells "herdr-core is not installed for this login" from a network failure (phase 7 r3, the row-1 finding).
 * Only the shell's "command not found" status counts; a command that ran and failed, or one that did not end, does not.
 */
class CoreCommandTest {
    @Test
    fun commandNotFoundIsCoreMissing() {
        assertTrue(isCoreMissing(EXIT_COMMAND_NOT_FOUND))
        assertTrue(isCoreMissing(127))
    }

    @Test
    fun otherExitStatusesAreNotCoreMissing() {
        assertFalse(isCoreMissing(0))
        assertFalse(isCoreMissing(1))
        assertFalse(isCoreMissing(126))
        assertFalse(isCoreMissing(255))
    }

    @Test
    fun aCommandThatDidNotEndIsNotCoreMissing() {
        assertFalse(isCoreMissing(null))
    }
}
