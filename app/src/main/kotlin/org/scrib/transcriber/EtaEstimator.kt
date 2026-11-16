package org.scrib.transcriber

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

// What the finished runs of one model say about how fast it goes: seconds of work per second of audio.
data class EtaModelStats(val modelName: String, val runs: List<BenchmarkRun>, val speed: Double)

// Estimates come from the benchmark history, so no second store is kept. Only runs of the same model
// count — the same file takes very different time on a small model and a large one.
object EtaEstimator {

    private const val RECENT_RUNS = 20

    private fun usable(run: BenchmarkRun) = run.metrics.audioDurationMs > 0L && run.metrics.inferenceMs > 0L

    private fun speedOf(runs: List<BenchmarkRun>): Double? {
        val audio = runs.sumOf { it.metrics.audioDurationMs }
        if (audio <= 0L) return null
        return runs.sumOf { it.metrics.inferenceMs }.toDouble() / audio
    }

    // Newest first, as BenchmarkStore.load returns them.
    fun speedFor(runs: List<BenchmarkRun>, modelName: String?): Double? {
        if (modelName == null) return null
        return speedOf(runs.filter { it.modelName == modelName && usable(it) }.take(RECENT_RUNS))
    }

    fun summaries(runs: List<BenchmarkRun>): List<EtaModelStats> =
        runs.filter { usable(it) }
            .groupBy { it.modelName ?: "—" }
            .mapNotNull { (name, list) -> speedOf(list.take(RECENT_RUNS))?.let { EtaModelStats(name, list.take(RECENT_RUNS), it) } }

    fun audioDurationMs(context: Context, uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0L }
        } catch (e: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Throwable) {
                // Nothing left to free.
            }
        }
    }
}

// "45 sec", "2 min 15 sec", "1 h 05 min 10 sec". Rounded to the nearest second, never zero.
fun preciseDuration(ms: Long): String {
    val total = ((ms + 500) / 1000).coerceAtLeast(1)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return when {
        h > 0 -> "$h h $m min $s sec"
        m > 0 -> "$m min $s sec"
        else -> "$s sec"
    }
}
