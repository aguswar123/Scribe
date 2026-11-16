package org.scrib.transcriber

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RecordButtonStyle { ROUND, WIDE }

// Everything the Record UI can change. Off by default, so an existing install looks exactly as before.
data class RecordUiConfig(
    val enabled: Boolean = false,
    val buttonStyle: RecordButtonStyle = RecordButtonStyle.ROUND,
    val buttonSizeDp: Int = 96,
    val pulse: Boolean = true,
    val cardStyle: HomeCardStyle = HomeCardStyle.FILLED,
    val cornerDp: Int = 20,
    val iconSizeDp: Int = 22,
    val previewLines: Int = 2,
    val showTime: Boolean = true,
    val confirmDelete: Boolean = true,
    val showMeter: Boolean = true,
    val showHint: Boolean = true,
    // Lucide names.
    val recordIcon: String = "mic",
    val itemIcon: String = "file-text"
)

object RecordUiSettings {
    private const val PREFS = "record-ui-settings"

    private val _config = MutableStateFlow(RecordUiConfig())

    @Volatile
    private var loaded = false

    fun flow(context: Context): StateFlow<RecordUiConfig> {
        load(context)
        return _config
    }

    @Synchronized
    fun update(context: Context, change: (RecordUiConfig) -> RecordUiConfig) {
        load(context)
        val next = clamp(change(_config.value))
        _config.value = next
        prefs(context).edit()
            .putBoolean("enabled", next.enabled)
            .putString("buttonStyle", next.buttonStyle.name)
            .putInt("buttonSize", next.buttonSizeDp)
            .putBoolean("pulse", next.pulse)
            .putString("cardStyle", next.cardStyle.name)
            .putInt("corner", next.cornerDp)
            .putInt("iconSize", next.iconSizeDp)
            .putInt("previewLines", next.previewLines)
            .putBoolean("showTime", next.showTime)
            .putBoolean("confirmDelete", next.confirmDelete)
            .putBoolean("showMeter", next.showMeter)
            .putBoolean("showHint", next.showHint)
            .putString("recordIcon", next.recordIcon)
            .putString("itemIcon", next.itemIcon)
            .apply()
    }

    private fun clamp(c: RecordUiConfig): RecordUiConfig {
        val d = RecordUiConfig()
        return c.copy(
            buttonSizeDp = c.buttonSizeDp.coerceIn(64, 120),
            cornerDp = c.cornerDp.coerceIn(8, 32),
            iconSizeDp = c.iconSizeDp.coerceIn(16, 32),
            previewLines = c.previewLines.coerceIn(0, 3),
            recordIcon = c.recordIcon.ifBlank { d.recordIcon },
            itemIcon = c.itemIcon.ifBlank { d.itemIcon }
        )
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) {
            return
        }
        val p = prefs(context)
        val d = RecordUiConfig()
        _config.value = clamp(
            RecordUiConfig(
                enabled = p.getBoolean("enabled", d.enabled),
                buttonStyle = runCatching { RecordButtonStyle.valueOf(p.getString("buttonStyle", null) ?: "") }
                    .getOrDefault(d.buttonStyle),
                buttonSizeDp = p.getInt("buttonSize", d.buttonSizeDp),
                pulse = p.getBoolean("pulse", d.pulse),
                cardStyle = runCatching { HomeCardStyle.valueOf(p.getString("cardStyle", null) ?: "") }
                    .getOrDefault(d.cardStyle),
                cornerDp = p.getInt("corner", d.cornerDp),
                iconSizeDp = p.getInt("iconSize", d.iconSizeDp),
                previewLines = p.getInt("previewLines", d.previewLines),
                showTime = p.getBoolean("showTime", d.showTime),
                confirmDelete = p.getBoolean("confirmDelete", d.confirmDelete),
                showMeter = p.getBoolean("showMeter", d.showMeter),
                showHint = p.getBoolean("showHint", d.showHint),
                recordIcon = p.getString("recordIcon", null) ?: d.recordIcon,
                itemIcon = p.getString("itemIcon", null) ?: d.itemIcon
            )
        )
        loaded = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
