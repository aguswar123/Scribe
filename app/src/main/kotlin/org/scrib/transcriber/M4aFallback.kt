package org.scrib.transcriber

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// "Read .m4a directly": when Android's MediaExtractor finds no audio track in an AAC .m4a, the
// decoder reads the samples itself (Mp4AacReader). On by default: it only runs after the normal
// path has already failed, so a file that worked before behaves exactly as it did.
object M4aFallback {
    private const val PREFS = "m4a-fallback"
    private const val KEY_ENABLED = "enabled"

    private val state = MutableStateFlow(true)

    @Volatile
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        state.value = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
        loaded = true
    }

    fun flow(context: Context): StateFlow<Boolean> {
        ensureLoaded(context)
        return state
    }

    fun isEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return state.value
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
        state.value = enabled
    }
}

// The settings row for the switch above. Self-contained, like the Speed mode row.
@Composable
internal fun M4aFallbackEntry() {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val enabled by M4aFallback.flow(context).collectAsState()
    val shape = RoundedCornerShape(16.dp)

    Surface(
        color = cs.surfaceContainer, shape = shape,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape)
            .clickable { M4aFallback.setEnabled(context, !enabled) }
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("🎧", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.m4a_fallback_title),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface
                )
                Text(
                    stringResource(R.string.m4a_fallback_sub),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = { M4aFallback.setEnabled(context, it) })
        }
    }
}
