package org.scrib.transcriber

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

private val LightScheme = lightColorScheme(
    primary = Color(0xFF006A6A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC7EAE8),
    onPrimaryContainer = Color(0xFF00201F),
    background = Color(0xFFF6F9F8),
    onBackground = Color(0xFF171D1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171D1C),
    surfaceContainer = Color(0xFFEEF3F1),
    onSurfaceVariant = Color(0xFF3F4A48),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFD3DEDC),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4FD8DB),
    onPrimary = Color(0xFF00373A),
    primaryContainer = Color(0xFF00504F),
    onPrimaryContainer = Color(0xFF7AF4F4),
    background = Color(0xFF0E1413),
    onBackground = Color(0xFFDEE4E2),
    surface = Color(0xFF0E1413),
    onSurface = Color(0xFFDEE4E2),
    surfaceContainer = Color(0xFF1A2221),
    onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF889492),
    outlineVariant = Color(0xFF3F4A48),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

// Used while the New UI is on with the iOS style: system blue, white groups on a light grey page.
private val IosLightScheme = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5F0FF),
    onPrimaryContainer = Color(0xFF004A9F),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceContainer = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF6C6C70),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFC6C6C8),
    error = Color(0xFFFF3B30),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val IosDarkScheme = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF0A2A4D),
    onPrimaryContainer = Color(0xFF64B5FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF636366),
    outlineVariant = Color(0xFF38383A),
    error = Color(0xFFFF453A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScribTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val ui by NewUiSettings.flow(context).collectAsState()
    val ios = ui.enabled && ui.iosStyle
    val scheme = when {
        ios -> if (dark) IosDarkScheme else IosLightScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            CompositionLocalProvider(
                LocalCompactUi provides ui.enabled,
                LocalIosStyle provides ios,
                LocalUiScale provides (ui.densityPct / 100f)
            ) {
                content()
            }
        }
    }
}
