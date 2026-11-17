package org.scrib.transcriber

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// Where a finished transcript goes by itself. The folders are ones the person allowed through the
// system folder picker; Android gives an app no other way to write into a user's storage.
data class AutoSaveConfig(
    val enabled: Boolean = false,
    val formats: Set<TranscriptFormat> = setOf(TranscriptFormat.TXT, TranscriptFormat.SRT, TranscriptFormat.LRC),
    // True: next to the audio. False: always into oneFolder.
    val sameFolder: Boolean = true,
    // A folder the audio files live in (or under). Needed to write beside them.
    val sameRoot: String? = null,
    val oneFolder: String? = null
) {
    fun formatList(): List<TranscriptFormat> =
        TranscriptFormat.entries.filter { it in formats && it != TranscriptFormat.WORDS }
}

// What happened to the files of one run, for the card on the Transcribing screen.
data class AutoSaveOutcome(
    val saved: List<String>,
    val place: String,
    val error: String? = null
)

object AutoSaveSettings {
    private const val PREFS = "auto-save-settings"

    private val _config = MutableStateFlow(AutoSaveConfig())

    @Volatile
    private var loaded = false

    fun flow(context: Context): StateFlow<AutoSaveConfig> {
        load(context)
        return _config
    }

    fun current(context: Context): AutoSaveConfig {
        load(context)
        return _config.value
    }

    @Synchronized
    fun update(context: Context, change: (AutoSaveConfig) -> AutoSaveConfig) {
        load(context)
        val next = change(_config.value)
        _config.value = next
        prefs(context).edit()
            .putBoolean("enabled", next.enabled)
            .putString("formats", next.formats.joinToString(",") { it.name })
            .putBoolean("sameFolder", next.sameFolder)
            .putString("sameRoot", next.sameRoot)
            .putString("oneFolder", next.oneFolder)
            .apply()
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) {
            return
        }
        val p = prefs(context)
        val d = AutoSaveConfig()
        val saved = p.getString("formats", null)
        val formats = if (saved == null) {
            d.formats
        } else {
            saved.split(',').mapNotNull { name -> runCatching { TranscriptFormat.valueOf(name) }.getOrNull() }.toSet()
        }
        _config.value = AutoSaveConfig(
            enabled = p.getBoolean("enabled", d.enabled),
            formats = formats,
            sameFolder = p.getBoolean("sameFolder", d.sameFolder),
            sameRoot = p.getString("sameRoot", null),
            oneFolder = p.getString("oneFolder", null)
        )
        loaded = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object AutoSave {

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    private sealed class Target(val label: String) {
        class Tree(val tree: Uri, val dirId: String, label: String) : Target(label)
        class Local(val dir: File, label: String) : Target(label)
    }

    // Writes the chosen formats of a finished run. Null when auto save is off or there is nothing
    // to write. It is part of the Transcribing UI, so it follows that switch too.
    fun run(context: Context, t: TranscribeUi): AutoSaveOutcome? {
        val app = context.applicationContext
        if (!TranscribingUiSettings.current(app).enabled) {
            return null
        }
        val config = AutoSaveSettings.current(app)
        val formats = config.formatList()
        if (!config.enabled || formats.isEmpty() || t.segments.isEmpty()) {
            return null
        }
        val base = t.fileName.substringBeforeLast('.').ifBlank { "transcript" }
        var target: Target? = null
        var fellBack = false
        if (config.sameFolder) {
            val source = t.sourceUri
            val root = config.sameRoot
            if (source != null && root != null) {
                target = besideAudio(source, Uri.parse(root))
            }
            if (target == null) {
                fellBack = true
            }
        }
        if (target == null) {
            target = config.oneFolder?.let { chosen -> treeTarget(Uri.parse(chosen), "the chosen folder") }
        }
        val dest: Target = target ?: Target.Local(
            File(app.getExternalFilesDir(null) ?: app.filesDir, "Transcripts"),
            "the app folder"
        )
        val place = if (fellBack) "${dest.label} (the audio's folder was not reachable)" else dest.label
        val saved = ArrayList<String>()
        val failed = ArrayList<String>()
        for (format in formats) {
            val name = "$base.${format.extension}"
            val ok = try {
                write(app.contentResolver, dest, name, mimeOf(format), t.segments.format(format))
            } catch (e: Throwable) {
                StateLog.log("auto save failed: $name ${e.message}")
                false
            }
            if (ok) saved.add(name) else failed.add(format.name)
        }
        val error = if (failed.isEmpty()) null else "Could not save: ${failed.joinToString(", ")}"
        StateLog.log("auto save: file=${t.fileName} saved=${saved.size} failed=${failed.size} place=$place")
        return AutoSaveOutcome(saved, place, error)
    }

    private fun mimeOf(format: TranscriptFormat): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(format.extension) ?: "application/octet-stream"

    private fun treeTarget(tree: Uri, label: String): Target? = try {
        Target.Tree(tree, DocumentsContract.getTreeDocumentId(tree), label)
    } catch (e: Exception) {
        null
    }

    // The folder of the audio file, when it sits inside the folder the person allowed. Only the
    // phone's own storage tells its folders in its file ids; other providers do not, so those fall
    // through to the chosen folder.
    private fun besideAudio(source: Uri, root: Uri): Target? {
        if (source.authority != EXTERNAL_STORAGE || root.authority != EXTERNAL_STORAGE) {
            return null
        }
        return try {
            val sourceId = DocumentsContract.getDocumentId(source)
            val rootId = DocumentsContract.getTreeDocumentId(root)
            val slash = sourceId.lastIndexOf('/')
            val colon = sourceId.indexOf(':')
            val parentId = when {
                slash >= 0 -> sourceId.substring(0, slash)
                colon >= 0 -> sourceId.substring(0, colon + 1)
                else -> return null
            }
            val inside = parentId == rootId ||
                (if (rootId.endsWith(":")) parentId.startsWith(rootId) else parentId.startsWith("$rootId/"))
            if (inside) Target.Tree(root, parentId, "next to the audio") else null
        } catch (e: Exception) {
            null
        }
    }

    private fun write(resolver: ContentResolver, target: Target, name: String, mime: String, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return when (target) {
            is Target.Local -> {
                target.dir.mkdirs()
                File(target.dir, name).writeBytes(bytes)
                true
            }
            is Target.Tree -> writeTree(resolver, target, name, mime, bytes)
        }
    }

    private fun writeTree(resolver: ContentResolver, target: Target.Tree, name: String, mime: String, bytes: ByteArray): Boolean {
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(target.tree, target.dirId)
        // The same name again replaces the earlier file instead of piling up copies.
        val file = findChild(resolver, target.tree, target.dirId, name)
            ?: DocumentsContract.createDocument(resolver, dirUri, mime, name)
            ?: return false
        val stream = resolver.openOutputStream(file, "wt") ?: return false
        stream.use { it.write(bytes) }
        return true
    }

    private fun findChild(resolver: ContentResolver, tree: Uri, parentId: String, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        return try {
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                var found: Uri? = null
                while (found == null && cursor.moveToNext()) {
                    if (cursor.getString(1) == name) {
                        found = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    }
                }
                found
            }
        } catch (e: Exception) {
            null
        }
    }
}
