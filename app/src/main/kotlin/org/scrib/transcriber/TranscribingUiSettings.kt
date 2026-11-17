package org.scrib.transcriber

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TranscribingProgressStyle { RING, BAR }

// The blocks under the header and progress that the person can put in any order.
enum class TranscribingBlock { DETAILS, TEXT, STATS, AUTO, KEEP, HINT }

// Everything the Transcribing UI can change. Off by default, so an existing install looks exactly
// as before. The icon is a Lucide name.
data class TranscribingUiConfig(
    val enabled: Boolean = false,
    val progressStyle: TranscribingProgressStyle = TranscribingProgressStyle.RING,
    val ringWidthDp: Int = 3,
    val showPercent: Boolean = true,
    val showEta: Boolean = true,
    val detailedEta: Boolean = false,
    val showBatch: Boolean = true,
    val liveText: Boolean = true,
    val formatPicker: Boolean = true,
    val textSizeSp: Int = 15,
    val cardStyle: HomeCardStyle = HomeCardStyle.FILLED,
    val cornerDp: Int = 20,
    val iconSizeDp: Int = 22,
    val showHint: Boolean = true,
    val showKeepScreen: Boolean = true,
    val saveIcon: String = "download",
    val order: List<TranscribingBlock> = TranscribingBlock.entries.toList()
)

object TranscribingUiSettings {
    private const val PREFS = "transcribing-ui-settings"

    private val _config = MutableStateFlow(TranscribingUiConfig())

    @Volatile
    private var loaded = false

    fun flow(context: Context): StateFlow<TranscribingUiConfig> {
        load(context)
        return _config
    }

    fun current(context: Context): TranscribingUiConfig {
        load(context)
        return _config.value
    }

    @Synchronized
    fun update(context: Context, change: (TranscribingUiConfig) -> TranscribingUiConfig) {
        load(context)
        val next = clamp(change(_config.value))
        _config.value = next
        prefs(context).edit()
            .putBoolean("enabled", next.enabled)
            .putString("progressStyle", next.progressStyle.name)
            .putInt("ringWidth", next.ringWidthDp)
            .putBoolean("showPercent", next.showPercent)
            .putBoolean("showEta", next.showEta)
            .putBoolean("detailedEta", next.detailedEta)
            .putBoolean("showBatch", next.showBatch)
            .putBoolean("liveText", next.liveText)
            .putBoolean("formatPicker", next.formatPicker)
            .putInt("textSize", next.textSizeSp)
            .putString("cardStyle", next.cardStyle.name)
            .putInt("corner", next.cornerDp)
            .putInt("iconSize", next.iconSizeDp)
            .putBoolean("showHint", next.showHint)
            .putBoolean("showKeepScreen", next.showKeepScreen)
            .putString("saveIcon", next.saveIcon)
            .putString("order", next.order.joinToString(",") { it.name })
            .apply()
    }

    private fun clamp(c: TranscribingUiConfig): TranscribingUiConfig {
        val d = TranscribingUiConfig()
        return c.copy(
            ringWidthDp = c.ringWidthDp.coerceIn(2, 6),
            textSizeSp = c.textSizeSp.coerceIn(12, 22),
            cornerDp = c.cornerDp.coerceIn(8, 32),
            iconSizeDp = c.iconSizeDp.coerceIn(16, 32),
            saveIcon = c.saveIcon.ifBlank { d.saveIcon },
            order = normalizeOrder(c.order)
        )
    }

    // Each block once, in the person's order; one that is missing (a build with a new block) goes last.
    private fun normalizeOrder(order: List<TranscribingBlock>): List<TranscribingBlock> {
        val seen = order.distinct()
        return seen + TranscribingBlock.entries.filter { it !in seen }
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) {
            return
        }
        val p = prefs(context)
        val d = TranscribingUiConfig()
        _config.value = clamp(
            TranscribingUiConfig(
                enabled = p.getBoolean("enabled", d.enabled),
                progressStyle = runCatching { TranscribingProgressStyle.valueOf(p.getString("progressStyle", null) ?: "") }
                    .getOrDefault(d.progressStyle),
                ringWidthDp = p.getInt("ringWidth", d.ringWidthDp),
                showPercent = p.getBoolean("showPercent", d.showPercent),
                showEta = p.getBoolean("showEta", d.showEta),
                detailedEta = p.getBoolean("detailedEta", d.detailedEta),
                showBatch = p.getBoolean("showBatch", d.showBatch),
                liveText = p.getBoolean("liveText", d.liveText),
                formatPicker = p.getBoolean("formatPicker", d.formatPicker),
                textSizeSp = p.getInt("textSize", d.textSizeSp),
                cardStyle = runCatching { HomeCardStyle.valueOf(p.getString("cardStyle", null) ?: "") }
                    .getOrDefault(d.cardStyle),
                cornerDp = p.getInt("corner", d.cornerDp),
                iconSizeDp = p.getInt("iconSize", d.iconSizeDp),
                showHint = p.getBoolean("showHint", d.showHint),
                showKeepScreen = p.getBoolean("showKeepScreen", d.showKeepScreen),
                saveIcon = p.getString("saveIcon", null) ?: d.saveIcon,
                order = p.getString("order", null)
                    ?.split(',')
                    ?.mapNotNull { name -> runCatching { TranscribingBlock.valueOf(name) }.getOrNull() }
                    ?: d.order
            )
        )
        loaded = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
