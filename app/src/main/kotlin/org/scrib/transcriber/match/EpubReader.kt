package org.scrib.transcriber.match

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

class EpubChapter(val title: String, val text: String, val words: Int)

private val BLOCK = setOf(
    "P", "DIV", "H1", "H2", "H3", "H4", "H5", "H6", "LI", "BLOCKQUOTE", "TR", "SECTION", "ARTICLE", "PRE",
    "DT", "DD", "FIGCAPTION", "TABLE", "UL", "OL", "HR", "BODY", "ASIDE", "HEADER", "FOOTER"
)

fun htmlToText(html: String): String {
    val doc = Jsoup.parse(html)
    doc.select("script,style,rt,rp").remove()
    val out = ArrayList<String>()
    val cur = StringBuilder()
    fun flush() {
        val t = collapseWs(cur.toString())
        if (t.isNotEmpty()) out.add(t)
        cur.setLength(0)
    }
    fun walk(n: Node) {
        if (n is TextNode) { cur.append(n.wholeText); return }
        if (n !is Element) return
        val tag = n.tagName().uppercase()
        if (tag == "BR") { flush(); return }
        val b = tag in BLOCK
        if (b) flush()
        for (c in n.childNodes()) walk(c)
        if (b) flush()
    }
    walk(doc.body())
    flush()
    return out.joinToString("\n\n")
}

private fun resolvePath(dir: String, href: String): String {
    val h = try { Uri.decode(href) } catch (e: Exception) { href }
    val out = ArrayList<String>()
    for (p in (dir + h).split("/")) {
        if (p == "" || p == ".") continue
        if (p == "..") { if (out.isNotEmpty()) out.removeAt(out.size - 1) } else out.add(p)
    }
    return out.joinToString("/")
}

private fun dirOf(path: String): String = if (path.contains('/')) path.substring(0, path.lastIndexOf('/') + 1) else ""

private class ManItem(val href: String, val type: String, val props: String)

/** Opens an EPUB and returns its readable chapters (title + plain text). */
suspend fun loadEpub(ctx: Context, uri: Uri): List<EpubChapter> = withContext(Dispatchers.IO) {
    val tmp = File(ctx.cacheDir, "caption_book.epub")
    tmp.delete()
    ctx.contentResolver.openInputStream(uri)?.use { i ->
        tmp.outputStream().use { o -> i.copyTo(o) }
    } ?: error("Cannot open the EPUB")
    try {
        ZipFile(tmp).use { zf -> parseEpub(zf) }
    } catch (e: ZipException) {
        throw Exception("This is not a valid EPUB (zip) file.")
    } finally {
        tmp.delete()
    }
}

private fun parseEpub(zf: ZipFile): List<EpubChapter> {
    fun dec(name: String): String? {
        val e = zf.getEntry(name) ?: return null
        return zf.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
    }
    fun xml(s: String) = Jsoup.parse(s, "", Parser.xmlParser())

    val cont = dec("META-INF/container.xml") ?: error("container.xml not found.")
    val opfPath = xml(cont).selectFirst("rootfile")?.attr("full-path")?.takeIf { it.isNotEmpty() }
        ?: error("The EPUB has no package file.")
    val opfDir = dirOf(opfPath)
    val opf = xml(dec(opfPath) ?: error("The EPUB package file is missing."))

    val man = LinkedHashMap<String, ManItem>()
    for (i in opf.select("manifest > item")) {
        man[i.attr("id")] = ManItem(i.attr("href"), i.attr("media-type"), i.attr("properties"))
    }

    // Chapter titles from the table of contents (EPUB 3 nav, or EPUB 2 NCX)
    val titles = LinkedHashMap<String, String>()
    try {
        val navItem = man.values.firstOrNull { Regex("\\bnav\\b").containsMatchIn(it.props) }
        if (navItem != null) {
            val np = resolvePath(opfDir, navItem.href)
            val nd = dirOf(np)
            val doc = Jsoup.parse(dec(np) ?: "")
            for (a in doc.select("a[href]")) {
                val p = resolvePath(nd, a.attr("href").split("#")[0])
                if (p.isNotEmpty() && !titles.containsKey(p)) titles[p] = collapseWs(a.text())
            }
        } else {
            val tocId = opf.selectFirst("spine")?.attr("toc").orEmpty()
            val ncx = if (tocId.isNotEmpty()) man[tocId] else null
            if (ncx != null) {
                val np = resolvePath(opfDir, ncx.href)
                val nd = dirOf(np)
                for (pt in xml(dec(np) ?: "").select("navPoint")) {
                    val lab = pt.selectFirst("navLabel > text")
                    val c = pt.selectFirst("content")
                    if (lab != null && c != null) {
                        val p = resolvePath(nd, c.attr("src").split("#")[0])
                        if (!titles.containsKey(p)) titles[p] = collapseWs(lab.text())
                    }
                }
            }
        }
    } catch (e: Exception) { /* titles are optional */ }

    val chapters = ArrayList<EpubChapter>()
    for (ref in opf.select("spine > itemref")) {
        val it = man[ref.attr("idref")] ?: continue
        if (!it.type.contains("html")) continue
        val path = resolvePath(opfDir, it.href)
        val html = dec(path) ?: continue
        val text = htmlToText(html)
        val words = if (text.isEmpty()) 0 else text.split(WS_RE).size
        val h = Jsoup.parse(html).selectFirst("h1,h2,h3")
        val title = titles[path]?.takeIf { t -> t.isNotBlank() }
            ?: h?.text()?.let { t -> collapseWs(t) }?.takeIf { t -> t.isNotBlank() }
            ?: path.substringAfterLast('/')
        chapters.add(EpubChapter(title, text, words))
    }
    return chapters
}
