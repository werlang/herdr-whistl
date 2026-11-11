package io.github.werlang.whistl.domain

/**
 * The keyboard field that feeds the terminal (phase 9, SPEC §2.5), read as edits.
 *
 * Two things the field must do, and both are easy to get wrong:
 *
 * 1. It must never be empty. A text field with nothing in it gives the IME nothing to delete, so pressing backspace
 *    reports nothing at all and the key is lost — text already on the terminal's line (a prompt, a paste) could not be
 *    erased (0.5.8).
 * 2. The cursor must stay at the end of the value the field actually holds. Resetting the field to a bare sentinel left
 *    the cursor before it, and every backspace after the first one deleted nothing (0.5.9: "só a última tecla é apagada").
 *
 * So the field keeps what was typed, up to [KEPT] characters, with the sentinel always last and the cursor after it.
 */
object KeyboardSink {
    /** The zero-width character the field always keeps at its end. */
    const val SENTINEL: Char = '\u200B'

    /** How many typed characters the field keeps, so a delete has something to remove and the value stays small. */
    const val KEPT: Int = 64

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
     * The value the field should hold after [text]: what was typed (the last [KEPT] characters) and the sentinel.
     *
     * @param text what the IME produced.
     * @return the value to set, cursor at its end.
     */
    fun keep(text: String): String = text.replace(SENTINEL.toString(), "").takeLast(KEPT) + SENTINEL

    /**
     * Reads the edit from [previous] to [next].
     *
     * @param previous the field's value before the edit.
     * @param next the value the IME produced.
     * @return the characters to press and how many deletions; both may be empty.
     */
    fun read(previous: String, next: String): Edit {
        val before = previous.replace(SENTINEL.toString(), "")
        val after = next.replace(SENTINEL.toString(), "")
        val common = before.commonPrefixWith(after).length
        val typed = after.substring(common).replace("\n", "\r")
        val deleted = before.length - common
        // A sentinel eaten by a backspace is a key press too: the field was empty of text and the user still deleted.
        val sentinelEaten = next.count { it == SENTINEL } < previous.count { it == SENTINEL }
        val backspaces = deleted + if (sentinelEaten) 1 else 0
        return Edit(typed.ifEmpty { null }, backspaces)
    }
}
