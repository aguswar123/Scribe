package org.scrib.transcriber

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// A Lucide icon, or a plain character when this build of the library does not have it.
@Composable
private fun GlyphIcon(name: String, glyph: String, tint: Color, size: Dp) {
    val vector = remember(name) { LucideIcons.resolve(name) }
    if (vector != null) {
        Icon(vector, null, tint = tint, modifier = Modifier.size(size))
    } else {
        Text(glyph, color = tint, fontSize = (size.value * 0.8f).sp)
    }
}

@Composable
private fun HomeCard(
    config: HomeUiConfig,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(config.cornerDp.dp)
    val filled = config.cardStyle == HomeCardStyle.FILLED
    val color = when {
        highlighted -> cs.primaryContainer
        filled -> cs.surfaceContainer
        else -> Color.Transparent
    }
    val border = when {
        highlighted -> BorderStroke(1.5.dp, cs.primary)
        filled -> null
        else -> BorderStroke(1.5.dp, cs.outlineVariant)
    }
    val click = if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier
    Surface(color = color, shape = shape, border = border, modifier = modifier.then(click)) {
        content()
    }
}

@Composable
private fun HomeIconBox(config: HomeUiConfig, name: String, glyph: String, big: Boolean) {
    val cs = MaterialTheme.colorScheme
    val plain = config.iconShape == HomeIconShape.PLAIN
    val shape = when (config.iconShape) {
        HomeIconShape.CIRCLE -> CircleShape
        HomeIconShape.SQUARE -> RoundedCornerShape(12.dp)
        HomeIconShape.PLAIN -> RoundedCornerShape(0.dp)
    }
    val box = (config.iconSizeDp + if (big) 22 else 18).dp
    Box(
        Modifier.size(box).then(if (plain) Modifier else Modifier.clip(shape).background(cs.primaryContainer)),
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(name, glyph, if (plain) cs.primary else cs.onPrimaryContainer, config.iconSizeDp.dp)
    }
}

@Composable
fun HomeStatusCard(
    config: HomeUiConfig,
    activeName: String?,
    remoteHost: String?,
    onSwitchModel: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?
) {
    val cs = MaterialTheme.colorScheme
    HomeCard(config, Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (activeName != null) {
                val rowModifier = Modifier.fillMaxWidth().testTag("scrib/home/active_model")
                    .then(
                        if (onSwitchModel != null) {
                            Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onSwitchModel)
                        } else {
                            Modifier
                        }
                    )
                Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                    HomeIconBox(config, "cpu", "●", big = false)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (BuildConfig.LOCAL_MODELS) R.string.active_model else R.string.active_endpoint),
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp,
                            color = cs.onSurfaceVariant
                        )
                        Text(
                            activeName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (onSwitchModel != null) {
                        GlyphIcon("chevron-right", "›", cs.onSurfaceVariant, 20.dp)
                    }
                }
            } else {
                Text(
                    stringResource(if (BuildConfig.LOCAL_MODELS) R.string.no_active_model else R.string.no_active_endpoint),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = cs.onSurface
                )
                Text(
                    stringResource(if (BuildConfig.LOCAL_MODELS) R.string.pick_model_hint else R.string.pick_endpoint_hint),
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (onOpenSettings != null) {
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.nav_settings), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (config.showPrivacy) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(1.dp).background(cs.outlineVariant.copy(alpha = 0.6f)))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(if (remoteHost != null) "cloud" else "lock", if (remoteHost != null) "☁" else "🔒", cs.onSurfaceVariant, 14.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (remoteHost != null) stringResource(R.string.privacy_remote, remoteHost) else stringResource(R.string.privacy_short),
                        fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeActionCard(
    config: HomeUiConfig,
    icon: String,
    glyph: String,
    title: String,
    subtitle: String,
    tile: Boolean,
    tag: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    if (tile) {
        HomeCard(config, modifier.testTag(tag), onClick = onClick) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeIconBox(config, icon, glyph, big = true)
                Column {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, lineHeight = 18.sp)
                    Text(subtitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp)
                }
            }
        }
    } else {
        HomeCard(config, modifier.fillMaxWidth().padding(bottom = 10.dp).testTag(tag), onClick = onClick) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                HomeIconBox(config, icon, glyph, big = false)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                    Text(subtitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp)
                }
                GlyphIcon("chevron-right", "›", cs.onSurfaceVariant, 20.dp)
            }
        }
    }
}

@Composable
fun HomeActions(config: HomeUiConfig, onRecord: () -> Unit, onPickFile: () -> Unit) {
    val recTitle = stringResource(R.string.record_and_transcribe)
    val fileTitle = stringResource(R.string.transcribe_audio_file)
    val recSub = stringResource(R.string.home_record_sub)
    val fileSub = stringResource(R.string.home_file_sub)
    if (config.layout == HomeLayout.TILES) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HomeActionCard(
                config, config.recordIcon, "🎙", recTitle, recSub, true, "scrib/home/record",
                Modifier.weight(1f).fillMaxHeight(), onRecord
            )
            HomeActionCard(
                config, config.fileIcon, "📁", fileTitle, fileSub, true, "scrib/home/pick_file",
                Modifier.weight(1f).fillMaxHeight(), onPickFile
            )
        }
    } else {
        HomeActionCard(config, config.recordIcon, "🎙", recTitle, recSub, false, "scrib/home/record", Modifier, onRecord)
        HomeActionCard(config, config.fileIcon, "📁", fileTitle, fileSub, false, "scrib/home/pick_file", Modifier, onPickFile)
    }
}

@Composable
private fun HomeQuickRow(config: HomeUiConfig, row: ModelRow, actions: Actions) {
    val cs = MaterialTheme.colorScheme
    val active = row.state == RowState.Active
    HomeCard(
        config, Modifier.fillMaxWidth().padding(bottom = 10.dp), highlighted = active,
        onClick = { actions.onUse(row.id) }
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.name, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (active) cs.onPrimaryContainer else cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    row.badge, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = if (active) cs.onPrimaryContainer.copy(alpha = 0.75f) else cs.onSurfaceVariant
                )
            }
            if (active) {
                GlyphIcon("check", "✓", cs.primary, 20.dp)
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable { actions.onTogglePinned(row.id) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_star_filled),
                    contentDescription = stringResource(R.string.quick_access_remove),
                    tint = if (active) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// The new Home tab. The first-run setup card is not part of it and stays as it was.
@Composable
internal fun HomeTabNew(
    config: HomeUiConfig,
    state: ScribUiState,
    actions: Actions,
    onOpenSettings: () -> Unit,
    onRecord: () -> Unit,
    onPickFile: () -> Unit,
    onSwitchModel: (() -> Unit)?
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
    ) {
        if (config.showStatus) {
            item {
                HomeStatusCard(config, state.activeName, state.remoteHost, onSwitchModel, onOpenSettings)
            }
        }
        item { HomeActions(config, onRecord, onPickFile) }
        if (config.showQuick) {
            val quick = (state.standard + state.custom + state.sherpaRows).filter { it.id in state.pinnedIds }
            if (quick.isNotEmpty()) {
                item {
                    Row(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon("star", "★", cs.onSurfaceVariant, 14.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.quick_access_title),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
                items(quick, key = { it.id }) { row -> HomeQuickRow(config, row, actions) }
            }
        }
    }
}

// Settings row: the switch turns the Home UI on, Open shows its own settings.
@Composable
fun HomeUiEntry(config: HomeUiConfig, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                GlyphIcon("house", "🏠", cs.primary, 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Home UI", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    "New look for the Home tab",
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
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun IconRow(title: String, icon: String, glyph: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainer).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            GlyphIcon(icon, glyph, cs.onSurface, 24.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
    }
}

@Composable
fun HomeUiDialog(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val config by HomeUiSettings.flow(context).collectAsState()
    var pick by remember { mutableStateOf<String?>(null) }
    fun change(edit: (HomeUiConfig) -> HomeUiConfig) = HomeUiSettings.update(context, edit)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("‹ Back", fontWeight = FontWeight.SemiBold) }
                    Text("Home UI", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = cs.onBackground)
                }
                // Live preview above the scrolling settings.
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(270.dp)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                        if (config.showStatus) {
                            HomeStatusCard(config, "Whisper Small", null, null, null)
                        }
                        HomeActions(config, {}, {})
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    SectionLabel("Layout")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BarChip("Tiles", config.layout == HomeLayout.TILES) { change { it.copy(layout = HomeLayout.TILES) } }
                        BarChip("List", config.layout == HomeLayout.LIST) { change { it.copy(layout = HomeLayout.LIST) } }
                    }
                    SectionLabel("Card style")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BarChip("Filled", config.cardStyle == HomeCardStyle.FILLED) { change { it.copy(cardStyle = HomeCardStyle.FILLED) } }
                        BarChip("Outlined", config.cardStyle == HomeCardStyle.OUTLINED) { change { it.copy(cardStyle = HomeCardStyle.OUTLINED) } }
                    }
                    SectionLabel("Icon shape")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BarChip("Circle", config.iconShape == HomeIconShape.CIRCLE) { change { it.copy(iconShape = HomeIconShape.CIRCLE) } }
                        BarChip("Square", config.iconShape == HomeIconShape.SQUARE) { change { it.copy(iconShape = HomeIconShape.SQUARE) } }
                        BarChip("Plain", config.iconShape == HomeIconShape.PLAIN) { change { it.copy(iconShape = HomeIconShape.PLAIN) } }
                    }
                    SectionLabel("Size")
                    SliderRow("Corner radius", config.cornerDp, 8, 32, "dp") { v -> change { it.copy(cornerDp = v) } }
                    SliderRow("Icon size", config.iconSizeDp, 16, 32, "dp") { v -> change { it.copy(iconSizeDp = v) } }
                    SectionLabel("Show")
                    SwitchRow("Status card", null, config.showStatus) { on -> change { it.copy(showStatus = on) } }
                    SwitchRow("Privacy line", "Where your audio is processed", config.showPrivacy) { on -> change { it.copy(showPrivacy = on) } }
                    SwitchRow("Quick access", "Your pinned models", config.showQuick) { on -> change { it.copy(showQuick = on) } }
                    SectionLabel("Icons")
                    IconRow("Record and transcribe", config.recordIcon, "🎙") { pick = "record" }
                    IconRow("Transcribe an audio file", config.fileIcon, "📁") { pick = "file" }
                    Text(
                        "Icons are from Lucide.",
                        fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                    TextButton(onClick = { change { HomeUiConfig(enabled = it.enabled) } }) {
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
                change { if (which == "record") it.copy(recordIcon = name) else it.copy(fileIcon = name) }
                pick = null
            },
            onDismiss = { pick = null }
        )
    }
}
