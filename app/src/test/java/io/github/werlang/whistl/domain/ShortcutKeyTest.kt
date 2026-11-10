package io.github.werlang.whistl.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** The shortcut keys are exactly the SPEC §2 set, and the bar rules keep order and reject duplicates. */
class ShortcutKeyTest {

    /** Exactly the eighteen keys of SPEC §2, in SPEC order, with the labels SPEC shows. */
    @Test
    fun keysAreExactlyTheSpecSet() {
        assertEquals(
            listOf("Esc", "Tab", "Ctrl", "↑", "↓", "←", "→", "Enter", "^C", "^D", "Home", "End", "PgUp", "PgDn", "/", "|", "~", "-"),
            ShortcutKey.entries.map { it.label },
        )
    }

    /** The default bar is the SPEC §2 default, in order. */
    @Test
    fun defaultBarIsTheSpecDefault() {
        assertEquals(
            listOf("Esc", "Tab", "Ctrl", "↑", "↓", "←", "→", "Enter"),
            ShortcutBar.DEFAULT.map { it.label },
        )
    }

    /** Moving the first key up, or the last key down, leaves the bar as it is. */
    @Test
    fun movingAtTheEdgesLeavesTheBarUnchanged() {
        assertEquals(ShortcutResult.Ok(ShortcutBar.DEFAULT), ShortcutBar.moveUp(ShortcutBar.DEFAULT, ShortcutKey.ESC))
        assertEquals(
            ShortcutResult.Ok(ShortcutBar.DEFAULT),
            ShortcutBar.moveDown(ShortcutBar.DEFAULT, ShortcutKey.ENTER),
        )
    }

    /** Stored names that match no key, and repeats, are dropped; the order of the rest is kept. */
    @Test
    fun parseDropsUnknownNamesAndRepeatsKeepingOrder() {
        assertEquals(
            listOf(ShortcutKey.TAB, ShortcutKey.ESC),
            ShortcutBar.parse(listOf("TAB", "NOPE", "ESC", "TAB")),
        )
    }

    /** Enabling one arrow enables the bundle: the bar never holds one arrow alone. */
    @Test
    fun addingOneArrowAddsTheBundle() {
        val bar = listOf(ShortcutKey.ESC)
        assertEquals(
            ShortcutResult.Ok(listOf(ShortcutKey.ESC, ShortcutKey.UP, ShortcutKey.DOWN, ShortcutKey.LEFT, ShortcutKey.RIGHT)),
            ShortcutBar.add(bar, ShortcutKey.LEFT),
        )
    }

    /** Disabling one arrow disables the bundle, and the rest of the bar keeps its order. */
    @Test
    fun removingOneArrowRemovesTheBundle() {
        val bar = listOf(ShortcutKey.ESC, ShortcutKey.UP, ShortcutKey.DOWN, ShortcutKey.ENTER, ShortcutKey.LEFT, ShortcutKey.RIGHT)
        assertEquals(
            ShortcutResult.Ok(listOf(ShortcutKey.ESC, ShortcutKey.ENTER)),
            ShortcutBar.remove(bar, ShortcutKey.RIGHT),
        )
    }

    /** The bundle moves as one block and keeps its internal order. */
    @Test
    fun theBundleMovesWhole() {
        val up = ShortcutBar.moveUp(
            listOf(ShortcutKey.ESC, ShortcutKey.TAB, ShortcutKey.UP, ShortcutKey.DOWN, ShortcutKey.LEFT, ShortcutKey.RIGHT),
            ShortcutKey.UP,
        )
        assertEquals(
            ShortcutResult.Ok(listOf(ShortcutKey.ESC, ShortcutKey.UP, ShortcutKey.DOWN, ShortcutKey.LEFT, ShortcutKey.RIGHT, ShortcutKey.TAB)),
            up,
        )

        val down = ShortcutBar.moveDown(
            listOf(ShortcutKey.ESC, ShortcutKey.UP, ShortcutKey.DOWN, ShortcutKey.LEFT, ShortcutKey.RIGHT, ShortcutKey.TAB),
            ShortcutKey.LEFT,
        )
        assertEquals(
            ShortcutResult.Ok(listOf(ShortcutKey.ESC, ShortcutKey.TAB, ShortcutKey.UP, ShortcutKey.DOWN, ShortcutKey.LEFT, ShortcutKey.RIGHT)),
            down,
        )
    }

    /** A single key steps over the bundle instead of landing inside it. */
    @Test
    fun aSingleKeyStepsOverTheBundle() {
        val bar = listOf(ShortcutKey.UP, ShortcutKey.DOWN, ShortcutKey.LEFT, ShortcutKey.RIGHT, ShortcutKey.ESC)

        assertEquals(
            ShortcutResult.Ok(listOf(ShortcutKey.ESC, ShortcutKey.UP, ShortcutKey.DOWN, ShortcutKey.LEFT, ShortcutKey.RIGHT)),
            ShortcutBar.moveUp(bar, ShortcutKey.ESC),
        )
    }

    /** A stored bar that holds half a bundle comes back whole and contiguous. */
    @Test
    fun parseCompletesAHalfBundle() {
        assertEquals(
            listOf(ShortcutKey.ESC, ShortcutKey.UP, ShortcutKey.DOWN, ShortcutKey.LEFT, ShortcutKey.RIGHT, ShortcutKey.ENTER),
            ShortcutBar.parse(listOf("ESC", "UP", "ENTER")),
        )
    }

    /** Re-adding an arrow that is already present is a duplicate, not a second bundle. */
    @Test
    fun anArrowAlreadyInTheBarIsADuplicate() {
        val bar = ShortcutBar.ARROWS
        assertEquals(ShortcutResult.Duplicate(ShortcutKey.UP), ShortcutBar.add(bar, ShortcutKey.UP))
    }
}
