package io.github.werlang.whistl.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.werlang.whistl.R
import io.github.werlang.whistl.data.AppLanguage
import io.github.werlang.whistl.data.FontSize
import io.github.werlang.whistl.data.ThemeChoice
import io.github.werlang.whistl.domain.ShortcutBar
import io.github.werlang.whistl.domain.ShortcutKey
import io.github.werlang.whistl.ui.components.MinTouchTarget
import io.github.werlang.whistl.ui.components.SegmentOption
import io.github.werlang.whistl.ui.components.SheetDragHandle
import io.github.werlang.whistl.ui.keyNameRes
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens
import io.github.werlang.whistl.ui.theme.WhistlMono

/** The test tag of the Add key sheet's content, so a test can find and capture it. */
const val ADD_KEY_SHEET_TAG: String = "add-key-sheet"

/** The test tag of the open-source licences sheet. */
const val LICENSES_SHEET_TAG: String = "licenses-sheet"

/**
 * The Settings screen (SPEC §2.6): General (language, terminal font, theme), the shortcut keys in bar order with the Add key
 * sheet, About with the open-source licenses, and, in debug builds only, the demo switch.
 *
 * @param viewModel the screen's state and events.
 * @param modifier the modifier for the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val update by viewModel.update.collectAsState()

    // Coming back from the system screen where installs are allowed: if it was granted, the install continues by itself
    // instead of leaving the card offering "Allow" forever (0.4.0 review, P1).
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onReturnedToScreen()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item { GeneralSection(state = state, viewModel = viewModel) }
            item { ShortcutSection(state = state, viewModel = viewModel) }
            item {
                UpdateSection(
                    versionName = state.versionName,
                    update = update,
                    onCheck = viewModel::checkForUpdates,
                    onDownload = viewModel::downloadUpdate,
                    onInstall = viewModel::installUpdate,
                    onAllow = viewModel::openInstallPermission,
                )
            }
            item { AboutSection(state = state, onLicenses = viewModel::openLicenses) }
            if (state.demoAvailable) {
                item { DemoSection(state = state, onChange = viewModel::setDemo) }
            }
        }
    }
    if (state.addSheetOpen) {
        ModalBottomSheet(dragHandle = { SheetDragHandle() }, onDismissRequest = viewModel::closeAddSheet) {
            AddKeySheet(addable = state.addable, onAdd = viewModel::add)
        }
    }
    if (state.licensesOpen) {
        ModalBottomSheet(dragHandle = { SheetDragHandle() }, onDismissRequest = viewModel::closeLicenses, modifier = Modifier.testTag(LICENSES_SHEET_TAG)) {
            LicensesSheet()
        }
    }
}

/** A section heading, in the prototype's small uppercase style. */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = LocalWhistlTokens.current.muted,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** A rounded card that groups a section's rows. */
@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/** Language, terminal font and theme: a label and its segmented choice on each row. */
@Composable
private fun GeneralSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.settings_general))
        SettingsCard {
            ChoiceRow(label = stringResource(R.string.settings_language)) {
                AppLanguage.entries.forEach { language ->
                    SegmentOption(
                        label = stringResource(languageLabelRes(language)),
                        selected = state.language == language,
                        onClick = { viewModel.setLanguage(language) },
                    )
                }
            }
            ChoiceRow(label = stringResource(R.string.settings_theme)) {
                ThemeChoice.entries.forEach { choice ->
                    SegmentOption(
                        label = stringResource(themeLabelRes(choice)),
                        selected = state.theme == choice,
                        onClick = { viewModel.setTheme(choice) },
                    )
                }
            }
            ChoiceRow(label = stringResource(R.string.settings_font)) {
                FontSize.entries.forEach { size ->
                    SegmentOption(
                        label = stringResource(fontLabelRes(size)),
                        selected = state.fontSize == size,
                        onClick = { viewModel.setFontSize(size) },
                    )
                }
            }
        }
    }
}

/**
 * A row with a label, then its choices. The choices wrap to the next line on a narrow screen or a large font.
 *
 * @param label the row's label.
 * @param options the segmented choices.
 */
@Composable
private fun ChoiceRow(label: String, options: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            options()
        }
    }
}

/** The shortcut keys in bar order, each with move up, move down and remove; then Add key and Restore defaults. */
@Composable
private fun ShortcutSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.settings_shortcuts))
        Text(
            text = stringResource(R.string.settings_shortcuts_hint),
            style = MaterialTheme.typography.bodySmall,
            color = LocalWhistlTokens.current.muted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        SettingsCard {
            // The arrow bundle is one entry: four arrows or none, so Settings shows it once and moves or removes it whole.
            var arrowsDrawn = false
            state.bar.forEachIndexed { index, key ->
                val arrows = ShortcutBar.isArrow(key)
                if (arrows && arrowsDrawn) return@forEachIndexed
                if (arrows) arrowsDrawn = true
                KeyRow(
                    key = key,
                    canUp = index > 0,
                    canDown = index < state.bar.lastIndex,
                    onUp = { viewModel.moveUp(key) },
                    onDown = { viewModel.moveDown(key) },
                    onRemove = { viewModel.remove(key) },
                    glyph = if (arrows) stringResource(R.string.settings_key_arrows_glyphs) else null,
                    name = if (arrows) stringResource(R.string.settings_key_arrows) else null,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = viewModel::openAddSheet, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_add_key))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::restoreDefaults, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.settings_restore_keys))
                }
            }
        }
    }
}

/**
 * One key in the bar: its glyph, its name, and the three buttons. Each button names the key it acts on, and is 48 dp.
 *
 * @param key the key.
 * @param canUp whether it can move earlier.
 * @param canDown whether it can move later.
 * @param onUp called to move it earlier.
 * @param onDown called to move it later.
 * @param onRemove called to remove it from the bar.
 */
@Composable
private fun KeyRow(
    key: ShortcutKey,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
    glyph: String? = null,
    name: String? = null,
) {
    val shown = name ?: stringResource(keyNameRes(key))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphChip(glyph ?: key.label)
        Spacer(Modifier.width(10.dp))
        Text(
            text = shown,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(MinTouchTarget)) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.settings_move_key_up, shown),
            )
        }
        IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(MinTouchTarget)) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.settings_move_key_down, shown),
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(MinTouchTarget)) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.settings_remove_key, shown),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** A key's glyph in its chip, in monospace. The glyph is not translated. */
@Composable
private fun GlyphChip(glyph: String) {
    Box(
        modifier = Modifier
            .widthIn(min = 52.dp)
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(LocalWhistlTokens.current.field)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, fontFamily = WhistlMono, style = MaterialTheme.typography.labelLarge)
    }
}

/** The About card: the app name and version, the one-line privacy statement, and the open-source licenses. */
@Composable
private fun AboutSection(state: SettingsUiState, onLicenses: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.settings_about))
        SettingsCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_version, state.versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalWhistlTokens.current.dim,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_about_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalWhistlTokens.current.muted,
                )
            }
            TextButton(onClick = onLicenses, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.settings_licenses))
            }
        }
    }
}

/** Demo mode, in debug builds only. The whole row is the switch, so its name is part of its control. */
@Composable
private fun DemoSection(state: SettingsUiState, onChange: (Boolean) -> Unit) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .toggleable(value = state.demoEnabled, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.settings_demo), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(text = stringResource(R.string.settings_demo_hint), style = MaterialTheme.typography.bodySmall, color = LocalWhistlTokens.current.muted)
            }
            Switch(checked = state.demoEnabled, onCheckedChange = null)
        }
    }
}

/**
 * The Add key sheet: every key that is not in the bar, in table order. With every key in the bar, it says so.
 *
 * @param addable the keys to offer.
 * @param onAdd called with the key tapped; the sheet closes.
 */
@Composable
private fun AddKeySheet(addable: List<ShortcutKey>, onAdd: (ShortcutKey) -> Unit) {
    Column(
        modifier = Modifier
            .testTag(ADD_KEY_SHEET_TAG)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_add_key),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (addable.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_all_keys_added),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalWhistlTokens.current.muted,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            addable.forEach { key ->
                // One row for the whole arrow bundle, with the bundle's name and notation (0.3.2 review, P2).
                val arrows = ShortcutBar.isArrow(key)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .clickable { onAdd(key) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphChip(if (arrows) stringResource(R.string.settings_key_arrows_glyphs) else key.label)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (arrows) {
                            stringResource(R.string.settings_key_arrows)
                        } else {
                            stringResource(keyNameRes(key))
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(Modifier.size(16.dp))
    }
}

/** The open-source licenses, one line each. Names and licence identifiers are not translated. */
@Composable
private fun LicensesSheet() {
    val lines = listOf(
        R.string.license_jetbrains_mono,
        R.string.license_sshj,
        R.string.license_bouncy_castle,
        R.string.license_androidx,
        R.string.license_kotlinx,
    )
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.licenses_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(lines) { line ->
                Text(text = stringResource(line), style = MaterialTheme.typography.bodyMedium, color = LocalWhistlTokens.current.muted)
            }
        }
    }
}

/** The label resource of a language choice. */
internal fun languageLabelRes(language: AppLanguage): Int = when (language) {
    AppLanguage.System -> R.string.language_system
    AppLanguage.English -> R.string.language_english
    AppLanguage.Portuguese -> R.string.language_portuguese
}

/** The label resource of a theme choice. */
internal fun themeLabelRes(choice: ThemeChoice): Int = when (choice) {
    ThemeChoice.System -> R.string.theme_system
    ThemeChoice.Dark -> R.string.theme_dark
    ThemeChoice.Light -> R.string.theme_light
}

/** The label resource of a terminal font size. */
internal fun fontLabelRes(size: FontSize): Int = when (size) {
    FontSize.Small -> R.string.font_small
    FontSize.Medium -> R.string.font_medium
    FontSize.Large -> R.string.font_large
}
