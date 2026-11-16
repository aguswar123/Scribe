package org.scrib.transcriber

import androidx.compose.ui.graphics.vector.ImageVector

// Lucide icons by their usual name ("audio-lines"). The library is reached by reflection so that a
// name which does not exist simply gives null instead of breaking the build, and so any icon of the
// library can be used by typing its name, not only the ones listed below.
object LucideIcons {

    private val packages = listOf("com.composables.icons.lucide", "com.composables.icons.lucide.cmp")
    private val cache = HashMap<String, ImageVector?>()

    private fun pascal(name: String): String =
        name.trim().lowercase().split('-', '_', ' ').filter { it.isNotEmpty() }
            .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }

    @Synchronized
    fun resolve(name: String): ImageVector? {
        if (name.isBlank()) return null
        if (cache.containsKey(name)) return cache[name]
        val found = lookup(pascal(name))
        cache[name] = found
        return found
    }

    private fun lookup(pascal: String): ImageVector? {
        if (pascal.isEmpty()) return null
        for (pkg in packages) {
            try {
                val receiver = Class.forName("$pkg.Lucide").getField("INSTANCE").get(null)
                val owner = Class.forName("$pkg.${pascal}Kt")
                val getter = owner.getMethod("get$pascal", receiver.javaClass)
                val value = getter.invoke(null, receiver)
                if (value is ImageVector) return value
            } catch (e: Throwable) {
                // Not in this package, or no such icon.
            }
        }
        return null
    }

    // Names offered in the picker. Ones the installed library does not know are left out.
    // Any other Lucide name can still be typed into the search box.
    val suggested: List<String> by lazy {
        NAMES.filter { resolve(it) != null }
    }

    private val NAMES = listOf(
        "activity", "airplay", "alarm-clock", "album", "align-center", "align-left", "anchor", "aperture",
        "archive", "armchair", "arrow-down", "arrow-left", "arrow-right", "arrow-up", "asterisk", "at-sign",
        "atom", "audio-lines", "audio-waveform", "award", "baby", "backpack", "badge-check", "banknote",
        "barcode", "battery", "battery-charging", "bed", "beer", "bell", "bell-off", "bell-ring", "bike",
        "bluetooth", "bold", "bolt", "bomb", "bone", "book", "book-open", "bookmark", "bot", "box", "boxes",
        "brain", "briefcase", "brush", "bug", "building", "bus", "cake", "calculator", "calendar",
        "calendar-days", "camera", "car", "carrot", "cast", "check", "check-check", "chevron-down",
        "chevron-left", "chevron-right", "chevron-up", "circle", "circle-check", "circle-dot",
        "circle-help", "circle-play", "circle-user", "clapperboard", "clipboard", "clipboard-list",
        "clock", "cloud", "cloud-download", "cloud-upload", "code", "coffee", "cog", "coins", "compass",
        "contact", "cookie", "copy", "cpu", "credit-card", "crop", "crown", "database", "delete",
        "diamond", "disc", "dollar-sign", "download", "droplet", "drum", "ear", "earth", "egg", "eraser",
        "expand", "external-link", "eye", "eye-off", "factory", "fan", "feather", "file", "file-audio",
        "file-text", "file-video", "film", "filter", "fingerprint", "flag", "flame", "flashlight",
        "flask-conical", "flower", "folder", "folder-open", "footprints", "forward", "frame", "fuel",
        "gamepad-2", "gauge", "gem", "ghost", "gift", "git-branch", "glasses", "globe", "grip", "guitar",
        "hammer", "hand", "hard-drive", "hash", "headphones", "heart", "heart-pulse", "history", "house",
        "hourglass", "image", "inbox", "infinity", "info", "italic", "joystick", "key", "keyboard", "lamp",
        "landmark", "languages", "laptop", "layers", "layout-dashboard", "layout-grid", "leaf", "library",
        "life-buoy", "lightbulb", "link", "list", "list-checks", "list-music", "list-todo", "loader",
        "lock", "lock-open", "log-in", "log-out", "luggage", "magnet", "mail", "map", "map-pin",
        "maximize", "medal", "megaphone", "menu", "message-circle", "message-square", "mic", "mic-off",
        "microscope", "minimize", "minus", "monitor", "moon", "mountain", "mouse", "move", "music",
        "navigation", "network", "newspaper", "notebook", "notebook-pen", "package", "paintbrush",
        "palette", "panel-left", "paperclip", "party-popper", "pause", "pen", "pen-line", "pencil",
        "percent", "phone", "pin", "pizza", "plane", "play", "plug", "plus", "podcast", "power",
        "printer", "puzzle", "qr-code", "quote", "radio", "receipt", "redo", "refresh-cw", "repeat",
        "reply", "rocket", "rotate-ccw", "rotate-cw", "route", "rss", "ruler", "save", "scale", "scan",
        "scissors", "scroll", "search", "send", "server", "settings", "settings-2", "share", "share-2",
        "shield", "shield-check", "shirt", "shopping-bag", "shopping-cart", "shuffle", "sigma", "signal",
        "skip-back", "skip-forward", "slash", "sliders-horizontal", "smartphone", "smile", "snowflake",
        "sparkles", "speaker", "square", "star", "stethoscope", "sticky-note", "store", "subtitles",
        "sun", "sunrise", "sunset", "table", "tablet", "tag", "target", "tent", "terminal", "text",
        "thermometer", "thumbs-down", "thumbs-up", "ticket", "timer", "toggle-left", "toggle-right",
        "trash", "trash-2", "trees", "trending-up", "triangle", "trophy", "truck", "tv", "type",
        "umbrella", "underline", "undo", "upload", "user", "user-round", "users", "video", "view",
        "voicemail", "volume-1", "volume-2", "volume-x", "wallet", "wand-sparkles", "watch", "waves",
        "webcam", "wifi", "wind", "wrench", "x", "zap", "zoom-in", "zoom-out"
    )
}
