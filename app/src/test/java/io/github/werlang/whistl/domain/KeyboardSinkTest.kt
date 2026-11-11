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

    /** Every character lost is one DELETE, and the sentinel eaten is one too: the field always has something to delete. */
    @Test
    fun everyCharacterLostIsOneDelete() {
        assertEquals(1, KeyboardSink.read(KeyboardSink.empty, "").backspaces)
        assertEquals(1, KeyboardSink.read("abc" + KeyboardSink.SENTINEL, "ab" + KeyboardSink.SENTINEL).backspaces)
        assertEquals(1, KeyboardSink.read("abc" + KeyboardSink.SENTINEL, "abc").backspaces)
    }

    /** The field keeps what was typed plus the sentinel, cursor after both, so the next delete has a character to eat. */
    @Test
    fun theFieldKeepsWhatWasTypedAndTheSentinel() {
        assertEquals("abc" + KeyboardSink.SENTINEL, KeyboardSink.keep("abc" + KeyboardSink.SENTINEL))
        assertEquals(KeyboardSink.SENTINEL.toString(), KeyboardSink.keep(KeyboardSink.SENTINEL.toString()))
        assertEquals("x".repeat(KeyboardSink.KEPT) + KeyboardSink.SENTINEL, KeyboardSink.keep("x".repeat(500)))
    }

    /**
     * Every backspace eats the sentinel and leaves exactly one DELETE for the terminal. The field is a joystick, not a
     * buffer: the line belongs to the terminal, so what was typed stays in the field while the deletes go out (0.5.9).
     */
    @Test
    fun everyBackspaceEatsTheSentinelAndSendsOneDelete() {
        var value = KeyboardSink.keep("abc")
        assertEquals("abc" + KeyboardSink.SENTINEL, value)

        var deletes = 0
        repeat(4) {
            val next = value.dropLast(1)
            val edit = KeyboardSink.read(value, next)
            assertEquals("one DELETE per press", 1, edit.backspaces)
            assertNull("a delete types nothing", edit.typed)
            deletes += edit.backspaces
            value = KeyboardSink.keep(next)
        }

        assertEquals(4, deletes)
        assertEquals("abc" + KeyboardSink.SENTINEL, value)
    }

    /** Nothing changed: nothing is pressed. */
    @Test
    fun anEditThatChangesNothingPressesNothing() {
        val edit = KeyboardSink.read(KeyboardSink.empty, KeyboardSink.empty)

        assertNull(edit.typed)
        assertEquals(0, edit.backspaces)
    }
}
