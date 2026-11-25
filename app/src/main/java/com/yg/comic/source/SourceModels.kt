package com.yg.comic.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 与 Rust 端 model.rs 对应的数据类。 */

@Serializable
data class SearchBook(
    val name: String = "",
    val author: String = "",
    val kind: String = "",
    @SerialName("word_count") val wordCount: String = "",
    @SerialName("last_chapter") val lastChapter: String = "",
    val intro: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("book_url") val bookUrl: String = "",
    @SerialName("source_name") val sourceName: String = "",
    @SerialName("source_url") val sourceUrl: String = "",
)

/** 一本书的单个来源（多源聚合，与 legado SearchBook.origins 对应）。 */
@Serializable
data class SourceOrigin(
    val sourceId: Long = 0L,
    val sourceJson: String = "",
    val sourceName: String = "",
    val bookUrl: String = "",
    val tocUrl: String = "",
)

@Serializable
data class BookDetail(
    val name: String = "",
    val author: String = "",
    val kind: String = "",
    @SerialName("word_count") val wordCount: String = "",
    @SerialName("last_chapter") val lastChapter: String = "",
    val intro: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("toc_url") val tocUrl: String = "",
)

@Serializable
data class SourceChapter(
    val title: String = "",
    val url: String = "",
    @SerialName("is_vip") val isVip: Boolean = false,
    @SerialName("is_volume") val isVolume: Boolean = false,
)

@Serializable
data class ChapterContent(
    val title: String = "",
    val text: String = "",
    val images: List<String> = emptyList(),
    @SerialName("next_url") val nextUrl: String = "",
)

@Serializable
data class ParseResult(
    val ok: Boolean = false,
    val error: String = "",
    val name: String = "",
    val url: String = "",
    val hasSearch: Boolean = false,
    val hasExplore: Boolean = false,
)

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * 书源引擎高层封装：把 native 的 JSON 字符串解析成数据类；同时负责按源 JSON 内容
 * 路由到不同的 SourceProvider（legado / 异次元 / Mihon）。
 * 调用需在后台线程（网络阻塞）。
 */
object SourceRepository {

    /** 从源 JSON 里探测应该用哪个 provider。 */
    fun providerFor(sourceJson: String): SourceProvider {
        val compact = sourceJson.replace(Regex("\\s"), "")
        return when {
            compact.contains("\"provider\":\"mihon\"") -> SourceProviderRegistry.get("mihon")
            compact.contains("\"provider\":\"yiciyuan\"") ||
                (sourceJson.contains("ruleBookContent") && sourceJson.contains("ruleChapterList")) ->
                SourceProviderRegistry.get("yiciyuan")
            else -> LegadoProvider
        }
    }

    fun parse(sourceJson: String): ParseResult = when (val p = providerFor(sourceJson)) {
        LegadoProvider -> legadoParse(sourceJson)
        else -> p.parse(sourceJson)
    }

    fun search(sourceJson: String, key: String, page: Int = 1): Result<List<SearchBook>> =
        when (val p = providerFor(sourceJson)) {
            LegadoProvider -> legadoSearch(sourceJson, key, page)
            else -> p.search(sourceJson, key, page)
        }

    fun explore(sourceJson: String, kindUrl: String, page: Int = 1): Result<List<SearchBook>> =
        when (val p = providerFor(sourceJson)) {
            LegadoProvider -> legadoExplore(sourceJson, kindUrl, page)
            else -> p.explore(sourceJson, kindUrl, page)
        }

    fun detail(sourceJson: String, bookUrl: String): Result<BookDetail> =
        when (val p = providerFor(sourceJson)) {
            LegadoProvider -> legadoDetail(sourceJson, bookUrl)
            else -> p.detail(sourceJson, bookUrl)
        }.map { it.copy(intro = cleanIntro(it.intro), name = cleanText(it.name)) }

    /**
     * 简介里塞进整页 HTML：部分源（异次元那批、ruleBookInfo.intro 没填的源）解析规则空着时，
     * 上游直接把整个详情页的 HTML 当简介返回，页面上就是 `<!DOCTYPE html> <html> ...` 一大坨。
     * 这里做一次收口：认得出是 HTML 就先取 `<meta name="description">`，取不到就剥标签取正文；
     * 普通文本原样返回，长简介截断到 400 字，别让简介区把整页顶下去。
     */
    fun cleanIntro(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        if (!looksLikeHtml(s)) return if (s.length > 600) s.take(600) + "…" else s

        // 1) 优先 meta description / og:description，站点自己的简介写在这儿最干净
        val meta = Regex(
            """<meta[^>]*?(?:name|property)\s*=\s*["'](?:description|og:description)["'][^>]*?content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(s) ?: Regex(
            """<meta[^>]*?content\s*=\s*["']([^"']+)["'][^>]*?(?:name|property)\s*=\s*["'](?:description|og:description)["']""",
            RegexOption.IGNORE_CASE,
        ).find(s)
        val fromMeta = meta?.groupValues?.getOrNull(1)?.let { cleanText(it) }.orEmpty()
        if (fromMeta.length >= 20) return fromMeta

        // 2) 兜底：去掉 head/script/style，再剥标签、解实体
        var body = s
        for (block in listOf("head", "script", "style", "noscript")) {
            body = Regex("<$block[^>]*>[\\s\\S]*?</$block>", RegexOption.IGNORE_CASE).replace(body, " ")
        }
        body = Regex("<[^>]+>").replace(body, " ")
        val text = cleanText(body)
        return if (text.length > 400) text.take(400) + "…" else text
    }

    private fun looksLikeHtml(s: String): Boolean {
        val lower = s.lowercase()
        if (lower.contains("<!doctype") || lower.contains("<html")) return true
        // 标签数量明显多于普通文本里的尖括号（比如「<第一章>」这种）
        val tagCount = Regex("<[a-zA-Z/][^>]{0,80}>").findAll(s).count()
        return tagCount >= 5
    }

    /** 去标签、解常见 HTML 实体、压空白 */
    fun cleanText(raw: String): String {
        var s = raw
        if (s.contains("<")) s = Regex("<[^>]+>").replace(s, " ")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        s = Regex("&#(\\d+);").replace(s) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: 0
            if (code in 1..0x10FFFF) String(Character.toChars(code)) else ""
        }
        return s.replace(Regex("[\\s\\u00A0]+"), " ").trim()
    }

    fun toc(sourceJson: String, tocUrl: String): Result<List<SourceChapter>> =
        when (val p = providerFor(sourceJson)) {
            LegadoProvider -> legadoToc(sourceJson, tocUrl)
            else -> p.toc(sourceJson, tocUrl)
        }.map { orderAscending(normalizeToc(it)) }

    /**
     * 很多日漫站的目录天生是「最新→最旧」，用户点"正序"看到的第一条却是结局。
     * 这里按章号数值判断方向：倒序的翻成正序，正序原样返回；章号识别不出来就不动。
     */
    fun orderAscending(list: List<SourceChapter>): List<SourceChapter> {
        if (list.size < 5) return list
        val nums = list.map { chapterNumber(it.title) }
        if (nums.count { it > 0 } < list.size * 0.8) return list
        var desc = 0
        var asc = 0
        for (i in 1 until nums.size) {
            val a = nums[i - 1]
            val b = nums[i]
            if (a <= 0 || b <= 0) continue
            if (b < a) desc++ else if (b > a) asc++
        }
        return if (desc > asc * 2) list.asReversed() else list
    }

    private fun chapterNumber(title: String): Double {
        val m = Regex("第\\s*(\\d+(?:\\.\\d+)?)\\s*[话話章回集节]").find(title)
            ?: Regex("^(\\d+(?:\\.\\d+)?)[.、\\s]").find(title.trim())
        return m?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    /**
     * 目录结构收口（所有书源、所有解析核心的输出都过这一道）。
     *
     * 线上真实库里的两个坏样本：
     *  - 云起书院这类站点的目录页连着渲染两份（最新→最旧 + 最旧→最新），`.list@a` 一次全抓，
     *    落地就是 2N 条、第 i 条和第 n-1-i 条同 URL（4090 条 / 2045 个真章节）；
     *  - JSON 目录源解析失败时，url 会变成 `","seq":1,"title":"第1章"}` 这种碎片、title 为空。
     *
     * 两种坏数据都会让「章号 ↔ 正文」整体错位，而且每次重新拉目录落点还可能变。
     * 这里统一处理：
     *  1) 丢掉 url 为空的（卷标题/解析失败项，占着章号点进去是空章）和明显不是地址的碎片；
     *  2) 回文式重复目录只留升序那半份（用户按目录数的章号才对得上）；
     *  3) 再按 URL 去重，保留首次出现，一章只占一个章号；
     *  4) 有「第N话/章/回」这类章号时，按章号升序排一遍——Mihon 扩展吐回来的目录经常是
     *     「最新在前」甚至混着分组页的乱序（实测包子漫画：170,198,207,206,205,204,197…），
     *     不排序的话正序/反序两个方向都是乱的，点"第一条"永远落到奇怪的位置。
     */
    fun normalizeToc(list: List<SourceChapter>): List<SourceChapter> {
        if (list.isEmpty()) return list
        val valid = list.filter { ch ->
            val u = ch.url.trim()
            u.isNotEmpty() && !u.contains('"') && !u.contains('{') &&
                !u.contains(' ') && !u.contains('\n') && !u.contains('\t')
        }
        if (valid.isEmpty()) return list
        var body = valid
        val n = body.size
        if (n >= 6 && n % 2 == 0) {
            val half = n / 2
            var mirrored = 0
            for (i in 0 until half) if (body[i].url == body[n - 1 - i].url) mirrored++
            // 9/10 以上对得上就判定「同一份目录正反各渲染了一次」
            if (mirrored >= half * 9 / 10) body = body.subList(half, n)
        }
        val out = ArrayList<SourceChapter>(body.size)
        val seen = HashSet<String>(body.size)
        for (ch in body) if (seen.add(ch.url)) out.add(ch)

        // 章号排序：解析得出来的占 3/5 以上才动，避免把「番外/公告」这类没号的书排乱。
        val nums = out.map { chapterNumberOf(it.title) }
        val parsed = nums.count { it != null }
        val ordered = if (parsed >= out.size * 3 / 5) {
            out.mapIndexed { i, ch -> Triple(nums[i], i, ch) }
                .sortedWith(compareBy({ it.first ?: Double.MAX_VALUE }, { it.second }))
                .map { it.third }
        } else out

        // 同名同号的重复章（同章被拆成两条）只留一条，否则点进去还是错位。
        val dedup = ArrayList<SourceChapter>(ordered.size)
        val seenTitle = HashSet<String>(ordered.size)
        for (ch in ordered) {
            val t = ch.title.trim()
            if (t.isNotEmpty() && !seenTitle.add(t)) continue
            dedup.add(ch)
        }
        return if (dedup.isEmpty()) list else dedup
    }

    /** 从章节标题抠章号；抠不出来返回 null。全角数字先转半角。
     *
     * 目录标题格式并不统一，实测包子漫画同一本书里三种都有：
     *  - `第1话 ROMANCE DAWN`（带「话」）
     *  - `第916 和之国大相扑`（只有「第N」，后面直接跟标题）
     *  - `1081 黑胡子海盗团…`（连「第」都没有，纯数字开头）
     * 少认一种就会有一批章被当成"没号"扔到列表末尾，反序时全糊在最前面。
     */
    private fun chapterNumberOf(title: String): Double? {
        if (title.isBlank()) return null
        val t = buildString(title.length) {
            for (c in title) append(if (c in '０'..'９') ('0' + (c - '０')) else c)
        }
        Regex("第\\s*([0-9]{1,5}(?:\\.[0-9]{1,3})?)\\s*[话話章回集節节篇卷]").find(t)
            ?.let { return it.groupValues[1].toDoubleOrNull() }
        Regex("第\\s*([0-9]{1,5}(?:\\.[0-9]{1,3})?)(?![0-9])").find(t)
            ?.let { return it.groupValues[1].toDoubleOrNull() }
        Regex("^\\s*[\\[【(（]?\\s*([0-9]{1,5}(?:\\.[0-9]{1,3})?)(?![0-9])").find(t)
            ?.let { return it.groupValues[1].toDoubleOrNull() }
        return null
    }

    /** 中文数字转阿拉伯（零〇一二三四五六七八九十百千万两），认不出返回 null。 */
    private fun cnToInt(raw: String): Int? {
        if (raw.isBlank()) return null
        val digit = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
            '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0
        var section = 0
        var num = 0
        var seen = false
        for (c in raw) {
            when {
                c in digit -> { num = digit[c]!!; seen = true }
                c == '十' -> { section += (if (num == 0) 1 else num) * 10; num = 0; seen = true }
                c == '百' -> { section += (if (num == 0) 1 else num) * 100; num = 0; seen = true }
                c == '千' -> { section += (if (num == 0) 1 else num) * 1000; num = 0; seen = true }
                c == '万' -> { total += (if (section + num == 0) 1 else section + num) * 10000; section = 0; num = 0; seen = true }
                else -> return null
            }
        }
        return if (seen) total + section + num else null
    }

    /** 智能排序用章号：阿拉伯走原逻辑，认不出再试中文数字。 */
    private fun chapterNumberOfSmart(title: String): Double? {
        chapterNumberOf(title)?.let { return it }
        if (title.isBlank()) return null
        val t = buildString(title.length) { for (c in title) append(if (c in '０'..'９') ('0' + (c - '０')) else c) }
        val cn = "[零〇一二两三四五六七八九十百千万]{1,10}"
        Regex("第\\s*($cn)\\s*[话話章回集節节篇卷]").find(t)
            ?.let { cnToInt(it.groupValues[1])?.let { n -> return n.toDouble() } }
        Regex("第\\s*($cn)(?!$cn)").find(t)
            ?.let { cnToInt(it.groupValues[1])?.let { n -> return n.toDouble() } }
        Regex("^\\s*[\\[【(（]?\\s*($cn)(?!$cn)").find(t)
            ?.let { cnToInt(it.groupValues[1])?.let { n -> return n.toDouble() } }
        return null
    }

    /**
     * 智能排序：返回按章号升序的「原下标」序列（序/楔子/前言置顶，无章号的排末尾）。
     * 只给目录展示/点选映射用，基准数组与进度语义不变。
     */
    fun smartTocOrder(titles: List<String>): List<Int> {
        if (titles.size < 2) return titles.indices.toList()
        val nums = titles.map { chapterNumberOfSmart(it) }
        val front = listOf("序", "楔子", "前言", "引言", "序言", "序章", "开篇", "prologue")
        fun isFront(s: String): Boolean {
            val t = s.trim()
            return front.any { t.startsWith(it, ignoreCase = true) }
        }
        return titles.indices.sortedWith { a, b ->
            val fa = isFront(titles[a]); val fb = isFront(titles[b])
            val na = nums[a]; val nb = nums[b]
            when {
                fa && fb -> a.compareTo(b)
                fa -> -1
                fb -> 1
                na != null && nb != null -> {
                    val c = na.compareTo(nb); if (c != 0) c else a.compareTo(b)
                }
                na != null -> -1
                nb != null -> 1
                else -> a.compareTo(b)
            }
        }
    }

    fun content(sourceJson: String, chapterUrl: String): Result<ChapterContent> =
        when (val p = providerFor(sourceJson)) {
            LegadoProvider -> legadoContent(sourceJson, chapterUrl)
            else -> p.content(sourceJson, chapterUrl)
        }

    // ---- 原有 Rust 引擎直连实现（LegadoProvider 与异次元映射后都走这里） ----

    internal fun legadoParse(sourceJson: String): ParseResult =
        runCatching { json.decodeFromString<ParseResult>(SourceEngine.nativeParseSource(sourceJson)) }
            .getOrDefault(ParseResult())

    internal fun legadoSearch(sourceJson: String, key: String, page: Int = 1): Result<List<SearchBook>> =
        runCatching {
            val raw = SourceEngine.nativeSearch(sourceJson, key, page)
            if (raw.trimStart().startsWith("{")) {
                throw RuntimeException("搜索失败:$raw")
            }
            json.decodeFromString<List<SearchBook>>(raw)
        }

    internal fun legadoExplore(sourceJson: String, kindUrl: String, page: Int = 1): Result<List<SearchBook>> =
        runCatching {
            val raw = SourceEngine.nativeExplore(sourceJson, kindUrl, page)
            if (raw.trimStart().startsWith("{")) throw RuntimeException("发现失败:$raw")
            json.decodeFromString<List<SearchBook>>(raw)
        }

    internal fun legadoDetail(sourceJson: String, bookUrl: String): Result<BookDetail> =
        runCatching { json.decodeFromString<BookDetail>(SourceEngine.nativeDetail(sourceJson, bookUrl)).also {
            if (it.name.isBlank()) throw RuntimeException("详情为空")
        } }

    internal fun legadoToc(sourceJson: String, tocUrl: String): Result<List<SourceChapter>> =
        runCatching {
            val raw = SourceEngine.nativeToc(sourceJson, tocUrl)
            if (raw.trimStart().startsWith("{")) throw RuntimeException("目录失败:$raw")
            json.decodeFromString<List<SourceChapter>>(raw)
        }

    internal fun legadoContent(sourceJson: String, chapterUrl: String): Result<ChapterContent> =
        runCatching { json.decodeFromString<ChapterContent>(SourceEngine.nativeContent(sourceJson, chapterUrl)) }
}
