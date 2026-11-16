package org.scrib.transcriber

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

private const val METER_BARS_NEW = 30

private fun stamp(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

private fun clock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun RecordCard(
    config: RecordUiConfig,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(config.cornerDp.dp)
    val filled = config.cardStyle == HomeCardStyle.FILLED
    val click = if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier
    Surface(
        color = if (filled) cs.surfaceContainer else Color.Transparent,
        shape = shape,
        border = if (filled) null else BorderStroke(1.5.dp, cs.outlineVariant),
        modifier = modifier.then(click)
    ) {
        content()
    }
}

@Composable
private fun RecordHintLine(config: RecordUiConfig, remoteHost: String?, centered: Boolean) {
    if (!config.showHint) {
        return
    }
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start
    ) {
        GlyphIcon(if (remoteHost != null) "cloud" else "lock", if (remoteHost != null) "☁" else "🔒", cs.onSurfaceVariant, 13.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            if (remoteHost != null) stringResource(R.string.record_hint_remote, remoteHost) else stringResource(R.string.record_hint_short),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp
        )
    }
}

@Composable
private fun RecordButtonNew(config: RecordUiConfig, remoteHost: String?, onRecord: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (config.buttonStyle == RecordButtonStyle.ROUND) {
        val size = config.buttonSizeDp.dp
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(size + 36.dp), contentAlignment = Alignment.Center) {
                if (config.pulse) {
                    val progress by rememberInfiniteTransition().animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(1800, easing = LinearOutSlowInEasing))
                    )
                    Box(
                        Modifier.size(size)
                            .graphicsLayer {
                                val scale = 0.85f + 0.3f * progress
                                scaleX = scale
                                scaleY = scale
                                alpha = 0.5f * (1f - progress)
                            }
                            .border(3.dp, cs.primary, CircleShape)
                    )
                }
                Box(
                    Modifier.size(size).testTag("scrib/record/button").clip(CircleShape)
                        .background(cs.primary).clickable(onClick = onRecord),
                    contentAlignment = Alignment.Center
                ) {
                    GlyphIcon(config.recordIcon, "🎙", cs.onPrimary, (config.buttonSizeDp * 0.42f).dp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.record_and_transcribe),
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurface
            )
            Spacer(Modifier.height(4.dp))
            RecordHintLine(config, remoteHost, centered = true)
        }
    } else {
        val shape = RoundedCornerShape(config.cornerDp.dp)
        Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().height(58.dp).testTag("scrib/record/button")
                    .clip(shape).background(cs.primary).clickable(onClick = onRecord),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                GlyphIcon(config.recordIcon, "🎙", cs.onPrimary, 22.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.record_and_transcribe),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onPrimary
                )
            }
            Spacer(Modifier.height(8.dp))
            RecordHintLine(config, remoteHost, centered = false)
        }
    }
}

@Composable
private fun RecordItemCard(
    config: RecordUiConfig,
    entry: TranscriptHistory.Entry,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val deleteLabel = stringResource(R.string.action_delete)
    RecordCard(config, Modifier.fillMaxWidth().padding(bottom = 10.dp), onClick = onOpen) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size((config.iconSizeDp + 18).dp).clip(CircleShape).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    GlyphIcon(config.itemIcon, "📄", cs.onPrimaryContainer, config.iconSizeDp.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.fileName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (config.showTime) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon("clock", "🕒", cs.onSurfaceVariant, 12.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(stamp(entry.savedAtMs), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                        }
                    }
                }
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(cs.error.copy(alpha = 0.12f))
                        .clickable(onClick = onDelete).semantics { contentDescription = deleteLabel },
                    contentAlignment = Alignment.Center
                ) {
                    GlyphIcon("trash-2", "🗑", cs.error, 19.dp)
                }
            }
            if (config.previewLines > 0 && entry.text.isNotBlank()) {
                Text(
                    entry.text,
                    fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                    lineHeight = 17.sp, maxLines = config.previewLines, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    config: RecordUiConfig,
    entry: TranscriptHistory.Entry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(maxOf(config.cornerDp, 20).dp), color = cs.surfaceContainerHigh) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp)) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(cs.error.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    GlyphIcon("trash-2", "🗑", cs.error, 24.dp)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.record_delete_title),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = cs.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.record_delete_message), fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainer)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(cs.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        GlyphIcon(config.itemIcon, "📄", cs.onPrimaryContainer, 18.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.fileName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(stamp(entry.savedAtMs), fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainer)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.action_cancel), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    }
                    Row(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(50)).background(cs.error)
                            .clickable(onClick = onConfirm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        GlyphIcon("trash-2", "🗑", cs.onError, 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_delete), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onError)
                    }
                }
            }
        }
    }
}

// The new Record tab. The first-run setup card is not part of it and stays as it was.
@Composable
internal fun RecordTabNew(
    config: RecordUiConfig,
    remoteHost: String?,
    history: List<TranscriptHistory.Entry>,
    onRecord: () -> Unit,
    onOpenEntry: (TranscriptHistory.Entry) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var showClear by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TranscriptHistory.Entry?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        item { RecordButtonNew(config, remoteHost, onRecord) }
        if (history.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(4.dp))
                    GlyphIcon("history", "↺", cs.onSurfaceVariant, 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.history_section_title),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                        color = cs.onSurfaceVariant, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showClear = true }) {
                        GlyphIcon("trash-2", "🗑", cs.error, 14.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.history_clear_all), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.error)
                    }
                }
            }
            items(history, key = { it.id }) { entry ->
                RecordItemCard(
                    config, entry,
                    onOpen = { onOpenEntry(entry) },
                    onDelete = { if (config.confirmDelete) deleteTarget = entry else onDeleteEntry(entry.id) }
                )
            }
        }
    }
    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_message)) },
            confirmButton = {
                TextButton(onClick = { showClear = false; onClearHistory() }) {
                    Text(stringResource(R.string.history_clear_all), color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    deleteTarget?.let { entry ->
        DeleteConfirmDialog(
            config, entry,
            onConfirm = { deleteTarget = null; onDeleteEntry(entry.id) },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun RoundAction(color: Color, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(64.dp).clip(CircleShape).background(color).clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

// The recording screen. Neither a tap outside nor Back may drop a take by accident: the two round
// buttons are the way out, as with the old dialog.
@Composable
internal fun RecordingScreenNew(
    config: RecordUiConfig,
    r: RecordingUi,
    remoteHost: String?,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val blink by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse)
    )
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).graphicsLayer { alpha = blink }.clip(CircleShape).background(cs.error))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.recording), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.error)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    clock(r.elapsedMs), fontSize = 46.sp, fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace, color = cs.onBackground
                )
                if (config.showMeter) {
                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier.fillMaxWidth().height(70.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        for (i in 0 until METER_BARS_NEW) {
                            val level = r.levels.getOrElse(r.levels.size - METER_BARS_NEW + i) { 0f }
                            val height = 4.dp + 62.dp * sqrt(level.coerceIn(0f, 1f))
                            Box(
                                Modifier.weight(1f).height(height).clip(RoundedCornerShape(3.dp))
                                    .background(cs.primary.copy(alpha = if (level > 0f) 1f else 0.35f))
                            )
                        }
                    }
                }
                if (config.showHint) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (remoteHost != null) stringResource(R.string.record_hint_remote, remoteHost) else stringResource(R.string.record_hint),
                        fontSize = 12.5.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp, textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(30.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    RoundAction(cs.surfaceContainer, stringResource(R.string.action_cancel), onCancel) {
                        GlyphIcon("x", "✕", cs.onSurface, 26.dp)
                    }
                    RoundAction(cs.error, stringResource(R.string.action_stop), onStop) {
                        GlyphIcon("square", "■", cs.onError, 24.dp)
                    }
                }
            }
        }
    }
}

// Settings row: the switch turns the Record UI on, Open shows its own settings.
@Composable
fun RecordUiEntry(config: RecordUiConfig, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                GlyphIcon("mic", "🎙", cs.primary, 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Record UI", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    "New look for the Record tab",
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

@Composable
fun RecordUiDialog(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val config by RecordUiSettings.flow(context).collectAsState()
    var pick by remember { mutableStateOf<String?>(null) }
    fun change(edit: (RecordUiConfig) -> RecordUiConfig) = RecordUiSettings.update(context, edit)
    val samples = remember {
        listOf(
            TranscriptHistory.Entry(
                "sample-1", System.currentTimeMillis(), "Recording 09:41", null,
                "Okay so the plan for tomorrow is to call the supplier first thing in the morning, then move the team meeting to Thursday afternoon, and after that send the updated notes to everyone who could not join us today.",
                emptyList()
            ),
            TranscriptHistory.Entry(
                "sample-2", System.currentTimeMillis() - 86_400_000L, "Recording 18:02", null,
                "Remember to buy milk, bread and something for dinner, maybe pasta with the tomatoes from the weekend.",
                emptyList()
            )
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("‹ Back", fontWeight = FontWeight.SemiBold) }
                    Text("Record UI", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = cs.onBackground)
                }
                // Live preview above the scrolling settings.
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(300.dp)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                        RecordButtonNew(config, null) {}
                        samples.forEach { RecordItemCard(config, it, onOpen = {}, onDelete = {}) }
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    SectionLabel("Record button")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BarChip("Round", config.buttonStyle == RecordButtonStyle.ROUND) { change { it.copy(buttonStyle = RecordButtonStyle.ROUND) } }
                        BarChip("Wide", config.buttonStyle == RecordButtonStyle.WIDE) { change { it.copy(buttonStyle = RecordButtonStyle.WIDE) } }
                    }
                    Spacer(Modifier.height(8.dp))
                    SwitchRow("Pulse animation", null, config.pulse) { on -> change { it.copy(pulse = on) } }
                    if (config.buttonStyle == RecordButtonStyle.ROUND) {
                        SliderRow("Button size", config.buttonSizeDp, 64, 120, "dp") { v -> change { it.copy(buttonSizeDp = v) } }
                    }
                    SectionLabel("Recordings list")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BarChip("Filled", config.cardStyle == HomeCardStyle.FILLED) { change { it.copy(cardStyle = HomeCardStyle.FILLED) } }
                        BarChip("Outlined", config.cardStyle == HomeCardStyle.OUTLINED) { change { it.copy(cardStyle = HomeCardStyle.OUTLINED) } }
                    }
                    Spacer(Modifier.height(8.dp))
                    SwitchRow("Show time", null, config.showTime) { on -> change { it.copy(showTime = on) } }
                    SwitchRow("Confirm before delete", "Ask first when deleting a recording", config.confirmDelete) { on ->
                        change { it.copy(confirmDelete = on) }
                    }
                    SliderRow("Corner radius", config.cornerDp, 8, 32, "dp") { v -> change { it.copy(cornerDp = v) } }
                    SliderRow("Icon size", config.iconSizeDp, 16, 32, "dp") { v -> change { it.copy(iconSizeDp = v) } }
                    SliderRow("Preview lines", config.previewLines, 0, 3, "") { v -> change { it.copy(previewLines = v) } }
                    SectionLabel("Recording screen")
                    SwitchRow("Level meter", null, config.showMeter) { on -> change { it.copy(showMeter = on) } }
                    SwitchRow("Privacy hint", "Where the audio goes", config.showHint) { on -> change { it.copy(showHint = on) } }
                    SectionLabel("Icons")
                    IconRow("Record button", config.recordIcon, "🎙") { pick = "record" }
                    IconRow("Recording item", config.itemIcon, "📄") { pick = "item" }
                    Text(
                        "Icons are from Lucide. The corner radius also applies to the wide button.",
                        fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                    TextButton(onClick = { change { RecordUiConfig(enabled = it.enabled) } }) {
                        Text("Reset to default", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    pick?.let { which ->
        IconPickerDialog(
            onPick = { name ->
                change { if (which == "record") it.copy(recordIcon = name) else it.copy(itemIcon = name) }
                pick = null
            },
            onDismiss = { pick = null }
        )
    }
}
