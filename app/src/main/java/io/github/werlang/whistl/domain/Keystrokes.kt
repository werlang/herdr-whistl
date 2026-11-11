package io.github.werlang.whistl.domain

/**
 * The keys that produce [text], in the names the core presses (phase 10, SPEC §2.5).
 *
 * The core types what `terminal.keys` names: `enter`, `tab`, `backspace`, `esc`, `space`, and a single printable ASCII
 * character. It cannot type text — `terminal.send` pastes and then presses Enter — so a keystroke must travel as a key
 * name, never as text: sending `"a"` as text submits the line, which is the bug 0.5.5 shipped (Pablo: "eu aperto, ele
 * envia, como se eu apertasse + enter").
 *
 * @property names the key names, in the order they were typed.
 * @property dropped what could not be a key: anything outside ASCII (an accent, an emoji).
 */
data class Keystrokes(val names: List<String>, val dropped: String = "") {
    /** Nothing to press. */
    val isEmpty: Boolean get() = names.isEmpty()

    companion object {
        /**
         * The keys for [text].
         *
         * @param text what the keyboard produced.
         * @return the key names, and what had to be left out.
         */
        fun of(text: String): Keystrokes {
            val names = ArrayList<String>(text.length)
            val dropped = StringBuilder()
            text.forEach { character ->
                when {
                    character == '\r' || character == '\n' -> names += "enter"
                    character == '\t' -> names += "tab"
                    character == '\u007f' || character == '\b' -> names += "backspace"
                    character == '\u001b' -> names += "esc"
                    character == ' ' -> names += "space"
                    // Measured on hc 0.1.19: a bare uppercase letter arrives lowercase, `shift+a` arrives as `A`, and
                    // `shift+1` still arrives as `1` — Shift only reaches letters, so a shifted symbol is not a key.
                    character in 'A'..'Z' -> names += "shift+" + character.lowercaseChar()
                    character in 'a'..'z' -> names += character.toString()
                    character in '0'..'9' -> names += character.toString()
                    character in UNSHIFTED -> names += character.toString()
                    else -> dropped.append(character)
                }
            }
            return Keystrokes(names, dropped.toString())
        }

        /** [count] times the backspace key. */
        fun backspaces(count: Int): Keystrokes = Keystrokes(List(count) { "backspace" })

        /**
         * The printable ASCII characters that are a key on their own, measured on hc 0.1.19. Everything a US layout needs
         * Shift for (`! | ~ _ + " ( ) @ # $ % & * ^ { } : < >`) is refused by `terminal.keys`.
         */
        private val UNSHIFTED: Set<Char> = " \t-=/\\[];',.`".filterNot { it == ' ' || it == '\t' }.toSet()
    }
}
