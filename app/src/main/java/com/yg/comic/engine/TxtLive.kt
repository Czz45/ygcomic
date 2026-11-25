package com.yg.comic.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.charset.Charset

/**
 * TXT 小说实时读取：不复制原文件，只登记引用（content:// Uri 或绝对路径），
 * 阅读时按页索引实时读取并分页。编码自动探测 UTF-8 / UTF-16 / GBK，避免中文乱码。
 */
object TxtLive {
    private val UTF8 = Charset.forName("UTF-8")
    private val UTF16 = Charset.forName("UTF-16")
    private val GBK = Charset.forName("GBK")

    /** 每页字符数（阅读器按此把全文切成页，进度=页索引） */
    const val CHARS_PER_PAGE = 800

    private fun openStream(context: Context, ref: String) =
        if (ref.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(ref))
        } else {
            val f = File(ref)
            if (f.exists()) f.inputStream() else null
        }

    /** 按探测到的编码一次性读入全文；行数统计与分页共用。失败返回 null。 */
    fun readAll(context: Context, ref: String): String? {
        val bytes = openStream(context, ref)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty()) return ""
        val charset = detectCharset(bytes)
        return try {
            String(bytes, charset)
        } catch (_: Exception) {
            try { String(bytes, GBK) } catch (_: Exception) { String(bytes, UTF8) }
        }
    }

    /** 编码探测：优先 BOM；否则用严格 UTF-8 解码试探，失败退回 GBK。 */
    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF)
            return UTF8
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xFE) return UTF16
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF) return UTF16
        // 严格 UTF-8 试探（REPORT 模式，非法字节直接抛异常）
        return try {
            UTF8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
            UTF8
        } catch (_: Exception) {
            GBK
        }
    }

    /** 依据字符数估算页数（至少 1 页） */
    fun pageCount(context: Context, ref: String, charsPerPage: Int = CHARS_PER_PAGE): Int {
        val text = readAll(context, ref) ?: return 1
        if (text.isEmpty()) return 1
        return ((text.length + charsPerPage - 1) / charsPerPage).coerceAtLeast(1)
    }

    /** 取指定页文本（按字符切分，尽量在换行处收尾避免断字） */
    fun pageAt(text: String, pageIndex: Int, charsPerPage: Int = CHARS_PER_PAGE): String {
        if (text.isEmpty()) return ""
        val start = pageIndex * charsPerPage
        if (start >= text.length) return ""
        val end = (start + charsPerPage).coerceAtMost(text.length)
        var slice = text.substring(start, end)
        // 若本页不是最后一页，尝试截到最后一个换行，减少跨页断行
        if (end < text.length) {
            val nl = slice.lastIndexOf('\n')
            if (nl > charsPerPage / 2) slice = slice.substring(0, nl)
        }
        return slice.replace("\r\n", "\n")
    }

    // ---- 小说：章节识别 + 自动分段 ----

    /** 章节标题正则：第X章/节/回/卷、Chapter N、序章、楔子、前言、后记、番外 等常见写法 */
    private val CHAPTER_REGEX = Regex(
        "^\\s*(?:第[0-9一二三四五六七八九十百千零两]+[章节回卷集部篇]|" +
            "Chapter\\s+[0-9IVXLC]+|chapter\\s+[0-9]+|" +
            "序章|序言|楔子|引子|前言|后记|尾声|番外|终章|大结局)" +
            "[^\\n]{0,40}$",
        setOf(RegexOption.IGNORE_CASE),
    )

    data class Chapter(val title: String, val start: Int, val end: Int)

    /** 从全文识别章节：无章节时整篇算一章。返回按出现顺序的章节区间。 */
    fun splitChapters(text: String): List<Chapter> {
        if (text.isBlank()) return emptyList()
        val lines = text.split("\n")
        var offset = 0
        val marks = mutableListOf<Pair<String, Int>>() // title to startOffset
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && trimmed.length <= 50 && CHAPTER_REGEX.matches(trimmed)) {
                marks += trimmed to offset
            }
            offset += line.length + 1 // +1 for the '\n'
        }
        if (marks.isEmpty()) {
            return listOf(Chapter("全文", 0, text.length))
        }
        // 首个标记之前若有正文，并入第一章（标题设为「开始」）
        val result = mutableListOf<Chapter>()
        if (marks.first().second > 0) {
            val head = text.substring(0, marks.first().second).trim()
            if (head.isNotEmpty()) result += Chapter("开始", 0, marks.first().second)
        }
        for (i in marks.indices) {
            val start = marks[i].second
            val end = if (i + 1 < marks.size) marks[i + 1].second else text.length
            result += Chapter(marks[i].first, start, end)
        }
        return result
    }

    /**
     * 自动分段：把章节原文规整为「段」列表。
     * - 统一换行符；
     * - autoPara=true 时按空行/单行切段并清理多余空行，把长段重新按句读包裹；
     * 这里返回已经过基本清理的段落文本，供 UI 逐段渲染（段间距、首行缩进交给 UI）。
     */
    fun toParagraphs(raw: String, autoPara: Boolean): List<String> {
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
        // 无论是否自动分段，都必须先合并「段内软换行」——txt 小说常一句跨多行，
        // 不合并会导致一行孤字（"我""么""人"单独成行）。合并规则：行尾是句末标点/引号则断段，
        // 否则与下一行拼接。autoPara=false 时也做这层合并（否则根本无法正常阅读）。
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() { val t = sb.toString().trim(); if (t.isNotEmpty()) out += t; sb.setLength(0) }
        // 行首缩进（全角空格/空格）通常标示意新段开始
        val paraStarts = listOf("　　", "  ", "\u3000")
        for (line in normalized.split("\n")) {
            val t = line.trim()
            if (t.isEmpty()) { flush(); continue }
            val startsNew = paraStarts.any { line.startsWith(it) }
            if (sb.isNotEmpty() && startsNew) flush()
            sb.append(t)
            if (endsParagraph(t)) flush()
        }
        flush()
        return if (out.isEmpty() && normalized.isNotBlank()) listOf(normalized.trim()) else out
    }

    /** 行尾是否为「句末」标点（据此判断段落结束） */
    private fun endsParagraph(s: String): Boolean {
        val last = s.trimEnd().lastOrNull() ?: return false
        return last in "。！？…”』」》】）!?.\"'"
    }

    /** 章节内的纯文本（用于取段落） */
    fun chapterText(text: String, chapter: Chapter): String =
        text.substring(chapter.start.coerceIn(0, text.length), chapter.end.coerceIn(0, text.length))

    /**
     * 把一章按「字符预算」切成一页页（一页≈一屏）。
     * 段落是自然单位，尽量在段落边界收尾；单个段落超过预算时按字符硬切成多页，
     * 保证翻页模式（无页内滚动）下任何一页都不会超屏裁字。
     */
    fun paginateChapter(paragraphs: List<String>, charsPerPage: Int): List<List<String>> {
        if (paragraphs.isEmpty()) return listOf(emptyList())
        val budget = charsPerPage.coerceAtLeast(20)
        val pages = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        var curLen = 0
        fun flush() { if (cur.isNotEmpty()) { pages += cur; cur = mutableListOf(); curLen = 0 } }
        for (p in paragraphs) {
            if (p.length > budget) {
                // 超长段落：先结算当前页，再按字符切成多页
                flush()
                var start = 0
                while (start < p.length) {
                    val end = (start + budget).coerceAtMost(p.length)
                    pages += listOf(p.substring(start, end))
                    start = end
                }
                continue
            }
            val add = p.length + 1
            if (cur.isNotEmpty() && curLen + add > budget) flush()
            cur += p
            curLen += add
        }
        flush()
        return if (pages.isEmpty()) listOf(emptyList()) else pages
    }
}
