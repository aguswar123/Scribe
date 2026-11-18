package org.scrib.transcriber

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// How many CPU threads the local engine uses. 0 means Auto: the engine picks the fast cores itself,
// exactly as it did before this setting existed.
object ThreadsSetting {
    private const val PREFS = "threads-setting"
    private const val KEY_THREADS = "threads"

    private val state = MutableStateFlow(0)

    @Volatile
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        state.value = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_THREADS, 0)
            .coerceAtLeast(0)
        loaded = true
    }

    fun maxThreads(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    fun flow(context: Context): StateFlow<Int> {
        ensureLoaded(context)
        return state
    }

    // 0 for Auto, otherwise a thread count the phone can actually run.
    fun chosen(context: Context): Int {
        ensureLoaded(context)
        val value = state.value
        return if (value <= 0) 0 else value.coerceIn(1, maxThreads())
    }

    fun set(context: Context, threads: Int) {
        ensureLoaded(context)
        val value = threads.coerceAtLeast(0)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_THREADS, value).apply()
        state.value = value
    }
}
