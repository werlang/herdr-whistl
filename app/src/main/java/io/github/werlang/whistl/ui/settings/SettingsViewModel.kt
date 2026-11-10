package io.github.werlang.whistl.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.werlang.whistl.data.AppLanguage
import io.github.werlang.whistl.data.FontSize
import io.github.werlang.whistl.data.SettingsRepository
import io.github.werlang.whistl.data.ThemeChoice
import io.github.werlang.whistl.domain.ShortcutBar
import io.github.werlang.whistl.domain.ShortcutKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Applies the app language to the running app (SPEC §7): `AppCompatDelegate.setApplicationLocales` in the app, a recorder in
 * tests. An empty choice means the system language.
 */
fun interface LanguageApplier {
    /**
     * Applies a language.
     *
     * @param language the language the user chose.
     */
    fun apply(language: AppLanguage)
}

/**
 * The debug-only demo switch (phase 4 F5). The release build has none: [available] is `false` and nothing can enable it.
 */
interface DemoControl {
    /** Whether this build offers the demo switch. */
    val available: Boolean

    /** Whether demo mode is on. It takes effect at the next start of the app. */
    val enabled: Boolean

    /**
     * Turns demo mode on or off for the next start.
     *
     * @param on the new state.
     */
    fun setEnabled(on: Boolean)
}

/**
 * What the Settings screen shows (SPEC §2.6).
 *
 * @property language the app language.
 * @property fontSize the terminal font size.
 * @property theme the theme.
 * @property bar the shortcut keys, in bar order.
 * @property addable the keys not in the bar, in the order the table lists them.
 * @property addSheetOpen whether the Add key sheet is open.
 * @property licensesOpen whether the open-source licenses list is open.
 * @property demoAvailable whether this build offers the demo switch.
 * @property demoEnabled whether demo mode is on.
 * @property versionName the app version.
 */
data class SettingsUiState(
    val language: AppLanguage,
    val fontSize: FontSize,
    val theme: ThemeChoice,
    val bar: List<ShortcutKey>,
    val addable: List<ShortcutKey>,
    val addSheetOpen: Boolean,
    val licensesOpen: Boolean,
    val demoAvailable: Boolean,
    val demoEnabled: Boolean,
    val versionName: String,
)

/** The two sheets the screen can open. */
private data class Sheets(val add: Boolean = false, val licenses: Boolean = false)

/**
 * The Settings screen (SPEC §2.6). Every change is persisted by [SettingsRepository] at once; the language is also applied to
 * the running app through [language]. Events in, state out.
 *
 * @param settings the persisted settings and the shortcut bar.
 * @param language applies the app language.
 * @param demo the debug-only demo switch.
 * @param versionName the app version to show.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val language: LanguageApplier,
    private val demo: DemoControl,
    private val versionName: String,
) : ViewModel() {
    private val sheets = MutableStateFlow(Sheets())
    private val demoOn = MutableStateFlow(demo.enabled)

    /** The screen's state. */
    val state: StateFlow<SettingsUiState> = combine(settings.settings, sheets, demoOn) { prefs, open, demoEnabled ->
        SettingsUiState(
            language = prefs.language,
            fontSize = prefs.fontSize,
            theme = prefs.theme,
            bar = prefs.shortcutBar,
            // The arrow bundle is offered once, under its own name: adding any arrow adds all four, so four rows
            // promising four single keys would lie (0.3.2 review, P2).
            addable = ShortcutKey.entries
                .filter { it !in prefs.shortcutBar }
                .filter { !ShortcutBar.isArrow(it) || it == ShortcutKey.UP },
            addSheetOpen = open.add,
            licensesOpen = open.licenses,
            demoAvailable = demo.available,
            demoEnabled = demoEnabled,
            versionName = versionName,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, initial())

    /** @param choice the language; applied to the running app as well as stored. */
    fun setLanguage(choice: AppLanguage) {
        settings.setLanguage(choice)
        language.apply(choice)
    }

    /** @param size the terminal font size. */
    fun setFontSize(size: FontSize) = settings.setFontSize(size)

    /** @param choice the theme. */
    fun setTheme(choice: ThemeChoice) = settings.setTheme(choice)

    /** @param key a key in the bar, moved one place earlier. */
    fun moveUp(key: ShortcutKey) {
        settings.moveShortcutUp(key)
    }

    /** @param key a key in the bar, moved one place later. */
    fun moveDown(key: ShortcutKey) {
        settings.moveShortcutDown(key)
    }

    /** @param key a key in the bar, removed from it. */
    fun remove(key: ShortcutKey) {
        settings.removeShortcut(key)
    }

    /** Opens the Add key sheet. */
    fun openAddSheet() {
        sheets.value = sheets.value.copy(add = true)
    }

    /** Closes the Add key sheet. */
    fun closeAddSheet() {
        sheets.value = sheets.value.copy(add = false)
    }

    /** @param key a key not in the bar; appended at the end, and the sheet closes. */
    fun add(key: ShortcutKey) {
        settings.addShortcut(key)
        closeAddSheet()
    }

    /** Restores the SPEC default bar. */
    fun restoreDefaults() {
        settings.restoreDefaultShortcuts()
    }

    /** Opens the open-source licenses list. */
    fun openLicenses() {
        sheets.value = sheets.value.copy(licenses = true)
    }

    /** Closes the open-source licenses list. */
    fun closeLicenses() {
        sheets.value = sheets.value.copy(licenses = false)
    }

    /** @param on demo mode for the next start (debug builds only). */
    fun setDemo(on: Boolean) {
        demo.setEnabled(on)
        demoOn.value = demo.enabled
    }

    private fun initial(): SettingsUiState = SettingsUiState(
        language = AppLanguage.System,
        fontSize = FontSize.Medium,
        theme = ThemeChoice.Dark,
        bar = emptyList(),
        addable = ShortcutKey.entries,
        addSheetOpen = false,
        licensesOpen = false,
        demoAvailable = false,
        demoEnabled = false,
        versionName = versionName,
    )
}
