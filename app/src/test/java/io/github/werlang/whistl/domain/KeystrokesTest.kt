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

    /** Punctuation is a key as it is: the core takes any printable ASCII character. */
    @Test
    fun punctuationIsAKey() {
        assertEquals(listOf("c", "d", "space", "~", "/", "a"), Keystrokes.of("cd ~/a").names)
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
