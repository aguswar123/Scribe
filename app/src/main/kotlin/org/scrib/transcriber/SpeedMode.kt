package org.scrib.transcriber

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Speed mode: the local engine decodes two 30-second windows at the same time. It is off by default
// and has nothing to do with the Benchmark history or the ETA history -- its runs live in
// SpeedStore, and its estimates come from there alone.
object SpeedMode {
    private const val PREFS = "speed-mode"
    private const val KEY_ENABLED = "enabled"

    private val state = MutableStateFlow(false)

    @Volatile
    private var loaded = false

    // Set by TranscriptionRun for the run it is about to start; read by the engine. Only one run
    // happens at a time, so a plain flag is enough.
    @Volatile
    var runActive = false

    // Set by the engine when the windows really were decoded side by side.
    @Volatile
    var usedParallel = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        state.value = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
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

// The history of runs made with Speed mode. Same shape as the Benchmark history so the ETA maths
// can be reused, but a different file: the two never mix.
object SpeedStore {
    private const val FILE_NAME = "speed-history.json"
    private const val MAX_RUNS = 100
    private const val LOG_TAG = "SpeedStore"

    private val _revisions = MutableStateFlow(0L)
    val revisions: StateFlow<Long> = _revisions

    @Synchronized
    fun record(context: Context, run: BenchmarkRun) {
        val runs = load(context).toMutableList()
        runs.add(0, run)
        if (runs.size > MAX_RUNS) {
            runs.subList(MAX_RUNS, runs.size).clear()
        }
        try {
            file(context).writeText(serialize(runs))
            _revisions.value += 1
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't save speed history", e)
        }
    }

    @Synchronized
    fun clear(context: Context): Boolean {
        val file = file(context)
        if (!file.exists()) return true
        return try {
            if (file.delete()) {
                _revisions.value += 1
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't clear speed history", e)
            false
        }
    }

    @Synchronized
    fun exportJson(context: Context): String = serialize(load(context))

    @Synchronized
    fun load(context: Context): List<BenchmarkRun> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            ArrayList<BenchmarkRun>(array.length()).apply {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val m = item.optJSONObject("metrics") ?: JSONObject()
                    add(
                        BenchmarkRun(
                            timestampMs = item.optLong("timestampMs"),
                            modelName = item.optString("model").takeIf { it.isNotBlank() },
                            fileName = item.optString("file"),
                            metrics = TranscriptionMetrics(
                                audioDurationMs = m.optLong("audioDurationMs"),
                                decodeMs = m.optLong("decodeMs"),
                                inferenceMs = m.optLong("inferenceMs"),
                                modelLoadMs = m.optLong("modelLoadMs"),
                                modelPssMb = m.optInt("modelPssMb"),
                                modelMemoryDeltaMb = m.optInt("modelMemoryDeltaMb"),
                                totalMs = m.optLong("totalMs"),
                                pssMb = m.optInt("pssMb"),
                                peakPssMb = m.optInt("peakPssMb"),
                                freeRamMb = m.optInt("freeRamMb")
                            )
                        )
                    )
                }
            }.sortedByDescending { it.timestampMs }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't read speed history", e)
            emptyList()
        }
    }

    private fun serialize(runs: List<BenchmarkRun>): String {
        val array = JSONArray()
        runs.forEach { run ->
            array.put(
                JSONObject()
                    .put("timestampMs", run.timestampMs)
                    .put("model", run.modelName)
                    .put("file", run.fileName)
                    .put(
                        "metrics", JSONObject()
                            .put("audioDurationMs", run.metrics.audioDurationMs)
                            .put("decodeMs", run.metrics.decodeMs)
                            .put("inferenceMs", run.metrics.inferenceMs)
                            .put("modelLoadMs", run.metrics.modelLoadMs)
                            .put("modelPssMb", run.metrics.modelPssMb)
                            .put("modelMemoryDeltaMb", run.metrics.modelMemoryDeltaMb)
                            .put("totalMs", run.metrics.totalMs)
                            .put("pssMb", run.metrics.pssMb)
                            .put("peakPssMb", run.metrics.peakPssMb)
                            .put("freeRamMb", run.metrics.freeRamMb)
                    )
            )
        }
        return array.toString()
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
