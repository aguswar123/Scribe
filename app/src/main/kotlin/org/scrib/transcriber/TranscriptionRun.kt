package org.scrib.transcriber

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

enum class TranscriptionRunResult {
    SUCCESS,
    FAILED,
    CANCELLED,
    REJECTED
}

// A run outlives the screen that started it. The work sits on a process-wide scope rather than in
// the view model, so leaving the app neither cancels it nor drops the text already recognised, and
// the foreground service alongside keeps the process off the low-memory killer's list — an hour of
// audio is hundreds of megabytes of native buffers, which is what the system reclaims first.
object TranscriptionRun {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<TranscribeUi?>(null)
    val state: StateFlow<TranscribeUi?> = _state

    private val _historyUpdates = MutableStateFlow(0L)
    val historyUpdates: StateFlow<Long> = _historyUpdates

    @Volatile
    private var token: CancellationToken? = null

    val running: Boolean get() = token != null

    private val batchQueue = ArrayDeque<BatchItem>()
    private var batchPosition = 0
    private var batchTotal = 0
    private var batchId = 0L

    // A recording has no place in the user's storage to point the save dialog at, and its file is
    // the app's own — hence source stays null there and onFinished cleans the file up.
    fun start(context: Context, uri: Uri, name: String, source: Uri?, onFinished: () -> Unit = {}): Boolean =
        startInternal(context, uri, name, source, { onFinished() }, persistTranscript = true, allowBatch = false)

    fun startEphemeral(context: Context, uri: Uri, name: String, source: Uri?): Boolean =
        startInternal(context, uri, name, source, onFinished = {}, persistTranscript = false, allowBatch = false, ephemeral = true)

    fun startFiles(context: Context, items: List<Pair<Uri, String>>): Boolean {
        if (items.isEmpty()) return false
        val id: Long
        val queued = items.drop(1).map { BatchItem(it.first, it.second) }
        synchronized(this) {
            if (token != null || BenchmarkBatchGate.isActive) return false
            batchQueue.clear()
            queued.forEach { batchQueue.addLast(it) }
            batchPosition = 0
            batchTotal = items.size
            id = ++batchId
        }
        val first = items.first()
        val started = startInternal(
            context, first.first, first.second, first.first,
            onFinished = {}, persistTranscript = true, allowBatch = false,
            batchIndex = 0, batchTotal = items.size, batchId = id
        )
        if (!started) {
            synchronized(this) {
                batchQueue.clear()
                batchTotal = 0
            }
        } else if (queued.isNotEmpty()) {
            // Lengths of the files still waiting, read off the main thread: the all-files estimate
            // needs them. A file whose length can't be read counts as zero rather than blocking it.
            val app = context.applicationContext
            scope.launch {
                queued.forEach { it.durationMs = EtaEstimator.audioDurationMs(app, it.uri) ?: 0L }
            }
        }
        return started
    }

    fun startForBenchmark(
        context: Context,
        uri: Uri,
        name: String,
        source: Uri?,
        onFinished: (TranscriptionRunResult) -> Unit
    ): Boolean = startInternal(
        context,
        uri,
        name,
        source,
        onFinished,
        persistTranscript = false,
        allowBatch = true
    )

    private fun startInternal(
        context: Context,
        uri: Uri,
        name: String,
        source: Uri?,
        onFinished: (TranscriptionRunResult) -> Unit,
        persistTranscript: Boolean,
        allowBatch: Boolean,
        ephemeral: Boolean = false,
        batchIndex: Int = 0,
        batchTotal: Int = 0,
        batchId: Long = 0
    ): Boolean {
        val app = context.applicationContext
        val cancellation = CancellationToken()
        synchronized(this) {
            if (token != null || (BenchmarkBatchGate.isActive && !allowBatch)) {
                return false
            }
            token = cancellation
        }
        val modelName = ModelManager.activeDisplayName(app)
        if (persistTranscript) TranscriptStore.clear(app)
        _state.value = TranscribeUi(
            fileName = name,
            text = "",
            running = true,
            error = null,
            sourceUri = source,
            modelName = modelName,
            batchIndex = batchIndex,
            batchTotal = batchTotal
        )
        StateLog.log("run started: file=$name model=$modelName${if (ephemeral) " (ephemeral)" else ""}")
        TranscriptionForegroundService.start(app)
        scope.launch {
            val eta = EtaClock()
            val liveStats = ModelManager.liveStats(app)
            val initialMemory = processMemory(app)
            val startedAt = SystemClock.elapsedRealtime()
            var audioDurationMs = 0L
            var decodeMs = 0L
            var inferenceMs = 0L
            var modelLoadMs = 0L
            var modelPssMb = 0
            var modelMemoryDeltaMb = 0
            var inferenceStartedAt = 0L
            var peakPssMb = initialMemory.pssMb
            var currentPssMb = initialMemory.pssMb
            var freeRamMb = initialMemory.freeRamMb
            val metricsLock = Any()

            fun currentMetrics(): TranscriptionMetrics = synchronized(metricsLock) {
                val now = SystemClock.elapsedRealtime()
                val memory = processMemory(app)
                currentPssMb = memory.pssMb
                freeRamMb = memory.freeRamMb
                peakPssMb = maxOf(peakPssMb, currentPssMb)
                val liveInferenceMs = if (inferenceStartedAt > 0L) {
                    maxOf(inferenceMs, now - inferenceStartedAt)
                } else {
                    inferenceMs
                }
                TranscriptionMetrics(
                    audioDurationMs = audioDurationMs,
                    decodeMs = decodeMs,
                    inferenceMs = liveInferenceMs,
                    modelLoadMs = modelLoadMs,
                    modelPssMb = modelPssMb,
                    modelMemoryDeltaMb = modelMemoryDeltaMb,
                    totalMs = now - startedAt,
                    pssMb = currentPssMb,
                    peakPssMb = peakPssMb,
                    freeRamMb = freeRamMb
                )
            }

            fun publishMetrics() {
                val metrics = currentMetrics()
                _state.update { it?.copy(metrics = metrics) }
            }

            // Sampling every tick is what makes the figures live, and it is the only thing in the run
            // that reaches outside the process. With the setting off the run keeps its timings and
            // takes a single reading when it ends, so the result and the history still have numbers.
            val metricsJob = if (liveStats) launch {
                while (isActive) {
                    publishMetrics()
                    delay(METRICS_INTERVAL_MS)
                }
            } else {
                null
            }
            val historySpeed = EtaEstimator.speedFor(BenchmarkStore.load(app), modelName)
            val knownDurationMs = EtaEstimator.audioDurationMs(app, uri) ?: 0L

            // Live speed wins once the run has measured it; until then the history of the same model
            // stands in. The queue behind the file is priced at the same speed.
            fun publishEstimate(percent: Int, liveLeftMs: Long?) {
                val duration = synchronized(metricsLock) { audioDurationMs }.takeIf { it > 0L } ?: knownDurationMs
                val liveSpeed = if (liveLeftMs != null && duration > 0L && percent in 0..99) {
                    liveLeftMs.toDouble() / (duration * (100 - percent) / 100.0)
                } else {
                    null
                }
                val speed = liveSpeed ?: historySpeed
                val fileLeft = liveLeftMs ?: if (speed != null && duration > 0L) {
                    (duration * speed * (100 - percent.coerceAtLeast(0)) / 100.0).toLong()
                } else {
                    null
                }
                val totalLeft = if (fileLeft != null && speed != null) {
                    queuedAudioMs(batchId)?.let { fileLeft + (it * speed).toLong() }
                } else {
                    null
                }
                _state.update {
                    it?.copy(
                        fileEstimateMs = fileLeft,
                        totalEstimateMs = totalLeft,
                        estimateFromHistory = liveSpeed == null
                    )
                }
            }
            publishEstimate(-1, null)

            var result = TranscriptionRunResult.FAILED
            var nextMilestone = 25
            try {
                val pfd = app.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw RuntimeException(app.getString(R.string.transcribe_cant_open))
                val segments = TranscriptionEngine.get(app).transcribeToSegments(
                    pfd, ModelManager.language(app), cancellation,
                    // Percent and ETA are cheap state writes. The figures are not re-read here: the
                    // ticker above already republishes them twice a second, and sampling the process
                    // once per progress tick doubles that cost for nothing a person can see.
                    onProgress = { pct ->
                        val left = eta.mark(pct)
                        _state.update { it?.copy(percent = pct, etaMs = left) }
                        publishEstimate(pct, left)
                        if (pct >= nextMilestone) {
                            nextMilestone += 25
                            StateLog.log("run $pct%: file=$name")
                        }
                    },
                    onPartial = { partial -> _state.update { it?.copy(text = partial) } },
                    onMetrics = { metrics ->
                        synchronized(metricsLock) {
                            if (metrics.audioDurationMs > 0L) {
                                audioDurationMs = metrics.audioDurationMs
                                if (inferenceStartedAt == 0L) {
                                    inferenceStartedAt = SystemClock.elapsedRealtime()
                                }
                            }
                            if (metrics.decodeMs > 0L) {
                                decodeMs = metrics.decodeMs
                            }
                            if (metrics.inferenceMs > 0L) {
                                inferenceMs = metrics.inferenceMs
                            }
                            if (metrics.modelLoadMs > 0L) {
                                modelLoadMs = metrics.modelLoadMs
                            }
                            if (metrics.modelPssMb > 0) {
                                modelPssMb = metrics.modelPssMb
                            }
                            if (metrics.modelMemoryDeltaMb > 0) {
                                modelMemoryDeltaMb = metrics.modelMemoryDeltaMb
                            }
                        }
                    }
                )
                val text = segments.format(TranscriptFormat.TXT)
                if (cancellation.isCancelled) {
                    _state.value = null
                    result = TranscriptionRunResult.CANCELLED
                    StateLog.log("run cancelled: file=$name")
                } else {
                    _state.update {
                        it?.copy(
                            text = text.ifBlank { app.getString(R.string.transcribe_no_speech) },
                            segments = segments, running = false, percent = 100, etaMs = null,
                            fileEstimateMs = null, totalEstimateMs = null
                        )
                    }
                    StateLog.log("run done: file=$name text=${text.take(120)}")
                    val settled = processMemory(app)
                    synchronized(metricsLock) {
                        modelPssMb = settled.pssMb
                        modelMemoryDeltaMb = maxOf(0, settled.pssMb - initialMemory.pssMb)
                    }
                    publishMetrics()
                    if (!ephemeral) {
                        _state.value?.metrics?.let { metrics ->
                            BenchmarkStore.record(
                                app,
                                BenchmarkRun(
                                    timestampMs = System.currentTimeMillis(),
                                    modelName = modelName,
                                    fileName = name,
                                    metrics = metrics
                                )
                            )
                        }
                    }

                    if (persistTranscript && segments.isNotEmpty()) {
                        _state.value?.let {
                            TranscriptStore.save(app, it)
                            TranscriptHistory.save(app, it)
                            _historyUpdates.value += 1
                        }
                    }
                    result = TranscriptionRunResult.SUCCESS
                }
            } catch (e: DecodeStoppedException) {
                if (cancellation.isCancelled) {
                    _state.value = null
                    result = TranscriptionRunResult.CANCELLED
                    StateLog.log("run cancelled: file=$name")
                } else {
                    result = TranscriptionRunResult.FAILED
                    val message = if (e.totalMs > 0L) {
                        app.getString(R.string.transcribe_decode_stopped, elapsedOf(e.decodedMs), elapsedOf(e.totalMs))
                    } else {
                        app.getString(R.string.transcribe_decode_stopped_unknown, elapsedOf(e.decodedMs))
                    }
                    _state.update {
                        it?.copy(
                            running = false, etaMs = null, error = message,
                            fileEstimateMs = null, totalEstimateMs = null
                        )
                    }
                    publishMetrics()
                    StateLog.log("run failed: file=$name error=$message")
                }
            } catch (e: Throwable) {
                if (cancellation.isCancelled) {
                    _state.value = null
                    result = TranscriptionRunResult.CANCELLED
                    StateLog.log("run cancelled: file=$name")
                } else {
                    result = TranscriptionRunResult.FAILED
                    _state.update {
                        it?.copy(
                            running = false, etaMs = null,
                            fileEstimateMs = null, totalEstimateMs = null,
                            error = e.message ?: app.getString(R.string.transcribe_failed)
                        )
                    }
                    publishMetrics()
                    StateLog.log("run failed: file=$name error=${e.message}")
                }
            } finally {
                metricsJob?.cancelAndJoin()
                synchronized(this@TranscriptionRun) {
                    if (token === cancellation) token = null
                }
                onFinished(result)
                if (persistTranscript) chainBatch(app, batchId, result)
            }
        }
        return true
    }

    // Audio still waiting behind the running file, or null while some of its lengths are unread.
    private fun queuedAudioMs(id: Long): Long? = synchronized(this) {
        if (id != batchId) return@synchronized 0L
        var sum = 0L
        for (item in batchQueue) {
            sum += item.durationMs ?: return@synchronized null
        }
        sum
    }

    private fun chainBatch(context: Context, id: Long, last: TranscriptionRunResult) {
        synchronized(this) {
            if (id != batchId) {
                return
            }
            val next = if (last == TranscriptionRunResult.CANCELLED) null else batchQueue.removeFirstOrNull()
            if (next == null) {
                batchQueue.clear()
                batchTotal = 0
                return
            }
            batchPosition += 1
            startInternal(
                context, next.uri, next.name, next.uri,
                onFinished = {}, persistTranscript = true, allowBatch = false,
                batchIndex = batchPosition, batchTotal = batchTotal, batchId = id
            )
        }
    }

    fun restore(context: Context) {
        if (token != null || _state.value != null) {
            return
        }
        TranscriptStore.load(context.applicationContext)?.let { _state.value = it }
    }

    fun cancel(context: Context) {
        synchronized(this) {
            token?.cancel()
            batchQueue.clear()
            batchTotal = 0
        }
        TranscriptStore.clear(context.applicationContext)
        _state.value = null
    }

    fun cancelForBenchmark() {
        token?.cancel()
        _state.value = null
    }

    fun dismiss(context: Context) {
        TranscriptStore.clear(context.applicationContext)
        _state.value = null
    }

    fun clearState() {
        _state.value = null
    }

    fun setFormat(format: TranscriptFormat) {
        _state.update { it?.copy(format = format) }
    }
}

private const val METRICS_INTERVAL_MS = 500L

private class BatchItem(val uri: Uri, val name: String) {
    @Volatile
    var durationMs: Long? = null
}

// Whisper reports how far through the audio it is, and it advances at a steady rate once the model
// is warm — so the time the run has taken to cover the percent so far carries over to what is left.
// The first report starts the clock: everything before it was decoding, at a different speed.
private class EtaClock {

    private var startedAt = 0L
    private var basePercent = -1

    fun mark(percent: Int): Long? {
        val now = SystemClock.elapsedRealtime()
        if (basePercent < 0) {
            if (percent in 0..99) {
                startedAt = now
                basePercent = percent
            }
            return null
        }
        val covered = percent - basePercent
        val elapsed = now - startedAt
        if (covered <= 0 || percent >= 100 || elapsed < SETTLE_MS) {
            return null
        }
        return elapsed * (100 - percent) / covered
    }

    private companion object {
        // Too early on, one slow window skews the estimate into the absurd.
        const val SETTLE_MS = 4000L
    }
}
