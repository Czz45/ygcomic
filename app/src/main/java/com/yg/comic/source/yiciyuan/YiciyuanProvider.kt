package com.yg.comic.source.yiciyuan

import com.yg.comic.source.BookDetail
import com.yg.comic.source.ChapterContent
import com.yg.comic.source.ParseResult
import com.yg.comic.source.SearchBook
import com.yg.comic.source.SourceChapter
import com.yg.comic.source.SourceProvider
import com.yg.comic.source.SourceRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * 异次元书源适配器。
 *
 * 异次元的规则字段名与 legado 不同（ruleBookContent / ruleChapterList ...），
 * 但选择器语法几乎同源。这里把异次元 JSON 映射成 legado 字段后交给现有 Rust 引擎，
 * 从而不复制一套解析器，也不影响原有的 legado 源。
 */
object YiciyuanProvider : SourceProvider {
    override val providerId: String = "yiciyuan"
    override val displayName: String = "异次元书源"

    override fun parse(sourceJson: String): ParseResult {
        val mapped = runCatching { mapSource(sourceJson) }.getOrNull() ?: return ParseResult()
        val name = mapped.optString("bookSourceName")
        return ParseResult(ok = name.isNotBlank(), name = name, url = mapped.optString("bookSourceUrl"),
            hasSearch = mapped.optString("searchUrl").isNotBlank(),
            hasExplore = mapped.optString("exploreUrl").isNotBlank())
    }

    override fun search(sourceJson: String, key: String, page: Int): Result<List<SearchBook>> =
        runCatching { SourceRepository.legadoSearch(mapSource(sourceJson).toString(), key, page).getOrThrow() }

    override fun explore(sourceJson: String, kindUrl: String, page: Int): Result<List<SearchBook>> =
        runCatching { SourceRepository.legadoExplore(mapSource(sourceJson).toString(), kindUrl, page).getOrThrow() }

    override fun detail(sourceJson: String, bookUrl: String): Result<BookDetail> =
        runCatching { SourceRepository.legadoDetail(mapSource(sourceJson).toString(), bookUrl).getOrThrow() }

    override fun toc(sourceJson: String, tocUrl: String): Result<List<SourceChapter>> =
        runCatching { SourceRepository.legadoToc(mapSource(sourceJson).toString(), tocUrl).getOrThrow() }

    override fun content(sourceJson: String, chapterUrl: String): Result<ChapterContent> =
        runCatching { SourceRepository.legadoContent(mapSource(sourceJson).toString(), chapterUrl).getOrThrow() }

    /** 把单个异次元源映射成 legado 书源 JSON。 */
    fun mapSource(sourceJson: String): JSONObject {
        val src = JSONObject(sourceJson)
        val out = JSONObject()
        out.put("bookSourceName", src.optString("bookSourceName"))
        out.put("bookSourceUrl", src.optString("bookSourceUrl"))
        out.put("bookSourceGroup", src.optString("bookSourceGroup").ifBlank { "异次元" })
        out.put("bookSourceType", 2) // 漫画源
        out.put("provider", "yiciyuan")
        out.put("searchUrl", fixUrl(src.optString("ruleSearchUrl")))
        out.put("exploreUrl", fixUrl(src.optString("ruleFindUrl")))

        val search = JSONObject()
        search.put("bookList", fixRule(src.optString("ruleSearchList")))
        search.put("name", fixRule(src.optString("ruleSearchName")))
        search.put("author", fixRule(src.optString("ruleSearchAuthor")))
        search.put("kind", fixRule(src.optString("ruleSearchKind")))
        search.put("lastChapter", fixRule(src.optString("ruleSearchLastChapter")))
        search.put("intro", fixRule(src.optString("ruleSearchIntroduce")))
        search.put("coverUrl", fixRule(src.optString("ruleSearchCoverUrl")))
        search.put("bookUrl", fixRule(src.optString("ruleSearchNoteUrl")))
        out.put("ruleSearch", search)

        val info = JSONObject()
        // 很多异次元源没有 ruleBookName/CoverUrl，详情页需要回退到通用选择器或搜索规则。
        info.put("name", fixRule(firstNonBlank(src.optString("ruleBookName"), "tag.h1@text", "tag.title@text")))
        info.put("author", fixRule(firstNonBlank(src.optString("ruleBookAuthor"), src.optString("ruleSearchAuthor"))))
        info.put("kind", fixRule(firstNonBlank(src.optString("ruleBookKind"), src.optString("ruleSearchKind"))))
        info.put("lastChapter", fixRule(firstNonBlank(src.optString("ruleBookLastChapter"), src.optString("ruleSearchLastChapter"))))
        info.put("intro", fixRule(firstNonBlank(src.optString("ruleIntroduce"), src.optString("ruleSearchIntroduce"))))
        info.put("coverUrl", fixRule(firstNonBlank(src.optString("ruleCoverUrl"), src.optString("ruleSearchCoverUrl"))))
        info.put("tocUrl", fixRule(src.optString("ruleChapterUrl")))
        out.put("ruleBookInfo", info)

        val toc = JSONObject()
        toc.put("chapterList", fixRule(src.optString("ruleChapterList")))
        toc.put("chapterName", fixRule(src.optString("ruleChapterName")))
        toc.put("chapterUrl", fixRule(src.optString("ruleContentUrl")))
        out.put("ruleToc", toc)

        val content = JSONObject()
        content.put("content", fixRule(src.optString("ruleBookContent")))
        content.put("nextContentUrl", fixRule(src.optString("ruleContentUrlNext")))
        out.put("ruleContent", content)
        return out
    }

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() } ?: ""

    private fun fixUrl(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.replace("searchKey", "{{key}}")
            .replace("searchPage", "{{page}}")
            .replace("{{key}}", "{{key}}")
    }

    /**
     * 异次元选择器 -> 现有 Rust 规则引擎的等价写法：
     * - `!0` 索引 -> `.0`
     * - `!3:5:6` 多索引无法表达，降级取第一个
     * - `-id.xxx` 前导负号（排除语法）直接去掉
     * - `@text#正则` -> `@text##正则##`
     */
    private fun fixRule(raw: String): String {
        if (raw.isBlank()) return ""
        var r = raw.trim()
        if (r.startsWith("-")) r = r.substring(1)
        // Rust 引擎已支持 sel!N；多索引 !3:5:6 取第一个（3），其余暂不支持。
        r = Regex("!(\\d+)(?::\\d+)+").replace(r) { "!${it.groupValues[1]}" }
        if (r.contains("@text#") && !r.contains("##")) {
            val i = r.lastIndexOf('#')
            if (i > 0) r = r.substring(0, i) + "##" + r.substring(i + 1) + "##"
        }
        return r
    }

    /** 给 UI 展示用：把异次元 JSON 数组转成可直接入库的 legado 兼容 JSON 列表。 */
    fun convertAll(raw: String): List<String> {
        val arr = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { mapSource(arr.getJSONObject(i).toString()).toString() }.getOrNull()
        }
    }
}
