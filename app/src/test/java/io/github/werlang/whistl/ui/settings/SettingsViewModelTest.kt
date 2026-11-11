package io.github.werlang.whistl.ui.settings

import io.github.werlang.whistl.data.AppLanguage
import io.github.werlang.whistl.data.DefaultSettingsRepository
import io.github.werlang.whistl.data.FontSize
import io.github.werlang.whistl.data.ThemeChoice
import io.github.werlang.whistl.domain.ShortcutBar
import io.github.werlang.whistl.domain.ShortcutKey
import io.github.werlang.whistl.support.FakeInstaller
import io.github.werlang.whistl.support.FakeUpdates
import io.github.werlang.whistl.support.InMemorySettingsStore
import io.github.werlang.whistl.support.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Settings view model over the settings repository on an in-memory store (phase 4 r2, §12). */
class SettingsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    /** Records the languages the app was asked to apply. */
    private class RecordingApplier : LanguageApplier {
        val applied: MutableList<AppLanguage> = mutableListOf()

        override fun apply(language: AppLanguage) {
            applied += language
        }
    }

    /** A demo switch the test controls. */
    private class FakeDemo(override val available: Boolean) : DemoControl {
        override var enabled: Boolean = false
            private set

        override fun setEnabled(on: Boolean) {
            enabled = on
        }
    }

    /** A view model over one store, so a second repository on the same store reads what was saved. */
    private class Harness(
        val store: InMemorySettingsStore = InMemorySettingsStore(),
        val applier: RecordingApplier = RecordingApplier(),
        val demo: FakeDemo = FakeDemo(available = true),
        val updates: FakeUpdates = FakeUpdates(),
        val installer: FakeInstaller = FakeInstaller(),
    ) {
        val settings = DefaultSettingsRepository(store)
        val vm = SettingsViewModel(
            settings,
            applier,
            demo,
            versionName = "0.1.0",
            updates = updates,
            installer = installer,
        )
    }

    /** Each setting is saved, is read back by a fresh repository, and the language is applied to the running app. */
    @Test
    fun eachSettingPersistsAndApplies() {
        val h = Harness()

        h.vm.setLanguage(AppLanguage.Portuguese)
        h.vm.setFontSize(FontSize.Large)
        h.vm.setTheme(ThemeChoice.Light)

        assertEquals(listOf(AppLanguage.Portuguese), h.applier.applied)
        val saved = DefaultSettingsRepository(h.store).settings.value
        assertEquals(AppLanguage.Portuguese, saved.language)
        assertEquals(FontSize.Large, saved.fontSize)
        assertEquals(ThemeChoice.Light, saved.theme)
        val state = h.vm.state.value
        assertEquals(AppLanguage.Portuguese, state.language)
        assertEquals(FontSize.Large, state.fontSize)
        assertEquals(ThemeChoice.Light, state.theme)
    }

    /** The system language is applied as the empty choice; the choice itself is what is applied. */
    @Test
    fun theSystemLanguageIsTheChoiceApplied() {
        val h = Harness()

        h.vm.setLanguage(AppLanguage.English)
        h.vm.setLanguage(AppLanguage.System)

        assertEquals(listOf(AppLanguage.English, AppLanguage.System), h.applier.applied)
        assertEquals(AppLanguage.System, h.vm.state.value.language)
    }

    /** Keys move up and down in bar order, can be removed, and a removed key comes back at the end. */
    @Test
    fun keyEditingKeepsTheBarOrder() {
        val h = Harness()

        // Enter steps over the whole arrow bundle, which stays together and in bundle order.
        h.vm.moveUp(ShortcutKey.ENTER)
        assertEquals(
            listOf(ShortcutKey.ESC, ShortcutKey.DELETE, ShortcutKey.TAB, ShortcutKey.CTRL, ShortcutKey.ENTER) +
                ShortcutBar.ARROWS,
            h.vm.state.value.bar,
        )

        h.vm.remove(ShortcutKey.ESC)
        assertFalse(ShortcutKey.ESC in h.vm.state.value.bar)

        h.vm.moveDown(ShortcutKey.TAB)
        assertEquals(ShortcutKey.DELETE, h.vm.state.value.bar.first())

        h.vm.add(ShortcutKey.ESC)
        assertEquals(ShortcutKey.ESC, h.vm.state.value.bar.last())
    }

    /** The Add key list offers only keys that are not in the bar, and adding one closes the sheet. */
    @Test
    fun theAddKeyListExcludesTheKeysInTheBar() {
        val h = Harness()

        h.vm.openAddSheet()
        assertTrue(h.vm.state.value.addSheetOpen)
        assertEquals(ShortcutKey.entries - ShortcutBar.DEFAULT.toSet(), h.vm.state.value.addable)

        h.vm.add(ShortcutKey.SLASH)

        assertFalse(h.vm.state.value.addSheetOpen)
        assertTrue(ShortcutKey.SLASH in h.vm.state.value.bar)
        assertFalse(ShortcutKey.SLASH in h.vm.state.value.addable)
    }

    /** With every key in the bar, nothing is left to add. */
    @Test
    fun withEveryKeyInTheBarNothingIsLeftToAdd() {
        val h = Harness()

        h.vm.openAddSheet()
        h.vm.state.value.addable.forEach { h.vm.add(it) }

        assertEquals(emptyList<ShortcutKey>(), h.vm.state.value.addable)
        // Every key is in the bar; the order is the user's, which starts from the default bar.
        assertEquals(ShortcutKey.entries.toSet(), h.vm.state.value.bar.toSet())
    }

    /** Restore defaults brings back the SPEC bar, whatever was edited. */
    @Test
    fun restoreDefaultsBringsBackTheSpecBar() {
        val h = Harness()
        h.vm.remove(ShortcutKey.ESC)
        h.vm.moveDown(ShortcutKey.UP)

        h.vm.restoreDefaults()

        assertEquals(ShortcutBar.DEFAULT, h.vm.state.value.bar)
    }

    /** Demo mode is offered only where the build has it, and then it can be switched on. */
    @Test
    fun demoModeIsOfferedOnlyWhereTheBuildHasIt() {
        val release = Harness(demo = FakeDemo(available = false))
        assertFalse(release.vm.state.value.demoAvailable)

        val debug = Harness(demo = FakeDemo(available = true))
        debug.vm.setDemo(true)

        assertTrue(debug.vm.state.value.demoAvailable)
        assertTrue(debug.vm.state.value.demoEnabled)
    }

    /** The licences list opens and closes. */
    @Test
    fun theLicensesListOpensAndCloses() {
        val h = Harness()

        h.vm.openLicenses()
        assertTrue(h.vm.state.value.licensesOpen)
        h.vm.closeLicenses()
        assertFalse(h.vm.state.value.licensesOpen)
    }
}
