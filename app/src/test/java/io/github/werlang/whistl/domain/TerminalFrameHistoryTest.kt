package io.github.werlang.whistl.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A frame says whether it holds scrollback: without it, the pane has nothing to move and a drag must page the program. */
class TerminalFrameHistoryTest {
    /** A full-screen program draws over its rows and keeps nothing above them, however many rows it has (0.7.2). */
    @Test
    fun aTallFullScreenFrameHasNoHistory() {
        val frame = TerminalFrame(
            lines = List(62) { emptyList() },
            screenStart = 0,
            cursorLine = -1,
            cols = 104,
            rows = 62,
        )

        assertFalse(frame.hasHistory)
    }

    /** Rows above the screen are history, and the pane scrolls them. */
    @Test
    fun rowsAboveTheScreenAreHistory() {
        val frame = TerminalFrame(
            lines = List(70) { emptyList() },
            screenStart = 10,
            cursorLine = -1,
            cols = 104,
            rows = 60,
        )

        assertTrue(frame.hasHistory)
    }
}
