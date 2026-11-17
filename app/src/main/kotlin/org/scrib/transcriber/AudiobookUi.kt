package org.scrib.transcriber

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun abDuration(sec: Int): String = if (sec < 60) "$sec s" else "${(sec + 30) / 60} min"

private fun abToast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

// The Audiobook tab: pick a book, the history of split books, and the chapters of one book.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudiobookTab() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val books by AudiobookStore.books(context).collectAsState()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var pendingUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingName by rememberSaveable { mutableStateOf("") }

    // Chapters are written next to the book, which needs access to the folder it sits in. The
    // question is asked once; a folder granted earlier is reused for books inside it.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val doc = pendingUri
        val name = pendingName
        pendingUri = null
        if (tree != null && doc != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            AudiobookSplit.start(context, doc, name, tree)
        }
    }
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "audiobook.m4b"
            val granted = AudiobookFolders.findPersistedTree(context, uri)
            if (granted != null) {
                AudiobookSplit.start(context, uri, name, granted)
            } else {
                pendingUri = uri
                pendingName = name
                abToast(context, "Choose the folder this audiobook is in")
                folderPicker.launch(uri)
            }
        }
    }

    val book = books.firstOrNull { it.id == openId }
    LaunchedEffect(book, openId) {
        if (openId != null && book == null) openId = null
    }
    BackHandler(book != null) { openId = null }

    if (book == null) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
        ) {
            item {
                Text("Audiobook", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Spacer(Modifier.height(12.dp))
                Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Pick an audiobook and Scrib splits it into one file per chapter, saved in the same folder.",
                            fontSize = 12.5.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { bookPicker.launch(arrayOf("*/*")) },
                            enabled = !AudiobookSplit.running,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Pick audiobook") }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("History", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (books.isEmpty()) {
                    Text("Books you split will appear here.", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            }
            itemsIndexed(books, key = { _, b -> b.id }) { _, b ->
                val done = b.chapters.count { it.formats.isNotEmpty() }
                Surface(
                    color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .combinedClickable(onClick = { openId = b.id }, onLongClick = { deleteId = b.id })
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                b.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cs.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text("${b.chapters.size} chapters · $done transcribed", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                            Text(
                                b.folderLabel, fontSize = 12.sp, color = cs.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
            if (books.isNotEmpty()) {
                item { Text("Hold a book to delete it from the list.", fontSize = 12.sp, color = cs.onSurfaceVariant) }
            }
        }
    } else {
        ChapterList(book, onBack = { openId = null })
    }

    deleteId?.let { id ->
        val target = books.firstOrNull { it.id == id }
        if (target == null) {
            deleteId = null
        } else {
            AlertDialog(
                onDismissRequest = { deleteId = null },
                title = { Text("Delete from history?") },
                text = { Text("${target.name}\nOnly the list entry is removed. Your chapter files stay in the folder.") },
                confirmButton = {
                    TextButton(onClick = { AudiobookStore.remove(context, id); deleteId = null }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } }
            )
        }
    }

    SplitDialog()
}

@Composable
private fun ChapterList(book: Audiobook, onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val formats by AudiobookStore.formats(context).collectAsState()
    var selected by remember(book.id) { mutableStateOf(setOf<Int>()) }

    fun toggle(i: Int) {
        selected = if (i in selected) selected - i else selected + i
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 30.sp, color = cs.onSurface, modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        book.folderLabel, fontSize = 12.sp, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)
            ) {
                itemsIndexed(book.chapters, key = { _, c -> c.file }) { i, c ->
                    Surface(
                        color = cs.surfaceContainer, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable { toggle(i) }
                    ) {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${i + 1}. ${c.title}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cs.onSurface)
                                val info = abDuration(c.durationSec) +
                                    if (c.formats.isNotEmpty()) " · Transcribed as ${c.formats.joinToString(", ")}" else ""
                                Text(info, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
                            }
                            Checkbox(checked = i in selected, onCheckedChange = { toggle(i) })
                        }
                    }
                }
            }
        }

        // The floating bar: start, and the formats saved next to each chapter when it is done.
        Surface(
            color = cs.surface, shape = RoundedCornerShape(26.dp), shadowElevation = 8.dp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth()
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(26.dp))
        ) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        val chosen = book.chapters.filterIndexed { i, _ -> i in selected }
                        if (AudiobookRuns.start(context, book, chosen, formats)) {
                            selected = emptySet()
                        } else {
                            abToast(context, "Could not start. Another transcription may be running, or the chapter files were not found.")
                        }
                    },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (selected.isEmpty()) "Start transcribing" else "Start transcribing (${selected.size})",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp
                    )
                }
                AUDIOBOOK_FORMATS.forEach { f ->
                    FilterChip(
                        selected = f in formats,
                        onClick = {
                            val next = if (f in formats) formats - f else formats + f
                            if (next.isNotEmpty()) AudiobookStore.setFormats(context, next)
                        },
                        label = { Text(f, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }
        }
    }
}

// Shown while a book is being split, then the outcome until it is closed.
@Composable
private fun SplitDialog() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val summary = AudiobookSplit.summary
    if (AudiobookSplit.running) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Splitting") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { AudiobookSplit.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    if (AudiobookSplit.status.isNotEmpty()) {
                        Text(AudiobookSplit.status, fontSize = 13.sp, color = cs.onSurfaceVariant, lineHeight = 18.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        AudiobookSplit.log.takeLast(4).joinToString("\n"),
                        fontSize = 12.sp, color = cs.onSurface, lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {}
        )
    } else if (summary != null) {
        AlertDialog(
            onDismissRequest = { AudiobookSplit.dismiss() },
            title = { Text("Audiobook") },
            text = {
                Column {
                    Text(summary, fontSize = 14.sp, color = cs.onSurface)
                    val lastLog = AudiobookSplit.log.lastOrNull { it.startsWith("Error") || it.startsWith("Could not") || it.startsWith("No chapters") }
                    if (lastLog != null && lastLog != summary) {
                        Spacer(Modifier.height(8.dp))
                        Text(lastLog, fontSize = 12.sp, color = cs.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { AudiobookSplit.dismiss() }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Scrib audiobook debug", AudiobookSplit.debugReport().takeLast(200_000)))
                    abToast(context, "Debug log copied")
                }) { Text("Copy debug log") }
            }
        )
    }
}

// Settings row: the switch shows or hides the Audiobook button in the bottom bar.
@Composable
fun AudiobookEntry() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val on by AudiobookStore.tabEnabled(context).collectAsState()
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { Text("📖", fontSize = 18.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Audiobook tab", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    "Show the Audiobook button in the bottom bar.",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Switch(checked = on, onCheckedChange = { AudiobookStore.setTabEnabled(context, it) })
        }
    }
}
