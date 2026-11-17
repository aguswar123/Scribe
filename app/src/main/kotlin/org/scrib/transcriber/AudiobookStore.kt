package org.scrib.transcriber

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// One chapter file the splitter wrote. formats is what has been transcribed and saved next to it.
data class AudiobookChapter(
    val file: String,
    val title: String,
    val durationSec: Int,
    val formats: List<String>
)

// A split audiobook. The chapters sit in the folder at path (below the tree the person granted).
data class Audiobook(
    val id: String,
    val name: String,
    val treeUri: String,
    val path: List<String>,
    val chapters: List<AudiobookChapter>
) {
    val folderLabel: String get() = path.joinToString("/")
}

// The list of split books and the Audiobook settings. Kept in its own preferences file so the
// list is still there after the app is closed, until the person deletes an entry.
object AudiobookStore {
    private const val PREFS = "audiobooks"

    private val _books = MutableStateFlow<List<Audiobook>>(emptyList())
    private val _tab = MutableStateFlow(true)
    private val _formats = MutableStateFlow(listOf("SRT"))

    @Volatile
    private var loaded = false

    fun books(context: Context): StateFlow<List<Audiobook>> {
        load(context)
        return _books
    }

    fun tabEnabled(context: Context): StateFlow<Boolean> {
        load(context)
        return _tab
    }

    // The save-as formats picked on the floating bar, always at least one.
    fun formats(context: Context): StateFlow<List<String>> {
        load(context)
        return _formats
    }

    @Synchronized
    fun setTabEnabled(context: Context, on: Boolean) {
        load(context)
        _tab.value = on
        prefs(context).edit().putBoolean("tab", on).apply()
    }

    @Synchronized
    fun setFormats(context: Context, formats: List<String>) {
        load(context)
        val clean = AUDIOBOOK_FORMATS.filter { it in formats }.ifEmpty { listOf("SRT") }
        _formats.value = clean
        prefs(context).edit().putString("formats", clean.joinToString(",")).apply()
    }

    @Synchronized
    fun add(context: Context, book: Audiobook) {
        load(context)
        _books.value = listOf(book) + _books.value.filter { it.id != book.id }
        save(context)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        load(context)
        _books.value = _books.value.filter { it.id != id }
        save(context)
    }

    @Synchronized
    fun markTranscribed(context: Context, id: String, file: String, formats: List<String>) {
        load(context)
        _books.value = _books.value.map { book ->
            if (book.id != id) {
                book
            } else {
                book.copy(chapters = book.chapters.map { ch ->
                    if (ch.file != file) ch else ch.copy(formats = AUDIOBOOK_FORMATS.filter { it in ch.formats || it in formats })
                })
            }
        }
        save(context)
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        for (b in _books.value) {
            arr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("name", b.name)
                    .put("tree", b.treeUri)
                    .put("path", JSONArray(b.path))
                    .put("chapters", JSONArray().also { list ->
                        for (c in b.chapters) {
                            list.put(
                                JSONObject()
                                    .put("file", c.file)
                                    .put("title", c.title)
                                    .put("sec", c.durationSec)
                                    .put("formats", JSONArray(c.formats))
                            )
                        }
                    })
            )
        }
        prefs(context).edit().putString("books", arr.toString()).apply()
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        val p = prefs(context)
        _tab.value = p.getBoolean("tab", true)
        _formats.value = (p.getString("formats", null) ?: "SRT").split(',')
            .filter { it in AUDIOBOOK_FORMATS }.ifEmpty { listOf("SRT") }
        _books.value = runCatching {
            val arr = JSONArray(p.getString("books", null) ?: "[]")
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val ch = o.getJSONArray("chapters")
                Audiobook(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    treeUri = o.getString("tree"),
                    path = o.getJSONArray("path").let { a -> List(a.length()) { a.getString(it) } },
                    chapters = List(ch.length()) { j ->
                        val c = ch.getJSONObject(j)
                        AudiobookChapter(
                            file = c.getString("file"),
                            title = c.getString("title"),
                            durationSec = c.optInt("sec", 0),
                            formats = c.optJSONArray("formats")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
                        )
                    }
                )
            }
        }.getOrDefault(emptyList())
        loaded = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

val AUDIOBOOK_FORMATS = listOf("SRT", "VTT", "LRC")

// Where the chapter files live, and how to get from the folder the person granted to the folder
// the audiobook was picked from.
object AudiobookFolders {

    private fun prefix(treeId: String) = if (treeId.endsWith(":") || treeId.endsWith("/")) treeId else "$treeId/"

    // Folders between the granted tree and the file, for providers whose ids are paths
    // (the usual "primary:Audiobooks/Book/book.m4b"). Opaque ids give an empty list: the chapters
    // then go into the granted folder itself.
    fun parentSegments(tree: Uri, doc: Uri): List<String> = runCatching {
        if (tree.authority != doc.authority) return emptyList()
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val docId = DocumentsContract.getDocumentId(doc)
        val pre = prefix(treeId)
        if (docId.startsWith(pre)) docId.removePrefix(pre).split('/').dropLast(1).filter { it.isNotEmpty() } else emptyList()
    }.getOrDefault(emptyList())

    // A folder the person already granted that holds this file, so the question is asked once.
    fun findPersistedTree(context: Context, doc: Uri): Uri? = runCatching {
        val docId = DocumentsContract.getDocumentId(doc)
        context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission && it.uri.authority == doc.authority }
            .map { it.uri }
            .firstOrNull { t ->
                runCatching { docId.startsWith(prefix(DocumentsContract.getTreeDocumentId(t))) }.getOrDefault(false)
            }
    }.getOrNull()

    fun resolve(context: Context, tree: Uri, path: List<String>): DocumentFile? {
        var dir = DocumentFile.fromTreeUri(context, tree) ?: return null
        for (segment in path) {
            dir = dir.findFile(segment) ?: return null
        }
        return dir
    }
}

// Chapters handed to the normal transcription run, and what to save when each one finishes.
// The run itself is the app's own; this only remembers where its result goes.
object AudiobookRuns {

    private class Job(
        val bookId: String,
        val file: String,
        val formats: List<String>,
        val tree: Uri,
        val path: List<String>
    )

    private val jobs = ConcurrentHashMap<String, Job>()

    fun start(context: Context, book: Audiobook, chapters: List<AudiobookChapter>, formats: List<String>): Boolean {
        val app = context.applicationContext
        val dir = AudiobookFolders.resolve(app, Uri.parse(book.treeUri), book.path) ?: return false
        val items = chapters.mapNotNull { ch -> dir.findFile(ch.file)?.let { it.uri to ch.file } }
        if (items.isEmpty()) return false
        jobs.clear()
        val tree = Uri.parse(book.treeUri)
        items.forEach { (uri, file) -> jobs[uri.toString()] = Job(book.id, file, formats, tree, book.path) }
        val started = TranscriptionRun.startFiles(app, items)
        if (!started) jobs.clear()
        return started
    }

    // Formats a run of this file saves on its own, shown in the transcription window.
    fun formatsFor(uri: Uri): List<String> = jobs[uri.toString()]?.formats ?: emptyList()

    fun onFinished(context: Context, uri: Uri, segments: List<TranscriptSegment>) {
        val job = jobs.remove(uri.toString()) ?: return
        if (segments.isEmpty()) return
        val app = context.applicationContext
        val saved = ArrayList<String>()
        try {
            val dir = AudiobookFolders.resolve(app, job.tree, job.path) ?: return
            val base = job.file.substringBeforeLast('.')
            for (name in job.formats) {
                val format = runCatching { TranscriptFormat.valueOf(name) }.getOrNull() ?: continue
                val fileName = "$base.${format.extension}"
                try {
                    dir.findFile(fileName)?.delete()
                    val target = dir.createFile("application/octet-stream", fileName) ?: continue
                    val out = app.contentResolver.openOutputStream(target.uri, "wt") ?: continue
                    out.use { it.write(segments.format(format).toByteArray(Charsets.UTF_8)) }
                    saved.add(name)
                } catch (e: Exception) {
                    StateLog.log("audiobook save failed: $fileName ${e.message}")
                }
            }
        } catch (e: Exception) {
            StateLog.log("audiobook save failed: ${e.message}")
        }
        if (saved.isNotEmpty()) AudiobookStore.markTranscribed(app, job.bookId, job.file, saved)
    }
}
