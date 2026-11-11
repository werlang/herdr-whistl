package io.github.werlang.whistl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The keyboard field always keeps a character the IME can delete: an empty field swallows backspace, and that is why text
 * already on the terminal's line could not be erased.
 */
class KeyboardSinkTest {
    /** A typed letter is pressed as a key; the field goes back to its sentinel. */
    @Test
    fun aTypedLetterIsPressed() {
        assertEquals(KeyboardSink.Edit("a", 0), KeyboardSink.read(KeyboardSink.empty, KeyboardSink.empty + "a"))
    }

    /** Enter arrives as a newline and leaves as the carriage return the terminal expects. */
    @Test
    fun enterIsACarriageReturn() {
        assertEquals(KeyboardSink.Edit("a\r", 0), KeyboardSink.read(KeyboardSink.empty, KeyboardSink.empty + "a\n"))
    }

    /** A backspace that eats the sentinel is still reported: this is the case that was lost before 0.5.8. */
    @Test
    fun backspaceOnAnEmptyFieldDeletesOnTheTerminal() {
        val edit = KeyboardSink.read(KeyboardSink.empty, "")

        assertNull("nothing was typed", edit.typed)
        assertEquals(1, edit.backspaces)
    }

    /** Every character lost is one DELETE: the field holds only the sentinel, so this is one per backspace press. */
    @Test
    fun everyCharacterLostIsOneDelete() {
        assertEquals(1, KeyboardSink.read(KeyboardSink.empty, "").backspaces)
        assertEquals(4, KeyboardSink.read(KeyboardSink.empty + "abc", "").backspaces)
    }

    /** Nothing changed: nothing is pressed. */
    @Test
    fun anEditThatChangesNothingPressesNothing() {
        val edit = KeyboardSink.read(KeyboardSink.empty, KeyboardSink.empty)

        assertNull(edit.typed)
        assertEquals(0, edit.backspaces)
    }
}
