package io.github.werlang.whistl.domain

/**
 * The keyboard field that feeds the terminal (phase 9, SPEC §2.5), read as edits.
 *
 * The field is invisible and empty of meaning, but it must never be empty of characters: a text field with nothing in it
 * gives the IME nothing to delete, so pressing backspace reports nothing at all and the key is lost. That is why text
 * already on the terminal's line — a prompt, a paste, anything not typed through this field — could not be erased
 * (0.5.8). The field therefore always holds [SENTINEL], a zero-width character, so every backspace deletes something and
 * is reported.
 */
object KeyboardSink {
    /** The zero-width character the field always keeps. */
    const val SENTINEL: Char = '\u200B'

    /** The field's value when the user has typed nothing. */
    val empty: String = SENTINEL.toString()

    /**
     * What one IME edit means for the terminal.
     *
     * @property typed what to press as characters, or `null` when the edit typed nothing.
     * @property backspaces how many times to press DELETE.
     */
    data class Edit(val typed: String?, val backspaces: Int)

    /**
     * Reads the edit from [previous] to [next].
     *
     * @param previous the field's value before the edit.
     * @param next the value the IME produced.
     * @return the characters to press, and how many deletions; both may be empty.
     */
    fun read(previous: String, next: String): Edit {
        val typed = next.replace(SENTINEL.toString(), "")
        if (typed.isNotEmpty()) return Edit(typed.replace("\n", "\r"), 0)
        // Nothing was typed, so whatever shrank was deleted: each lost character is one DELETE.
        return Edit(null, (previous.length - next.length).coerceAtLeast(0))
    }
}
