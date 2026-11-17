package org.scrib.transcriber

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

// The Transcribing UI: the transcribing view as a full screen instead of a window in the middle.
// Off by default; the old window stays in the app and is used whenever this is off.

private fun trMb(mb: Int): String = when {
    mb <= 0 -> "—"
    mb >= 1024 -> String.format(Locale.ROOT, "%.1f GB", mb / 1024f)
    else -> "$mb MB"
}

private fun trMs(ms: Long): String = if (ms > 0L) elapsedOf(ms) else "—"

@Composable
private fun TrCard(config: TranscribingUiConfig, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(config.cornerDp.dp)
    val filled = config.cardStyle == HomeCardStyle.FILLED
    Surface(
        color = if (filled) cs.surfaceContainer else Color.Transparent,
        shape = shape,
        border = if (filled) null else BorderStroke(1.5.dp, cs.outlineVariant),
        modifier = modifier
    ) {
        content()
    }
}

// The real screen: a full-screen dialog around the content. Back or the close button ask first
// while it is running, since cancelling throws the work away.
@Composable
fun TranscribingScreen(
    config: TranscribingUiConfig,
    t: TranscribeUi,
    showStats: Boolean,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onSave: (Uri) -> Unit,
    onFormat: (TranscriptFormat) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val auto by AutoSaveSettings.flow(context).collectAsState()
    var confirmCancel by remember { mutableStateOf(false) }
    val baseName = t.fileName.substringBeforeLast('.').ifBlank { "transcript" }
    val saveContract = remember(t.sourceUri, t.format) {
        CreateTranscriptDocument(t.sourceUri, transcriptMimeType(t.format))
    }
    val saveLauncher = rememberLauncherForActivityResult(saveContract) { uri ->
        if (uri != null) onSave(uri)
    }
    val leave = { if (t.running) confirmCancel = true else onClose() }

    Dialog(
        onDismissRequest = leave,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                TranscribingContent(
                    config = config,
                    auto = auto,
                    t = t,
                    showStats = showStats,
                    onCloseOrCancel = leave,
                    onCopy = { clipboard.setText(AnnotatedString(t.formatted)) },
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, t.formatted)
                            .putExtra(Intent.EXTRA_SUBJECT, baseName)
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    onSave = { saveLauncher.launch("$baseName.${t.format.extension}") },
                    onFormat = onFormat
                )
            }
        }
    }
    if (confirmCancel && t.running) {
        val moreQueued = t.batchTotal > 1 && t.batchIndex + 1 < t.batchTotal
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel transcribing?") },
            text = {
                Text("Progress so far will be lost." + if (moreQueued) " The other files in the queue will stop too." else "")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    onCancel()
                }) { Text("Cancel transcribing", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text("Keep going") }
            }
        )
    }
}

@Composable
internal fun TranscribingContent(
    config: TranscribingUiConfig,
    auto: AutoSaveConfig,
    t: TranscribeUi,
    showStats: Boolean,
    onCloseOrCancel: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onFormat: (TranscriptFormat) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val alarm by AlarmSettings.flow(context).collectAsState()
    val failed = t.error != null
    val finished = !t.running && !failed
    val hasText = !failed && t.text.isNotBlank()
    val showText = !failed && ((t.running && config.liveText && t.text.isNotBlank()) || finished)
    val detailsCard = t.running && config.detailedEta
    // Audiobook chapters are saved on their own, so they get no format picker or Save button.
    val chapter = t.autoSave.isNotEmpty()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        TrHeader(config, t, hasText, onCloseOrCancel, onCopy, onShare)
        Spacer(Modifier.height(14.dp))
        if (!failed) {
            if (config.progressStyle == TranscribingProgressStyle.BAR) {
                if (t.percent < 0 && t.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                } else {
                    LinearProgressIndicator(
                        progress = { (if (finished) 100 else t.percent).coerceIn(0, 100) / 100f },
                        drawStopIndicator = {},
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (!detailsCard) {
                val line = trInfoLine(config, t)
                if (line.isNotEmpty()) {
                    Text(line, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        if (failed) {
            TrCard(config, Modifier.fillMaxWidth()) {
                Text(t.error ?: "", fontSize = 13.sp, color = cs.error, lineHeight = 18.sp, modifier = Modifier.padding(16.dp))
            }
            Spacer(Modifier.weight(1f))
        } else {
            // The blocks in the order the person chose, skipping the ones that are off. Without the
            // transcript there is no block that stretches, so a spacer first pushes the rest down
            // together and nothing is left floating with a gap under it.
            val blocks = config.order.filter { block ->
                when (block) {
                    TranscribingBlock.DETAILS -> detailsCard
                    TranscribingBlock.TEXT -> showText
                    TranscribingBlock.STATS -> showStats && t.metrics.hasData
                    TranscribingBlock.AUTO -> trAutoSaveShown(auto, t)
                    TranscribingBlock.KEEP -> t.running && config.showKeepScreen
                    TranscribingBlock.HINT -> t.running && config.showHint
                }
            }
            if (TranscribingBlock.TEXT !in blocks) {
                Spacer(Modifier.weight(1f))
            }
            blocks.forEachIndexed { index, block ->
                if (index > 0) {
                    Spacer(Modifier.height(10.dp))
                }
                when (block) {
                    TranscribingBlock.DETAILS -> TrDetails(config, t)
                    TranscribingBlock.TEXT -> TrCard(config, Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                            if (finished && config.formatPicker && !chapter && t.segments.isNotEmpty()) {
                                TrFormats(t.format, t.segments.any { it.words.isNotEmpty() }, onFormat)
                                Spacer(Modifier.height(10.dp))
                            }
                            val timed = t.format != TranscriptFormat.TXT && t.segments.isNotEmpty()
                            Text(
                                t.formatted,
                                fontSize = if (timed) 12.sp else config.textSizeSp.sp,
                                lineHeight = if (timed) 17.sp else (config.textSizeSp * 1.45f).sp,
                                fontFamily = if (timed) FontFamily.Monospace else null,
                                color = cs.onSurface
                            )
                        }
                    }
                    TranscribingBlock.STATS -> TrStats(config, t.metrics)
                    TranscribingBlock.AUTO -> TrAutoSave(config, auto, t)
                    TranscribingBlock.KEEP -> TrCard(config, Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.keep_screen_title), fontSize = 13.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                            Switch(
                                checked = alarm.keepScreenOn,
                                onCheckedChange = { on -> AlarmSettings.update(context) { it.copy(keepScreenOn = on) } }
                            )
                        }
                    }
                    TranscribingBlock.HINT -> Text(
                        stringResource(R.string.transcribe_background_hint),
                        fontSize = 11.5.sp, color = cs.onSurfaceVariant, lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
        if (finished && hasText && !chapter) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                GlyphIcon(config.saveIcon, "⬇", cs.onPrimary, 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_save_ext, t.format.extension), fontWeight = FontWeight.Bold)
            }
        }
        if (failed) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCloseOrCancel, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.action_close), fontWeight = FontWeight.Bold)
            }
        }
    }
}

// "32% · ~1m 1s left" while running, empty when both parts are switched off.
private fun trInfoLine(config: TranscribingUiConfig, t: TranscribeUi): String {
    if (t.running) {
        if (t.percent < 0) return "Decoding audio…"
        val parts = ArrayList<String>()
        if (config.showPercent) parts.add("${t.percent.coerceIn(0, 100)}%")
        val left = t.fileEstimateMs ?: t.etaMs
        if (config.showEta && left != null) parts.add("~" + shortDuration(left) + " left")
        return parts.joinToString(" · ")
    }
    return if (t.metrics.totalMs > 0L) "Finished · ${elapsedOf(t.metrics.totalMs)}" else "Finished"
}

@Composable
private fun TrHeader(
    config: TranscribingUiConfig,
    t: TranscribeUi,
    hasText: Boolean,
    onCloseOrCancel: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val status = when {
        t.error != null -> stringResource(R.string.transcribe_failed)
        t.running && t.percent < 0 -> "Decoding audio"
        t.running -> "Transcribing"
        else -> "Finished"
    }
    val subtitle = buildString {
        append(status)
        if (!t.modelName.isNullOrBlank()) append(" · ").append(t.modelName)
        if (config.showBatch && t.running && t.batchTotal > 1) append(" · ${t.batchIndex + 1} of ${t.batchTotal}")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TrCloseButton(config, t, onCloseOrCancel)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.fileName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = cs.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        TrRoundButton("copy", "⧉", stringResource(R.string.action_copy), hasText, config, onCopy)
        Spacer(Modifier.width(8.dp))
        TrRoundButton("share-2", "⤴", stringResource(R.string.action_share), hasText, config, onShare)
    }
}

@Composable
private fun TrRoundButton(name: String, glyph: String, label: String, enabled: Boolean, config: TranscribingUiConfig, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(44.dp).alpha(if (enabled) 1f else 0.4f).clip(CircleShape).background(cs.surfaceContainer)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(name, glyph, cs.onSurface, config.iconSizeDp.dp)
    }
}

// The close button doubles as the progress: with the ring style the ring runs inside the circle,
// around the cross, and fills as the percent grows.
@Composable
private fun TrCloseButton(config: TranscribingUiConfig, t: TranscribeUi, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ring = config.progressStyle == TranscribingProgressStyle.RING && t.error == null
    val label = if (t.running) stringResource(R.string.action_cancel) else stringResource(R.string.action_close)
    val spin by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing))
    )
    val track = cs.outlineVariant
    val color = cs.primary
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(cs.surfaceContainer)
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (ring) {
            val indeterminate = t.running && t.percent < 0
            val fraction = (if (t.running) t.percent else 100).coerceIn(0, 100) / 100f
            val width = config.ringWidthDp
            Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                val stroke = width.dp.toPx()
                val topLeft = Offset(stroke / 2f, stroke / 2f)
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(width = stroke))
                drawArc(
                    color = color,
                    startAngle = if (indeterminate) spin - 90f else -90f,
                    sweepAngle = if (indeterminate) 90f else 360f * fraction,
                    useCenter = false, topLeft = topLeft, size = arc,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        GlyphIcon("x", "✕", cs.onSurface, (if (ring) minOf(config.iconSizeDp, 18) else config.iconSizeDp).dp)
    }
}

@Composable
private fun TrDetails(config: TranscribingUiConfig, t: TranscribeUi) {
    val calculating = stringResource(R.string.eta_calculating)
    val remainingFiles = t.batchTotal - t.batchIndex
    val thisFileMs = if (t.decoding) {
        t.decodeLeftMs?.let { d -> t.fileEstimateMs?.let { d + it } }
    } else {
        t.fileEstimateMs
    }
    val left = t.fileEstimateMs ?: t.etaMs
    val fileText = when {
        t.decoding -> t.decodeLeftMs?.let { stringResource(R.string.eta_decode_left, shortDuration(it)) }
        else -> t.fileEstimateMs?.let { stringResource(R.string.eta_inference_left, shortDuration(it)) }
    } ?: calculating
    TrCard(config, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (config.showPercent) {
                TrRow("Progress", if (t.percent < 0) "…" else "${t.percent.coerceIn(0, 100)}%")
            }
            if (config.showEta) {
                TrRow("Time left", left?.let { "~" + shortDuration(it) } ?: calculating)
            }
            TrRow(stringResource(R.string.eta_total), thisFileMs?.let { shortDuration(it) } ?: calculating)
            TrRow(stringResource(R.string.eta_this_file), fileText)
            if (remainingFiles > 1) {
                TrRow(stringResource(R.string.eta_all_remaining, remainingFiles), t.totalEstimateMs?.let { shortDuration(it) } ?: calculating)
            }
        }
    }
}

@Composable
private fun TrRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
    }
}

@Composable
private fun TrFormats(selected: TranscriptFormat, hasWords: Boolean, onSelect: (TranscriptFormat) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TranscriptFormat.entries.forEach { format ->
            if (format == TranscriptFormat.WORDS && !hasWords) {
                return@forEach
            }
            val on = format == selected
            Box(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(if (on) cs.primary else cs.surfaceContainerHigh)
                    .clickable { onSelect(format) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    format.name, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp,
                    color = if (on) cs.onPrimary else cs.onSurfaceVariant
                )
            }
        }
    }
}

// Live performance figures as small tiles. They follow the Live stats switch, so with it off the
// run only has the figures it took at the end.
@Composable
private fun TrStats(config: TranscribingUiConfig, m: TranscriptionMetrics) {
    val cs = MaterialTheme.colorScheme
    val tiles = listOf(
        "Speed" to (m.rtf?.let { String.format(Locale.ROOT, "%.2f×", it) } ?: "—"),
        "Memory" to trMb(m.pssMb),
        "Peak" to trMb(m.peakPssMb),
        "Free RAM" to trMb(m.freeRamMb),
        "Decode" to trMs(m.decodeMs),
        "Model load" to trMs(m.modelLoadMs)
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tiles.chunked(3).forEach { rowTiles ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowTiles.forEach { tile ->
                    TrCard(config, Modifier.weight(1f)) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                            Text(tile.first, fontSize = 10.5.sp, color = cs.onSurfaceVariant, maxLines = 1)
                            Text(tile.second, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// Whether the auto save card has anything to show, so the block list can leave it out.
private fun trAutoSaveShown(auto: AutoSaveConfig, t: TranscribeUi): Boolean {
    if (t.error != null) return false
    if (t.autoSave.isNotEmpty()) return true
    if (t.running) return auto.enabled
    return t.autoSaved != null
}

private fun trBlockLabel(block: TranscribingBlock): String = when (block) {
    TranscribingBlock.DETAILS -> "Detailed estimates card"
    TranscribingBlock.TEXT -> "Transcript text"
    TranscribingBlock.STATS -> "Live performance stats"
    TranscribingBlock.AUTO -> "Auto save card"
    TranscribingBlock.KEEP -> "Keep screen on"
    TranscribingBlock.HINT -> "Background hint"
}

private fun trMove(order: List<TranscribingBlock>, index: Int, delta: Int): List<TranscribingBlock> {
    val to = index + delta
    if (index !in order.indices || to !in order.indices) return order
    val list = order.toMutableList()
    val moved = list.removeAt(index)
    list.add(to, moved)
    return list
}

@Composable
private fun TrMoveButton(name: String, glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(38.dp).alpha(if (enabled) 1f else 0.3f).clip(CircleShape).background(cs.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(name, glyph, cs.onSurface, 20.dp)
    }
}

// One compact card: while running what will be saved, afterwards what was.
@Composable
private fun TrAutoSave(config: TranscribingUiConfig, auto: AutoSaveConfig, t: TranscribeUi) {
    if (t.error != null) return
    val chapter = t.autoSave.isNotEmpty()
    val outcome = t.autoSaved
    if (!chapter) {
        if (t.running && !auto.enabled) return
        if (!t.running && outcome == null) return
    }
    val cs = MaterialTheme.colorScheme
    val title: String
    val line: String
    val icon: String
    if (chapter) {
        title = if (t.running) "Auto save" else "Saved"
        line = t.autoSave.joinToString(" · ") + " · in the chapters folder"
        icon = if (t.running) "save" else "check"
    } else if (t.running) {
        title = "Auto save"
        val names = auto.formatList().joinToString(" · ") { it.name }.ifEmpty { "No format chosen" }
        line = names + " · " + if (auto.sameFolder) "next to audio" else "chosen folder"
        icon = "save"
    } else if (outcome != null && outcome.error != null) {
        title = "Auto save failed"
        line = outcome.error
        icon = "circle-alert"
    } else {
        val files = outcome?.saved.orEmpty()
        title = "Saved ${files.size} file" + if (files.size == 1) "" else "s"
        line = files.joinToString(" · ") { it.substringAfterLast('.').uppercase() } + " · " + (outcome?.place ?: "")
        icon = "check"
    }
    TrCard(config, Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                GlyphIcon(icon, if (icon == "circle-alert") "!" else "✓", cs.onPrimary, 16.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(line, fontSize = 11.5.sp, color = cs.onSurfaceVariant, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// Settings row: the switch turns the Transcribing UI on, Open shows its own settings.
@Composable
fun TranscribingUiEntry(config: TranscribingUiConfig, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                GlyphIcon("audio-lines", "≋", cs.primary, 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Transcribing UI", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    "Full-screen look for transcribing",
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

private fun trFolderLabel(uri: String?): String {
    if (uri == null) return "Not chosen yet"
    val id = try {
        DocumentsContract.getTreeDocumentId(Uri.parse(uri))
    } catch (e: Exception) {
        return "Chosen"
    }
    val path = id.substringAfter(':', "")
    val volume = if (id.startsWith("primary:")) "Internal storage" else "Storage"
    return if (path.isEmpty()) volume else volume + " › " + path.replace("/", " › ")
}

@Composable
fun TranscribingUiDialog(liveStats: Boolean, onLiveStats: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val config by TranscribingUiSettings.flow(context).collectAsState()
    val auto by AutoSaveSettings.flow(context).collectAsState()
    var pickIcon by remember { mutableStateOf(false) }
    var previewFinished by remember { mutableStateOf(false) }
    var previewFormat by remember { mutableStateOf(TranscriptFormat.TXT) }
    fun change(edit: (TranscribingUiConfig) -> TranscribingUiConfig) = TranscribingUiSettings.update(context, edit)
    fun changeAuto(edit: (AutoSaveConfig) -> AutoSaveConfig) = AutoSaveSettings.update(context, edit)

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            changeAuto { if (it.sameFolder) it.copy(sameRoot = uri.toString()) else it.copy(oneFolder = uri.toString()) }
        }
    }

    val sample = remember(previewFinished, previewFormat) {
        val segments = listOf(
            TranscriptSegment(0L, 4_200L, "Welcome back to the show."),
            TranscriptSegment(4_200L, 9_000L, "Today we are talking about how small teams ship software.")
        )
        val text = segments.format(TranscriptFormat.TXT)
        val metrics = TranscriptionMetrics(
            audioDurationMs = 600_000L, decodeMs = 3_200L, inferenceMs = 252_000L, modelLoadMs = 1_100L,
            totalMs = 256_000L, pssMb = 610, peakPssMb = 650, freeRamMb = 2_100
        )
        if (previewFinished) {
            TranscribeUi(
                fileName = "team-meeting.m4a", text = text, running = false, error = null,
                modelName = "whisper-small", metrics = metrics, segments = segments, format = previewFormat, percent = 100,
                autoSaved = AutoSaveOutcome(listOf("team-meeting.txt", "team-meeting.srt", "team-meeting.lrc"), "next to the audio")
            )
        } else {
            TranscribeUi(
                fileName = "team-meeting.m4a", text = text, running = true, error = null,
                modelName = "whisper-small", metrics = metrics, percent = 42, etaMs = 61_000L,
                fileEstimateMs = 61_000L, totalEstimateMs = 360_000L, batchIndex = 1, batchTotal = 3
            )
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("‹ Back", fontWeight = FontWeight.SemiBold) }
                    Text("Transcribing UI", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = cs.onBackground)
                }
                // Live preview above the scrolling settings.
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(300.dp)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))
                ) {
                    TranscribingContent(
                        config = config, auto = auto, t = sample, showStats = liveStats,
                        onCloseOrCancel = {}, onCopy = {}, onShare = {}, onSave = {},
                        onFormat = { previewFormat = it }
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BarChip("Running", !previewFinished) { previewFinished = false }
                    BarChip("Finished", previewFinished) { previewFinished = true }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    SectionLabel("Progress")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BarChip("Ring", config.progressStyle == TranscribingProgressStyle.RING) { change { it.copy(progressStyle = TranscribingProgressStyle.RING) } }
                        BarChip("Bar", config.progressStyle == TranscribingProgressStyle.BAR) { change { it.copy(progressStyle = TranscribingProgressStyle.BAR) } }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (config.progressStyle == TranscribingProgressStyle.RING) {
                        SliderRow("Ring thickness", config.ringWidthDp, 2, 6, "dp") { v -> change { it.copy(ringWidthDp = v) } }
                    }
                    SwitchRow("Percent number", null, config.showPercent) { on -> change { it.copy(showPercent = on) } }
                    SwitchRow("Time left", "Shown as “32% · ~1m 1s left”", config.showEta) { on -> change { it.copy(showEta = on) } }
                    SwitchRow(
                        "Detailed estimates", "Percent and time left move into a card with total, this file, all files",
                        config.detailedEta
                    ) { on -> change { it.copy(detailedEta = on) } }
                    SwitchRow("File position", "“2 of 3” when several files are queued", config.showBatch) { on -> change { it.copy(showBatch = on) } }

                    SectionLabel("Transcript")
                    SwitchRow("Live text while running", "Words appear as they are found", config.liveText) { on -> change { it.copy(liveText = on) } }
                    SwitchRow("Format picker", "TXT, SRT, VTT, LRC when finished", config.formatPicker) { on -> change { it.copy(formatPicker = on) } }
                    SliderRow("Text size", config.textSizeSp, 12, 22, "sp") { v -> change { it.copy(textSizeSp = v) } }

                    SectionLabel("Auto save")
                    SwitchRow("Save automatically", "Every finished file is saved for you", auto.enabled) { on -> changeAuto { it.copy(enabled = on) } }
                    if (auto.enabled) {
                        Text("Formats (pick one or more)", fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(TranscriptFormat.TXT, TranscriptFormat.SRT, TranscriptFormat.VTT, TranscriptFormat.LRC).forEach { format ->
                                BarChip(format.name, format in auto.formats) {
                                    changeAuto { if (format in it.formats) it.copy(formats = it.formats - format) else it.copy(formats = it.formats + format) }
                                }
                            }
                        }
                        Text("Save to", fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BarChip("Same folder as audio", auto.sameFolder) { changeAuto { it.copy(sameFolder = true) } }
                            BarChip("One chosen folder", !auto.sameFolder) { changeAuto { it.copy(sameFolder = false) } }
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Folder access", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                                    Text(
                                        trFolderLabel(if (auto.sameFolder) auto.sameRoot else auto.oneFolder),
                                        fontSize = 12.sp, color = cs.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { folderPicker.launch(null) }) { Text("Change ›", fontWeight = FontWeight.SemiBold) }
                            }
                        }
                        Text(
                            if (auto.sameFolder) {
                                "Pick several audio files at once: each transcript is saved next to its own audio. Android only lets the app write in a folder you have allowed, so pick the folder your audio is in (or one above it) once. Audio from other places, such as Downloads or cloud storage, goes to the chosen folder instead, or to the app folder."
                            } else {
                                "All transcripts go into this one folder."
                            },
                            fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                        Text(
                            "A file with the same name is replaced. Auto save works while the Transcribing UI is on.",
                            fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp)
                        )
                    }

                    SectionLabel("Cards")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BarChip("Filled", config.cardStyle == HomeCardStyle.FILLED) { change { it.copy(cardStyle = HomeCardStyle.FILLED) } }
                        BarChip("Outlined", config.cardStyle == HomeCardStyle.OUTLINED) { change { it.copy(cardStyle = HomeCardStyle.OUTLINED) } }
                    }
                    Spacer(Modifier.height(8.dp))
                    SliderRow("Corner radius", config.cornerDp, 8, 32, "dp") { v -> change { it.copy(cornerDp = v) } }
                    SliderRow("Icon size", config.iconSizeDp, 16, 32, "dp") { v -> change { it.copy(iconSizeDp = v) } }

                    SectionLabel("Details")
                    SwitchRow(
                        "Live performance stats", "Speed, memory, load time. Same switch as Live stats in Settings",
                        liveStats
                    ) { on -> onLiveStats(on) }
                    SwitchRow("Background hint", "You can leave this screen", config.showHint) { on -> change { it.copy(showHint = on) } }
                    SwitchRow(
                        "Keep screen on", "Shows the switch on this screen. Same setting as in Settings",
                        config.showKeepScreen
                    ) { on -> change { it.copy(showKeepScreen = on) } }

                    SectionLabel("Order")
                    Text(
                        "Choose the order of the blocks on the screen. The header, progress and Save button stay where they are. Blocks that are off are skipped. With the transcript off, the rest sits together at the bottom.",
                        fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                    )
                    config.order.forEachIndexed { index, block ->
                        Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(trBlockLabel(block), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, modifier = Modifier.weight(1f))
                                TrMoveButton("chevron-up", "▲", index > 0) { change { it.copy(order = trMove(it.order, index, -1)) } }
                                Spacer(Modifier.width(6.dp))
                                TrMoveButton("chevron-down", "▼", index < config.order.lastIndex) { change { it.copy(order = trMove(it.order, index, 1)) } }
                            }
                        }
                    }

                    SectionLabel("Icons")
                    IconRow("Save icon", config.saveIcon, "⬇") { pickIcon = true }
                    Text(
                        "Icons are from Lucide. The view is always full screen.",
                        fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                    TextButton(onClick = { change { TranscribingUiConfig(enabled = it.enabled) } }) {
                        Text("Reset to default", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (pickIcon) {
        IconPickerDialog(
            onPick = { name ->
                change { it.copy(saveIcon = name) }
                pickIcon = false
            },
            onDismiss = { pickIcon = false }
        )
    }
}
