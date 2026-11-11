package io.github.werlang.whistl.ui

import androidx.annotation.StringRes
import io.github.werlang.whistl.R
import io.github.werlang.whistl.domain.ShortcutKey

/**
 * The accessible name of a shortcut key (SPEC §7). The glyph on the key itself is not translated; the name is.
 *
 * @param key the shortcut key.
 * @return the string resource with the name.
 */
@StringRes
fun keyNameRes(key: ShortcutKey): Int = when (key) {
    ShortcutKey.ESC -> R.string.key_esc
    ShortcutKey.TAB -> R.string.key_tab
    ShortcutKey.CTRL -> R.string.key_ctrl
    ShortcutKey.UP -> R.string.key_up
    ShortcutKey.DOWN -> R.string.key_down
    ShortcutKey.LEFT -> R.string.key_left
    ShortcutKey.RIGHT -> R.string.key_right
    ShortcutKey.ENTER -> R.string.key_enter
    ShortcutKey.CTRL_C -> R.string.key_ctrl_c
    ShortcutKey.CTRL_D -> R.string.key_ctrl_d
    ShortcutKey.HOME -> R.string.key_home
    ShortcutKey.END -> R.string.key_end
    ShortcutKey.PG_UP -> R.string.key_pg_up
    ShortcutKey.PG_DN -> R.string.key_pg_dn
    ShortcutKey.SLASH -> R.string.key_slash
    ShortcutKey.PIPE -> R.string.key_pipe
    ShortcutKey.TILDE -> R.string.key_tilde
    ShortcutKey.MINUS -> R.string.key_dash
    ShortcutKey.PASTE -> R.string.key_paste
}
