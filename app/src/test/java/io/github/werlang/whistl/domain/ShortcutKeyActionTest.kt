package io.github.werlang.whistl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One assertion per row of the key table (phase 4 r2): every key's action, and the Ctrl combinations. */
class ShortcutKeyActionTest {

    @Test
    fun everyRowMapsToItsAction() {
        assertEquals(KeyAction.Send("esc"), ShortcutKey.ESC.action())
        assertEquals(KeyAction.Send("tab"), ShortcutKey.TAB.action())
        assertEquals(KeyAction.Modifier, ShortcutKey.CTRL.action())
        assertEquals(KeyAction.Send("up"), ShortcutKey.UP.action())
        assertEquals(KeyAction.Send("down"), ShortcutKey.DOWN.action())
        assertEquals(KeyAction.Send("left"), ShortcutKey.LEFT.action())
        assertEquals(KeyAction.Send("right"), ShortcutKey.RIGHT.action())
        assertEquals(KeyAction.Send("enter"), ShortcutKey.ENTER.action())
        assertEquals(KeyAction.Send("ctrl+c"), ShortcutKey.CTRL_C.action())
        assertEquals(KeyAction.Send("ctrl+d"), ShortcutKey.CTRL_D.action())
        assertEquals(KeyAction.Send("home"), ShortcutKey.HOME.action())
        assertEquals(KeyAction.Send("end"), ShortcutKey.END.action())
        assertEquals(KeyAction.Send("pageup"), ShortcutKey.PG_UP.action())
        assertEquals(KeyAction.Send("pagedown"), ShortcutKey.PG_DN.action())
        assertEquals(KeyAction.Insert('/'), ShortcutKey.SLASH.action())
        assertEquals(KeyAction.Insert('|'), ShortcutKey.PIPE.action())
        assertEquals(KeyAction.Insert('~'), ShortcutKey.TILDE.action())
        assertEquals(KeyAction.Insert('-'), ShortcutKey.MINUS.action())
    }

    /** Only the `ctrl+<letter>` rows are Ctrl combinations; any other key disarms Ctrl and goes out as it is. */
    @Test
    fun onlyCtrlLetterRowsAreCtrlCombinations() {
        assertEquals("ctrl+c", ShortcutKey.CTRL_C.ctrlCombination())
        assertEquals("ctrl+d", ShortcutKey.CTRL_D.ctrlCombination())
        assertNull(ShortcutKey.UP.ctrlCombination())
        assertNull(ShortcutKey.ESC.ctrlCombination())
        assertNull(ShortcutKey.SLASH.ctrlCombination())
        assertNull(ShortcutKey.CTRL.ctrlCombination())
    }

    /** A typed letter becomes `ctrl+<lowercase>` in either case; other characters are not letters. */
    @Test
    fun aTypedLetterBecomesACtrlCombination() {
        assertEquals("ctrl+a", letterCombination('a'))
        assertEquals("ctrl+z", letterCombination('Z'))
        assertEquals("ctrl+x", letterCombination('X'))
        assertNull(letterCombination('1'))
        assertNull(letterCombination('/'))
        assertNull(letterCombination(' '))
        assertNull(letterCombination('é'))
    }
}
