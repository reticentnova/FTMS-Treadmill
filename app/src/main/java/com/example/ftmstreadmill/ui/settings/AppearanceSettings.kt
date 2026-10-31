package com.example.ftmstreadmill.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.data.DarkMode
import com.example.ftmstreadmill.data.ThemeColors
import com.example.ftmstreadmill.data.ThemeSettings
import com.example.ftmstreadmill.ui.theme.ThemePresets
import com.example.ftmstreadmill.ui.theme.isDark
import com.example.ftmstreadmill.ui.theme.rememberColorScheme
import java.util.Locale

/** Light/dark mode and the colour theme picker. */
@Composable
fun AppearanceSettings(
    theme: ThemeSettings,
    onDarkMode: (DarkMode) -> Unit,
    onPreset: (String) -> Unit,
    onCustom: (ThemeColors) -> Unit,
) {
    var editingCustom by remember { mutableStateOf(false) }

    Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.bodyLarge)
    val modes = listOf(
        DarkMode.SYSTEM to R.string.theme_mode_system,
        DarkMode.LIGHT to R.string.theme_mode_light,
        DarkMode.DARK to R.string.theme_mode_dark,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = theme.darkMode == mode,
                onClick = { onDarkMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
            ) { Text(stringResource(label)) }
        }
    }

    Text(stringResource(R.string.theme_colors), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
    // The wallpaper swatch shows what the wallpaper scheme currently resolves to.
    val wallpaper = rememberColorScheme(null, theme.isDark())
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(ThemePresets.all, key = { it.id }) { preset ->
            ThemeChoice(
                name = stringResource(preset.name),
                primary = preset.colors?.let { Color(it.primary) } ?: wallpaper.primary,
                secondary = preset.colors?.let { Color(it.secondary) } ?: wallpaper.secondary,
                selected = theme.presetId == preset.id,
                onClick = { onPreset(preset.id) },
            )
        }
        item(key = ThemeSettings.PRESET_CUSTOM) {
            val selected = theme.presetId == ThemeSettings.PRESET_CUSTOM
            ThemeChoice(
                name = stringResource(R.string.theme_custom),
                primary = Color(theme.custom.primary),
                secondary = Color(theme.custom.secondary),
                selected = selected,
                editable = true,
                // First tap applies the saved custom colours; tapping again while selected edits them.
                onClick = { if (selected) editingCustom = true else onPreset(ThemeSettings.PRESET_CUSTOM) },
            )
        }
    }

    if (editingCustom) {
        CustomThemeDialog(
            initial = theme.custom,
            dark = theme.isDark(),
            onDismiss = { editingCustom = false },
            onSave = { onCustom(it); editingCustom = false },
        )
    }
}

@Composable
private fun ThemeChoice(
    name: String,
    primary: Color,
    secondary: Color,
    selected: Boolean,
    onClick: () -> Unit,
    editable: Boolean = false,
) {
    Column(
        Modifier
            .width(64.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Swatch(primary, secondary, 48.dp, selected)
            when {
                selected && editable -> Icon(Icons.Filled.Edit, stringResource(R.string.edit), tint = contentColorOn(primary))
                selected -> Icon(Icons.Filled.Check, null, tint = contentColorOn(primary))
            }
        }
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** A circle split diagonally between the two seed colours. */
@Composable
private fun Swatch(primary: Color, secondary: Color, size: Dp, selected: Boolean) {
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(0.5f to primary, 0.5f to secondary))
            .border(if (selected) 3.dp else 1.dp, ring, CircleShape),
    )
}

private fun contentColorOn(color: Color) = if (color.luminance() > 0.4f) Color.Black else Color.White

@Composable
private fun CustomThemeDialog(initial: ThemeColors, dark: Boolean, onDismiss: () -> Unit, onSave: (ThemeColors) -> Unit) {
    var primary by rememberSaveable { mutableStateOf(initial.primary) }
    var secondary by rememberSaveable { mutableStateOf(initial.secondary) }
    val colors = ThemeColors(primary, secondary)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_custom_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ColorPicker(stringResource(R.string.theme_primary), primary) { primary = it }
                ColorPicker(stringResource(R.string.theme_secondary), secondary) { secondary = it }
                ThemePreview(colors, dark)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(colors) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ColorPicker(label: String, value: Long, onChange: (Long) -> Unit) {
    // The field keeps its own text so partial input isn't overwritten while typing.
    var hexText by rememberSaveable { mutableStateOf(formatHex(value)) }
    LaunchedEffect(value) {
        // A palette tap changed the colour; show it in the field.
        if (parseHex(hexText) != value) hexText = formatHex(value)
    }
    val invalid = parseHex(hexText) == null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        ThemePresets.palette.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { color ->
                    val selected = color == value
                    val name = formatHex(color)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(Color(color))
                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable(role = Role.RadioButton) { onChange(color) }
                            .semantics { contentDescription = name; this.selected = selected },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(value)))
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = hexText,
                onValueChange = { text ->
                    hexText = text.take(7)
                    parseHex(hexText)?.let(onChange)
                },
                label = { Text(stringResource(R.string.theme_hex)) },
                isError = invalid,
                supportingText = if (invalid) ({ Text(stringResource(R.string.theme_hex_invalid)) }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A few typical components drawn with the generated scheme, so the user can judge it before saving. */
@Composable
private fun ThemePreview(colors: ThemeColors, dark: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.theme_preview), style = MaterialTheme.typography.titleSmall)
        MaterialTheme(colorScheme = rememberColorScheme(colors, dark)) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.theme_preview_card),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {}) { Text(stringResource(R.string.theme_preview_button)) }
                        Spacer(Modifier.weight(1f))
                        Switch(checked = true, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

private fun formatHex(argb: Long) = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

/** Parses `#RRGGBB` (the `#` is optional) into an opaque ARGB value. */
internal fun parseHex(text: String): Long? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6) return null
    return digits.toLongOrNull(16)?.let { it or 0xFF000000 }
}
