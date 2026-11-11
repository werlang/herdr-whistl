package io.github.werlang.whistl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A typed character is a key name, never text: text sent to the core arrives with Enter. */
class KeystrokesTest {
    /** A letter is its own key, one per character, in order. */
    @Test
    fun lettersAreKeys() {
        assertEquals(listOf("l", "s"), Keystrokes.of("ls").names)
    }

    /** Enter, Tab, Esc and space have names in the core. */
    @Test
    fun theNamedKeys() {
        assertEquals(listOf("enter"), Keystrokes.of("\r").names)
        assertEquals(listOf("enter"), Keystrokes.of("\n").names)
        assertEquals(listOf("a", "enter"), Keystrokes.of("a\r").names)
        assertEquals(listOf("tab"), Keystrokes.of("\t").names)
        assertEquals(listOf("esc"), Keystrokes.of("\u001b").names)
        assertEquals(listOf("space"), Keystrokes.of(" ").names)
    }

    /** Delete is the backspace key, and it repeats. */
    @Test
    fun deleteIsBackspace() {
        assertEquals(listOf("backspace"), Keystrokes.of("\u007f").names)
        assertEquals(listOf("backspace", "backspace"), Keystrokes.backspaces(2).names)
    }

    /** Unshifted punctuation is a key as it is; a shifted symbol is not a key the core takes. */
    @Test
    fun punctuationIsAKey() {
        val typed = Keystrokes.of("ls /tmp/x")
        assertEquals(listOf("l", "s", "space", "/", "t", "m", "p", "/", "x"), typed.names)
        // `!` and `~` need Shift, and `shift+1` arrives as `1` on hc 0.1.19: they are reported, not typed.
        val shifted = Keystrokes.of("a!|~")
        assertEquals(listOf("a"), shifted.names)
        assertEquals("!|~", shifted.dropped)
    }

    /** An uppercase letter is Shift plus the letter: a bare `A` arrives as `a` from the core. */
    @Test
    fun uppercaseIsShift() {
        assertEquals(listOf("shift+h", "i", "shift+o"), Keystrokes.of("HiO").names)
    }

    /** An accent cannot be a key: it is reported, never sent as text. */
    @Test
    fun anAccentIsDroppedAndReported() {
        val keystrokes = Keystrokes.of("não")

        assertEquals(listOf("n", "o"), keystrokes.names)
        assertEquals("ã", keystrokes.dropped)
        assertTrue(Keystrokes.of("ã").isEmpty)
    }
}
