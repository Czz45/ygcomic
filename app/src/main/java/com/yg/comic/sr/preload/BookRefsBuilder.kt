package com.yg.comic.sr.preload

import com.yg.comic.data.BookRepository
import com.yg.comic.engine.PdfLive
import com.yg.comic.model.FragmentKind
import com.yg.comic.source.SourceProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 按 bookId 构造整本书的页引用，供书架长按「预超分」一次入队。
 * 逻辑与 ReaderViewModel 打开同一本书时生成 pageRefs 严格一致，
 * 否则写入侧的 key 与阅读侧读到的 key 对不上，整批白落盘。
 */
object BookRefsBuilder {
    suspend fun build(bookId: Long, repo: BookRepository): List<SrPageRef> = withContext(Dispatchers.IO) {
        val net = repo.networkBook(bookId)
        if (net != null && net.sourceJson.isNotBlank()) {
            buildNetwork(bookId, net.sourceJson, net.tocUrl)
        } else {
            buildLocal(bookId, repo)
        }
    }

    private fun buildNetwork(bookId: Long, sourceJson: String, tocUrl: String): List<SrPageRef> {
        val provider = SourceProviderRegistry.default
        val referer = deriveReferer(sourceJson, tocUrl)
        val chapters = provider.toc(sourceJson, tocUrl).getOrNull().orEmpty()
        val imagesByChapter = chapters.map { ch ->
            provider.content(sourceJson, ch.url).getOrNull()?.images
                .orEmpty().filter { it.isNotBlank() }
        }
        return buildNetworkRefs(bookId, referer, imagesByChapter)
    }

    private suspend fun buildLocal(bookId: Long, repo: BookRepository): List<SrPageRef> {
        val frags = repo.fragments(bookId)
        val pdf = frags.firstOrNull { it.kind == FragmentKind.PDF }
        if (pdf != null) {
            val live = PdfLive.pageCount(repo.appContext, pdf.sourcePath)
            val count = (if (live > 0) live else pdf.pageCount).coerceAtLeast(1)
            return (0 until count).map { SrPageRef.Pdf(bookId, 0, it, pdf.sourcePath) }
        }
        val paths = mutableListOf<String>()
        for (f in frags) {
            var dir = File(f.sourcePath)
            if (!dir.isDirectory) dir = repo.libraryDir().resolve("book_${bookId}_f${f.id}")
            if (!dir.isDirectory) continue
            dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { paths += it.absolutePath }
        }
        return paths.mapIndexed { i, p -> SrPageRef.Local(bookId, 0, i, p) }
    }

    /** 与 ReaderViewModel.deriveReferer 同款，写入/读取两侧必须同值才能命中同一缓存。 */
    private fun deriveReferer(sourceJson: String, tocUrl: String): String = runCatching {
        val src = runCatching {
            org.json.JSONObject(sourceJson).optString("bookSourceUrl", "")
        }.getOrDefault("")
        val uri = java.net.URI.create(src.ifBlank { tocUrl })
        val scheme = uri.scheme
        val host = uri.host
        if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) "$scheme://$host/" else ""
    }.getOrDefault("")
}
