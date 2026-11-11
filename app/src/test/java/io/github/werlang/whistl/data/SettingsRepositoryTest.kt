package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.ShortcutBar
import io.github.werlang.whistl.domain.ShortcutKey
import io.github.werlang.whistl.domain.ShortcutResult
import io.github.werlang.whistl.support.InMemorySettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings (round p3r1 §2.9): each setting round-trips through the in-memory store, shortcut edits keep order, and
 * duplicates and unknown keys are rejected with typed results.
 */
class SettingsRepositoryTest {

    @Test
    fun defaultsAreTheDocumentedDefaults() {
        val repo = DefaultSettingsRepository(InMemorySettingsStore())

        val s = repo.settings.value

        assertEquals(AppLanguage.System, s.language)
        assertEquals(FontSize.Medium, s.fontSize)
        assertEquals(ThemeChoice.Dark, s.theme)
        assertEquals(ShortcutBar.DEFAULT, s.shortcutBar)
    }

    /** Each setting is written to the store and read back by a fresh repository over the same store. */
    @Test
    fun eachSettingRoundTripsThroughTheStore() {
        val store = InMemorySettingsStore()
        val first = DefaultSettingsRepository(store)

        first.setLanguage(AppLanguage.Portuguese)
        first.setFontSize(FontSize.Large)
        first.setTheme(ThemeChoice.Light)
        first.moveShortcutUp(ShortcutKey.ENTER)
        first.removeShortcut(ShortcutKey.TAB)

        val reloaded = DefaultSettingsRepository(store).settings.value
        assertEquals(AppLanguage.Portuguese, reloaded.language)
        assertEquals(FontSize.Large, reloaded.fontSize)
        assertEquals(ThemeChoice.Light, reloaded.theme)
        assertEquals(first.settings.value.shortcutBar, reloaded.shortcutBar)
        assertEquals("pt-BR", AppLanguage.Portuguese.tag)
    }

    /** Move up and move down change the bar's order and keep every key. */
    @Test
    fun moveUpAndMoveDownKeepOrderAndEveryKey() {
        val repo = DefaultSettingsRepository(InMemorySettingsStore())

        repo.moveShortcutDown(ShortcutKey.ESC)
        assertEquals(
            listOf(ShortcutKey.DELETE, ShortcutKey.ESC, ShortcutKey.TAB, ShortcutKey.CTRL, ShortcutKey.UP,
                ShortcutKey.DOWN, ShortcutKey.LEFT, ShortcutKey.RIGHT, ShortcutKey.ENTER),
            repo.settings.value.shortcutBar,
        )

        // A single key steps over the arrow bundle instead of landing inside it.
        repo.moveShortcutUp(ShortcutKey.ENTER)
        assertEquals(
            listOf(ShortcutKey.DELETE, ShortcutKey.ESC, ShortcutKey.TAB, ShortcutKey.CTRL, ShortcutKey.ENTER,
                ShortcutKey.UP, ShortcutKey.DOWN, ShortcutKey.LEFT, ShortcutKey.RIGHT),
            repo.settings.value.shortcutBar,
        )
        assertEquals(9, repo.settings.value.shortcutBar.toSet().size)
    }

    /** Remove drops a key; adding it back places it at the end; the bar keeps its order otherwise. */
    @Test
    fun removeAndAddKeepTheRestOfTheOrder() {
        val repo = DefaultSettingsRepository(InMemorySettingsStore())

        repo.removeShortcut(ShortcutKey.TAB)
        assertEquals(ShortcutBar.DEFAULT - ShortcutKey.TAB, repo.settings.value.shortcutBar)

        val result = repo.addShortcut(ShortcutKey.TAB)
        assertEquals(ShortcutResult.Ok(ShortcutBar.DEFAULT - ShortcutKey.TAB + ShortcutKey.TAB), result)
        assertEquals(ShortcutKey.TAB, repo.settings.value.shortcutBar.last())
    }

    /** Adding a key already in the bar is rejected, and the bar is unchanged. */
    @Test
    fun duplicateKeyIsRejectedAndBarIsUnchanged() {
        val repo = DefaultSettingsRepository(InMemorySettingsStore())
        val before = repo.settings.value.shortcutBar

        val result = repo.addShortcut(ShortcutKey.ESC)

        assertEquals(ShortcutResult.Duplicate(ShortcutKey.ESC), result)
        assertEquals(before, repo.settings.value.shortcutBar)
    }

    /** A name that matches no key is rejected with [ShortcutResult.UnknownKey]; the bar is unchanged. */
    @Test
    fun unknownKeyNameIsRejectedAndBarIsUnchanged() {
        val repo = DefaultSettingsRepository(InMemorySettingsStore())
        val before = repo.settings.value.shortcutBar

        val result = repo.addShortcutByName("NOT_A_KEY")

        assertEquals(ShortcutResult.UnknownKey("NOT_A_KEY"), result)
        assertEquals(before, repo.settings.value.shortcutBar)
    }

    /** Moving or removing a key that is not in the bar is rejected with [ShortcutResult.NotInBar]. */
    @Test
    fun keyNotInTheBarCannotBeMovedOrRemoved() {
        val repo = DefaultSettingsRepository(InMemorySettingsStore())
        repo.removeShortcut(ShortcutKey.TAB)

        assertEquals(ShortcutResult.NotInBar(ShortcutKey.TAB), repo.moveShortcutUp(ShortcutKey.TAB))
        assertEquals(ShortcutResult.NotInBar(ShortcutKey.TAB), repo.removeShortcut(ShortcutKey.TAB))
    }

    /** Restore defaults gives back exactly the SPEC default bar. */
    @Test
    fun restoreDefaultsGivesTheSpecDefaultBar() {
        val repo = DefaultSettingsRepository(InMemorySettingsStore())
        repo.removeShortcut(ShortcutKey.ESC)
        repo.moveShortcutDown(ShortcutKey.CTRL)
        repo.addShortcut(ShortcutKey.CTRL_C)

        val result = repo.restoreDefaultShortcuts()

        assertEquals(ShortcutResult.Ok(ShortcutBar.DEFAULT), result)
        assertEquals(
            listOf("Esc", "⌫", "Tab", "Ctrl", "↑", "↓", "←", "→", "Enter"),
            repo.settings.value.shortcutBar.map { it.label },
        )
    }

    /** A stored bar with unknown and repeated names loads as the valid keys, each once, in order. */
    @Test
    fun storedBarWithUnknownAndRepeatedNamesLoadsAsValidKeys() {
        val store = InMemorySettingsStore()
        store.put("shortcut_bar", "CTRL_C,BOGUS,CTRL_C,ESC")

        val loaded = DefaultSettingsRepository(store).settings.value.shortcutBar

        assertEquals(listOf(ShortcutKey.CTRL_C, ShortcutKey.ESC), loaded)
    }
}
