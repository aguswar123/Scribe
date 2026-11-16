package org.scrib.transcriber

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.sin

// What the person picked in Settings. Kept in one object so the Settings card and the toggle on the
// transcribe dialog read and write the same thing.
data class AlarmConfig(
    val enabled: Boolean = false,
    val fileSound: String = AlarmPlayer.BEEP,
    val fileTimes: Int = 1,
    val allSound: String = AlarmPlayer.TRIPLE,
    val allTimes: Int = 1,
    val keepScreenOn: Boolean = false
)

object AlarmSettings {
    private const val PREFS = "alarm-settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FILE_SOUND = "fileSound"
    private const val KEY_FILE_TIMES = "fileTimes"
    private const val KEY_ALL_SOUND = "allSound"
    private const val KEY_ALL_TIMES = "allTimes"
    private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
    const val MAX_TIMES = 5

    private val _config = MutableStateFlow(AlarmConfig())

    @Volatile
    private var loaded = false

    fun flow(context: Context): StateFlow<AlarmConfig> {
        load(context)
        return _config
    }

    fun config(context: Context): AlarmConfig = flow(context).value

    @Synchronized
    fun update(context: Context, change: (AlarmConfig) -> AlarmConfig) {
        load(context)
        val next = change(_config.value).let {
            it.copy(
                fileTimes = it.fileTimes.coerceIn(1, MAX_TIMES),
                allTimes = it.allTimes.coerceIn(1, MAX_TIMES)
            )
        }
        _config.value = next
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_FILE_SOUND, next.fileSound)
            .putInt(KEY_FILE_TIMES, next.fileTimes)
            .putString(KEY_ALL_SOUND, next.allSound)
            .putInt(KEY_ALL_TIMES, next.allTimes)
            .putBoolean(KEY_KEEP_SCREEN_ON, next.keepScreenOn)
            .apply()
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) {
            return
        }
        val p = prefs(context)
        val defaults = AlarmConfig()
        _config.value = AlarmConfig(
            enabled = p.getBoolean(KEY_ENABLED, defaults.enabled),
            fileSound = p.getString(KEY_FILE_SOUND, null) ?: defaults.fileSound,
            fileTimes = p.getInt(KEY_FILE_TIMES, defaults.fileTimes).coerceIn(1, MAX_TIMES),
            allSound = p.getString(KEY_ALL_SOUND, null) ?: defaults.allSound,
            allTimes = p.getInt(KEY_ALL_TIMES, defaults.allTimes).coerceIn(1, MAX_TIMES),
            keepScreenOn = p.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn)
        )
        loaded = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// The beeps are drawn as sine tones and played on the alarm stream, so they sound with the phone on
// silent or vibrate and need no audio files. A sound from the phone is played as a ringtone on the
// same stream.
object AlarmPlayer {
    const val BEEP = "beep"
    const val DOUBLE = "double"
    const val TRIPLE = "triple"
    const val RISING = "rising"
    const val LONG = "long"
    val builtIn = listOf(BEEP, DOUBLE, TRIPLE, RISING, LONG)

    private const val LOG_TAG = "AlarmPlayer"
    private const val SYSTEM_PREFIX = "system:"
    private const val SAMPLE_RATE = 22050
    private const val REPEAT_GAP_MS = 350
    private const val MAX_SYSTEM_MS = 10_000L

    private class Tone(val hz: Int, val ms: Int, val gapMs: Int = 0)

    private val patterns = mapOf(
        BEEP to listOf(Tone(880, 160)),
        DOUBLE to listOf(Tone(880, 130, 90), Tone(880, 130)),
        TRIPLE to listOf(Tone(880, 110, 80), Tone(880, 110, 80), Tone(880, 110)),
        RISING to listOf(Tone(660, 140, 30), Tone(880, 140, 30), Tone(1100, 200)),
        LONG to listOf(Tone(740, 650))
    )

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun systemId(uri: Uri): String = SYSTEM_PREFIX + uri.toString()

    fun isSystem(id: String): Boolean = id.startsWith(SYSTEM_PREFIX)

    private fun systemUri(id: String): Uri? =
        id.removePrefix(SYSTEM_PREFIX).takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }

    fun systemTitle(context: Context, id: String): String? {
        val uri = systemUri(id) ?: return null
        return try {
            RingtoneManager.getRingtone(context.applicationContext, uri)?.getTitle(context)
        } catch (e: Throwable) {
            null
        }
    }

    // One file is done. With the last one — or the only one — the other sound plays, so a glance
    // at the phone is not needed to tell a batch still going from a batch over.
    fun finished(context: Context, allDone: Boolean) {
        val app = context.applicationContext
        val config = AlarmSettings.config(app)
        if (!config.enabled) {
            return
        }
        if (allDone) {
            play(app, config.allSound, config.allTimes)
        } else {
            play(app, config.fileSound, config.fileTimes)
        }
    }

    fun play(context: Context, sound: String, times: Int) {
        val app = context.applicationContext
        val count = times.coerceIn(1, AlarmSettings.MAX_TIMES)
        try {
            if (isSystem(sound)) {
                systemUri(sound)?.let { playSystem(app, it, count) }
            } else {
                playTones(patterns[sound] ?: patterns.getValue(BEEP), count)
            }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't play the alarm sound", e)
        }
    }

    private fun playTones(pattern: List<Tone>, times: Int) {
        val sequence = ArrayList<Tone>()
        repeat(times) { round ->
            pattern.forEachIndexed { index, tone ->
                val gap = if (round < times - 1 && index == pattern.lastIndex) REPEAT_GAP_MS else tone.gapMs
                sequence.add(Tone(tone.hz, tone.ms, gap))
            }
        }
        val totalMs = sequence.sumOf { it.ms + it.gapMs }
        val samples = ShortArray(SAMPLE_RATE * totalMs / 1000)
        val fade = (SAMPLE_RATE / 100).toFloat()
        var position = 0
        sequence.forEach { tone ->
            val count = SAMPLE_RATE * tone.ms / 1000
            for (i in 0 until count) {
                val at = position + i
                if (at >= samples.size) {
                    break
                }
                val envelope = minOf(1f, i / fade, (count - i) / fade)
                val value = sin(2.0 * PI * tone.hz * i / SAMPLE_RATE) * envelope * 0.6 * Short.MAX_VALUE
                samples[at] = value.toInt().toShort()
            }
            position += SAMPLE_RATE * (tone.ms + tone.gapMs) / 1000
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        track.play()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                track.release()
            } catch (e: Throwable) {
                // Already gone.
            }
        }, totalMs + 500L)
    }

    private fun playSystem(context: Context, uri: Uri, times: Int) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            val ringtone = try {
                RingtoneManager.getRingtone(context, uri)
            } catch (e: Throwable) {
                null
            }
            if (ringtone == null) {
                return@post
            }
            ringtone.audioAttributes = attributes
            val step = object : Runnable {
                var left = times
                var playing = false
                var startedAt = 0L

                override fun run() {
                    if (playing) {
                        if (ringtone.isPlaying && SystemClock.elapsedRealtime() - startedAt < MAX_SYSTEM_MS) {
                            handler.postDelayed(this, 150)
                            return
                        }
                        ringtone.stop()
                        playing = false
                        if (left > 0) {
                            handler.postDelayed(this, REPEAT_GAP_MS.toLong())
                        }
                        return
                    }
                    if (left <= 0) {
                        return
                    }
                    left -= 1
                    playing = true
                    startedAt = SystemClock.elapsedRealtime()
                    ringtone.play()
                    handler.postDelayed(this, 300)
                }
            }
            handler.post(step)
        }
    }
}
