package io.github.werlang.whistl.domain

/**
 * The shortcut keys the terminal bar can hold (SPEC §2): exactly these eighteen. Glyphs are not translated
 * (SPEC §7), so [label] is the text shown on the key.
 *
 * @property label the key as shown.
 */
enum class ShortcutKey(val label: String) {
    /** Escape. */
    ESC("Esc"),

    /** Tab. */
    TAB("Tab"),

    /** Ctrl, a sticky modifier: armed, the next key is sent with Ctrl. */
    CTRL("Ctrl"),

    /** Up arrow. */
    UP("↑"),

    /** Down arrow. */
    DOWN("↓"),

    /** Left arrow. */
    LEFT("←"),

    /** Right arrow. */
    RIGHT("→"),

    /** Enter. */
    ENTER("Enter"),

    /** Ctrl+C, interrupt. */
    CTRL_C("^C"),

    /** Ctrl+D, end of input. */
    CTRL_D("^D"),

    /** Home. */
    HOME("Home"),

    /** End. */
    END("End"),

    /** Page up. */
    PG_UP("PgUp"),

    /** Page down. */
    PG_DN("PgDn"),

    /** Slash. */
    SLASH("/"),

    /** Pipe. */
    PIPE("|"),

    /** Tilde. */
    TILDE("~"),

    /** Minus. */
    MINUS("-"),
}

/**
 * Outcome of an edit to the shortcut bar. Invalid edits are values, not exceptions.
 */
sealed interface ShortcutResult {
    /**
     * The edit applied.
     *
     * @property bar the bar after the edit, in order.
     */
    data class Ok(val bar: List<ShortcutKey>) : ShortcutResult

    /**
     * The key is already in the bar, so it cannot be added again.
     *
     * @property key the duplicate key.
     */
    data class Duplicate(val key: ShortcutKey) : ShortcutResult

    /**
     * The name does not match any [ShortcutKey].
     *
     * @property name the unknown name, as given.
     */
    data class UnknownKey(val name: String) : ShortcutResult

    /**
     * The key is not in the bar, so it cannot be moved or removed.
     *
     * @property key the key that is missing.
     */
    data class NotInBar(val key: ShortcutKey) : ShortcutResult
}

/**
 * Pure rules for the shortcut bar (SPEC §2): order is the user's, duplicates are not allowed, and the defaults are the
 * SPEC default bar.
 */
object ShortcutBar {
    /** The default bar (SPEC §2): Esc, Tab, Ctrl, ↑, ↓, ←, →, Enter. */
    val DEFAULT: List<ShortcutKey> = listOf(
        ShortcutKey.ESC,
        ShortcutKey.TAB,
        ShortcutKey.CTRL,
        ShortcutKey.UP,
        ShortcutKey.DOWN,
        ShortcutKey.LEFT,
        ShortcutKey.RIGHT,
        ShortcutKey.ENTER,
    )

    /**
     * Moves [key] one place earlier. Moving the first key leaves the bar unchanged.
     *
     * @param bar the current bar.
     * @param key the key to move.
     * @return the new bar, or [ShortcutResult.NotInBar].
     */
    fun moveUp(bar: List<ShortcutKey>, key: ShortcutKey): ShortcutResult {
        val index = bar.indexOf(key)
        if (index < 0) return ShortcutResult.NotInBar(key)
        if (index == 0) return ShortcutResult.Ok(bar)
        return ShortcutResult.Ok(bar.swap(index, index - 1))
    }

    /**
     * Moves [key] one place later. Moving the last key leaves the bar unchanged.
     *
     * @param bar the current bar.
     * @param key the key to move.
     * @return the new bar, or [ShortcutResult.NotInBar].
     */
    fun moveDown(bar: List<ShortcutKey>, key: ShortcutKey): ShortcutResult {
        val index = bar.indexOf(key)
        if (index < 0) return ShortcutResult.NotInBar(key)
        if (index == bar.lastIndex) return ShortcutResult.Ok(bar)
        return ShortcutResult.Ok(bar.swap(index, index + 1))
    }

    /**
     * Removes [key] from the bar.
     *
     * @param bar the current bar.
     * @param key the key to remove.
     * @return the new bar, or [ShortcutResult.NotInBar].
     */
    fun remove(bar: List<ShortcutKey>, key: ShortcutKey): ShortcutResult =
        if (key in bar) ShortcutResult.Ok(bar - key) else ShortcutResult.NotInBar(key)

    /**
     * Appends [key] to the bar. Only a key not already in the bar may be added.
     *
     * @param bar the current bar.
     * @param key the key to add.
     * @return the new bar, or [ShortcutResult.Duplicate].
     */
    fun add(bar: List<ShortcutKey>, key: ShortcutKey): ShortcutResult =
        if (key in bar) ShortcutResult.Duplicate(key) else ShortcutResult.Ok(bar + key)

    /**
     * Appends the key called [name] to the bar. The name must match a [ShortcutKey] exactly.
     *
     * @param bar the current bar.
     * @param name the enum name of the key, for example `CTRL_C`.
     * @return the new bar, or [ShortcutResult.UnknownKey] or [ShortcutResult.Duplicate].
     */
    fun addByName(bar: List<ShortcutKey>, name: String): ShortcutResult {
        val key = ShortcutKey.entries.firstOrNull { it.name == name } ?: return ShortcutResult.UnknownKey(name)
        return add(bar, key)
    }

    /**
     * The bar from stored names: unknown names and repeats are dropped, order is kept.
     *
     * @param names enum names in stored order.
     * @return the valid keys, each once, in order.
     */
    fun parse(names: List<String>): List<ShortcutKey> {
        val known = ShortcutKey.entries.associateBy { it.name }
        return names.mapNotNull { known[it] }.distinct()
    }

    /**
     * The SPEC default bar.
     *
     * @return [DEFAULT].
     */
    fun restoreDefaults(): ShortcutResult = ShortcutResult.Ok(DEFAULT)

    private fun List<ShortcutKey>.swap(i: Int, j: Int): List<ShortcutKey> {
        val copy = toMutableList()
        copy[i] = this[j]
        copy[j] = this[i]
        return copy
    }
}
