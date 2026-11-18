package org.scrib.transcriber

import android.media.MediaExtractor
import android.media.MediaFormat
import android.system.Os
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer

// Where the decoder takes its compressed samples from: Android's MediaExtractor, or, when that
// finds no audio track, Mp4AacReader. Same contract as the MediaExtractor methods it mirrors.
internal interface SampleSource {
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int

    fun sampleTime(): Long

    fun advance(): Boolean
}

internal class ExtractorSource(private val extractor: MediaExtractor) : SampleSource {
    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int = extractor.readSampleData(buffer, offset)

    override fun sampleTime(): Long = extractor.sampleTime

    override fun advance(): Boolean = extractor.advance()
}

// A small reader for AAC audio in an MP4/M4A file, used only when MediaExtractor finds no audio
// track in a file that really has one. It reads the sample tables out of the 'moov' box itself
// and hands the raw AAC frames to the decoder, with the AudioSpecificConfig as codec data.
// Reads are positional (pread), so the file descriptor's own offset is never touched.
internal class Mp4AacReader private constructor(
    private val fd: FileDescriptor,
    private val offsets: LongArray,
    private val sizes: IntArray,
    private val times: LongArray,
    val format: MediaFormat
) : SampleSource {

    private var index = 0

    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
        if (index >= sizes.size) {
            return -1
        }
        val size = sizes[index]
        if (offset + size > buffer.capacity()) {
            throw IOException("Audio frame is larger than the decoder's input buffer")
        }
        buffer.clear()
        buffer.limit(offset + size)
        buffer.position(offset)
        readFully(fd, offsets[index], buffer)
        return size
    }

    override fun sampleTime(): Long = if (index < times.size) times[index] else -1L

    override fun advance(): Boolean {
        index++
        return index < sizes.size
    }

    private class Box(val type: Int, val start: Int, val end: Int)

    private class Track(
        val sampleRate: Int,
        val channels: Int,
        val config: ByteArray,
        val offsets: LongArray,
        val sizes: IntArray,
        val times: LongArray,
        val durationUs: Long
    )

    companion object {
        private const val TAG = "Mp4AacReader"

        // A moov box holds one table row per audio frame: a few hundred KB for an hour of audio.
        private const val MAX_MOOV_BYTES = 64L * 1024 * 1024

        private val MOOV = cc("moov")
        private val TRAK = cc("trak")
        private val MDIA = cc("mdia")
        private val MDHD = cc("mdhd")
        private val HDLR = cc("hdlr")
        private val MINF = cc("minf")
        private val STBL = cc("stbl")
        private val STSD = cc("stsd")
        private val STTS = cc("stts")
        private val STSC = cc("stsc")
        private val STSZ = cc("stsz")
        private val STCO = cc("stco")
        private val CO64 = cc("co64")
        private val ESDS = cc("esds")
        private val SOUN = cc("soun")
        private val MP4A = cc("mp4a")

        private val SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
        )

        private fun cc(s: String): Int =
            (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code

        // Null when the file has no AAC audio this reader understands; the caller then keeps
        // its original "No audio track found" failure.
        fun open(fd: FileDescriptor): Mp4AacReader? {
            return try {
                val track = parse(fd) ?: return null
                val format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, track.sampleRate, track.channels
                )
                format.setByteBuffer("csd-0", ByteBuffer.wrap(track.config))
                format.setLong(MediaFormat.KEY_DURATION, track.durationUs)
                Mp4AacReader(fd, track.offsets, track.sizes, track.times, format)
            } catch (e: Exception) {
                Log.w(TAG, "Fallback reader could not read the file", e)
                null
            }
        }

        private fun readFully(fd: FileDescriptor, position: Long, dst: ByteBuffer) {
            var pos = position
            while (dst.hasRemaining()) {
                val n = Os.pread(fd, dst, pos)
                if (n <= 0) {
                    throw IOException("Unexpected end of file")
                }
                pos += n
            }
        }

        private fun parse(fd: FileDescriptor): Track? {
            val moov = readMoov(fd, Os.fstat(fd).st_size) ?: return null
            for (trak in boxes(moov, 0, moov.limit())) {
                if (trak.type != TRAK) {
                    continue
                }
                val mdia = find(boxes(moov, trak.start, trak.end), MDIA) ?: continue
                val mdiaKids = boxes(moov, mdia.start, mdia.end)
                val hdlr = find(mdiaKids, HDLR) ?: continue
                if (moov.getInt(hdlr.start + 8) != SOUN) {
                    continue
                }
                val mdhd = find(mdiaKids, MDHD) ?: continue
                val minf = find(mdiaKids, MINF) ?: continue
                val stbl = find(boxes(moov, minf.start, minf.end), STBL) ?: continue
                val track = readTrack(moov, mdhd, stbl)
                if (track != null) {
                    return track
                }
            }
            return null
        }

        // Walks the top-level boxes by position and returns the payload of 'moov'; the audio
        // data ('mdat') is never read here, so a big file costs only the header reads.
        private fun readMoov(fd: FileDescriptor, fileSize: Long): ByteBuffer? {
            val header = ByteBuffer.allocate(16)
            var pos = 0L
            while (pos + 8 <= fileSize) {
                val want = minOf(16L, fileSize - pos).toInt()
                header.clear()
                header.limit(want)
                readFully(fd, pos, header)
                var size = header.getInt(0).toLong() and 0xFFFFFFFFL
                val type = header.getInt(4)
                var headerSize = 8
                if (size == 1L) {
                    if (want < 16) {
                        return null
                    }
                    size = header.getLong(8)
                    headerSize = 16
                } else if (size == 0L) {
                    size = fileSize - pos
                }
                if (size < headerSize) {
                    return null
                }
                if (type == MOOV) {
                    val payload = size - headerSize
                    if (payload > MAX_MOOV_BYTES) {
                        return null
                    }
                    val box = ByteBuffer.allocate(payload.toInt())
                    readFully(fd, pos + headerSize, box)
                    box.flip()
                    return box
                }
                pos += size
            }
            return null
        }

        // The child boxes between start and end. A box that claims to run past end stops the walk.
        private fun boxes(b: ByteBuffer, start: Int, end: Int): List<Box> {
            val out = ArrayList<Box>()
            var p = start
            while (p + 8 <= end) {
                var size = b.getInt(p).toLong() and 0xFFFFFFFFL
                val type = b.getInt(p + 4)
                var header = 8
                if (size == 1L) {
                    if (p + 16 > end) {
                        break
                    }
                    size = b.getLong(p + 8)
                    header = 16
                } else if (size == 0L) {
                    size = (end - p).toLong()
                }
                if (size < header || p + size > end) {
                    break
                }
                out.add(Box(type, p + header, (p + size).toInt()))
                p = (p + size).toInt()
            }
            return out
        }

        private fun find(list: List<Box>, type: Int): Box? = list.firstOrNull { it.type == type }

        private fun u32(b: ByteBuffer, index: Int): Long = b.getInt(index).toLong() and 0xFFFFFFFFL

        private fun readTrack(b: ByteBuffer, mdhd: Box, stbl: Box): Track? {
            val version = b.get(mdhd.start).toInt()
            val timescale = b.getInt(mdhd.start + (if (version == 1) 20 else 12))
            val mediaDuration = if (version == 1) b.getLong(mdhd.start + 24) else u32(b, mdhd.start + 16)
            if (timescale <= 0) {
                return null
            }

            val kids = boxes(b, stbl.start, stbl.end)
            val stsd = find(kids, STSD) ?: return null
            val stts = find(kids, STTS) ?: return null
            val stsc = find(kids, STSC) ?: return null
            val stsz = find(kids, STSZ) ?: return null
            val chunkBox = find(kids, STCO) ?: find(kids, CO64) ?: return null
            val wide = chunkBox.type == CO64

            // Sample description: only plain 'mp4a' entries (AAC) are handled.
            check(b.getInt(stsd.start + 4) >= 1) { "No sample description" }
            val entry = stsd.start + 8
            if (b.getInt(entry + 4) != MP4A) {
                return null
            }
            val entryEnd = minOf(entry + b.getInt(entry), stsd.end)
            val qtVersion = b.getShort(entry + 16).toInt() and 0xFFFF
            var channels = b.getShort(entry + 24).toInt() and 0xFFFF
            var sampleRate = b.getInt(entry + 32) ushr 16
            var childStart = entry + 36
            if (qtVersion == 1) {
                childStart += 16
            } else if (qtVersion == 2) {
                childStart += 36
            }
            val esds = find(boxes(b, childStart, entryEnd), ESDS) ?: return null
            val config = readAudioSpecificConfig(b, esds.start, esds.end) ?: return null
            if (sampleRate <= 0) {
                val rateIndex = ((config[0].toInt() and 0x07) shl 1) or ((config[1].toInt() and 0x80) ushr 7)
                sampleRate = SAMPLE_RATES.getOrElse(rateIndex) { 44100 }
            }
            if (channels <= 0) {
                channels = ((config[1].toInt() shr 3) and 0x0F).coerceAtLeast(1)
            }

            // Sample sizes.
            val fixedSize = u32(b, stsz.start + 4)
            val count = u32(b, stsz.start + 8)
            check(count in 1..50_000_000L) { "Odd sample count $count" }
            val sampleCount = count.toInt()
            val sizes = IntArray(sampleCount)
            if (fixedSize != 0L) {
                sizes.fill(fixedSize.toInt())
            } else {
                check(stsz.start + 12 + count * 4 <= stsz.end) { "Sample size table is cut off" }
                for (i in 0 until sampleCount) {
                    sizes[i] = b.getInt(stsz.start + 12 + i * 4)
                    check(sizes[i] >= 0) { "Odd sample size" }
                }
            }

            // Sample times, in microseconds.
            val times = LongArray(sampleCount)
            val timeRows = u32(b, stts.start + 4)
            check(stts.start + 8 + timeRows * 8 <= stts.end) { "Time table is cut off" }
            var s = 0
            var ticks = 0L
            var lastDelta = 0L
            for (row in 0 until timeRows.toInt()) {
                val n = u32(b, stts.start + 8 + row * 8)
                lastDelta = u32(b, stts.start + 12 + row * 8)
                var k = 0L
                while (k < n && s < sampleCount) {
                    times[s++] = ticks * 1_000_000L / timescale
                    ticks += lastDelta
                    k++
                }
            }
            while (s < sampleCount) {
                times[s++] = ticks * 1_000_000L / timescale
                ticks += lastDelta
            }

            // Chunk offsets, then each sample's file position from the chunk it sits in.
            val chunkCount = u32(b, chunkBox.start + 4)
            val chunkWidth = if (wide) 8 else 4
            check(chunkBox.start + 8 + chunkCount * chunkWidth <= chunkBox.end) { "Chunk table is cut off" }
            val stscRows = u32(b, stsc.start + 4).toInt()
            check(stscRows >= 1 && stsc.start + 8 + stscRows.toLong() * 12 <= stsc.end) { "Chunk map is cut off" }
            val offsets = LongArray(sampleCount)
            var s2 = 0
            var row = 0
            for (chunk in 0 until chunkCount.toInt()) {
                while (row + 1 < stscRows && chunk + 1 >= u32(b, stsc.start + 8 + (row + 1) * 12)) {
                    row++
                }
                val perChunk = u32(b, stsc.start + 12 + row * 12)
                var position = if (wide) {
                    b.getLong(chunkBox.start + 8 + chunk * 8)
                } else {
                    u32(b, chunkBox.start + 8 + chunk * 4)
                }
                var k = 0L
                while (k < perChunk && s2 < sampleCount) {
                    offsets[s2] = position
                    position += sizes[s2]
                    s2++
                    k++
                }
            }
            check(s2 == sampleCount) { "Chunk tables cover $s2 of $sampleCount samples" }

            val durationUs = if (mediaDuration > 0) {
                mediaDuration * 1_000_000L / timescale
            } else {
                times[sampleCount - 1]
            }
            return Track(sampleRate, channels, config, offsets, sizes, times, durationUs)
        }

        // esds holds nested descriptors: ES (0x03) > decoder config (0x04) > decoder specific
        // info (0x05), the last being the AudioSpecificConfig the AAC decoder needs.
        private fun readAudioSpecificConfig(b: ByteBuffer, start: Int, end: Int): ByteArray? {
            var p = start + 4
            if (b.get(p++).toInt() != 0x03) {
                return null
            }
            p = skipLength(b, p)
            p += 2
            val flags = b.get(p++).toInt() and 0xFF
            if ((flags and 0x80) != 0) {
                p += 2
            }
            if ((flags and 0x40) != 0) {
                p += 1 + (b.get(p).toInt() and 0xFF)
            }
            if ((flags and 0x20) != 0) {
                p += 2
            }
            if (b.get(p++).toInt() != 0x04) {
                return null
            }
            p = skipLength(b, p)
            p += 13
            if (b.get(p++).toInt() != 0x05) {
                return null
            }
            var length = 0
            for (i in 0 until 4) {
                val v = b.get(p++).toInt() and 0xFF
                length = (length shl 7) or (v and 0x7F)
                if ((v and 0x80) == 0) {
                    break
                }
            }
            if (length < 2 || p + length > end) {
                return null
            }
            val config = ByteArray(length)
            val copy = b.duplicate()
            copy.position(p)
            copy.get(config)
            return config
        }

        // Steps over a descriptor's 1-4 byte length and returns the position after it.
        private fun skipLength(b: ByteBuffer, start: Int): Int {
            var p = start
            for (i in 0 until 4) {
                val v = b.get(p++).toInt() and 0xFF
                if ((v and 0x80) == 0) {
                    break
                }
            }
            return p
        }
    }
}
