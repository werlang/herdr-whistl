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

    /** Paste: the clipboard lands at the cursor. Optional in the bar, so it can be removed from it (0.5.8). */
    PASTE("Paste"),
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
    /**
     * The arrow keys as one bundle: they are added, removed and moved together, and the bar draws them as one compact
     * cluster. A bar never holds two of them without the other two.
     */
    val ARROWS: List<ShortcutKey> = listOf(
        ShortcutKey.UP,
        ShortcutKey.DOWN,
        ShortcutKey.LEFT,
        ShortcutKey.RIGHT,
    )

    /** Whether [key] belongs to the arrow bundle. */
    fun isArrow(key: ShortcutKey): Boolean = key in ARROWS

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
        val block = if (isArrow(key)) arrowBlock(bar) else null
        if (block != null) {
            // The bundle moves as one: it passes the whole element above it, never splitting or reordering itself.
            if (block.first == 0) return ShortcutResult.Ok(bar)
            val moved = bar.toMutableList()
            val above = moved.removeAt(block.first - 1)
            moved.add(block.last, above)
            return ShortcutResult.Ok(moved)
        }
        // A single key steps over the bundle rather than landing inside it: it is taken out and put before the block.
        if (index - 1 >= 0 && isArrow(bar[index - 1])) {
            val blockFirst = arrowBlock(bar)?.first ?: index
            val moved = bar.toMutableList()
            val stepped = moved.removeAt(index)
            moved.add(blockFirst, stepped)
            return ShortcutResult.Ok(moved)
        }
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
        val block = if (isArrow(key)) arrowBlock(bar) else null
        if (block != null) {
            if (block.last == bar.lastIndex) return ShortcutResult.Ok(bar)
            val moved = bar.toMutableList()
            val below = moved.removeAt(block.last + 1)
            moved.add(block.first, below)
            return ShortcutResult.Ok(moved)
        }
        // A single key steps over the bundle rather than landing inside it: it is taken out and put after the block.
        if (index + 1 <= bar.lastIndex && isArrow(bar[index + 1])) {
            val blockLast = arrowBlock(bar)?.last ?: index
            val moved = bar.toMutableList()
            val stepped = moved.removeAt(index)
            moved.add(blockLast, stepped)
            return ShortcutResult.Ok(moved)
        }
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
    fun remove(bar: List<ShortcutKey>, key: ShortcutKey): ShortcutResult {
        if (key !in bar) return ShortcutResult.NotInBar(key)
        // Disabling one arrow disables the bundle: four arrows or none.
        if (isArrow(key)) return ShortcutResult.Ok(bar.filterNot { it in ARROWS })
        return ShortcutResult.Ok(bar - key)
    }

    /**
     * Appends [key] to the bar. Only a key not already in the bar may be added.
     *
     * @param bar the current bar.
     * @param key the key to add.
     * @return the new bar, or [ShortcutResult.Duplicate].
     */
    fun add(bar: List<ShortcutKey>, key: ShortcutKey): ShortcutResult {
        if (key in bar) return ShortcutResult.Duplicate(key)
        // Enabling one arrow enables the bundle, in bundle order.
        if (isArrow(key)) return ShortcutResult.Ok(bar + ARROWS)
        return ShortcutResult.Ok(bar + key)
    }

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
        return normaliseArrows(names.mapNotNull { known[it] }.distinct())
    }

    /**
     * The bar with the arrow bundle whole and contiguous, in bundle order.
     *
     * A stored bar from before the bundle existed may hold one, two or three arrows, or the four scattered. The bundle
     * is completed and gathered at the place of the first arrow, so the bar the user sees never has half a bundle.
     */
    fun normaliseArrows(bar: List<ShortcutKey>): List<ShortcutKey> {
        val block = arrowBlock(bar) ?: return bar
        val withoutArrows = bar.filterNot { it in ARROWS }
        val insertAt = bar.subList(0, block.first).count { it !in ARROWS }
        return withoutArrows.subList(0, insertAt) + ARROWS + withoutArrows.subList(insertAt, withoutArrows.size)
    }

    /** The run of arrows [bar] holds, or `null` when it holds none. */
    private fun arrowBlock(bar: List<ShortcutKey>): IntRange? {
        val first = bar.indexOfFirst { it in ARROWS }
        if (first < 0) return null
        var last = first
        while (last + 1 <= bar.lastIndex && bar[last + 1] in ARROWS) last++
        return first..last
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

/**
 * What a shortcut key does in the agent terminal (phase 4 r2). Measured on hc 0.1.18: `terminal.keys` accepts the names
 * `esc tab up down left right enter home end pageup pagedown ctrl+c ctrl+d / -` and `ctrl+<a-z>`, and rejects `|` and `~`.
 * The four character keys therefore insert their character into the text field, and every other key is sent at once.
 */
sealed interface KeyAction {
    /**
     * Sent at once with `terminal.keys`, under the hc key name [hcName].
     *
     * @property hcName the key name hc accepts, for example `ctrl+c`.
     */
    data class Send(val hcName: String) : KeyAction

    /**
     * Typed into the text field at the cursor. hc does not take this character as a key name.
     *
     * @property char the character to insert.
     */
    data class Insert(val char: Char) : KeyAction

    /** Ctrl: it arms the sticky modifier and sends nothing itself. */
    data object Modifier : KeyAction

    /** Paste: the screen reads the clipboard and types it; the core has no paste RPC that keeps the line (0.5.8). */
    data object Paste : KeyAction
}

/**
 * The action for this key. One row per key; the table is tested row by row.
 *
 * @return the key's action.
 */
fun ShortcutKey.action(): KeyAction = when (this) {
    ShortcutKey.ESC -> KeyAction.Send("esc")
    ShortcutKey.TAB -> KeyAction.Send("tab")
    ShortcutKey.CTRL -> KeyAction.Modifier
    ShortcutKey.UP -> KeyAction.Send("up")
    ShortcutKey.DOWN -> KeyAction.Send("down")
    ShortcutKey.LEFT -> KeyAction.Send("left")
    ShortcutKey.RIGHT -> KeyAction.Send("right")
    ShortcutKey.ENTER -> KeyAction.Send("enter")
    ShortcutKey.CTRL_C -> KeyAction.Send("ctrl+c")
    ShortcutKey.CTRL_D -> KeyAction.Send("ctrl+d")
    ShortcutKey.HOME -> KeyAction.Send("home")
    ShortcutKey.END -> KeyAction.Send("end")
    ShortcutKey.PG_UP -> KeyAction.Send("pageup")
    ShortcutKey.PG_DN -> KeyAction.Send("pagedown")
    ShortcutKey.SLASH -> KeyAction.Insert('/')
    ShortcutKey.PIPE -> KeyAction.Insert('|')
    ShortcutKey.TILDE -> KeyAction.Insert('~')
    ShortcutKey.MINUS -> KeyAction.Insert('-')
    ShortcutKey.PASTE -> KeyAction.Paste
}

/**
 * The Ctrl combination a bar key becomes while Ctrl is armed, or `null` when the table has none. Only the `ctrl+<letter>`
 * rows qualify (`^C` and `^D`); any other key disarms Ctrl and is sent as it is.
 *
 * @return the hc key name, for example `ctrl+c`, or `null`.
 */
fun ShortcutKey.ctrlCombination(): String? = (action() as? KeyAction.Send)?.hcName?.takeIf { it.startsWith("ctrl+") }

/**
 * The Ctrl combination for a typed character while Ctrl is armed: `ctrl+<lowercase letter>` for a letter a–z (either case),
 * and `null` for anything else. Typed text is never sent through `terminal.keys` as text: only this name is.
 *
 * @param char the first typed character.
 * @return the hc key name, or `null` when the character is not a letter.
 */
fun letterCombination(char: Char): String? =
    if (char in 'a'..'z' || char in 'A'..'Z') "ctrl+${char.lowercaseChar()}" else null
