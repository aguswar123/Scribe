package org.scrib.transcriber

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class HomeLayout { TILES, LIST }

enum class HomeCardStyle { FILLED, OUTLINED }

enum class HomeIconShape { CIRCLE, SQUARE, PLAIN }

// Everything the Home UI can change. Off by default, so an existing install looks exactly as before.
data class HomeUiConfig(
    val enabled: Boolean = false,
    val layout: HomeLayout = HomeLayout.TILES,
    val cardStyle: HomeCardStyle = HomeCardStyle.FILLED,
    val iconShape: HomeIconShape = HomeIconShape.CIRCLE,
    val cornerDp: Int = 20,
    val iconSizeDp: Int = 22,
    val showStatus: Boolean = true,
    val showPrivacy: Boolean = true,
    val showQuick: Boolean = true,
    // Lucide names.
    val recordIcon: String = "mic",
    val fileIcon: String = "audio-lines"
)

object HomeUiSettings {
    private const val PREFS = "home-ui-settings"

    private val _config = MutableStateFlow(HomeUiConfig())

    @Volatile
    private var loaded = false

    fun flow(context: Context): StateFlow<HomeUiConfig> {
        load(context)
        return _config
    }

    @Synchronized
    fun update(context: Context, change: (HomeUiConfig) -> HomeUiConfig) {
        load(context)
        val next = clamp(change(_config.value))
        _config.value = next
        prefs(context).edit()
            .putBoolean("enabled", next.enabled)
            .putString("layout", next.layout.name)
            .putString("cardStyle", next.cardStyle.name)
            .putString("iconShape", next.iconShape.name)
            .putInt("corner", next.cornerDp)
            .putInt("iconSize", next.iconSizeDp)
            .putBoolean("showStatus", next.showStatus)
            .putBoolean("showPrivacy", next.showPrivacy)
            .putBoolean("showQuick", next.showQuick)
            .putString("recordIcon", next.recordIcon)
            .putString("fileIcon", next.fileIcon)
            .apply()
    }

    private fun clamp(c: HomeUiConfig): HomeUiConfig {
        val d = HomeUiConfig()
        return c.copy(
            cornerDp = c.cornerDp.coerceIn(8, 32),
            iconSizeDp = c.iconSizeDp.coerceIn(16, 32),
            recordIcon = c.recordIcon.ifBlank { d.recordIcon },
            fileIcon = c.fileIcon.ifBlank { d.fileIcon }
        )
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) {
            return
        }
        val p = prefs(context)
        val d = HomeUiConfig()
        _config.value = clamp(
            HomeUiConfig(
                enabled = p.getBoolean("enabled", d.enabled),
                layout = runCatching { HomeLayout.valueOf(p.getString("layout", null) ?: "") }.getOrDefault(d.layout),
                cardStyle = runCatching { HomeCardStyle.valueOf(p.getString("cardStyle", null) ?: "") }
                    .getOrDefault(d.cardStyle),
                iconShape = runCatching { HomeIconShape.valueOf(p.getString("iconShape", null) ?: "") }
                    .getOrDefault(d.iconShape),
                cornerDp = p.getInt("corner", d.cornerDp),
                iconSizeDp = p.getInt("iconSize", d.iconSizeDp),
                showStatus = p.getBoolean("showStatus", d.showStatus),
                showPrivacy = p.getBoolean("showPrivacy", d.showPrivacy),
                showQuick = p.getBoolean("showQuick", d.showQuick),
                recordIcon = p.getString("recordIcon", null) ?: d.recordIcon,
                fileIcon = p.getString("fileIcon", null) ?: d.fileIcon
            )
        )
        loaded = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
