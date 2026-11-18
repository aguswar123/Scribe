package org.scrib.transcriber

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// Two rows for the settings list: the Speed mode switch, and the Speed history (its own runs and
// its own ETA). Self-contained so nothing else has to be wired through.
@Composable
internal fun SpeedModeEntries() {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val enabled by SpeedMode.flow(context).collectAsState()
    val revision by SpeedStore.revisions.collectAsState()
    val runs = remember(revision) { SpeedStore.load(context) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    Surface(
        color = cs.surfaceContainer, shape = shape,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape)
            .clickable { SpeedMode.setEnabled(context, !enabled) }
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("⚡", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.speed_mode_title),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface
                )
                Text(
                    stringResource(if (enabled) R.string.speed_mode_on else R.string.speed_mode_sub),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = { SpeedMode.setEnabled(context, it) })
        }
    }

    Surface(
        color = cs.surfaceContainer, shape = shape,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape)
            .clickable { showHistory = true }
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("⚡", fontSize = 18.sp, color = cs.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.speed_history),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface
                )
                Text(
                    stringResource(R.string.speed_history_sub, runs.size),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(16.dp))
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }

    if (showHistory) {
        SpeedHistoryDialog(runs, onDismiss = { showHistory = false })
    }
}

@Composable
private fun SpeedHistoryDialog(runs: List<BenchmarkRun>, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val groups = remember(runs) { EtaEstimator.summaries(runs) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val message = try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(SpeedStore.exportJson(context))
                } ?: throw RuntimeException("cannot write")
                R.string.speed_history_exported
            } catch (e: Throwable) {
                R.string.speed_history_export_failed
            }
            Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.background,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.speed_history)) },
        text = {
            if (runs.isEmpty()) {
                Text(stringResource(R.string.speed_history_empty), fontSize = 13.sp, color = cs.onSurfaceVariant)
            } else {
                Column(
                    Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.speed_eta_title),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurface
                    )
                    groups.forEach { stats ->
                        Text(
                            stringResource(
                                R.string.speed_eta_line,
                                stats.modelName,
                                stats.runs.size,
                                String.format(Locale.ROOT, "%.2f", stats.speed),
                                preciseDuration((60_000 * stats.speed).toLong())
                            ),
                            fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = cs.primary
                        )
                    }
                    Text(
                        stringResource(R.string.speed_runs_title),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurface,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    runs.forEach { run -> BenchmarkRunCard(run) }
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { exportLauncher.launch("scrib-speed-history.json") },
                    enabled = runs.isNotEmpty()
                ) { Text(stringResource(R.string.benchmark_export_json)) }
                TextButton(onClick = { confirmClear = true }, enabled = runs.isNotEmpty()) {
                    Text(stringResource(R.string.benchmark_clear_history), color = cs.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = cs.background,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.speed_clear_title)) },
            text = { Text(stringResource(R.string.speed_clear_message)) },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    if (!SpeedStore.clear(context)) {
                        Toast.makeText(
                            context, context.getString(R.string.speed_history_clear_failed), Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text(stringResource(R.string.benchmark_clear_history), color = cs.error) }
            }
        )
    }
}
