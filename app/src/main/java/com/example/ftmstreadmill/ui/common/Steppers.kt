package com.example.ftmstreadmill.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ftmstreadmill.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A labelled value with large − / + buttons that repeat while held. Tapping the value itself opens
 * [onEdit] for typing an exact number, which is faster than tapping for big changes.
 */
@Composable
fun ValueStepper(
    label: String,
    value: String,
    unit: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    showLabel: Boolean = true,
) {
    val buttonSize = if (compact) 44.dp else 52.dp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showLabel) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RepeatButton(Icons.Default.Remove, "${stringResource(R.string.decrease)} $label", onDecrease, enabled, buttonSize)
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .then(
                        if (onEdit != null && enabled) {
                            Modifier.clickable(onClickLabel = stringResource(R.string.edit_value)) { onEdit() }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        value,
                        style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (unit.isNotEmpty()) {
                        Text(
                            unit,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
            }
            RepeatButton(Icons.Default.Add, "${stringResource(R.string.increase)} $label", onIncrease, enabled, buttonSize)
        }
    }
}

/** A round tonal button that fires once on press and then repeatedly while held. */
@Composable
fun RepeatButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 52.dp,
) {
    val current by rememberUpdatedState(onClick)
    val interactions = remember { MutableInteractionSource() }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
                if (enabled) onClick { current(); true }
            }
            .indication(interactions, ripple())
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val press = PressInteraction.Press(down.position)
                        interactions.tryEmit(press)
                        current()
                        val repeater = launch {
                            delay(REPEAT_START_MS)
                            while (true) {
                                current()
                                delay(REPEAT_EVERY_MS)
                            }
                        }
                        val up = waitForUpOrCancellation()
                        repeater.cancel()
                        interactions.tryEmit(if (up != null) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                    }
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = LocalContentColor.current)
        }
    }
}

/** Asks for a decimal number; confirms on the keyboard's Done key too. */
@Composable
fun NumberEditDialog(
    title: String,
    initial: String,
    unit: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val parsed = field.text.replace(',', '.').toDoubleOrNull()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                suffix = { Text(unit) },
                singleLine = true,
                isError = parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { parsed?.let(onConfirm) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Asks for a duration as minutes and seconds. */
@Composable
fun DurationEditDialog(
    title: String,
    initialSec: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initialMin = (initialSec / 60).toString()
    val initialS = "%02d".format(initialSec % 60)
    var minutes by remember { mutableStateOf(TextFieldValue(initialMin, TextRange(0, initialMin.length))) }
    var seconds by remember { mutableStateOf(TextFieldValue(initialS, TextRange(0, initialS.length))) }
    val m = minutes.text.ifBlank { "0" }.toIntOrNull()
    val s = seconds.text.ifBlank { "0" }.toIntOrNull()
    val total = if (m != null && s != null && s in 0..59) m * 60 + s else null
    val valid = total != null && total > 0
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it },
                    label = { Text(stringResource(R.string.minutes)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                Text(":", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = seconds,
                    onValueChange = { seconds = it },
                    label = { Text(stringResource(R.string.seconds)) },
                    singleLine = true,
                    isError = s == null || s !in 0..59,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(total!!) }),
                    modifier = Modifier.weight(1f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(total!!) }, enabled = valid) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A small rounded label, e.g. "Completed" or "Adjusted". */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (emphasized) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

private const val REPEAT_START_MS = 450L
private const val REPEAT_EVERY_MS = 90L
