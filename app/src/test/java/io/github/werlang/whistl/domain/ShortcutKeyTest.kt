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
}
