package org.scrib.transcriber

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

// One tab of the bar: the app's own name and icon are the fallback when nothing is customised.
class NewBarItem(val tag: String, val label: String, val fallbackIcon: Int)

@Composable
fun LucideIcon(name: String, fallback: Int?, tint: Color, size: Dp, description: String? = null) {
    val vector = remember(name) { LucideIcons.resolve(name) }
    when {
        vector != null -> Icon(vector, description, tint = tint, modifier = Modifier.size(size))
        fallback != null -> Icon(painterResource(fallback), description, tint = tint, modifier = Modifier.size(size))
        else -> Text("?", color = tint, fontSize = (size.value * 0.8f).sp)
    }
}

// The floating bar. The caller decides where it sits; margins are applied here.
@Composable
fun NewBar(
    config: NewUiConfig,
    items: List<NewBarItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(config.cornerDp.dp)
    val outline = when (config.outlineColor) {
        BarOutlineColor.ACCENT -> cs.primary
        BarOutlineColor.TEXT -> cs.onSurface
        BarOutlineColor.DEFAULT -> cs.outline
    }
    Row(
        modifier
            .padding(start = config.sideMarginDp.dp, end = config.sideMarginDp.dp, bottom = config.bottomMarginDp.dp)
            .fillMaxWidth()
            .height(config.heightDp.dp)
            .shadow(config.shadowDp.dp, shape)
            .background(cs.surfaceContainer, shape)
            .then(if (config.outlineDp > 0) Modifier.border(config.outlineDp.dp, outline, shape) else Modifier)
            .clip(shape)
            .padding(horizontal = config.paddingDp.dp)
    ) {
        items.forEachIndexed { index, item ->
            if (index == NewUiConfig.TRANSCRIBE_INDEX && !config.showTranscribeTab) return@forEachIndexed
            NewBarTab(config, item, index, index == selected) { onSelect(index) }
        }
    }
}

@Composable
private fun RowScope.NewBarTab(config: NewUiConfig, item: NewBarItem, index: Int, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val showIcon = config.display != BarDisplay.TEXT
    val showText = config.display != BarDisplay.ICON
    val label = config.labelFor(index, item.label)
    val iconColor = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant
    val textColor = if (on) cs.primary else cs.onSurfaceVariant
    Column(
        Modifier.weight(1f).fillMaxHeight().testTag(item.tag)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val pillSize = when {
            !showIcon -> Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            showText -> Modifier.size(width = 48.dp, height = 32.dp)
            else -> Modifier.size(width = 56.dp, height = 36.dp)
        }
        Box(
            pillSize.clip(RoundedCornerShape(50)).background(if (on) cs.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            if (showIcon) {
                LucideIcon(config.iconFor(index), item.fallbackIcon, iconColor, config.iconSizeDp.dp, label)
            } else {
                Text(
                    label, fontSize = (config.textSizeSp + 2).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant
                )
            }
        }
        if (showIcon && showText) {
            Spacer(Modifier.height(3.dp))
            Text(
                label, fontSize = config.textSizeSp.sp, lineHeight = (config.textSizeSp + 1).sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Settings row: the switch turns the New UI on, Open shows its own settings.
@Composable
fun NewUiEntry(config: NewUiConfig, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("✨", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("New UI", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    "Floating bar and a cleaner screen. Hides the top bar.",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            if (config.enabled) {
                TextButton(onClick = onOpen) { Text("Open ›", fontWeight = FontWeight.SemiBold) }
            }
            Switch(checked = config.enabled, onCheckedChange = onToggle)
        }
    }
}

// With the New UI on the top bar is gone, so About lives here.
@Composable
fun AboutEntry(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = cs.surfaceContainer, shape = shape,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("ⓘ", fontSize = 18.sp, color = cs.primary)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                androidx.compose.ui.res.stringResource(R.string.about),
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, modifier = Modifier.weight(1f)
            )
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun BarChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .background(if (selected) cs.primary else cs.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) cs.onPrimary else cs.onSurface)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(), fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun SliderRow(label: String, value: Int, min: Int, max: Int, unit: String, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 13.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
            Text("$value $unit", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat()
        )
    }
}

@Composable
fun NewUiDialog(items: List<NewBarItem>, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val config by NewUiSettings.flow(context).collectAsState()
    var pickIndex by remember { mutableStateOf<Int?>(null) }
    var previewSelected by remember { mutableStateOf(0) }
    var resetCount by remember { mutableStateOf(0) }
    fun change(edit: (NewUiConfig) -> NewUiConfig) = NewUiSettings.update(context, edit)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("‹ Back", fontWeight = FontWeight.SemiBold) }
                    Text("New UI", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = cs.onBackground)
                }
                // Live preview, kept above the scrolling settings so every change is visible at once.
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(150.dp)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (config.newBar) {
                        NewBar(config, items, previewSelected, { previewSelected = it })
                    } else {
                        Text(
                            "New bar style is off", fontSize = 12.5.sp, color = cs.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(12.dp))
                    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("New bar style", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                                Text("Floating bottom bar", fontSize = 12.sp, color = cs.onSurfaceVariant)
                            }
                            Switch(checked = config.newBar, onCheckedChange = { on -> change { it.copy(newBar = on) } })
                        }
                    }
                    if (config.newBar) {
                        SectionLabel("Tabs in bar")
                        Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Transcribe tab", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                                    Text("Turn off to hide it. It is also in Home.", fontSize = 12.sp, color = cs.onSurfaceVariant)
                                }
                                Switch(checked = config.showTranscribeTab, onCheckedChange = { on -> change { it.copy(showTranscribeTab = on) } })
                            }
                        }
                        SectionLabel("Show in bar")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BarChip("Icon + text", config.display == BarDisplay.BOTH) { change { it.copy(display = BarDisplay.BOTH) } }
                            BarChip("Icon only", config.display == BarDisplay.ICON) { change { it.copy(display = BarDisplay.ICON) } }
                            BarChip("Text only", config.display == BarDisplay.TEXT) { change { it.copy(display = BarDisplay.TEXT) } }
                        }
                        SectionLabel("Size & padding")
                        SliderRow("Bar height", config.heightDp, 44, 96, "dp") { v -> change { it.copy(heightDp = v) } }
                        SliderRow("Side margin", config.sideMarginDp, 0, 32, "dp") { v -> change { it.copy(sideMarginDp = v) } }
                        SliderRow("Bottom margin", config.bottomMarginDp, 0, 40, "dp") { v -> change { it.copy(bottomMarginDp = v) } }
                        SliderRow("Corner radius", config.cornerDp, 0, 40, "dp") { v -> change { it.copy(cornerDp = v) } }
                        SliderRow("Inner padding", config.paddingDp, 0, 24, "dp") { v -> change { it.copy(paddingDp = v) } }
                        SliderRow("Icon size", config.iconSizeDp, 14, 32, "dp") { v -> change { it.copy(iconSizeDp = v) } }
                        SliderRow("Text size", config.textSizeSp, 8, 14, "sp") { v -> change { it.copy(textSizeSp = v) } }
                        SectionLabel("Shadow & outline")
                        SliderRow("Shadow", config.shadowDp, 0, 24, "dp") { v -> change { it.copy(shadowDp = v) } }
                        SliderRow("Outline thickness", config.outlineDp, 0, 4, "dp") { v -> change { it.copy(outlineDp = v) } }
                        if (config.outlineDp > 0) {
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BarChip("Default", config.outlineColor == BarOutlineColor.DEFAULT) { change { it.copy(outlineColor = BarOutlineColor.DEFAULT) } }
                                BarChip("Accent", config.outlineColor == BarOutlineColor.ACCENT) { change { it.copy(outlineColor = BarOutlineColor.ACCENT) } }
                                BarChip("Text", config.outlineColor == BarOutlineColor.TEXT) { change { it.copy(outlineColor = BarOutlineColor.TEXT) } }
                            }
                        }
                        SectionLabel("Tabs")
                        items.forEachIndexed { index, item ->
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                                        .background(cs.surfaceContainer)
                                        .clickable { pickIndex = index },
                                    contentAlignment = Alignment.Center
                                ) {
                                    LucideIcon(config.iconFor(index), item.fallbackIcon, cs.onSurface, 24.dp, "Pick icon")
                                }
                                Spacer(Modifier.width(10.dp))
                                LabelField(
                                    initial = config.labels.getOrNull(index) ?: "",
                                    hint = item.label,
                                    resetKey = resetCount,
                                    modifier = Modifier.weight(1f)
                                ) { text ->
                                    change { c -> c.copy(labels = c.labels.toMutableList().also { l -> l[index] = text }) }
                                }
                            }
                        }
                        Text(
                            "Leave a name empty to use the app's own. Icons are from Lucide.",
                            fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                        TextButton(onClick = {
                            change { NewUiConfig(enabled = it.enabled, newBar = it.newBar) }
                            resetCount++
                        }) {
                            Text("Reset bar to default", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    pickIndex?.let { index ->
        IconPickerDialog(
            onPick = { name ->
                change { c -> c.copy(icons = c.icons.toMutableList().also { l -> l[index] = name }) }
                pickIndex = null
            },
            onDismiss = { pickIndex = null }
        )
    }
}

// Typing is kept in local state so a fast typist never races the saved copy of the setting.
@Composable
private fun LabelField(initial: String, hint: String, resetKey: Int, modifier: Modifier, onChange: (String) -> Unit) {
    var text by remember(resetKey) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value.take(14)
            onChange(text)
        },
        placeholder = { Text(hint) },
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun IconPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val suggested = remember { LucideIcons.suggested }
    val q = query.trim().lowercase().replace(' ', '-')
    val results = remember(q) { if (q.isEmpty()) suggested else suggested.filter { it.contains(q) } }
    // Any name of the library works, even one that is not in the suggested list.
    val typed = remember(q) { q.takeIf { it.isNotEmpty() && it !in results && LucideIcons.resolve(it) != null } }
    val shown = remember(results, typed) { if (typed != null) listOf(typed) + results else results }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("Search or type a Lucide name") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text("Close", fontWeight = FontWeight.SemiBold) }
                }
                if (shown.isEmpty()) {
                    Text(
                        if (suggested.isEmpty()) "Lucide icons are not available in this build." else "No icon with that name.",
                        fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(8.dp)
                    )
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(76.dp), modifier = Modifier.weight(1f)) {
                    items(shown) { name ->
                        Column(
                            Modifier.padding(3.dp).clip(RoundedCornerShape(12.dp)).clickable { onPick(name) }
                                .padding(vertical = 10.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LucideIcon(name, null, cs.onSurface, 26.dp, name)
                            Spacer(Modifier.height(4.dp))
                            Text(name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
