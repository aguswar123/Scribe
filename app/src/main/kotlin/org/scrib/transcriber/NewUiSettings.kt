package org.scrib.transcriber

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class BarDisplay { BOTH, ICON, TEXT }

enum class BarOutlineColor { DEFAULT, ACCENT, TEXT }

// Everything the New UI can change. Off by default, so an existing install looks exactly as before.
// An empty label means "use the app's own (translated) name for the tab".
data class NewUiConfig(
    val enabled: Boolean = false,
    val newBar: Boolean = false,
    val showTranscribeTab: Boolean = true,
    val display: BarDisplay = BarDisplay.BOTH,
    val heightDp: Int = 64,
    val sideMarginDp: Int = 16,
    val bottomMarginDp: Int = 12,
    val cornerDp: Int = 28,
    val paddingDp: Int = 8,
    val shadowDp: Int = 8,
    val outlineDp: Int = 0,
    val outlineColor: BarOutlineColor = BarOutlineColor.DEFAULT,
    val iconSizeDp: Int = 21,
    val textSizeSp: Int = 10,
    val labels: List<String> = List(TAB_COUNT) { "" },
    val icons: List<String> = DEFAULT_ICONS
) {
    val floating: Boolean get() = enabled && newBar

    fun labelFor(index: Int, default: String): String =
        labels.getOrNull(index)?.takeIf { it.isNotBlank() } ?: default

    fun iconFor(index: Int): String = icons.getOrNull(index)?.takeIf { it.isNotBlank() } ?: DEFAULT_ICONS[index]

    companion object {
        const val TAB_COUNT = 6

        // Position of the Audiobook tab. Labels and icons saved before it existed sit one slot lower.
        const val AUDIOBOOK_INDEX = 3

        // Position of the Transcribe tab, the one that can be taken out of the bar.
        const val TRANSCRIBE_INDEX = 2

        // Lucide names, in the order of the tabs: Home, Record, Transcribe, Audiobook, Keyboard, Settings.
        val DEFAULT_ICONS = listOf("house", "mic", "audio-lines", "book-open", "keyboard", "settings")
    }
}

object NewUiSettings {
    private const val PREFS = "new-ui-settings"

    private val _config = MutableStateFlow(NewUiConfig())

    @Volatile
    private var loaded = false

    fun flow(context: Context): StateFlow<NewUiConfig> {
        load(context)
        return _config
    }

    fun config(context: Context): NewUiConfig = flow(context).value

    @Synchronized
    fun update(context: Context, change: (NewUiConfig) -> NewUiConfig) {
        load(context)
        val next = clamp(change(_config.value))
        _config.value = next
        val edit = prefs(context).edit()
            .putBoolean("enabled", next.enabled)
            .putBoolean("newBar", next.newBar)
            .putBoolean("showTranscribe", next.showTranscribeTab)
            .putString("display", next.display.name)
            .putInt("height", next.heightDp)
            .putInt("sideMargin", next.sideMarginDp)
            .putInt("bottomMargin", next.bottomMarginDp)
            .putInt("corner", next.cornerDp)
            .putInt("padding", next.paddingDp)
            .putInt("shadow", next.shadowDp)
            .putInt("outline", next.outlineDp)
            .putString("outlineColor", next.outlineColor.name)
            .putInt("iconSize", next.iconSizeDp)
            .putInt("textSize", next.textSizeSp)
            .putBoolean("audiobookSlot", true)
        for (i in 0 until NewUiConfig.TAB_COUNT) {
            edit.putString("label$i", next.labels[i])
            edit.putString("icon$i", next.icons[i])
        }
        edit.apply()
    }

    private fun clamp(c: NewUiConfig) = c.copy(
        heightDp = c.heightDp.coerceIn(44, 96),
        sideMarginDp = c.sideMarginDp.coerceIn(0, 32),
        bottomMarginDp = c.bottomMarginDp.coerceIn(0, 40),
        cornerDp = c.cornerDp.coerceIn(0, 40),
        paddingDp = c.paddingDp.coerceIn(0, 24),
        shadowDp = c.shadowDp.coerceIn(0, 24),
        outlineDp = c.outlineDp.coerceIn(0, 4),
        iconSizeDp = c.iconSizeDp.coerceIn(14, 32),
        textSizeSp = c.textSizeSp.coerceIn(8, 14),
        labels = List(NewUiConfig.TAB_COUNT) { c.labels.getOrNull(it) ?: "" },
        icons = List(NewUiConfig.TAB_COUNT) { c.icons.getOrNull(it) ?: NewUiConfig.DEFAULT_ICONS[it] }
    )

    @Synchronized
    private fun load(context: Context) {
        if (loaded) {
            return
        }
        val p = prefs(context)
        val d = NewUiConfig()
        val migrated = p.getBoolean("audiobookSlot", false)
        _config.value = clamp(
            NewUiConfig(
                enabled = p.getBoolean("enabled", d.enabled),
                newBar = p.getBoolean("newBar", d.newBar),
                showTranscribeTab = p.getBoolean("showTranscribe", d.showTranscribeTab),
                display = runCatching { BarDisplay.valueOf(p.getString("display", null) ?: "") }.getOrDefault(d.display),
                heightDp = p.getInt("height", d.heightDp),
                sideMarginDp = p.getInt("sideMargin", d.sideMarginDp),
                bottomMarginDp = p.getInt("bottomMargin", d.bottomMarginDp),
                cornerDp = p.getInt("corner", d.cornerDp),
                paddingDp = p.getInt("padding", d.paddingDp),
                shadowDp = p.getInt("shadow", d.shadowDp),
                outlineDp = p.getInt("outline", d.outlineDp),
                outlineColor = runCatching { BarOutlineColor.valueOf(p.getString("outlineColor", null) ?: "") }
                    .getOrDefault(d.outlineColor),
                iconSizeDp = p.getInt("iconSize", d.iconSizeDp),
                textSizeSp = p.getInt("textSize", d.textSizeSp),
                labels = List(NewUiConfig.TAB_COUNT) { i -> savedSlot(i, migrated)?.let { p.getString("label$it", null) } ?: "" },
                icons = List(NewUiConfig.TAB_COUNT) { i ->
                    savedSlot(i, migrated)?.let { p.getString("icon$it", null) } ?: NewUiConfig.DEFAULT_ICONS[i]
                }
            )
        )
        loaded = true
    }

    // Where tab i's label and icon were saved. Before the Audiobook tab there were five slots.
    private fun savedSlot(i: Int, migrated: Boolean): Int? = when {
        migrated || i < NewUiConfig.AUDIOBOOK_INDEX -> i
        i == NewUiConfig.AUDIOBOOK_INDEX -> null
        else -> i - 1
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
