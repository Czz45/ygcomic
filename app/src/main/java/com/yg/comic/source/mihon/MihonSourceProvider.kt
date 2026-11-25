package com.yg.comic.source.mihon

import com.yg.comic.source.BookDetail
import com.yg.comic.source.ChapterContent
import com.yg.comic.source.ParseResult
import com.yg.comic.source.SearchBook
import com.yg.comic.source.SourceChapter
import com.yg.comic.source.SourceProvider
import android.util.Log
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mihon / Tachiyomi 扩展书源 Provider。
 *
 * 每个源自身仍然是独立的扩展（独立 APK、独立 classloader、独立 id/name/lang），
 * 这里只负责把扩展的 SManga/SChapter/Page 转成 YGComic 的 DTO。
 */
object MihonSourceProvider : SourceProvider {

    override val providerId: String = "mihon"
    override val displayName: String = "Mihon 扩展"

    /**
     * 扩展源同时只跑 2 个：每个扩展是独立 DexClassLoader，12 路并发加载容易把内存打爆。
     */
    private val searchSemaphore = java.util.concurrent.Semaphore(2)

    override fun parse(sourceJson: String): ParseResult = runCatching {
        val handle = handle(sourceJson)
        ParseResult(
            ok = true,
            name = handle.source.name,
            url = (handle.source as? HttpSource)?.baseUrl ?: "",
            hasSearch = true,
            hasExplore = true,
        )
    }.getOrElse { ParseResult(ok = false, error = it.message ?: "Mihon 源解析失败") }

    override fun search(sourceJson: String, key: String, page: Int): Result<List<SearchBook>> = runCatching {
        val handle = handle(sourceJson)
        val source = handle.source
        val acquired = searchSemaphore.tryAcquire(30, TimeUnit.SECONDS)
        try {
            val filters = runCatching { source.getFilterList() }.getOrElse { FilterList() }
            val result = runBlocking(Dispatchers.IO) { source.getSearchManga(page, key, filters) }
            result.mangas.map { toSearchBook(handle, it) }
        } finally {
            if (acquired) searchSemaphore.release()
        }
    }

    override fun explore(sourceJson: String, kindUrl: String, page: Int): Result<List<SearchBook>> = runCatching {
        val handle = handle(sourceJson)
        val acquired = searchSemaphore.tryAcquire(30, TimeUnit.SECONDS)
        try {
            val source = handle.source
            val result = runBlocking(Dispatchers.IO) {
                if (kindUrl == "latest" && source.supportsLatest) {
                    source.getLatestUpdates(page)
                } else {
                    source.getPopularManga(page)
                }
            }
            result.mangas.map { toSearchBook(handle, it) }
        } finally {
            if (acquired) searchSemaphore.release()
        }
    }

    /**
     * 取扩展的筛选项描述，转成 UI 可读的纯 JSON 数组串（不暴露扩展 classloader 里的 Filter 类型）。
     * 每项：{key:索引字符串, name, type:header|separator|checkbox|select|text|sort|tristate,
     *        values:[String], state:<当前值>, ascending:Boolean（仅 sort）}
     * Group 暂当只读分组头处理（不展开子项），避免 UI 读不懂泛型子过滤器。
     */
    fun exploreFilters(sourceJson: String): Result<String> = runCatching {
        val handle = handle(sourceJson)
        val list = runCatching { handle.source.getFilterList() }.getOrElse { FilterList(emptyList()) }
        val arr = JSONArray()
        list.list.forEachIndexed { index, f ->
            val o = JSONObject().put("key", index.toString()).put("name", f.name)
            when (f) {
                is Filter.Header -> o.put("type", "header").put("state", 0)
                is Filter.Separator -> o.put("type", "separator").put("state", 0)
                is Filter.Select<*> -> o.put("type", "select")
                    .put("values", JSONArray(f.values.map { it.toString() }))
                    .put("state", f.state)
                is Filter.Text -> o.put("type", "text").put("state", f.state)
                is Filter.CheckBox -> o.put("type", "checkbox").put("state", f.state)
                is Filter.TriState -> o.put("type", "tristate").put("state", f.state)
                is Filter.Sort -> {
                    val sel = f.state
                    o.put("type", "sort")
                        .put("values", JSONArray(f.values.toList()))
                        .put("state", sel?.index ?: -1)
                        .put("ascending", sel?.ascending ?: true)
                }
                else -> o.put("type", "header").put("state", 0) // Group 等：只读分组头
            }
            arr.put(o)
        }
        arr.toString()
    }

    /**
     * 带筛选浏览：UI 把 [exploreFilters] 的数组改完 state 后原样传回，
     * 这里从扩展重新取一份 FilterList（Filter 必须来自扩展 classloader），按 key 把 state 写回，
     * 再调 getSearchManga(page, "", filters)。未知 key / 非法值安全跳过。
     */
    fun exploreWithFilters(sourceJson: String, filtersJson: String, page: Int): Result<List<SearchBook>> = runCatching {
        val handle = handle(sourceJson)
        val source = handle.source
        val filters = runCatching { source.getFilterList() }.getOrElse { FilterList(emptyList()) }
        val arr = runCatching { JSONArray(filtersJson) }.getOrElse { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optString("key").toIntOrNull() ?: continue
            if (idx !in filters.list.indices) continue
            when (val f = filters.list[idx]) {
                is Filter.Select<*> -> f.state = o.optInt("state", f.state)
                is Filter.Text -> f.state = o.optString("state", f.state)
                is Filter.CheckBox -> f.state = o.optBoolean("state", f.state)
                is Filter.TriState -> f.state = o.optInt("state", f.state)
                is Filter.Sort -> {
                    val si = o.optInt("state", -1)
                    f.state = if (si >= 0 && si < f.values.size) {
                        Filter.Sort.Selection(si, o.optBoolean("ascending", true))
                    } else null
                }
                else -> Unit
            }
        }
        val acquired = searchSemaphore.tryAcquire(30, TimeUnit.SECONDS)
        try {
            val result = runBlocking(Dispatchers.IO) { source.getSearchManga(page, "", filters) }
            result.mangas.map { toSearchBook(handle, it) }
        } finally {
            if (acquired) searchSemaphore.release()
        }
    }

    override fun detail(sourceJson: String, bookUrl: String): Result<BookDetail> = runCatching {
        val handle = handle(sourceJson)
        val source = handle.source
        val relativeUrl = toRelativeManga(handle, bookUrl)
        Log.d("MihonSourceProvider", "detail in=$bookUrl rel=$relativeUrl")
        val manga = SManga.create().apply { url = relativeUrl }
        val update = runBlocking(Dispatchers.IO) { source.getMangaUpdate(manga, emptyList(), true, false) }
        val m = update.manga
        BookDetail(
            name = m.title,
            author = m.author.orEmpty(),
            kind = m.genre.orEmpty(),
            lastChapter = "",
            intro = m.description.orEmpty(),
            coverUrl = absolutize(handle, m.thumbnail_url),
            tocUrl = bookUrl,
        ).also { if (it.name.isBlank()) error("详情为空") }
    }.onFailure { Log.w("MihonSourceProvider", "detail failed url=$bookUrl", it) }

    override fun toc(sourceJson: String, tocUrl: String): Result<List<SourceChapter>> = runCatching {
        val handle = handle(sourceJson)
        val source = handle.source
        val relativeUrl = toRelativeManga(handle, tocUrl)
        Log.d("MihonSourceProvider", "toc in=$tocUrl rel=$relativeUrl")
        val manga = SManga.create().apply { url = relativeUrl }
        // 先带详情一起取目录：部分扩展的 getChapterList 依赖 manga 的 title/author 等字段，
        // 只给 URL 会返回空列表（Kototoro 等第三方客户端也是先补齐详情再取章节）。
        val update = runBlocking(Dispatchers.IO) {
            runCatching { source.getMangaUpdate(manga, emptyList(), true, true) }
                .recoverCatching { source.getMangaUpdate(manga, emptyList(), false, true) }
                .getOrThrow()
        }
        update.chapters.map { chapter ->
            // 章节 url 必须保持扩展内部的相对形式。getChapterUrl() 会再拼一次 baseUrl/路径，
            // 存绝对地址会在 content() 里重复拼接（和旧版 Mihon 详情/目录失败同因）。
            SourceChapter(
                title = chapter.name.ifBlank { chapter.url },
                url = toRelativeChapter(handle, chapter.url),
            )
        }
    }.onFailure { Log.w("MihonSourceProvider", "toc failed url=$tocUrl", it) }

    private val imagePool = Executors.newFixedThreadPool(4)

    override fun content(sourceJson: String, chapterUrl: String): Result<ChapterContent> = runCatching {
        val handle = handle(sourceJson)
        val source = handle.source
        val chapter = SChapter.create().apply { url = toRelativeChapter(handle, chapterUrl) }
        val pages = runBlocking(Dispatchers.IO) { source.getPageList(chapter) }
        // 多数扩展在 getPageList 里已填好 imageUrl；没有的（如 Dm5）才按页调 getImageUrl 解析。
        // 用线程池并行解析，单页 6 秒超时，避免某个页面卡死拖住整章。
        val futures = pages.map { page ->
            val direct = page.imageUrl?.takeIf { it.isNotBlank() }
            page to (if (direct != null) null else imagePool.submit<String?> {
                runCatching { runBlocking(Dispatchers.IO) { (source as? HttpSource)?.getImageUrl(page) } }.getOrNull()
            })
        }
        val images = futures.mapNotNull { (page, pending) ->
            val resolved = if (pending == null) page.imageUrl?.takeIf { it.isNotBlank() }
            else runCatching { pending.get(6, TimeUnit.SECONDS) }.getOrNull()
            resolved?.takeIf { it.isNotBlank() }
                ?: page.url.takeIf { it.isNotBlank() }
        }.filter { it.isNotBlank() }
        if (images.isNotEmpty()) Log.d("MihonSourceProvider", "content firstImage=${images.first()} count=${images.size}")
        ChapterContent(title = chapter.name, images = images)
    }

    private fun handle(sourceJson: String): MihonSourceHandle {
        val o = JSONObject(sourceJson)
        val pkg = o.optString("packageName")
        if (pkg.isBlank()) error("Mihon 源缺少 packageName")
        val sourceId = o.optLong("sourceId", 0L).takeIf { it != 0L }
            ?: o.optString("sourceId").toLongOrNull()
            ?: error("Mihon 源缺少 sourceId")
        return MihonExtensionManager.find(pkg, sourceId)
            ?: error("Mihon 扩展未加载：$pkg")
    }

    /**
     * 扩展内部存的是相对路径，getMangaUrl/getChapterUrl 会拼 baseUrl。
     * 旧版本把 getMangaUrl() 的绝对地址存进了数据库；这里反推回扩展原始相对路径，
     * 否则再次调用 getMangaUrl() 会得到 /manga//manga/... 这种重复路径。
     */
    private fun toRelativeManga(handle: MihonSourceHandle, url: String): String {
        if (url.isBlank()) return url
        // 搜索/目录返回的本来就是扩展内部相对路径，必须原样保留：
        // 不同扩展的 getMangaUrl 对前导/结尾斜杠的约定不同（漫画柜要保留 /comic/1128/ 的完整路径）。
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        val source = handle.source as? HttpSource ?: return url
        val base = source.baseUrl.trimEnd('/')
        val path = url.removePrefix(base)
        for (candidate in candidatePaths(path)) {
            val probe = SManga.create().apply { this.url = candidate }
            val rebuilt = runCatching { source.getMangaUrl(probe) }.getOrNull()
            if (sameUrl(rebuilt, url)) return candidate
        }
        return path
    }

    private fun toRelativeChapter(handle: MihonSourceHandle, url: String): String {
        if (url.isBlank()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        val source = handle.source as? HttpSource ?: return url
        val base = source.baseUrl.trimEnd('/')
        val path = url.removePrefix(base)
        for (candidate in candidatePaths(path)) {
            val probe = SChapter.create().apply { this.url = candidate }
            val rebuilt = runCatching { source.getChapterUrl(probe) }.getOrNull()
            if (sameUrl(rebuilt, url)) return candidate
        }
        return path
    }

    /** 依次尝试去掉路径前缀，兼容扩展把 /manga/ 等路由写进 getMangaUrl/getChapterUrl 的情况。 */
    private fun candidatePaths(path: String): List<String> {
        val out = ArrayList<String>()
        var p = path
        while (true) {
            out.add(p)
            val slash = p.indexOf('/')
            if (slash < 0) break
            p = p.substring(slash + 1)
        }
        return out
    }

    private fun sameUrl(a: String?, b: String): Boolean {
        if (a.isNullOrBlank()) return false
        val ua = runCatching { java.net.URI(a) }.getOrNull()
        val ub = runCatching { java.net.URI(b) }.getOrNull()
        if (ua != null && ub != null && ua.path != null && ub.path != null) {
            return ua.path.trimEnd('/') == ub.path.trimEnd('/') && ua.query == ub.query
        }
        return a.substringBefore('#').trimEnd('/') == b.substringBefore('#').trimEnd('/')
    }

    private fun absolutize(handle: MihonSourceHandle, url: String?): String {
        if (url.isNullOrBlank()) return ""
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        val base = (handle.source as? HttpSource)?.baseUrl?.trimEnd('/') ?: return url
        return "$base/${url.trimStart('/')}"
    }

    private fun toSearchBook(handle: MihonSourceHandle, manga: SManga): SearchBook {
        // 存扩展内部相对路径，绝不存 getMangaUrl() 的绝对地址：
        // 详情/目录/正文都会再经过 getMangaUrl/getChapterUrl，绝对地址会重复拼路径。
        val bookUrl = toRelativeManga(handle, manga.url)
        Log.d("MihonSourceProvider", "search manga url=${manga.url} -> $bookUrl")
        return SearchBook(
            name = manga.title,
            author = manga.author.orEmpty(),
            kind = manga.genre.orEmpty(),
            lastChapter = "",
            intro = manga.description.orEmpty(),
            coverUrl = absolutize(handle, manga.thumbnail_url),
            bookUrl = bookUrl,
            sourceName = handle.source.name,
            sourceUrl = (handle.source as? HttpSource)?.baseUrl ?: "",
        )
    }
}
