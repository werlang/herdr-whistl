package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.ShortcutBar
import io.github.werlang.whistl.domain.ShortcutKey
import io.github.werlang.whistl.domain.ShortcutResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app language (SPEC §2): follow the system, or one of the two translations. */
enum class AppLanguage(val tag: String) {
    /** Follow the system language. */
    System("system"),

    /** English. */
    English("en"),

    /** Português (Brazil). */
    Portuguese("pt-BR"),
}

/** Terminal font size (SPEC §2). */
enum class FontSize(val code: String) {
    /** Small. */
    Small("S"),

    /** Medium, the default. */
    Medium("M"),

    /** Large. */
    Large("L"),
}

/** The app theme (SPEC §2). */
enum class ThemeChoice {
    /** Follow the system. */
    System,

    /** Always dark; the default, because the app is dark-first (SPEC §3). */
    Dark,

    /** Always light. */
    Light,
}

/**
 * Every setting the app persists (SPEC §2).
 *
 * @property language the app language.
 * @property fontSize the terminal font size.
 * @property theme the app theme.
 * @property shortcutBar the shortcut keys, in bar order.
 */
data class AppSettings(
    val language: AppLanguage,
    val fontSize: FontSize,
    val theme: ThemeChoice,
    val shortcutBar: List<ShortcutKey>,
) {
    companion object {
        /** The defaults: system language, medium font, dark theme, and the SPEC default shortcut bar. */
        val DEFAULT: AppSettings = AppSettings(
            language = AppLanguage.System,
            fontSize = FontSize.Medium,
            theme = ThemeChoice.Dark,
            shortcutBar = ShortcutBar.DEFAULT,
        )
    }
}

/**
 * Key/value persistence for settings. Values are strings, so the repository owns the encoding. The in-memory fake
 * serves tests. The DataStore or SharedPreferences implementation is written in a later round.
 */
interface SettingsStore {
    /**
     * Reads a value.
     *
     * @param key the setting key.
     * @return the stored text, or `null` when nothing was ever stored under [key].
     */
    fun get(key: String): String?

    /**
     * Stores a value, replacing any earlier one.
     *
     * @param key the setting key.
     * @param value the text to store.
     */
    fun put(key: String, value: String)
}

/**
 * The settings the user changes (SPEC §2). Every change is persisted immediately and reflected in [settings].
 */
interface SettingsRepository {
    /** The current settings. */
    val settings: StateFlow<AppSettings>

    /** @param language the new app language. */
    fun setLanguage(language: AppLanguage)

    /** @param size the new terminal font size. */
    fun setFontSize(size: FontSize)

    /** @param theme the new theme. */
    fun setTheme(theme: ThemeChoice)

    /**
     * Moves a key one place earlier in the bar.
     *
     * @param key the key to move.
     * @return [ShortcutResult.Ok] with the new bar, or [ShortcutResult.NotInBar].
     */
    fun moveShortcutUp(key: ShortcutKey): ShortcutResult

    /**
     * Moves a key one place later in the bar.
     *
     * @param key the key to move.
     * @return [ShortcutResult.Ok] with the new bar, or [ShortcutResult.NotInBar].
     */
    fun moveShortcutDown(key: ShortcutKey): ShortcutResult

    /**
     * Removes a key from the bar.
     *
     * @param key the key to remove.
     * @return [ShortcutResult.Ok] with the new bar, or [ShortcutResult.NotInBar].
     */
    fun removeShortcut(key: ShortcutKey): ShortcutResult

    /**
     * Adds a key at the end of the bar. Only a key not already in the bar may be added.
     *
     * @param key the key to add.
     * @return [ShortcutResult.Ok] with the new bar, or [ShortcutResult.Duplicate].
     */
    fun addShortcut(key: ShortcutKey): ShortcutResult

    /**
     * Adds the key whose enum name is [name]. An unknown name is rejected.
     *
     * @param name the enum name, for example `CTRL_C`.
     * @return [ShortcutResult.Ok], [ShortcutResult.Duplicate] or [ShortcutResult.UnknownKey].
     */
    fun addShortcutByName(name: String): ShortcutResult

    /**
     * Restores the SPEC default bar.
     *
     * @return [ShortcutResult.Ok] with the default bar.
     */
    fun restoreDefaultShortcuts(): ShortcutResult
}

/**
 * [SettingsRepository] over a [SettingsStore]. Missing or unknown stored values fall back to the defaults, so a value
 * from a newer app cannot break startup.
 *
 * @param store where the settings are kept.
 */
class DefaultSettingsRepository(private val store: SettingsStore) : SettingsRepository {
    private val state = MutableStateFlow(load())

    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun setLanguage(language: AppLanguage) = update { it.copy(language = language) }

    override fun setFontSize(size: FontSize) = update { it.copy(fontSize = size) }

    override fun setTheme(theme: ThemeChoice) = update { it.copy(theme = theme) }

    override fun moveShortcutUp(key: ShortcutKey): ShortcutResult = applyBar(ShortcutBar.moveUp(bar(), key))

    override fun moveShortcutDown(key: ShortcutKey): ShortcutResult = applyBar(ShortcutBar.moveDown(bar(), key))

    override fun removeShortcut(key: ShortcutKey): ShortcutResult = applyBar(ShortcutBar.remove(bar(), key))

    override fun addShortcut(key: ShortcutKey): ShortcutResult = applyBar(ShortcutBar.add(bar(), key))

    override fun addShortcutByName(name: String): ShortcutResult = applyBar(ShortcutBar.addByName(bar(), name))

    override fun restoreDefaultShortcuts(): ShortcutResult = applyBar(ShortcutBar.restoreDefaults())

    private fun bar(): List<ShortcutKey> = state.value.shortcutBar

    private fun applyBar(result: ShortcutResult): ShortcutResult {
        if (result is ShortcutResult.Ok) update { it.copy(shortcutBar = result.bar) }
        return result
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        state.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val defaults = AppSettings.DEFAULT
        val language = store.get(KEY_LANGUAGE)?.let { tag -> AppLanguage.entries.firstOrNull { it.tag == tag } }
        val font = store.get(KEY_FONT_SIZE)?.let { code -> FontSize.entries.firstOrNull { it.code == code } }
        val theme = store.get(KEY_THEME)?.let { name -> ThemeChoice.entries.firstOrNull { it.name == name } }
        val bar = store.get(KEY_SHORTCUT_BAR)?.let { encoded -> ShortcutBar.parse(decodeBar(encoded)) }
        return AppSettings(
            language = language ?: defaults.language,
            fontSize = font ?: defaults.fontSize,
            theme = theme ?: defaults.theme,
            shortcutBar = bar ?: defaults.shortcutBar,
        )
    }

    private fun save(settings: AppSettings) {
        store.put(KEY_LANGUAGE, settings.language.tag)
        store.put(KEY_FONT_SIZE, settings.fontSize.code)
        store.put(KEY_THEME, settings.theme.name)
        store.put(KEY_SHORTCUT_BAR, settings.shortcutBar.joinToString(",") { it.name })
    }

    private fun decodeBar(encoded: String): List<String> = encoded.split(',').filter { it.isNotEmpty() }

    private companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_THEME = "theme"
        const val KEY_SHORTCUT_BAR = "shortcut_bar"
    }
}
