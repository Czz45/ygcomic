package com.yg.comic.data

import android.content.Context
import com.yg.comic.engine.ComicEngine
import com.yg.comic.engine.Candidate
import com.yg.comic.engine.EngineSource
import com.yg.comic.engine.KotlinComicEngine
import com.yg.comic.model.*
import com.yg.comic.shared.PurifyRule
import com.yg.comic.shared.PurifyTarget
import com.yg.comic.shared.SearchRules
import com.yg.comic.shared.TextPurifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class BookRepository(context: Context) {
    private val db = AppDatabase.get(context)
    private val ctx = context.applicationContext
    /** 供阅读器实时打开 PDF 原文件（content:// Uri）用 */
    val appContext: Context get() = ctx
    val engine: ComicEngine = KotlinComicEngine(ctx)

    val books: Flow<List<Book>> = db.bookDao().allBooks().map { list -> list.map { it.toModel() } }
    fun booksIn(collectionId: Long?): Flow<List<Book>> =
        db.bookDao().booksInCollection(collectionId).map { it.map { e -> e.toModel() } }

    fun children(parentId: Long?): Flow<List<ComicCollection>> =
        db.collectionDao().children(parentId).map { it.map { e -> ComicCollection(e.id, e.name, e.parentId, e.sourceUri, e.orderIndex) } }

    suspend fun allCollections(): List<ComicCollection> = withContext(Dispatchers.IO) {
        db.collectionDao().all().map { ComicCollection(it.id, it.name, it.parentId, it.sourceUri, it.orderIndex) }
    }

    val sourceFolders: Flow<List<SourceFolder>> =
        db.sourceFolderDao().all().map { it.map { SourceFolder(it.id, it.uri, it.displayName) } }

    fun progress(bookId: Long): Flow<ReadProgress?> = db.progressDao().progress(bookId).map { it?.toModel() }

    /** 全部进度，供书架书名显示阅读进度 */
    val allProgress: Flow<Map<Long, ReadProgress>> = db.progressDao().all().map { list ->
        list.associate { it.bookId to it.toModel() }
    }

    /** 网络书 id 集合，供书架显示"网"角标 */
    val networkBookIds: Flow<Set<Long>> = db.networkBookDao().all().map { list ->
        list.map { it.bookId }.toSet()
    }

    /** 所有漫画（跨合集），供阅读器上一本/下一本 */
    val allComicBooks: Flow<List<Book>> = books.map { list -> list.filter { it.mediaType == MediaType.COMIC } }

    suspend fun addSourceFolder(uri: String, name: String) = withContext(Dispatchers.IO) {
        db.sourceFolderDao().insert(SourceFolderEntity(uri = uri, displayName = name))
    }

    suspend fun removeSourceFolder(id: Long) = withContext(Dispatchers.IO) {
        db.sourceFolderDao().delete(id)
    }

    suspend fun ensureCollection(name: String, parentId: Long?, sourceUri: String): Long = withContext(Dispatchers.IO) {
        val existing = db.collectionDao().all().find { it.name == name && it.parentId == parentId }
        existing?.id ?: db.collectionDao().upsert(
            CollectionEntity(name = name, parentId = parentId, sourceUri = sourceUri)
        )
    }

    suspend fun renameCollection(id: Long, name: String) = withContext(Dispatchers.IO) {
        db.collectionDao().rename(id, name)
    }

    /** 删除合集（文件夹）：书和子合集移到顶层，不删本地文件、不删书 */
    suspend fun deleteCollection(id: Long) = withContext(Dispatchers.IO) {
        db.bookDao().moveBooksToRoot(id)
        db.collectionDao().reparentChildrenToRoot(id)
        db.collectionDao().delete(id)
    }

    suspend fun saveBook(book: Book, fragments: List<Fragment>, collectionId: Long?): Long = withContext(Dispatchers.IO) {
        val id = db.bookDao().upsert(
            BookEntity(
                id = book.id, title = book.title, coverPath = book.coverPath,
                mediaType = book.mediaType.name, sourceLabel = book.sourceLabel,
                favorite = book.favorite, sortField = book.sortField,
                createdAt = if (book.createdAt == 0L) System.currentTimeMillis() else book.createdAt,
                updatedAt = System.currentTimeMillis(), pageCount = book.pageCount,
                collectionId = collectionId,
            )
        )
        // 片段先清理再重建（合并/拆分走同一入口）
        db.fragmentDao().deleteOfBook(id)
        db.fragmentDao().insertAll(fragments.map {
            FragmentEntity(
                bookId = id, kind = it.kind.name, sourcePath = it.sourcePath,
                entryPrefix = it.entryPrefix, orderIndex = it.orderIndex,
                pageCount = it.pageCount, passwordAlias = it.passwordAlias,
            )
        })
        id
    }

    suspend fun fragments(bookId: Long): List<Fragment> = withContext(Dispatchers.IO) {
        db.fragmentDao().fragments(bookId).map { it.toModel() }
    }

    suspend fun setFavorite(id: Long, fav: Boolean) = withContext(Dispatchers.IO) {
        db.bookDao().setFavorite(id, fav)
    }

    suspend fun mergeBooks(sourceIds: List<Long>, targetTitle: String): Long = withContext(Dispatchers.IO) {
        val allFragments = mutableListOf<Fragment>()
        var order = 0
        for (sid in sourceIds) {
            db.fragmentDao().fragments(sid).forEach { allFragments += it.toModel().copy(orderIndex = order++) }
        }
        val cover = allFragments.firstOrNull()?.sourcePath
        val newId = saveBook(
            Book(title = targetTitle, coverPath = cover, pageCount = allFragments.sumOf { it.pageCount }),
            allFragments,
            null,
        )
        sourceIds.forEach { db.bookDao().deleteBook(it) }
        newId
    }

    suspend fun deleteBook(id: Long) = withContext(Dispatchers.IO) {
        db.bookDao().deleteBook(id)
        db.fragmentDao().deleteOfBook(id)
        db.networkBookDao().delete(id)
    }

    suspend fun saveProgress(p: ReadProgress) = withContext(Dispatchers.IO) {
        db.progressDao().save(
            ProgressEntity(
                p.bookId, p.pageIndex, p.chapterOffset, p.mediaTimeMs, p.extra, System.currentTimeMillis()
            )
        )
    }

    /** 本地库根目录：只在这里读写，绝不删用户原始导入文件 */
    fun libraryDir(): File = File(ctx.filesDir, "library").apply { mkdirs() }

    fun releaseEngine() = engine.releaseIdle()

    // ---------------- 网络书源 ----------------

    /** 已安装书源列表（附带原始 JSON） */
    val bookSources: Flow<List<BookSourceEntity>> = db.bookSourceDao().all()

    suspend fun addBookSource(name: String, url: String, json: String): Long = withContext(Dispatchers.IO) {
        db.bookSourceDao().insert(
            BookSourceEntity(name = name, url = url, json = json, enabled = true, createdAt = System.currentTimeMillis())
        )
    }

    /** 批量导入书源：单事务一次入库，避免逐条 insert + reload 整表的 O(n²) 卡顿。 */
    suspend fun addBookSources(entities: List<BookSourceEntity>) = withContext(Dispatchers.IO) {
        db.bookSourceDao().insertAll(entities)
    }

    suspend fun setBookSourceEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        db.bookSourceDao().setEnabled(id, enabled)
    }

    suspend fun setBookSourcesEnabled(ids: List<Long>, enabled: Boolean) = withContext(Dispatchers.IO) {
        db.bookSourceDao().setEnabledForIds(ids, enabled)
    }

    suspend fun renameSubscribe(ids: List<Long>, name: String) = withContext(Dispatchers.IO) {
        db.bookSourceDao().setSubscribeNameForIds(ids, name)
    }

    /**
     * 一次性迁移：旧版导入异次元源时用 nameOverride 把 110 条源名全改成"异次元 (N)"，
     * 盖掉了真实网站名。这里把这些名字按各自 json 里的 bookSourceName 改回真名。
     * 只认"异次元 (数字)"这个确切形态且来自内置订阅的源，不碰用户自己改过的名字或其它订阅。
     * 用 SharedPreferences 标记仅执行一次；改完 sources Flow 会自动刷新，详情页/阅读器 tab 跟着更新。
     */
    suspend fun migrateYiciyuanSourceNames() = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("ygcomic_migrations", Context.MODE_PRIVATE)
        val key = "yiciyuan_display_name_v1"
        if (prefs.getBoolean(key, false)) return@withContext
        val legacyName = Regex("^异次元\\s*\\(\\d+\\)$")
        val pairs = db.bookSourceDao().all().first()
            .filter { legacyName.matches(it.name) && it.subscribeUrl == "builtin://yiciyuan" }
            .mapNotNull { e ->
                val real = runCatching { org.json.JSONObject(e.json).optString("bookSourceName") }.getOrDefault("")
                if (real.isBlank()) null else e.id to real
            }
        if (pairs.isNotEmpty()) db.bookSourceDao().setNamesByIds(pairs)
        prefs.edit().putBoolean(key, true).apply()
    }

    suspend fun removeBookSource(id: Long) = withContext(Dispatchers.IO) {
        db.bookSourceDao().delete(id)
    }

    suspend fun removeBookSources(ids: List<Long>) = withContext(Dispatchers.IO) {
        db.bookSourceDao().deleteAll(ids)
    }

    // ---- 屏蔽池 / 替换规则 ----

    /** 启用中的替换规则（模型层），供搜索/详情/正文净化使用 */
    val replaceRules: Flow<List<ReplaceRule>> = db.replaceRuleDao().all().map { list ->
        list.filter { it.isEnabled }.map { it.toModel() }
    }

    /** 全部替换规则（含禁用），供管理 UI 使用 */
    val allReplaceRules: Flow<List<ReplaceRule>> = db.replaceRuleDao().all().map { list -> list.map { it.toModel() } }

    suspend fun addReplaceRule(rule: ReplaceRule): Long = withContext(Dispatchers.IO) {
        db.replaceRuleDao().insert(rule.toEntity())
    }

    suspend fun addReplaceRules(rules: List<ReplaceRule>) = withContext(Dispatchers.IO) {
        db.replaceRuleDao().insertAll(rules.map { it.toEntity() })
    }

    suspend fun setReplaceRuleEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        db.replaceRuleDao().setEnabled(id, enabled)
    }

    suspend fun removeReplaceRule(id: Long) = withContext(Dispatchers.IO) {
        db.replaceRuleDao().delete(id)
    }

    suspend fun removeReplaceRules(ids: List<Long>) = withContext(Dispatchers.IO) {
        db.replaceRuleDao().deleteAll(ids)
    }

    // ---- 书签（字锚点） ----

    fun bookmarks(bookId: Long): Flow<List<com.yg.comic.data.BookmarkEntity>> = db.bookmarkDao().of(bookId)

    suspend fun addBookmark(
        bookId: Long, chapterIndex: Int, chapterTitle: String, charOffset: Int, snippet: String,
    ): Long = withContext(Dispatchers.IO) {
        db.bookmarkDao().insert(
            com.yg.comic.data.BookmarkEntity(
                bookId = bookId, chapterIndex = chapterIndex, chapterTitle = chapterTitle,
                charOffset = charOffset, snippet = snippet, createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun removeBookmark(id: Long) = withContext(Dispatchers.IO) {
        db.bookmarkDao().delete(id)
    }

    /** 首次启动时灌入内置默认屏蔽池（库为空才灌，不覆盖用户自定义） */
    suspend fun ensureDefaultReplaceRules() = withContext(Dispatchers.IO) {
        if (db.replaceRuleDao().all().firstOrNull().isNullOrEmpty()) {
            db.replaceRuleDao().insertAll(DefaultReplaceRules.list.map { it.toEntity() })
        }
    }

    // ---- 文本净化（屏蔽池应用） ----

    private fun ReplaceRule.toPurifyRule() = PurifyRule(
        id, name, group, pattern, replacement, scope, scopeTitle, scopeContent,
        excludeScope, isEnabled, isRegex, timeoutMillisecond, order,
    )

    /** 用当前启用规则净化正文（含 URL/标签/章节号保护），scopeKey 传来源 URL 供作用域匹配 */
    suspend fun purifyContent(text: String, scopeKey: String? = null): String {
        if (text.isEmpty()) return text
        val rules = replaceRules.firstOrNull().orEmpty().map { it.toPurifyRule() }
        if (rules.isEmpty()) return text
        return TextPurifier.purify(text, rules, PurifyTarget.CONTENT, scopeKey)
    }

    /** 用当前启用规则净化标题/作者/简介 */
    suspend fun purifyTitle(text: String, scopeKey: String? = null): String {
        if (text.isEmpty()) return text
        val rules = replaceRules.firstOrNull().orEmpty().map { it.toPurifyRule() }
        if (rules.isEmpty()) return text
        return TextPurifier.purify(text, rules, PurifyTarget.TITLE, scopeKey)
    }

    /** 网络书元数据（书源 JSON + 书页地址），非网络书返回 null */
    suspend fun networkBook(bookId: Long): NetworkBookEntity? = withContext(Dispatchers.IO) {
        db.networkBookDao().of(bookId)
    }

    /**
     * 把书源里搜到的一本书加入书架：建一条 NOVEL 书 + network_books 关联。
     * 返回新书 id。
     */
    suspend fun importNetworkBook(
        sourceId: Long,
        sourceJson: String,
        sourceName: String,
        title: String,
        author: String,
        bookUrl: String,
        tocUrl: String,
        coverUrl: String,
        originsJson: String = "",
        categoryIndex: Int = 0,
        mediaType: MediaType = MediaType.NOVEL,
    ): Long = withContext(Dispatchers.IO) {
        // 聚合的多源书 / 多次点加入：按归一书名（shelfKey）查已存在的网络书，命中则复用 id，
        // 避免同一本书在书架里重复出现。本地导入的 TXT/漫画书不参与合并。
        // mediaType 按书源类型判定：文字源→NOVEL，漫画源(sourceType==2)→COMIC。
        val typeName = mediaType.name
        val existing = db.bookDao().allBooks().first().firstOrNull {
            it.mediaType == typeName && SearchRules.shelfKey(it.title) == SearchRules.shelfKey(title)
        }
        val id = if (existing != null) {
            existing.id
        } else {
            db.bookDao().upsert(
                BookEntity(
                    // 网络书封面用书源返回的 http URL；Coil 的 AsyncImage 同时吃本地路径和 URL，
                    // 直接存进 coverPath 即可，无需给 books 表加列或做迁移。
                    title = title, coverPath = coverUrl.ifBlank { null }, mediaType = typeName,
                    sourceLabel = sourceName, favorite = false, sortField = 0,
                    createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                    pageCount = 0, collectionId = null,
                )
            )
        }
        db.networkBookDao().upsert(
            NetworkBookEntity(
                bookId = id, sourceId = sourceId, sourceJson = sourceJson,
                bookUrl = bookUrl, tocUrl = tocUrl, sourceName = sourceName,
                originsJson = originsJson, categoryIndex = categoryIndex,
            )
        )
        id
    }

    // ---- 网络书目录缓存（详情页秒显 + 后台补全） ----

    /** 目录缓存 key：书源 JSON + 详情页 URL，唯一标识某源某本书的目录 */
    fun chapterCacheKey(sourceJson: String, bookUrl: String): String = "$sourceJson|$bookUrl"

    /** 读本地缓存目录（可能为空） */
    suspend fun chaptersFor(cacheKey: String): List<ChapterEntity> = withContext(Dispatchers.IO) {
        db.chapterDao().of(cacheKey)
    }

    // ---- 书源内容质量标记（空正文源降权，仅内存外轻量持久化，不动 DB 结构） ----
    private val qualityFlags by lazy {
        appContext.getSharedPreferences("source_quality_flags", android.content.Context.MODE_PRIVATE)
    }
    private fun qualityKey(sourceJson: String, bookUrl: String) = "$sourceJson|$bookUrl"

    /** 标记某源的某书抓到的正文为空（内容质量差，排序降权） */
    fun markEmptyContent(sourceJson: String, bookUrl: String) {
        qualityFlags.edit().putBoolean(qualityKey(sourceJson, bookUrl), true).apply()
    }

    /** 正文恢复正常时清除空正文标记 */
    fun clearEmptyContent(sourceJson: String, bookUrl: String) {
        qualityFlags.edit().remove(qualityKey(sourceJson, bookUrl)).apply()
    }

    /** 该源的某书是否曾被标记为空正文 */
    fun hasEmptyContent(sourceJson: String, bookUrl: String): Boolean =
        qualityFlags.getBoolean(qualityKey(sourceJson, bookUrl), false)

    /** 后台拉到目录后落库（先删后插，整组替换） */
    suspend fun saveChapters(cacheKey: String, chapters: List<ChapterEntity>) = withContext(Dispatchers.IO) {
        db.chapterDao().replaceAll(cacheKey, chapters)
    }

    suspend fun clearChapters(cacheKey: String) = withContext(Dispatchers.IO) {
        db.chapterDao().clear(cacheKey)
    }

    // ---- 网络书正文缓存（第二次进入免重新加载） ----

    /** 读单章正文缓存，未命中返回 null */
    suspend fun cachedChapterContent(cacheKey: String, chapterUrl: String): String? = withContext(Dispatchers.IO) {
        db.chapterContentDao().of(cacheKey, chapterUrl)
    }

    /** 读某本书全部已缓存正文：chapterUrl -> text */
    suspend fun allCachedChapterContents(cacheKey: String): Map<String, String> = withContext(Dispatchers.IO) {
        db.chapterContentDao().allOf(cacheKey)
    }

    /** 单章正文落库 */
    suspend fun saveChapterContent(cacheKey: String, chapterUrl: String, text: String) = withContext(Dispatchers.IO) {
        db.chapterContentDao().save(ChapterContentEntity(cacheKey, chapterUrl, text, System.currentTimeMillis()))
    }

    /** 批量正文落库 */
    suspend fun saveChapterContents(cacheKey: String, items: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        db.chapterContentDao().saveAll(cacheKey, items)
    }

    suspend fun clearChapterContents(cacheKey: String) = withContext(Dispatchers.IO) {
        db.chapterContentDao().clear(cacheKey)
    }

    // ---- 书源智能排序（用得多/命中率高优先搜） ----

    /** 记录一次搜索使用了该源 */
    suspend fun recordSourceUse(sourceId: Long) = withContext(Dispatchers.IO) {
        db.bookSourceDao().recordUse(sourceId)
    }

    /** 记录该源搜到了有效结果 */
    suspend fun recordSourceHit(sourceId: Long) = withContext(Dispatchers.IO) {
        db.bookSourceDao().recordHit(sourceId)
    }

    /** 批量记录一次搜索的统计（usedIds=本次参与搜索的源，hitIds=搜到结果的源），一次事务一次 reload */
    suspend fun recordSearchStats(usedIds: List<Long>, hitIds: List<Long>) = withContext(Dispatchers.IO) {
        db.bookSourceDao().recordSearchStats(usedIds, hitIds)
    }

    /** 搜索过程中逐源实时写权重（useCount 必增、命中再增 hitCount），就地刷新内存不整表 reload */
    suspend fun recordStatLive(sourceId: Long, hit: Boolean) = withContext(Dispatchers.IO) {
        db.bookSourceDao().recordStatLive(sourceId, hit)
    }
}
