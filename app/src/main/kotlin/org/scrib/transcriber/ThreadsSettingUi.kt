package org.scrib.transcriber

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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

// The Threads row for the settings list, and the dialog it opens. Local models only; Sherpa and the
// cloud endpoint keep their own settings.
@Composable
internal fun ThreadsEntry() {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val chosen by ThreadsSetting.flow(context).collectAsState()
    val total = remember { ThreadsSetting.maxThreads() }
    val auto = remember { LocalModelsPlugins.plugin?.autoThreads() ?: 0 }
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    Surface(
        color = cs.surfaceContainer, shape = shape,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape)
            .clickable { showDialog = true }
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("🧵", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.threads_title),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface
                )
                Text(
                    when {
                        chosen > 0 -> stringResource(R.string.threads_fixed_sub, chosen.coerceIn(1, total), total)
                        auto > 0 -> stringResource(R.string.threads_auto_sub, auto, total)
                        else -> stringResource(R.string.threads_auto_unknown)
                    },
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(16.dp))
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }

    if (showDialog) {
        ThreadsDialog(chosen = chosen, auto = auto, total = total, onDismiss = { showDialog = false })
    }
}

@Composable
private fun ThreadsDialog(chosen: Int, auto: Int, total: Int, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val speedMode by SpeedMode.flow(context).collectAsState()
    val effective = if (chosen > 0) chosen.coerceIn(1, total) else auto
    val options = remember(total) { listOf(0) + (2..total).toList() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.background,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.threads_title)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.threads_desc), fontSize = 13.sp, color = cs.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
                    repeat(total) { i ->
                        val cellShape = RoundedCornerShape(6.dp)
                        Box(
                            Modifier.weight(1f).height(26.dp).clip(cellShape)
                                .background(if (i < effective) cs.primary else cs.surfaceVariant)
                                .then(if (auto > 0 && i < auto) Modifier.border(2.dp, cs.primary, cellShape) else Modifier)
                        )
                    }
                }
                Text(
                    stringResource(R.string.threads_legend),
                    fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                )
                if (effective > 0) {
                    Text(
                        if (speedMode) stringResource(R.string.threads_split_speed, (effective / 2).coerceAtLeast(1))
                        else stringResource(R.string.threads_split_normal, effective),
                        fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
                    )
                }
                options.forEach { value ->
                    val selected = if (value == 0) chosen <= 0 else chosen == value
                    Row(
                        Modifier.fillMaxWidth().clickable { ThreadsSetting.set(context, value) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = { ThreadsSetting.set(context, value) })
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (value == 0) stringResource(R.string.threads_opt_auto)
                                else stringResource(R.string.threads_opt_n, value),
                                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface
                            )
                            Text(
                                when {
                                    value == 0 && auto > 0 -> stringResource(R.string.threads_opt_auto_sub, auto)
                                    value == 0 -> stringResource(R.string.threads_opt_auto_sub_unknown)
                                    auto <= 0 -> ""
                                    value > auto -> stringResource(R.string.threads_more)
                                    value == auto -> stringResource(R.string.threads_same)
                                    else -> stringResource(R.string.threads_fewer)
                                },
                                fontSize = 12.sp, color = cs.onSurfaceVariant
                            )
                        }
                    }
                }
                if (chosen > 0 && auto > 0 && chosen > auto) {
                    Text(
                        stringResource(R.string.threads_warning),
                        fontSize = 12.5.sp, color = cs.error, modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { ThreadsSetting.set(context, 0) }, enabled = chosen > 0) {
                Text(stringResource(R.string.threads_back_auto))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.threads_done)) } }
    )
}
