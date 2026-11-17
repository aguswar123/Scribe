package org.scrib.transcriber.match

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.scrib.transcriber.Audiobook
import org.scrib.transcriber.AudiobookFolders
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Settings of the Batch button on the chapter list. Same options as the Match settings of STools.
// Kept in their own preferences file, so no existing setting is touched.
object BatchMatchSettings {
    private const val PREFS = "batch_match"

    @Volatile
    private var loaded = false

    var strict by mutableStateOf(false)        // fix case & punctuation too
    var unlimited by mutableStateOf(true)      // match the text exactly (no word limit)
    var maxIns by mutableStateOf("15")         // max words to insert when not unlimited
    var nameOn by mutableStateOf(false)        // name files after the EPUB chapter
    var nameNum by mutableStateOf(false)       // add chapter number (01 - ...)
    var nameDig by mutableStateOf(false)       // number words to digits
    var unbreakable by mutableStateOf(false)   // join broken sentences afterwards

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        strict = p.getBoolean("strict", false)
        unlimited = p.getBoolean("unlimited", true)
        maxIns = p.getString("maxIns", "15") ?: "15"
        nameOn = p.getBoolean("nameOn", false)
        nameNum = p.getBoolean("nameNum", false)
        nameDig = p.getBoolean("nameDig", false)
        unbreakable = p.getBoolean("unbreakable", false)
    }

    fun save(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("strict", strict)
            .putBoolean("unlimited", unlimited)
            .putString("maxIns", maxIns)
            .putBoolean("nameOn", nameOn)
            .putBoolean("nameNum", nameNum)
            .putBoolean("nameDig", nameDig)
            .putBoolean("unbreakable", unbreakable)
            .apply()
    }

    fun maxInsValue(): Int = if (unlimited) Int.MAX_VALUE else (maxIns.toIntOrNull() ?: 0).coerceIn(0, 200)
}

class FoundEpub(val uri: Uri, val name: String)

// One caption file of a chapter, ready to be matched.
private class CapJob(val chapterTitle: String, val name: String, val cap: Caption)

// Runs the batch: reads the caption files of the chosen chapters, matches each to its EPUB chapter
// and writes one zip into the folder of the audiobook. Lives here (not in the screen) so leaving
// the chapter list does not stop it.
object BatchRun {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val CAPTION_EXT = listOf("srt", "vtt", "lrc")

    var running by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var status by mutableStateOf("")
    var summary by mutableStateOf<String?>(null)

    fun dismiss() {
        if (!running) summary = null
    }

    fun nameOf(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull() ?: "book.epub"

    // The first .epub in the folder of the chapters, if there is one.
    fun findEpub(context: Context, book: Audiobook): FoundEpub? = runCatching {
        val dir = AudiobookFolders.resolve(context.applicationContext, Uri.parse(book.treeUri), book.path)
            ?: return@runCatching null
        dir.listFiles()
            .filter { it.isFile && (it.name ?: "").endsWith(".epub", ignoreCase = true) }
            .sortedBy { it.name?.lowercase() }
            .firstOrNull()
            ?.let { FoundEpub(it.uri, it.name ?: "book.epub") }
    }.getOrNull()

    fun zipNameFor(epubName: String): String = baseName(epubName) + "_batch.zip"

    fun start(context: Context, book: Audiobook, chapterFiles: List<String>, epubUri: Uri, epubName: String): Boolean {
        if (running || chapterFiles.isEmpty()) return false
        val app = context.applicationContext
        val strict = BatchMatchSettings.strict
        val maxIns = BatchMatchSettings.maxInsValue()
        val join = BatchMatchSettings.unbreakable
        val nameOn = BatchMatchSettings.nameOn
        val nameNum = BatchMatchSettings.nameNum
        val nameDig = BatchMatchSettings.nameDig
        running = true
        progress = 0f
        status = "Reading the EPUB…"
        summary = null
        scope.launch {
            summary = try {
                execute(app, book, chapterFiles, epubUri, epubName, strict, maxIns, join, nameOn, nameNum, nameDig)
            } catch (e: Throwable) {
                "Batch failed: ${e.message ?: e.javaClass.simpleName}"
            }
            running = false
        }
        return true
    }

    private suspend fun execute(
        app: Context, book: Audiobook, chapterFiles: List<String>, epubUri: Uri, epubName: String,
        strict: Boolean, maxIns: Int, join: Boolean, nameOn: Boolean, nameNum: Boolean, nameDig: Boolean
    ): String {
        val dir = AudiobookFolders.resolve(app, Uri.parse(book.treeUri), book.path)
            ?: return "Could not open the audiobook folder."
        val epub = loadEpub(app, epubUri)
        if (epub.isEmpty()) return "The EPUB has no readable chapters."

        // 1. Collect the caption files of the chosen chapters.
        val jobs = ArrayList<CapJob>()
        val noCaptions = ArrayList<String>()
        status = "Reading caption files…"
        for (file in chapterFiles) {
            val title = book.chapters.firstOrNull { it.file == file }?.title ?: file
            val base = file.substringBeforeLast('.')
            var any = false
            for (ext in CAPTION_EXT) {
                val doc = findCaptionFile(dir, "$base.$ext") ?: continue
                val text = app.contentResolver.openInputStream(doc.uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: continue
                val cap = parseCaptions(text)
                if (cap == null || cap.format == null || cap.cues.isEmpty()) continue
                jobs.add(CapJob(title, "$base.$ext", cap))
                any = true
            }
            if (!any) noCaptions.add(title)
        }
        if (jobs.isEmpty()) return "No SRT, VTT or LRC files were found for the selected chapters."

        // 2. Match every file to the EPUB chapter that fits it best, then fix its text.
        val grams = HashMap<Int, Set<String>>()
        val picked = HashMap<String, Pair<Int, Int>?>() // chapter file base -> EPUB chapter
        val results = ArrayList<Pair<String, String>>() // zip entry name -> text
        val used = HashSet<String>()
        val unmatched = ArrayList<String>()
        for ((n, j) in jobs.withIndex()) {
            status = "Matching ${n + 1} of ${jobs.size}: ${j.name}"
            progress = n.toFloat() / jobs.size
            val key = j.name.substringBeforeLast('.')
            val g = if (picked.containsKey(key)) picked[key] else guess(j.cap, epub, grams).also { picked[key] = it }
            if (g == null) { unmatched.add(j.name); continue }
            val out = try {
                matchOne(j.cap, epub[g.first].text, maxIns, strict, join)
            } catch (e: Exception) { null }
            if (out == null) { unmatched.add(j.name); continue }
            val fmt = j.cap.format ?: continue
            val name = if (nameOn) chapterFileName(j.name, fmt, epub[g.first].title, g.first, nameNum, nameDig) else j.name
            results.add(uniqueName(name, used) to out)
        }
        if (results.isEmpty()) return "No caption file could be matched to a chapter of the EPUB."

        // 3. One zip in the folder of the audiobook.
        status = "Saving the zip…"
        progress = 1f
        val zipName = zipNameFor(epubName)
        dir.findFile(zipName)?.delete()
        val target = dir.createFile("application/zip", zipName) ?: return "Could not create $zipName in the folder."
        val os = app.contentResolver.openOutputStream(target.uri, "wt") ?: return "Could not write $zipName."
        ZipOutputStream(os).use { z ->
            for ((name, text) in results) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }

        return buildString {
            append("Saved ${target.name ?: zipName} in the same folder as the chapters.\n")
            append("${results.size} of ${jobs.size} caption files matched.")
            if (noCaptions.isNotEmpty()) append("\nNo caption files: ${noCaptions.joinToString(", ")}")
            if (unmatched.isNotEmpty()) append("\nNo matching EPUB chapter: ${unmatched.joinToString(", ")}")
        }
    }

    private fun findCaptionFile(dir: DocumentFile, name: String): DocumentFile? {
        return dir.findFile(name)
    }

    // The EPUB chapter that shares the most 4-word runs with the captions (needs 15% or more).
    private fun guess(cap: Caption, chapters: List<EpubChapter>, cache: HashMap<Int, Set<String>>): Pair<Int, Int>? {
        val toks = tokenize(cap.cues.joinToString(" ") { it.plain }).map { it.key }
        val capGrams = ArrayList<String>()
        var i = 0
        while (i + 4 <= toks.size) { capGrams.add(toks.subList(i, i + 4).joinToString(" ")); i += 3 }
        if (capGrams.isEmpty()) return null
        var best = -1
        var bestScore = 0
        for ((ci, ch) in chapters.withIndex()) {
            val set = cache.getOrPut(ci) {
                val k = tokenize(ch.text).map { it.key }
                val s = HashSet<String>()
                var j = 0
                while (j + 4 <= k.size) { s.add(k.subList(j, j + 4).joinToString(" ")); j++ }
                s
            }
            var hits = 0
            for (g in capGrams) if (g in set) hits++
            if (hits > bestScore) { bestScore = hits; best = ci }
        }
        if (best >= 0 && bestScore.toDouble() / capGrams.size >= 0.15) {
            return best to Math.round(bestScore.toDouble() / capGrams.size * 100).toInt()
        }
        return null
    }

    private fun matchOne(cap: Caption, chapterText: String, maxIns: Int, strict: Boolean, join: Boolean): String? {
        val fmt = cap.format ?: return null
        val an = computeAnalysis(cap, chapterText, maxIns) ?: return null
        val texts = cap.cues.mapIndexed { i, c -> finalTextFor(cap, an, c, i, strict) }
        if (join) return buildUnbreakCC(unbreak(texts), cap)
        val idx = HashMap<Cue, Int>()
        cap.cues.forEachIndexed { i, c -> idx[c] = i }
        return serialize(fmt, cap.items) { texts[idx[it] ?: 0] }
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val m = Regex("^(.*?)(\\.[^.]*)?$").find(name)
        val stem = m?.groupValues?.get(1) ?: name
        val ext = m?.groupValues?.get(2) ?: ""
        var i = 2
        var n: String
        do { n = stem + "_" + (i++) + ext } while (n in used)
        used.add(n)
        return n
    }
}
