package org.scrib.transcriber

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The iOS-style, compact look. Everything here only changes anything while the New UI is on;
// with it off each helper returns the app's original size, shape or widget.
val LocalCompactUi = compositionLocalOf { false }
val LocalIosStyle = compositionLocalOf { false }
val LocalUiScale = compositionLocalOf { 1f }

@Composable
fun uiCardShape(): RoundedCornerShape = RoundedCornerShape(if (LocalCompactUi.current) 10.dp else 16.dp)

@Composable
fun uiGap(): Dp = if (LocalCompactUi.current) 6.dp else 10.dp

@Composable
fun uiRowPad(): Dp = if (LocalCompactUi.current) 8.dp else 14.dp

// Makes the screen content a little smaller (dp and text together) by the "Compact level" setting.
@Composable
fun CompactScope(content: @Composable () -> Unit) {
    val scale = LocalUiScale.current
    if (!LocalCompactUi.current || scale >= 0.999f) {
        content()
    } else {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale)) { content() }
    }
}

private val GLYPHS: Map<String, Pair<String, Long>> = mapOf(
    "\uD83C\uDF99" to ("mic" to 0xFFFF3B30),
    "\uD83D\uDCC1" to ("folder-open" to 0xFF007AFF),
    "\uD83C\uDF10" to ("globe" to 0xFF007AFF),
    "\uD83E\uDD2B" to ("volume-x" to 0xFF5856D6),
    "\uD83D\uDCA1" to ("lightbulb" to 0xFFFF9500),
    "\uD83D\uDD14" to ("bell" to 0xFFFF3B30),
    "\uD83D\uDCC8" to ("activity" to 0xFF34C759),
    "\uD83D\uDCCA" to ("gauge" to 0xFF5AC8FA),
    "\uD83D\uDCDD" to ("book-open" to 0xFFAF52DE),
    "\u2728" to ("sparkles" to 0xFFFF9500),
    "\u24D8" to ("info" to 0xFF007AFF)
)

// The icon at the start of a row: the original emoji, or a small coloured Lucide tile.
@Composable
fun EntryGlyph(glyph: String) {
    val spec = if (LocalCompactUi.current) GLYPHS[glyph] else null
    val vector = remember(glyph, spec) { spec?.let { LucideIcons.resolve(it.first) } }
    if (spec == null || vector == null) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Text(glyph, fontSize = 18.sp)
        }
    } else {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Color(spec.second)),
            contentAlignment = Alignment.Center
        ) {
            Icon(vector, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun EntryChevron() {
    val cs = MaterialTheme.colorScheme
    val vector = if (LocalCompactUi.current) remember { LucideIcons.resolve("chevron-right") } else null
    if (vector != null) {
        Icon(vector, null, tint = cs.outline, modifier = Modifier.size(16.dp))
    } else {
        Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
    }
}

// The dot on the record button becomes a microphone in the New UI.
@Composable
fun RecordDot(color: Color) {
    val vector = if (LocalCompactUi.current) remember { LucideIcons.resolve("mic") } else null
    if (vector != null) {
        Icon(vector, null, tint = color, modifier = Modifier.size(18.dp))
    } else {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

// Green iOS switch while the iOS style is on; the normal Material switch otherwise.
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (LocalIosStyle.current) {
        Switch(
            checked, onCheckedChange, modifier, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF34C759),
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
                uncheckedBorderColor = Color.Transparent
            )
        )
    } else {
        Switch(checked, onCheckedChange, modifier, enabled = enabled)
    }
}

@Composable
fun ScreenTitle(text: String) {
    Text(
        text, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp)
    )
}

// Home: the two main actions side by side.
@Composable
fun ServiceTile(glyph: String, title: String, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Surface(
        color = cs.surfaceContainer, shape = shape,
        modifier = modifier.testTag(tag).clip(shape).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            EntryGlyph(glyph)
            Spacer(Modifier.width(8.dp))
            Text(
                title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Flat iOS tab bar: thin line on top, no pill, the chosen tab in the accent colour.
// Uses the same tab names, icons, display mode and Transcribe-tab setting as the floating bar.
@Composable
fun IosTabBar(config: NewUiConfig, items: List<NewBarItem>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(cs.outlineVariant))
        Row(Modifier.fillMaxWidth().background(cs.surfaceContainer).navigationBarsPadding().height(50.dp)) {
            items.forEachIndexed { index, item ->
                if (index == NewUiConfig.TRANSCRIBE_INDEX && !config.showTranscribeTab) return@forEachIndexed
                val on = index == selected
                val tint = if (on) cs.primary else cs.onSurfaceVariant
                val label = config.labelFor(index, item.label)
                Column(
                    Modifier.weight(1f).fillMaxHeight().testTag(item.tag)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    if (config.display != BarDisplay.TEXT) {
                        LucideIcon(config.iconFor(index), item.fallbackIcon, tint, 22.dp, label)
                    }
                    if (config.display != BarDisplay.ICON) {
                        Text(
                            label, fontSize = 10.sp, lineHeight = 11.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
