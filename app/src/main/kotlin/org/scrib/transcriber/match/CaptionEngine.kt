package org.scrib.transcriber.match

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Pure Kotlin port of the caption/EPUB matching logic. No Android classes in here,
// so Match, Batch and Translate can all share it.

val WS_RE = Regex("[\\s\\u00A0\\uFEFF\\u2028\\u2029]+")
fun collapseWs(s: String): String = s.replace(WS_RE, " ").trim()
fun baseName(n: String): String = n.replace(Regex("\\.[^.]+$"), "")

// ------------------------------------------------------------------ Types

class Tok(val raw: String, val key: String)

class Cue(val head: String, val text: String) {
    val plain: String by lazy { cleanCaption(text) }
    var tokens: List<Tok> = emptyList()
}

class CapItem(val raw: String? = null, val cue: Cue? = null)
class Caption(val format: String?, val items: List<CapItem>, val cues: List<Cue>)

enum class OpType { EQ, STY, DEL, INS }
class Op(val t: OpType, val a: Tok?, val b: Tok?)
class CueOps(val ops: List<Op>, val changed: Boolean, val empty: Boolean = false)

class Skipped(val cue: Int, val n: Int)
class Analysis(
    val refs: List<List<Tok>>, val skipped: List<Skipped>,
    val total: Int, val matched: Int, val window: Pair<Int, Int>, val chWords: Int
)
class Group(val text: String, val idxs: List<Int>, val from: Int, val to: Int)

// ------------------------------------------------------------------ Caption parsing / serialising

private val LRC_LINE = Regex("""^((?:\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\])+)(.*)$""")
private val LRC_ANY = Regex("""^\s*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]""", RegexOption.MULTILINE)
private val TAG_RE = Regex("<[^>]*>")
private val ASS_RE = Regex("""\{\\[^}]*\}""")
private val BLOCK_SPLIT = Regex("\\n\\s*\\n")
private val NON_CUE = Regex("^(NOTE|STYLE|REGION)\\b")

fun decodeEnt(s: String): String = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

fun cleanCaption(s: String): String =
    collapseWs(decodeEnt(s.replace(TAG_RE, "").replace(ASS_RE, "")))

fun parseCaptions(raw: String): Caption? {
    val s = raw.removePrefix("\uFEFF").replace(Regex("\r\n?"), "\n").trimEnd()
    if (s.isBlank()) return null
    val format = when {
        Regex("^\\s*WEBVTT").containsMatchIn(s) -> "vtt"
        s.contains("-->") -> "srt"
        LRC_ANY.containsMatchIn(s) -> "lrc"
        else -> null
    } ?: return Caption(null, emptyList(), emptyList())
    val items = ArrayList<CapItem>()
    val cues = ArrayList<Cue>()
    fun add(head: String, text: String) {
        val cue = Cue(head, text)
        cues.add(cue); items.add(CapItem(cue = cue))
    }
    if (format == "lrc") {
        for (line in s.split("\n")) {
            val m = LRC_LINE.find(line)
            if (m != null) add(m.groupValues[1], m.groupValues[2]) else items.add(CapItem(raw = line))
        }
    } else {
        for (block in BLOCK_SPLIT.split(s)) {
            val lines = block.split("\n")
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0 || NON_CUE.containsMatchIn(lines[0])) items.add(CapItem(raw = block))
            else add(lines.subList(0, ti + 1).joinToString("\n"), lines.subList(ti + 1, lines.size).joinToString("\n"))
        }
    }
    return Caption(format, items, cues)
}

fun timeLabel(cue: Cue, fmt: String): String {
    if (fmt == "lrc") return cue.head.replace("][", "] [")
    return Regex("""(\d[\d:.,]*)\s*-->""").find(cue.head)?.groupValues?.get(1) ?: ""
}

fun serialize(format: String, items: List<CapItem>, textOf: (Cue) -> String): String {
    val parts = items.map {
        if (it.raw != null) it.raw
        else {
            val cue = it.cue!!
            val t = textOf(cue)
            if (format == "lrc") cue.head + t
            else if (t == "") cue.head else cue.head + "\n" + t
        }
    }
    return parts.joinToString(if (format == "lrc") "\n" else "\n\n") + "\n"
}

// ------------------------------------------------------------------ Tokens and alignment

private val MARKS = Regex("[\\u0300-\\u036f]")
private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
private val DASHES = Regex("([—–])")
val SENT_END = Regex("""[.!?…]["'”’)\]]*$""")

fun norm(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(MARKS, "").replace(NON_ALNUM, "")

fun tokenize(text: String): List<Tok> =
    text.replace(DASHES, "\$1 ").split(WS_RE).filter { it.isNotEmpty() }
        .map { Tok(it, norm(it).ifEmpty { it }) }

/** Finds the part of the chapter that the captions belong to (so a whole book can be searched fast). */
fun findWindow(A: List<String>, B: List<String>): Pair<Int, Int> {
    val K = 4
    if (A.size < K || B.size < K) return 0 to B.size
    val idx = HashMap<String, MutableList<Int>>()
    for (i in 0..B.size - K) {
        val k = B.subList(i, i + K).joinToString(" ")
        val l = idx.getOrPut(k) { mutableListOf() }
        if (l.size < 6) l.add(i)
    }
    val votes = LinkedHashMap<Int, Int>()
    val hits = ArrayList<Int>()
    for (i in 0..A.size - K) {
        val l = idx[A.subList(i, i + K).joinToString(" ")] ?: continue
        for (p in l) {
            val d = p - i
            val bk = Math.floorDiv(d, 64)
            votes[bk] = (votes[bk] ?: 0) + 1
            hits.add(d)
        }
    }
    if (hits.isEmpty()) return 0 to B.size
    var best = 0
    var bv = -1
    for ((k, v) in votes) {
        val t = v + (votes[k - 1] ?: 0) + (votes[k + 1] ?: 0)
        if (t > bv) { bv = t; best = k }
    }
    val d0 = (best + .5) * 64
    val tol = 200 + A.size * .1
    val ok = hits.filter { abs(it - d0) <= tol }
    if (ok.isEmpty()) return 0 to B.size
    val slack = max(30, Math.round(A.size * .08).toInt())
    val s = max(0, ok.min() - slack)
    val e = min(B.size, ok.max() + A.size + slack)
    return s to e
}

/** Longest increasing subsequence on the second value of each pair. */
fun lis(arr: List<IntArray>): List<IntArray> {
    val tails = ArrayList<Int>()
    val prev = IntArray(arr.size) { -1 }
    for (i in arr.indices) {
        var lo = 0
        var hi = tails.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[tails[mid]][1] < arr[i][1]) lo = mid + 1 else hi = mid
        }
        if (lo > 0) prev[i] = tails[lo - 1]
        if (lo == tails.size) tails.add(i) else tails[lo] = i
    }
    val out = ArrayList<IntArray>()
    var k = if (tails.isEmpty()) -1 else tails[tails.size - 1]
    while (k >= 0) { out.add(arr[k]); k = prev[k] }
    out.reverse()
    return out
}

fun lcsPairs(A: List<String>, B: List<String>, a0p: Int, a1p: Int, b0p: Int, b1p: Int, out: MutableList<IntArray>) {
    var a0 = a0p; var a1 = a1p; var b0 = b0p; var b1 = b1p
    while (a0 < a1 && b0 < b1 && A[a0] == B[b0]) { out.add(intArrayOf(a0, b0)); a0++; b0++ }
    val tail = ArrayList<IntArray>()
    while (a0 < a1 && b0 < b1 && A[a1 - 1] == B[b1 - 1]) { a1--; b1--; tail.add(intArrayOf(a1, b1)) }
    val n = a1 - a0
    val m = b1 - b0
    if (n > 0 && m > 0) {
        if (n.toLong() * m <= 4_000_000L) {
            val W = m + 1
            val dp = IntArray((n + 1) * W)
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                dp[i * W + j] = if (A[a0 + i] == B[b0 + j]) dp[(i + 1) * W + j + 1] + 1
                                else max(dp[(i + 1) * W + j], dp[i * W + j + 1])
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                if (A[a0 + i] == B[b0 + j]) { out.add(intArrayOf(a0 + i, b0 + j)); i++; j++ }
                else if (dp[(i + 1) * W + j] >= dp[i * W + j + 1]) i++ else j++
            }
        } else {
            val ca = HashMap<String, Int>()
            val cb = HashMap<String, Int>()
            for (i in a0 until a1) ca[A[i]] = (ca[A[i]] ?: 0) + 1
            for (j in b0 until b1) cb[B[j]] = (cb[B[j]] ?: 0) + 1
            val posB = HashMap<String, Int>()
            for (j in b0 until b1) if (cb[B[j]] == 1 && ca[B[j]] == 1) posB[B[j]] = j
            val cand = ArrayList<IntArray>()
            for (i in a0 until a1) {
                val j = posB[A[i]]
                if (j != null && ca[A[i]] == 1) cand.add(intArrayOf(i, j))
            }
            val anchors = lis(cand)
            if (anchors.isNotEmpty()) {
                var pa = a0
                var pb = b0
                for (an in anchors) {
                    lcsPairs(A, B, pa, an[0], pb, an[1], out)
                    out.add(intArrayOf(an[0], an[1]))
                    pa = an[0] + 1; pb = an[1] + 1
                }
                lcsPairs(A, B, pa, a1, pb, b1, out)
            }
        }
    }
    for (i in tail.indices.reversed()) out.add(tail[i])
}

/** Word-level diff between a caption cue (a) and what the chapter says (b). */
fun wdiff(a: List<Tok>, b: List<Tok>): List<Op> {
    val n = a.size
    val m = b.size
    val W = m + 1
    if (n.toLong() * m > 1_000_000L) {
        return a.map { Op(OpType.DEL, it, null) } + b.map { Op(OpType.INS, null, it) }
    }
    val dp = IntArray((n + 1) * W)
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        dp[i * W + j] = if (a[i].key == b[j].key) dp[(i + 1) * W + j + 1] + 1
                        else max(dp[(i + 1) * W + j], dp[i * W + j + 1])
    }
    val ops = ArrayList<Op>()
    var i = 0
    var j = 0
    while (i < n || j < m) {
        if (i < n && j < m && a[i].key == b[j].key) {
            ops.add(Op(if (a[i].raw == b[j].raw) OpType.EQ else OpType.STY, a[i], b[j])); i++; j++
        } else if (j >= m || (i < n && dp[(i + 1) * W + j] >= dp[i * W + j + 1])) {
            ops.add(Op(OpType.DEL, a[i], null)); i++
        } else {
            ops.add(Op(OpType.INS, null, b[j])); j++
        }
    }
    return ops
}

// ------------------------------------------------------------------ Analysis

private class Ins(val prev: List<Tok>, val next: List<Tok>)

/** maxIns = Int.MAX_VALUE means "match the text exactly (no word limit)". */
fun computeAnalysis(cap: Caption?, chText: String, maxIns: Int): Analysis? {
    if (cap == null || cap.cues.isEmpty() || chText.isBlank()) return null
    val C = tokenize(chText)
    val capTok = ArrayList<Tok>()
    val range = ArrayList<IntArray>()
    for (c in cap.cues) {
        c.tokens = tokenize(c.plain)
        range.add(intArrayOf(capTok.size, capTok.size + c.tokens.size))
        capTok.addAll(c.tokens)
    }
    val M = capTok.size
    if (M == 0) return null
    val A = capTok.map { it.key }
    val B = C.map { it.key }
    val (ws, we) = findWindow(A, B)
    val Bw = B.subList(ws, we)
    val pairs = ArrayList<IntArray>()
    lcsPairs(A, Bw, 0, M, 0, Bw.size, pairs)

    val outTok = arrayOfNulls<List<Tok>>(M)
    val ins = arrayOfNulls<Ins>(M + 1)
    val skipped = ArrayList<Skipped>()
    val cueOfTok = IntArray(M)
    for ((ci, r) in range.withIndex()) for (q in r[0] until r[1]) cueOfTok[q] = ci
    val all = pairs + listOf(intArrayOf(M, Bw.size))
    var q0 = 0
    var p0 = 0
    for (k in all.indices) {
        val q1 = all[k][0]
        val p1 = all[k][1]
        val first = k == 0
        val last = k == all.size - 1
        if (!last) outTok[q1] = listOf(C[ws + p1])
        val a = q1 - q0
        var b = p1 - p0
        var pp0 = p0
        if (a == 0) {
            if (b > 0 && !first && !last) {
                if (b.toLong() > maxIns.toLong()) {
                    skipped.add(Skipped(cueOfTok[q0], b))
                } else {
                    val run = C.subList(ws + p0, ws + p1)
                    val prev = C.getOrNull(ws + p0 - 1)
                    val cut = if (prev != null && SENT_END.containsMatchIn(prev.raw)) 0
                    else {
                        val e = run.indexOfFirst { SENT_END.containsMatchIn(it.raw) }
                        if (e < 0) 0 else e + 1
                    }
                    ins[q0] = Ins(run.subList(0, cut), run.subList(cut, run.size))
                }
            }
        } else {
            if (first && b > a + 3) { pp0 = p1 - (a + 3); b = a + 3 }
            if (last && b > a + 3) b = a + 3
            if (b.toLong() > 2L * a + maxIns.toLong()) {
                for (q in q0 until q1) outTok[q] = listOf(capTok[q])
                skipped.add(Skipped(cueOfTok[q0], b))
            } else {
                for (r in 0 until a) {
                    val lo = pp0 + (r * b) / a
                    val hi = pp0 + ((r + 1) * b) / a
                    outTok[q0 + r] = C.subList(ws + lo, ws + hi)
                }
            }
        }
        q0 = q1 + 1
        p0 = p1 + 1
    }
    val refs = range.mapIndexed { ci, r ->
        val s = r[0]
        val e = r[1]
        if (s == e) emptyList<Tok>()
        else {
            val res = ArrayList<Tok>()
            for (q in s until e) {
                val insQ = ins[q]
                if (q > s && insQ != null) { res.addAll(insQ.prev); res.addAll(insQ.next) }
                else if (q == s && q > 0 && insQ != null) res.addAll(insQ.next)
                res.addAll(outTok[q]!!)
            }
            val insE = ins[e]
            if (e < M && insE != null && cueOfTok[e] != ci) res.addAll(insE.prev)
            res
        }
    }
    return Analysis(refs, skipped, M, pairs.size, ws to we, C.size)
}

fun cueOpsFor(cap: Caption, an: Analysis, ci: Int, strict: Boolean): CueOps {
    val c = cap.cues[ci]
    val ref = an.refs[ci]
    if (c.tokens.isEmpty()) return CueOps(emptyList(), false, true)
    val ops = wdiff(c.tokens, ref)
    val changed = ops.any { it.t == OpType.DEL || it.t == OpType.INS || (strict && it.t == OpType.STY) }
    return CueOps(ops, changed, false)
}

// ------------------------------------------------------------------ Match -> output

fun wrapLike(orig: String, words: List<String>): String {
    val lines = orig.split("\n")
    if (lines.size < 2 || words.size < lines.size) return words.joinToString(" ")
    val lens = lines.map { max(1, it.length) }
    val total = lens.sum()
    val totalChars = words.joinToString(" ").length
    val out = ArrayList<String>()
    var wi = 0
    var acc = 0
    for (li in lines.indices) {
        if (li == lines.size - 1) { out.add(words.subList(wi, words.size).joinToString(" ")); break }
        acc += lens[li]
        val target = acc.toDouble() / total * totalChars
        var cut = wi
        var k = wi
        while (k < words.size - (lines.size - 1 - li)) {
            val used = words.subList(0, k + 1).joinToString(" ").length
            cut = k + 1
            if (used >= target) break
            k++
        }
        if (cut <= wi) cut = wi + 1
        out.add(words.subList(wi, cut).joinToString(" "))
        wi = cut
    }
    return out.joinToString("\n")
}

fun finalTextFor(
    cap: Caption, an: Analysis, cue: Cue, ci: Int, strict: Boolean,
    use: Boolean = true, pre: CueOps? = null
): String {
    if (!use) return cue.text
    val co = pre ?: cueOpsFor(cap, an, ci, strict)
    val ref = an.refs[ci]
    if (!co.changed || ref.isEmpty()) return cue.text
    var t = wrapLike(cue.text, ref.map { it.raw })
    if (cap.format == "vtt") t = t.replace("&", "&amp;").replace("<", "&lt;")
    return t
}

// ------------------------------------------------------------------ Unbreakable text

private val ABBR = setOf("mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "no", "mt", "capt",
    "col", "gen", "lt", "sgt", "rev", "hon", "fig", "vol")
private val LEAD_WORD = Regex("^\\p{L}[\\p{L}'’]*")
private val LEAD_I = Regex("^I($|['’])")
private val UPPER = Regex("\\p{Lu}")
private val ENDS_DASH = Regex("[-–—]$")
private val ALL_DASH = Regex("^[-–—]+$")
private val TRAIL_DASH = Regex("\\s*[-–—]+$")
private val ELLIPSIS = Regex("""(\.{2,}|…)["'”’)\]]*$""")
private val TRAIL_CLOSERS = Regex("""["'”’)\]]+$""")
private val LAST_WORD = Regex("([\\p{L}.]+)\\.$")
private val DOTS_END = Regex("\\.{2,}$")
private val DOTS_TRAIL = Regex("\\.+$")

fun unbreak(texts: List<String>, drop: Boolean = true, lower: Boolean = false, ell: Boolean = true): List<Group> {
    val out = ArrayList<Group>()
    var cur = ""
    var idxs = ArrayList<Int>()
    var inQ = false
    var lowerNext = false
    for (ix in texts.indices) {
        var t = cleanCaption(texts[ix])
        if (t.isEmpty()) continue
        if (lowerNext) {
            val w = LEAD_WORD.find(t)?.value
            if (w != null && !LEAD_I.containsMatchIn(w) && !UPPER.containsMatchIn(w.substring(1))) {
                t = t.substring(0, 1).lowercase() + t.substring(1)
            }
            lowerNext = false
        }
        for (ch in t) {
            when (ch) {
                '"' -> inQ = !inQ
                '\u201c' -> inQ = true
                '\u201d' -> inQ = false
            }
        }
        val cont = ENDS_DASH.containsMatchIn(t) && !ALL_DASH.containsMatchIn(t)
        if (cont) t = t.replace(TRAIL_DASH, "")
        val ellp = ell && ELLIPSIS.containsMatchIn(t)
        val lastWord = LAST_WORD.find(t.replace(TRAIL_CLOSERS, ""))?.groupValues?.get(1)
        val abbr = lastWord != null && lastWord.lowercase().replace(".", "") in ABBR
        val long = cur.length > 1500
        if (inQ && !cont && t.endsWith(".") && !DOTS_END.containsMatchIn(t) && !long) {
            if (drop) {
                t = t.replace(DOTS_TRAIL, "")
                if (lower) lowerNext = true
            }
        }
        cur = if (cur.isNotEmpty()) "$cur $t" else t
        idxs.add(ix)
        if (!cont && !ellp && !abbr && (!inQ || long) && SENT_END.containsMatchIn(t)) {
            out.add(Group(cur, idxs, idxs.first(), idxs.last()))
            cur = ""
            idxs = ArrayList()
            if (long) inQ = false
        }
    }
    if (cur.isNotEmpty()) out.add(Group(cur, idxs, idxs.first(), idxs.last()))
    return out
}

fun buildUnbreakCC(groups: List<Group>, cap: Caption): String {
    val format = cap.format ?: return ""
    val byFirst = groups.associateBy { it.from }
    val idx = HashMap<Cue, Int>()
    cap.cues.forEachIndexed { i, c -> idx[c] = i }
    val items = ArrayList<CapItem>()
    var n = 0
    for (it in cap.items) {
        if (it.raw != null) { items.add(it); continue }
        val ci = idx[it.cue!!] ?: continue
        val g = byFirst[ci] ?: continue
        var head = cap.cues[g.from].head
        if (format != "lrc") {
            val endTok = Regex("""-->\s*(\S+)""").find(cap.cues[g.to].head)?.groupValues?.get(1)
            if (endTok != null) {
                val m = Regex("""(-->\s*)\S+""").find(head)
                if (m != null) head = head.substring(0, m.range.first) + m.groupValues[1] + endTok + head.substring(m.range.last + 1)
            }
            if (format == "srt") {
                val L = head.split("\n").toMutableList()
                if (Regex("^\\d+$").matches(L[0].trim())) { n++; L[0] = n.toString(); head = L.joinToString("\n") }
            }
        }
        var text = g.text
        if (format == "vtt") text = text.replace("&", "&amp;").replace("<", "&lt;")
        items.add(CapItem(cue = Cue(head, text)))
    }
    return serialize(format, items) { it.text }
}

// ------------------------------------------------------------------ File names after the chapter

fun safeName(s: String): String =
    s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ")
        .replace(WS_RE, " ").trim()
        .replace(Regex("^\\.+"), "")
        .replace(Regex("[. ]+$"), "")
        .take(100).ifEmpty { "chapter" }

private val NW = linkedMapOf(
    "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
    "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
    "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
    "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
    "eighty" to 80, "ninety" to 90
)
private val NWRE = "(?:" + NW.keys.joinToString("|") + "|hundred|thousand)"
private val NUMRUN = Regex("\\b$NWRE(?:[\\s-]+(?:and[\\s-]+)?$NWRE)*\\b", RegexOption.IGNORE_CASE)
private val NUMMARK = Regex(
    "\\b(?:chapter|part|book|section|volume|vol|episode|act|track|lesson|day|week|scene|page|unit|story|tale|case|number)\\s+$|\\bno\\.\\s+$",
    RegexOption.IGNORE_CASE
)
private val LEAD_OK = Regex("^[\\s\\d.\\-–—:]*$")
private val AFTER_OK = Regex("^\\s*[:.\\-–—)!?,]")

fun wordsToNumber(run: String): Int? {
    var total = 0
    var cur = 0
    var seen = false
    for (w in run.lowercase().split(Regex("[\\s-]+"))) {
        if (w == "and") continue
        if (w == "hundred") { cur = (if (cur == 0) 1 else cur) * 100; seen = true }
        else if (w == "thousand") { total += (if (cur == 0) 1 else cur) * 1000; cur = 0; seen = true }
        else if (NW.containsKey(w)) { cur += NW[w]!!; seen = true }
        else return null
    }
    return if (seen) total + cur else null
}

fun numWordsToDigits(title: String): String = NUMRUN.replace(title) { m ->
    val off = m.range.first
    val before = title.substring(0, off)
    val after = title.substring(m.range.last + 1)
    val marked = NUMMARK.containsMatchIn(before)
    val lead = LEAD_OK.matches(before) && (after.isEmpty() || AFTER_OK.containsMatchIn(after))
    if (!marked && !lead) m.value else (wordsToNumber(m.value)?.toString() ?: m.value)
}

fun chapterFileName(orig: String, format: String, chTitle: String, chIdx: Int, withNum: Boolean, digits: Boolean): String {
    val ext = Regex("\\.[^.]+$").find(orig)?.value ?: ".$format"
    val title = safeName(if (digits) numWordsToDigits(chTitle) else chTitle)
    return (if (withNum) (chIdx + 1).toString().padStart(2, '0') + " - " else "") + title + ext
}
