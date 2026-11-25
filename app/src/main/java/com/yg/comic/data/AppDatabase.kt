package com.yg.comic.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.yg.comic.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// 纯 Android 手写 SQLite。内存 StateFlow 为唯一读取源，写操作同步落库后刷新对应表。
// 这样 UI 读全在内存（利于 120fps），跨进程重启从库重建，且完全不依赖 KSP/Room。

data class BookEntity(
    val id: Long = 0,
    val title: String,
    val coverPath: String? = null,
    val mediaType: String = "COMIC",
    val sourceLabel: String = "",
    val favorite: Boolean = false,
    val sortField: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val pageCount: Int = 0,
    val collectionId: Long? = null,
    val customTags: String = "",
)

data class FragmentEntity(
    val id: Long = 0,
    val bookId: Long,
    val kind: String,
    val sourcePath: String,
    val entryPrefix: String = "",
    val orderIndex: Int = 0,
    val pageCount: Int = 0,
    val passwordAlias: String? = null,
)

data class CollectionEntity(
    val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val sourceUri: String = "",
    val orderIndex: Int = 0,
)

data class ProgressEntity(
    val bookId: Long,
    val pageIndex: Int = 0,
    val chapterOffset: Int = 0,
    val mediaTimeMs: Long = 0L,
    val extra: String = "",
    val updatedAt: Long = 0L,
)

data class SourceFolderEntity(
    val id: Long = 0,
    val uri: String,
    val displayName: String,
)

/** 书源归属项目标记（字符串，不用枚举/sealed，方便后续接入更多软件的源）。 */
const val ORIGIN_LEGADO = "legado"
const val ORIGIN_YICIYUAN = "yiciyuan"
const val ORIGIN_MIHON = "mihon"

/** 由订阅链接派生项目标记；新增来源只要在此加一条字符串规则，不改表结构。 */
fun originTypeOf(subscribeUrl: String): String = when {
    subscribeUrl.startsWith("mihon://") -> ORIGIN_MIHON
    subscribeUrl == "builtin://yiciyuan" -> ORIGIN_YICIYUAN
    else -> ORIGIN_LEGADO
}

/** 已安装的网络书源（存 legado 书源 JSON 原文） */
data class BookSourceEntity(
    val id: Long = 0,
    val name: String,
    val url: String,
    val json: String,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    /** 智能排序：被搜索使用的次数（用得越多越靠前）。 */
    val useCount: Int = 0,
    /** 智能排序：搜到有效结果的次数（命中率越高越靠前）。 */
    val hitCount: Int = 0,
    /** 书源分组：legado bookSourceGroup，作者自定义逗号分隔标签（如"小说源,精校"）。 */
    val groupName: String = "",
    /** 书源类型：legado bookSourceType，0文字/1音频/2漫画/3下载/4视频。 */
    val sourceType: Int = 0,
    /** 导入该源的订阅链接（书源集合 URL），用于按订阅来源独立分组分类。 */
    val subscribeUrl: String = "",
    /** 订阅名称：导入订阅时用户填的名字（留空回退订阅域名），同一 subscribeUrl 下共享，首页显示它而非链接。 */
    val subscribeName: String = "",
    /** 归属项目标记（字符串）：legado/yiciyuan/mihon，可由更多来源扩展。空值入库时按 subscribeUrl 派生。 */
    val originType: String = "",
)

/** 网络书：本地 books 表的书 id → 对应书源 + 书页地址（详情页/目录页） */
data class NetworkBookEntity(
    val bookId: Long,
    val sourceId: Long,
    val sourceJson: String,
    val bookUrl: String,
    val tocUrl: String,
    val sourceName: String = "",
    /** 多源聚合：其余来源 JSON 数组（SourceOrigin 列表），主源仍是上面字段。 */
    val originsJson: String = "",
    /** 加入书架时所在的首页分类索引，便于后续按分类整理。 */
    val categoryIndex: Int = 0,
)

/** 网络书目录缓存：cacheKey=书源JSON|详情页URL，(cacheKey,orderIndex) 唯一定位一章。 */
data class ChapterEntity(
    val cacheKey: String,
    val url: String,
    val title: String,
    val isVip: Boolean,
    val isVolume: Boolean,
    val orderIndex: Int,
)

/** 网络书正文缓存：cacheKey=书源JSON|详情页URL，chapterUrl=章节地址。 */
data class ChapterContentEntity(
    val cacheKey: String,
    val chapterUrl: String,
    val text: String,
    val updatedAt: Long = 0,
)

/** 屏蔽池 / 替换规则（参照 legado ReplaceRule）。 */
data class ReplaceRuleEntity(
    val id: Long = 0,
    val name: String = "",
    val group: String = "",
    val pattern: String = "",
    val replacement: String = "",
    val scope: String = "",
    val scopeTitle: Boolean = false,
    val scopeContent: Boolean = true,
    val excludeScope: String = "",
    val isEnabled: Boolean = true,
    val isRegex: Boolean = true,
    val timeoutMillisecond: Long = 3000,
    val order: Int = 0,
)

/** 书签（字锚点：章序号 + 章内字符偏移，换字号/翻页后仍能定位）。 */
data class BookmarkEntity(
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val chapterTitle: String = "",
    val charOffset: Int = 0,
    val snippet: String = "",
    val createdAt: Long = 0,
)

private class DbOpenHelper(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "ygcomic.db", null, 12) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,coverPath TEXT,mediaType TEXT NOT NULL,sourceLabel TEXT NOT NULL,favorite INTEGER NOT NULL,sortField INTEGER NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,pageCount INTEGER NOT NULL,collectionId INTEGER,customTags TEXT NOT NULL)")
        db.execSQL("CREATE TABLE fragments(id INTEGER PRIMARY KEY AUTOINCREMENT,bookId INTEGER NOT NULL,kind TEXT NOT NULL,sourcePath TEXT NOT NULL,entryPrefix TEXT NOT NULL,orderIndex INTEGER NOT NULL,pageCount INTEGER NOT NULL,passwordAlias TEXT)")
        db.execSQL("CREATE INDEX idx_frag_book ON fragments(bookId,orderIndex)")
        db.execSQL("CREATE TABLE collections(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,parentId INTEGER,sourceUri TEXT NOT NULL,orderIndex INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_col_parent ON collections(parentId,orderIndex)")
        db.execSQL("CREATE TABLE progress(bookId INTEGER PRIMARY KEY,pageIndex INTEGER NOT NULL,chapterOffset INTEGER NOT NULL,mediaTimeMs INTEGER NOT NULL,extra TEXT NOT NULL,updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE source_folders(id INTEGER PRIMARY KEY AUTOINCREMENT,uri TEXT NOT NULL,displayName TEXT NOT NULL)")
        db.execSQL("CREATE TABLE book_sources(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,url TEXT NOT NULL,json TEXT NOT NULL,enabled INTEGER NOT NULL,createdAt INTEGER NOT NULL,useCount INTEGER NOT NULL DEFAULT 0,hitCount INTEGER NOT NULL DEFAULT 0,groupName TEXT NOT NULL DEFAULT '',sourceType INTEGER NOT NULL DEFAULT 0,subscribeUrl TEXT NOT NULL DEFAULT '',subscribeName TEXT NOT NULL DEFAULT '',originType TEXT NOT NULL DEFAULT 'legado')")
        db.execSQL("CREATE TABLE network_books(bookId INTEGER PRIMARY KEY,sourceId INTEGER NOT NULL,sourceJson TEXT NOT NULL,bookUrl TEXT NOT NULL,tocUrl TEXT NOT NULL,sourceName TEXT NOT NULL,originsJson TEXT NOT NULL DEFAULT '',categoryIndex INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE chapters(cacheKey TEXT NOT NULL,url TEXT NOT NULL,title TEXT NOT NULL,isVip INTEGER NOT NULL,isVolume INTEGER NOT NULL,orderIndex INTEGER NOT NULL,PRIMARY KEY(cacheKey,orderIndex))")
        db.execSQL("CREATE INDEX idx_chapter_key ON chapters(cacheKey,orderIndex)")
        db.execSQL("CREATE TABLE chapter_contents(cacheKey TEXT NOT NULL,chapterUrl TEXT NOT NULL,text TEXT NOT NULL,updatedAt INTEGER NOT NULL,PRIMARY KEY(cacheKey,chapterUrl))")
        db.execSQL("CREATE INDEX idx_content_key ON chapter_contents(cacheKey)")
        db.execSQL("CREATE TABLE replace_rules(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,groupName TEXT NOT NULL,pattern TEXT NOT NULL,replacement TEXT NOT NULL,scope TEXT NOT NULL DEFAULT '',scopeTitle INTEGER NOT NULL DEFAULT 0,scopeContent INTEGER NOT NULL DEFAULT 1,excludeScope TEXT NOT NULL DEFAULT '',isEnabled INTEGER NOT NULL DEFAULT 1,isRegex INTEGER NOT NULL DEFAULT 1,timeoutMillisecond INTEGER NOT NULL DEFAULT 3000,sortOrder INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT,bookId INTEGER NOT NULL,chapterIndex INTEGER NOT NULL,chapterTitle TEXT NOT NULL,charOffset INTEGER NOT NULL,snippet TEXT NOT NULL,createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_bookmark_book ON bookmarks(bookId,chapterIndex)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 增量迁移：绝不清用户已导入的 books/fragments/progress，只在需要时补建新表。
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS book_sources(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,url TEXT NOT NULL,json TEXT NOT NULL,enabled INTEGER NOT NULL,createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS network_books(bookId INTEGER PRIMARY KEY,sourceId INTEGER NOT NULL,sourceJson TEXT NOT NULL,bookUrl TEXT NOT NULL,tocUrl TEXT NOT NULL,sourceName TEXT NOT NULL,originsJson TEXT NOT NULL DEFAULT '')")
        }
        if (oldVersion < 3) {
            // 多源聚合：给已存在的 network_books 补列；旧库可能还没有这张表（v1 直接升 v3 时上面已带列建成）。
            runCatching { db.execSQL("ALTER TABLE network_books ADD COLUMN originsJson TEXT NOT NULL DEFAULT ''") }
        }
        if (oldVersion < 4) {
            // 目录落库：详情页目录缓存表。
            db.execSQL("CREATE TABLE IF NOT EXISTS chapters(cacheKey TEXT NOT NULL,url TEXT NOT NULL,title TEXT NOT NULL,isVip INTEGER NOT NULL,isVolume INTEGER NOT NULL,orderIndex INTEGER NOT NULL,PRIMARY KEY(cacheKey,orderIndex))")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_chapter_key ON chapters(cacheKey,orderIndex)")
        }
        if (oldVersion < 5) {
            // 正文落库 + 书源智能排序字段。
            db.execSQL("CREATE TABLE IF NOT EXISTS chapter_contents(cacheKey TEXT NOT NULL,chapterUrl TEXT NOT NULL,text TEXT NOT NULL,updatedAt INTEGER NOT NULL,PRIMARY KEY(cacheKey,chapterUrl))")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_content_key ON chapter_contents(cacheKey)")
            runCatching { db.execSQL("ALTER TABLE book_sources ADD COLUMN useCount INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE book_sources ADD COLUMN hitCount INTEGER NOT NULL DEFAULT 0") }
        }
        if (oldVersion < 6) {
            // 书源分类：legado bookSourceGroup(groupName) + bookSourceType(sourceType)。
            runCatching { db.execSQL("ALTER TABLE book_sources ADD COLUMN groupName TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE book_sources ADD COLUMN sourceType INTEGER NOT NULL DEFAULT 0") }
        }
        if (oldVersion < 7) {
            // 记住订阅链接：导入该源的订阅集合 URL，便于按订阅来源独立分组分类。
            runCatching { db.execSQL("ALTER TABLE book_sources ADD COLUMN subscribeUrl TEXT NOT NULL DEFAULT ''") }
        }
        if (oldVersion < 8) {
            // 加入书架时记住首页分类索引，便于后续按分类整理书架。
            runCatching { db.execSQL("ALTER TABLE network_books ADD COLUMN categoryIndex INTEGER NOT NULL DEFAULT 0") }
        }
        if (oldVersion < 9) {
            // 订阅名称：导入订阅时用户填的名字（同一 subscribeUrl 共享），首页显示名字不显示链接。
            runCatching { db.execSQL("ALTER TABLE book_sources ADD COLUMN subscribeName TEXT NOT NULL DEFAULT ''") }
        }
        if (oldVersion < 10) {
            // 屏蔽池 / 替换规则表。
            db.execSQL("CREATE TABLE IF NOT EXISTS replace_rules(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,groupName TEXT NOT NULL,pattern TEXT NOT NULL,replacement TEXT NOT NULL,scope TEXT NOT NULL DEFAULT '',scopeTitle INTEGER NOT NULL DEFAULT 0,scopeContent INTEGER NOT NULL DEFAULT 1,excludeScope TEXT NOT NULL DEFAULT '',isEnabled INTEGER NOT NULL DEFAULT 1,isRegex INTEGER NOT NULL DEFAULT 1,timeoutMillisecond INTEGER NOT NULL DEFAULT 3000,sortOrder INTEGER NOT NULL DEFAULT 0)")
        }
        if (oldVersion < 11) {
            // 书签表：字锚点（章序号 + 章内字符偏移）。
            db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT,bookId INTEGER NOT NULL,chapterIndex INTEGER NOT NULL,chapterTitle TEXT NOT NULL,charOffset INTEGER NOT NULL,snippet TEXT NOT NULL,createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmark_book ON bookmarks(bookId,chapterIndex)")
        }
        if (oldVersion < 12) {
            // 书源归属项目标记：先加列（默认 legado 覆盖所有旧行），再按订阅链接回填 Mihon / 异次元。
            // ADD COLUMN 与 UPDATE 都只动新增列，原有行和原有列一条不丢。
            // 回填口径与 originTypeOf() 保持一致：mihon:// 前缀→mihon，builtin://yiciyuan→yiciyuan，其余→legado。
            runCatching {
                db.execSQL("ALTER TABLE book_sources ADD COLUMN originType TEXT NOT NULL DEFAULT '$ORIGIN_LEGADO'")
                db.execSQL("UPDATE book_sources SET originType='$ORIGIN_MIHON' WHERE subscribeUrl LIKE 'mihon://%'")
                db.execSQL("UPDATE book_sources SET originType='$ORIGIN_YICIYUAN' WHERE subscribeUrl='builtin://yiciyuan'")
            }
        }
    }
}

class AppDatabase private constructor(context: Context) {
    private val helper = DbOpenHelper(context)

    private val booksS = MutableStateFlow<List<BookEntity>>(emptyList())
    private val fragmentsS = MutableStateFlow<List<FragmentEntity>>(emptyList())
    private val collectionsS = MutableStateFlow<List<CollectionEntity>>(emptyList())
    private val progressS = MutableStateFlow<List<ProgressEntity>>(emptyList())
    private val foldersS = MutableStateFlow<List<SourceFolderEntity>>(emptyList())
    private val bookSourcesS = MutableStateFlow<List<BookSourceEntity>>(emptyList())
    private val networkBooksS = MutableStateFlow<List<NetworkBookEntity>>(emptyList())
    private val replaceRulesS = MutableStateFlow<List<ReplaceRuleEntity>>(emptyList())
    private val bookmarksS = MutableStateFlow<List<BookmarkEntity>>(emptyList())

    init { reloadAll() }

    @Synchronized
    private fun reloadAll() {
        booksS.value = query("books") { it.toBook() }
        fragmentsS.value = query("fragments") { it.toFragment() }
        collectionsS.value = query("collections") { it.toCollection() }
        progressS.value = query("progress") { it.toProgress() }
        foldersS.value = query("source_folders") { it.toFolder() }
        bookSourcesS.value = query("book_sources") { it.toBookSource() }
        networkBooksS.value = query("network_books") { it.toNetworkBook() }
        // chapters 不进内存：每行 cacheKey 内嵌整份书源 JSON（几KB×几千章），
        // 启动全表 load 会 OOM（真机 256MB heap 实测崩溃）。改为按需查库。
        replaceRulesS.value = query("replace_rules") { it.toReplaceRule() }
        bookmarksS.value = query("bookmarks") { it.toBookmark() }
    }

    @Synchronized
    private fun reload(table: String) {
        when (table) {
            "books" -> booksS.value = query("books") { it.toBook() }
            "fragments" -> fragmentsS.value = query("fragments") { it.toFragment() }
            "collections" -> collectionsS.value = query("collections") { it.toCollection() }
            "progress" -> progressS.value = query("progress") { it.toProgress() }
            "source_folders" -> foldersS.value = query("source_folders") { it.toFolder() }
            "book_sources" -> bookSourcesS.value = query("book_sources") { it.toBookSource() }
            "network_books" -> networkBooksS.value = query("network_books") { it.toNetworkBook() }
            "replace_rules" -> replaceRulesS.value = query("replace_rules") { it.toReplaceRule() }
            "bookmarks" -> bookmarksS.value = query("bookmarks") { it.toBookmark() }
        }
    }

    private fun <T> query(table: String, map: (Cursor) -> T): List<T> = query(table, map, null, null)
    private fun <T> query(table: String, map: (Cursor) -> T, where: String?, args: Array<String>?): List<T> {
        val out = ArrayList<T>()
        val c = helper.readableDatabase.query(table, null, where, args, null, null, null)
        c.use {
            while (it.moveToNext()) out += map(it)
        }
        return out
    }

    @Synchronized
    private fun <T> write(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun cGet(c: Cursor, name: String): String {
        val i = c.getColumnIndex(name)
        return if (i >= 0 && !c.isNull(i)) c.getString(i) else ""
    }
    private fun cLong(c: Cursor, name: String): Long {
        val i = c.getColumnIndex(name)
        return if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
    }
    private fun cInt(c: Cursor, name: String): Int = cLong(c, name).toInt()
    private fun cNullableLong(c: Cursor, name: String): Long? {
        val i = c.getColumnIndex(name)
        return if (i >= 0 && !c.isNull(i)) c.getLong(i) else null
    }
    private fun cNullableStr(c: Cursor, name: String): String? {
        val i = c.getColumnIndex(name)
        return if (i >= 0 && !c.isNull(i)) c.getString(i) else null
    }

    private fun Cursor.toBook() = BookEntity(
        cLong(this, "id"), cGet(this, "title"), cNullableStr(this, "coverPath"),
        cGet(this, "mediaType"), cGet(this, "sourceLabel"), cInt(this, "favorite") == 1,
        cLong(this, "sortField"), cLong(this, "createdAt"), cLong(this, "updatedAt"),
        cInt(this, "pageCount"), cNullableLong(this, "collectionId"), cGet(this, "customTags"),
    )
    private fun Cursor.toFragment() = FragmentEntity(
        cLong(this, "id"), cLong(this, "bookId"), cGet(this, "kind"), cGet(this, "sourcePath"),
        cGet(this, "entryPrefix"), cInt(this, "orderIndex"), cInt(this, "pageCount"),
        cNullableStr(this, "passwordAlias"),
    )
    private fun Cursor.toCollection() = CollectionEntity(
        cLong(this, "id"), cGet(this, "name"), cNullableLong(this, "parentId"),
        cGet(this, "sourceUri"), cInt(this, "orderIndex"),
    )
    private fun Cursor.toProgress() = ProgressEntity(
        cLong(this, "bookId"), cInt(this, "pageIndex"), cInt(this, "chapterOffset"),
        cLong(this, "mediaTimeMs"), cGet(this, "extra"), cLong(this, "updatedAt"),
    )
    private fun Cursor.toFolder() = SourceFolderEntity(cLong(this, "id"), cGet(this, "uri"), cGet(this, "displayName"))
    private fun Cursor.toBookSource() = BookSourceEntity(
        cLong(this, "id"), cGet(this, "name"), cGet(this, "url"), cGet(this, "json"),
        cInt(this, "enabled") == 1, cLong(this, "createdAt"),
        cInt(this, "useCount"), cInt(this, "hitCount"),
        cGet(this, "groupName"), cInt(this, "sourceType"),
        cGet(this, "subscribeUrl"), cGet(this, "subscribeName"),
        cGet(this, "originType"),
    )
    private fun Cursor.toNetworkBook() = NetworkBookEntity(
        cLong(this, "bookId"), cLong(this, "sourceId"), cGet(this, "sourceJson"),
        cGet(this, "bookUrl"), cGet(this, "tocUrl"), cGet(this, "sourceName"),
        cGet(this, "originsJson"), cInt(this, "categoryIndex"),
    )
    private fun Cursor.toChapter() = ChapterEntity(
        cGet(this, "cacheKey"), cGet(this, "url"), cGet(this, "title"),
        cInt(this, "isVip") == 1, cInt(this, "isVolume") == 1, cInt(this, "orderIndex"),
    )
    private fun Cursor.toChapterContent() = ChapterContentEntity(
        cGet(this, "cacheKey"), cGet(this, "chapterUrl"), cGet(this, "text"), cLong(this, "updatedAt"),
    )
    private fun Cursor.toReplaceRule() = ReplaceRuleEntity(
        cLong(this, "id"), cGet(this, "name"), cGet(this, "groupName"),
        cGet(this, "pattern"), cGet(this, "replacement"), cGet(this, "scope"),
        cInt(this, "scopeTitle") == 1, cInt(this, "scopeContent") == 1,
        cGet(this, "excludeScope"), cInt(this, "isEnabled") == 1,
        cInt(this, "isRegex") == 1, cLong(this, "timeoutMillisecond"),
        cInt(this, "sortOrder"),
    )
    private fun Cursor.toBookmark() = BookmarkEntity(
        cLong(this, "id"), cLong(this, "bookId"), cInt(this, "chapterIndex"),
        cGet(this, "chapterTitle"), cInt(this, "charOffset"), cGet(this, "snippet"),
        cLong(this, "createdAt"),
    )

    private fun bookCv(e: BookEntity) = ContentValues().apply {
        if (e.id != 0L) put("id", e.id)
        put("title", e.title); put("coverPath", e.coverPath); put("mediaType", e.mediaType)
        put("sourceLabel", e.sourceLabel); put("favorite", if (e.favorite) 1 else 0)
        put("sortField", e.sortField); put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
        put("pageCount", e.pageCount); put("collectionId", e.collectionId); put("customTags", e.customTags)
    }
    private fun fragCv(e: FragmentEntity) = ContentValues().apply {
        if (e.id != 0L) put("id", e.id)
        put("bookId", e.bookId); put("kind", e.kind); put("sourcePath", e.sourcePath)
        put("entryPrefix", e.entryPrefix); put("orderIndex", e.orderIndex)
        put("pageCount", e.pageCount); put("passwordAlias", e.passwordAlias)
    }
    private fun colCv(e: CollectionEntity) = ContentValues().apply {
        if (e.id != 0L) put("id", e.id)
        put("name", e.name); put("parentId", e.parentId); put("sourceUri", e.sourceUri)
        put("orderIndex", e.orderIndex)
    }
    private fun progCv(e: ProgressEntity) = ContentValues().apply {
        put("bookId", e.bookId); put("pageIndex", e.pageIndex); put("chapterOffset", e.chapterOffset)
        put("mediaTimeMs", e.mediaTimeMs); put("extra", e.extra); put("updatedAt", e.updatedAt)
    }
    private fun folderCv(e: SourceFolderEntity) = ContentValues().apply {
        if (e.id != 0L) put("id", e.id)
        put("uri", e.uri); put("displayName", e.displayName)
    }
    private fun bookSourceCv(e: BookSourceEntity) = ContentValues().apply {
        if (e.id != 0L) put("id", e.id)
        put("name", e.name); put("url", e.url); put("json", e.json)
        put("enabled", if (e.enabled) 1 else 0); put("createdAt", e.createdAt)
        put("useCount", e.useCount); put("hitCount", e.hitCount)
        put("groupName", e.groupName); put("sourceType", e.sourceType)
        put("subscribeUrl", e.subscribeUrl); put("subscribeName", e.subscribeName)
        // 空值按订阅链接派生，让现有构造点无需改也能正确归类；显式指定则尊重指定值（可扩展）。
        put("originType", e.originType.ifBlank { originTypeOf(e.subscribeUrl) })
    }
    private fun networkBookCv(e: NetworkBookEntity) = ContentValues().apply {
        put("bookId", e.bookId); put("sourceId", e.sourceId); put("sourceJson", e.sourceJson)
        put("bookUrl", e.bookUrl); put("tocUrl", e.tocUrl); put("sourceName", e.sourceName)
        put("originsJson", e.originsJson); put("categoryIndex", e.categoryIndex)
    }
    private fun chapterCv(e: ChapterEntity) = ContentValues().apply {
        put("cacheKey", e.cacheKey); put("url", e.url); put("title", e.title)
        put("isVip", if (e.isVip) 1 else 0); put("isVolume", if (e.isVolume) 1 else 0)
        put("orderIndex", e.orderIndex)
    }
    private fun chapterContentCv(e: ChapterContentEntity) = ContentValues().apply {
        put("cacheKey", e.cacheKey); put("chapterUrl", e.chapterUrl)
        put("text", e.text); put("updatedAt", e.updatedAt)
    }
    private fun replaceRuleCv(e: ReplaceRuleEntity) = ContentValues().apply {
        if (e.id != 0L) put("id", e.id)
        put("name", e.name); put("groupName", e.group)
        put("pattern", e.pattern); put("replacement", e.replacement)
        put("scope", e.scope)
        put("scopeTitle", if (e.scopeTitle) 1 else 0)
        put("scopeContent", if (e.scopeContent) 1 else 0)
        put("excludeScope", e.excludeScope)
        put("isEnabled", if (e.isEnabled) 1 else 0)
        put("isRegex", if (e.isRegex) 1 else 0)
        put("timeoutMillisecond", e.timeoutMillisecond)
        put("sortOrder", e.order)
    }
    private fun bookmarkCv(e: BookmarkEntity) = ContentValues().apply {
        if (e.id != 0L) put("id", e.id)
        put("bookId", e.bookId); put("chapterIndex", e.chapterIndex)
        put("chapterTitle", e.chapterTitle); put("charOffset", e.charOffset)
        put("snippet", e.snippet); put("createdAt", e.createdAt)
    }

    private val bookDaoImpl = BookDaoImpl()
    private val fragmentDaoImpl = FragmentDaoImpl()
    private val collectionDaoImpl = CollectionDaoImpl()
    private val progressDaoImpl = ProgressDaoImpl()
    private val folderDaoImpl = SourceFolderDaoImpl()
    private val bookSourceDaoImpl = BookSourceDaoImpl()
    private val networkBookDaoImpl = NetworkBookDaoImpl()
    private val chapterDaoImpl = ChapterDaoImpl()
    private val chapterContentDaoImpl = ChapterContentDaoImpl()
    private val replaceRuleDaoImpl = ReplaceRuleDaoImpl()
    private val bookmarkDaoImpl = BookmarkDaoImpl()

    fun bookDao(): BookDao = bookDaoImpl
    fun fragmentDao(): FragmentDao = fragmentDaoImpl
    fun collectionDao(): CollectionDao = collectionDaoImpl
    fun progressDao(): ProgressDao = progressDaoImpl
    fun sourceFolderDao(): SourceFolderDao = folderDaoImpl
    fun bookSourceDao(): BookSourceDao = bookSourceDaoImpl
    fun networkBookDao(): NetworkBookDao = networkBookDaoImpl
    fun chapterDao(): ChapterDao = chapterDaoImpl
    fun chapterContentDao(): ChapterContentDao = chapterContentDaoImpl
    fun replaceRuleDao(): ReplaceRuleDao = replaceRuleDaoImpl
    fun bookmarkDao(): BookmarkDao = bookmarkDaoImpl

    inner class BookDaoImpl : BookDao {
        override fun allBooks(): Flow<List<BookEntity>> =
            booksS.map { it.sortedWith(compareBy({ it.sortField }, { it.id })) }
        override fun booksInCollection(collectionId: Long?): Flow<List<BookEntity>> =
            booksS.map { it.filter { b -> b.collectionId == collectionId }.sortedWith(compareBy({ it.sortField }, { it.id })) }
        override suspend fun book(id: Long): BookEntity? = booksS.value.firstOrNull { it.id == id }
        override fun favoriteBooks(): Flow<List<BookEntity>> = booksS.map { it.filter { b -> b.favorite } }
        override suspend fun upsert(book: BookEntity): Long {
            val id = write {
                val r = it.insertWithOnConflict("books", null, bookCv(book), SQLiteDatabase.CONFLICT_REPLACE)
                if (r < 0 && book.id != 0L) book.id else r
            }
            reload("books")
            return if (id < 0) book.id else id
        }
        override suspend fun setFavorite(id: Long, fav: Boolean) {
            write {
                val cv = ContentValues().apply { put("favorite", if (fav) 1 else 0) }
                it.update("books", cv, "id=?", arrayOf(id.toString()))
            }; reload("books")
        }
        override suspend fun deleteBook(id: Long) {
            write { it.delete("books", "id=?", arrayOf(id.toString())) }; reload("books")
        }
        override suspend fun setTags(id: Long, tags: String) {
            write {
                val cv = ContentValues().apply { put("customTags", tags) }
                it.update("books", cv, "id=?", arrayOf(id.toString()))
            }; reload("books")
        }
        override suspend fun moveBooksToRoot(collectionId: Long) {
            write {
                val cv = ContentValues().apply { putNull("collectionId") }
                it.update("books", cv, "collectionId=?", arrayOf(collectionId.toString()))
            }; reload("books")
        }
    }

    inner class FragmentDaoImpl : FragmentDao {
        override suspend fun fragments(bookId: Long): List<FragmentEntity> =
            fragmentsS.value.filter { it.bookId == bookId }.sortedBy { it.orderIndex }
        override suspend fun fragmentsOf(bookIds: List<Long>): List<FragmentEntity> =
            fragmentsS.value.filter { it.bookId in bookIds }.sortedWith(compareBy({ it.bookId }, { it.orderIndex }))
        override suspend fun insertAll(list: List<FragmentEntity>) {
            write { db -> list.forEach { db.insertWithOnConflict("fragments", null, fragCv(it), SQLiteDatabase.CONFLICT_REPLACE) } }
            reload("fragments")
        }
        override suspend fun deleteOfBook(bookId: Long) {
            write { it.delete("fragments", "bookId=?", arrayOf(bookId.toString())) }; reload("fragments")
        }
        override suspend fun reassign(fragmentId: Long, newBookId: Long, order: Int) {
            write {
                val cv = ContentValues().apply { put("bookId", newBookId); put("orderIndex", order) }
                it.update("fragments", cv, "id=?", arrayOf(fragmentId.toString()))
            }; reload("fragments")
        }
    }

    inner class CollectionDaoImpl : CollectionDao {
        override fun children(parentId: Long?): Flow<List<CollectionEntity>> =
            collectionsS.map { it.filter { c -> c.parentId == parentId }.sortedBy { it.orderIndex } }
        override suspend fun all(): List<CollectionEntity> = collectionsS.value
        override suspend fun upsert(entity: CollectionEntity): Long {
            val id = write {
                val r = it.insertWithOnConflict("collections", null, colCv(entity), SQLiteDatabase.CONFLICT_REPLACE)
                if (r < 0 && entity.id != 0L) entity.id else r
            }
            reload("collections")
            return if (id < 0) entity.id else id
        }
        override suspend fun delete(id: Long) {
            write { it.delete("collections", "id=?", arrayOf(id.toString())) }; reload("collections")
        }
        override suspend fun rename(id: Long, name: String) {
            write { it.execSQL("UPDATE collections SET name=? WHERE id=?", arrayOf(name, id.toString())) }
            reload("collections")
        }
        override suspend fun reparentChildrenToRoot(parentId: Long) {
            write {
                val cv = ContentValues().apply { putNull("parentId") }
                it.update("collections", cv, "parentId=?", arrayOf(parentId.toString()))
            }; reload("collections")
        }
    }

    inner class ProgressDaoImpl : ProgressDao {
        override fun progress(bookId: Long): Flow<ProgressEntity?> =
            progressS.map { it.firstOrNull { p -> p.bookId == bookId } }
        override fun all(): Flow<List<ProgressEntity>> = progressS.map { it.toList() }
        override suspend fun save(progress: ProgressEntity) {
            write { it.insertWithOnConflict("progress", null, progCv(progress), SQLiteDatabase.CONFLICT_REPLACE) }
            // 增量替换内存中的单条，不重查整表（进度保存高频，避免每次全表 reload）
            progressS.value = buildList {
                addAll(progressS.value.filter { it.bookId != progress.bookId })
                add(progress)
            }
        }
    }

    inner class SourceFolderDaoImpl : SourceFolderDao {
        override fun all(): Flow<List<SourceFolderEntity>> = foldersS.map { it.toList() }
        override suspend fun insert(entity: SourceFolderEntity): Long {
            val id = write { it.insertWithOnConflict("source_folders", null, folderCv(entity), SQLiteDatabase.CONFLICT_REPLACE) }
            reload("source_folders"); return id
        }
        override suspend fun delete(id: Long) {
            write { it.delete("source_folders", "id=?", arrayOf(id.toString())) }; reload("source_folders")
        }
    }

    inner class BookSourceDaoImpl : BookSourceDao {
        override fun all(): Flow<List<BookSourceEntity>> = bookSourcesS.map { it.toList() }
        override suspend fun insert(entity: BookSourceEntity): Long {
            val id = write { it.insertWithOnConflict("book_sources", null, bookSourceCv(entity), SQLiteDatabase.CONFLICT_REPLACE) }
            reload("book_sources"); return id
        }
        override suspend fun insertAll(list: List<BookSourceEntity>) {
            if (list.isEmpty()) return
            write { db -> list.forEach { db.insertWithOnConflict("book_sources", null, bookSourceCv(it), SQLiteDatabase.CONFLICT_REPLACE) } }
            reload("book_sources")
        }
        override suspend fun setEnabled(id: Long, enabled: Boolean) {
            write {
                val cv = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
                it.update("book_sources", cv, "id=?", arrayOf(id.toString()))
            }; reload("book_sources")
        }
        override suspend fun setEnabledForIds(ids: List<Long>, enabled: Boolean) {
            if (ids.isEmpty()) return
            write { db ->
                val cv = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
                ids.forEach { db.update("book_sources", cv, "id=?", arrayOf(it.toString())) }
            }
            reload("book_sources")
        }
        override suspend fun setSubscribeNameForIds(ids: List<Long>, name: String) {
            if (ids.isEmpty()) return
            write { db ->
                val cv = ContentValues().apply { put("subscribeName", name) }
                ids.forEach { db.update("book_sources", cv, "id=?", arrayOf(it.toString())) }
            }
            reload("book_sources")
        }
        override suspend fun setNamesByIds(pairs: List<Pair<Long, String>>) {
            if (pairs.isEmpty()) return
            write { db ->
                pairs.forEach { (id, name) ->
                    val cv = ContentValues().apply { put("name", name) }
                    db.update("book_sources", cv, "id=?", arrayOf(id.toString()))
                }
            }
            reload("book_sources")
        }
        override suspend fun delete(id: Long) {
            write { it.delete("book_sources", "id=?", arrayOf(id.toString())) }; reload("book_sources")
        }
        override suspend fun deleteAll(ids: List<Long>) {
            if (ids.isEmpty()) return
            write { db -> ids.forEach { db.delete("book_sources", "id=?", arrayOf(it.toString())) } }
            reload("book_sources")
        }
        override suspend fun recordUse(id: Long) {
            write { it.execSQL("UPDATE book_sources SET useCount=useCount+1 WHERE id=?", arrayOf(id.toString())) }
            reload("book_sources")
        }
        override suspend fun recordHit(id: Long) {
            write { it.execSQL("UPDATE book_sources SET hitCount=hitCount+1 WHERE id=?", arrayOf(id.toString())) }
            reload("book_sources")
        }
        override suspend fun recordSearchStats(usedIds: List<Long>, hitIds: List<Long>) {
            if (usedIds.isEmpty() && hitIds.isEmpty()) return
            write { db ->
                usedIds.forEach { db.execSQL("UPDATE book_sources SET useCount=useCount+1 WHERE id=?", arrayOf(it.toString())) }
                hitIds.forEach { db.execSQL("UPDATE book_sources SET hitCount=hitCount+1 WHERE id=?", arrayOf(it.toString())) }
            }
            reload("book_sources")
        }

        // 搜索过程中逐源实时写：只更新该源一行，并就地刷新内存 StateFlow（不整表 reload，避免 2000 源 O(n²) 卡顿）。
        override suspend fun recordStatLive(id: Long, hit: Boolean) {
            write { db ->
                db.execSQL("UPDATE book_sources SET useCount=useCount+1 WHERE id=?", arrayOf(id.toString()))
                if (hit) db.execSQL("UPDATE book_sources SET hitCount=hitCount+1 WHERE id=?", arrayOf(id.toString()))
            }
            val updated = bookSourcesS.value.map { e ->
                if (e.id == id) e.copy(useCount = e.useCount + 1, hitCount = e.hitCount + if (hit) 1 else 0) else e
            }
            bookSourcesS.value = updated
        }
    }

    inner class NetworkBookDaoImpl : NetworkBookDao {
        override fun all(): Flow<List<NetworkBookEntity>> = networkBooksS.map { it.toList() }
        override suspend fun of(bookId: Long): NetworkBookEntity? =
            networkBooksS.value.firstOrNull { it.bookId == bookId }
        override suspend fun upsert(entity: NetworkBookEntity) {
            write { it.insertWithOnConflict("network_books", null, networkBookCv(entity), SQLiteDatabase.CONFLICT_REPLACE) }
            reload("network_books")
        }
        override suspend fun delete(bookId: Long) {
            write { it.delete("network_books", "bookId=?", arrayOf(bookId.toString())) }; reload("network_books")
        }
    }

    inner class ChapterDaoImpl : ChapterDao {
        // 按需查库（只取单个 cacheKey 的目录），不进内存 StateFlow。
        override suspend fun of(cacheKey: String): List<ChapterEntity> = query(
            "chapters", { it.toChapter() }, "cacheKey=?", arrayOf(cacheKey),
        ).sortedBy { it.orderIndex }
        override suspend fun replaceAll(cacheKey: String, list: List<ChapterEntity>) {
            write { db ->
                db.delete("chapters", "cacheKey=?", arrayOf(cacheKey))
                list.forEach { db.insertWithOnConflict("chapters", null, chapterCv(it), SQLiteDatabase.CONFLICT_REPLACE) }
            }
        }
        override suspend fun clear(cacheKey: String) {
            write { it.delete("chapters", "cacheKey=?", arrayOf(cacheKey)) }
        }
    }

    inner class ChapterContentDaoImpl : ChapterContentDao {
        override suspend fun of(cacheKey: String, chapterUrl: String): String? = query(
            "chapter_contents", { it.toChapterContent() },
        ).firstOrNull { it.cacheKey == cacheKey && it.chapterUrl == chapterUrl }?.text
        override suspend fun allOf(cacheKey: String): Map<String, String> = query(
            "chapter_contents", { it.toChapterContent() },
        ).filter { it.cacheKey == cacheKey }.associate { it.chapterUrl to it.text }
        override suspend fun save(entity: ChapterContentEntity) {
            write { it.insertWithOnConflict("chapter_contents", null, chapterContentCv(entity), SQLiteDatabase.CONFLICT_REPLACE) }
        }
        override suspend fun saveAll(cacheKey: String, items: List<Pair<String, String>>) {
            if (items.isEmpty()) return
            write { db -> items.forEach { (url, text) ->
                db.insertWithOnConflict("chapter_contents", null, chapterContentCv(ChapterContentEntity(cacheKey, url, text, System.currentTimeMillis())), SQLiteDatabase.CONFLICT_REPLACE)
            } }
        }
        override suspend fun clear(cacheKey: String) {
            write { it.delete("chapter_contents", "cacheKey=?", arrayOf(cacheKey)) }
        }
    }

    inner class ReplaceRuleDaoImpl : ReplaceRuleDao {
        override fun all(): Flow<List<ReplaceRuleEntity>> = replaceRulesS.map { it.toList() }
        override suspend fun insert(entity: ReplaceRuleEntity): Long = write { db ->
            db.insertWithOnConflict("replace_rules", null, replaceRuleCv(entity), SQLiteDatabase.CONFLICT_REPLACE)
        }.also { reload("replace_rules") }
        override suspend fun insertAll(items: List<ReplaceRuleEntity>) {
            if (items.isEmpty()) return
            write { db -> items.forEach { item ->
                db.insertWithOnConflict("replace_rules", null, replaceRuleCv(item), SQLiteDatabase.CONFLICT_REPLACE)
            } }
            reload("replace_rules")
        }
        override suspend fun setEnabled(id: Long, enabled: Boolean) {
            write { it.execSQL("UPDATE replace_rules SET isEnabled=? WHERE id=?", arrayOf(if (enabled) 1 else 0, id)) }
            reload("replace_rules")
        }
        override suspend fun delete(id: Long) {
            write { it.delete("replace_rules", "id=?", arrayOf(id.toString())) }
            reload("replace_rules")
        }
        override suspend fun deleteAll(ids: List<Long>) {
            if (ids.isEmpty()) return
            write { db -> ids.forEach { id -> db.delete("replace_rules", "id=?", arrayOf(id.toString())) } }
            reload("replace_rules")
        }
        override suspend fun clear() {
            write { it.delete("replace_rules", null, null) }
            reload("replace_rules")
        }
    }

    inner class BookmarkDaoImpl : BookmarkDao {
        override fun all(): Flow<List<BookmarkEntity>> = bookmarksS.map { it.toList() }
        override fun of(bookId: Long): Flow<List<BookmarkEntity>> = bookmarksS.map { list ->
            list.filter { it.bookId == bookId }.sortedByDescending { it.createdAt }
        }
        override suspend fun insert(entity: BookmarkEntity): Long = write { db ->
            db.insertWithOnConflict("bookmarks", null, bookmarkCv(entity), SQLiteDatabase.CONFLICT_REPLACE)
        }.also { reload("bookmarks") }
        override suspend fun delete(id: Long) {
            write { it.delete("bookmarks", "id=?", arrayOf(id.toString())) }
            reload("bookmarks")
        }
        override suspend fun clearBook(bookId: Long) {
            write { it.delete("bookmarks", "bookId=?", arrayOf(bookId.toString())) }
            reload("bookmarks")
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: AppDatabase(context.applicationContext).also { INSTANCE = it }
        }
    }
}

interface BookDao {
    fun allBooks(): Flow<List<BookEntity>>
    fun booksInCollection(collectionId: Long?): Flow<List<BookEntity>>
    suspend fun book(id: Long): BookEntity?
    fun favoriteBooks(): Flow<List<BookEntity>>
    suspend fun upsert(book: BookEntity): Long
    suspend fun setFavorite(id: Long, fav: Boolean)
    suspend fun deleteBook(id: Long)
    suspend fun setTags(id: Long, tags: String)
    suspend fun moveBooksToRoot(collectionId: Long)
}

interface FragmentDao {
    suspend fun fragments(bookId: Long): List<FragmentEntity>
    suspend fun fragmentsOf(bookIds: List<Long>): List<FragmentEntity>
    suspend fun insertAll(list: List<FragmentEntity>)
    suspend fun deleteOfBook(bookId: Long)
    suspend fun reassign(fragmentId: Long, newBookId: Long, order: Int)
}

interface CollectionDao {
    fun children(parentId: Long?): Flow<List<CollectionEntity>>
    suspend fun all(): List<CollectionEntity>
    suspend fun upsert(entity: CollectionEntity): Long
    suspend fun delete(id: Long)
    suspend fun rename(id: Long, name: String)
    suspend fun reparentChildrenToRoot(parentId: Long)
}

interface ProgressDao {
    fun progress(bookId: Long): Flow<ProgressEntity?>
    fun all(): Flow<List<ProgressEntity>>
    suspend fun save(progress: ProgressEntity)
}

interface SourceFolderDao {
    fun all(): Flow<List<SourceFolderEntity>>
    suspend fun insert(entity: SourceFolderEntity): Long
    suspend fun delete(id: Long)
}

interface BookSourceDao {
    fun all(): Flow<List<BookSourceEntity>>
    suspend fun insert(entity: BookSourceEntity): Long
    suspend fun insertAll(list: List<BookSourceEntity>)
    suspend fun setEnabled(id: Long, enabled: Boolean)
    suspend fun setEnabledForIds(ids: List<Long>, enabled: Boolean)
    suspend fun setSubscribeNameForIds(ids: List<Long>, name: String)
    /** 一次性迁移用：按 id 批量改写源显示名（name 列），不动 json/订阅分组。 */
    suspend fun setNamesByIds(pairs: List<Pair<Long, String>>)
    suspend fun delete(id: Long)
    suspend fun deleteAll(ids: List<Long>)
    suspend fun recordUse(id: Long)
    suspend fun recordHit(id: Long)
    suspend fun recordSearchStats(usedIds: List<Long>, hitIds: List<Long>)
    suspend fun recordStatLive(id: Long, hit: Boolean)
}

interface NetworkBookDao {
    fun all(): Flow<List<NetworkBookEntity>>
    suspend fun of(bookId: Long): NetworkBookEntity?
    suspend fun upsert(entity: NetworkBookEntity)
    suspend fun delete(bookId: Long)
}

interface ChapterDao {
    suspend fun of(cacheKey: String): List<ChapterEntity>
    suspend fun replaceAll(cacheKey: String, list: List<ChapterEntity>)
    suspend fun clear(cacheKey: String)
}

interface ChapterContentDao {
    suspend fun of(cacheKey: String, chapterUrl: String): String?
    suspend fun allOf(cacheKey: String): Map<String, String>
    suspend fun save(entity: ChapterContentEntity)
    suspend fun saveAll(cacheKey: String, items: List<Pair<String, String>>)
    suspend fun clear(cacheKey: String)
}

interface ReplaceRuleDao {
    fun all(): Flow<List<ReplaceRuleEntity>>
    suspend fun insert(entity: ReplaceRuleEntity): Long
    suspend fun insertAll(items: List<ReplaceRuleEntity>)
    suspend fun setEnabled(id: Long, enabled: Boolean)
    suspend fun delete(id: Long)
    suspend fun deleteAll(ids: List<Long>)
    suspend fun clear()
}

interface BookmarkDao {
    fun all(): Flow<List<BookmarkEntity>>
    fun of(bookId: Long): Flow<List<BookmarkEntity>>
    suspend fun insert(entity: BookmarkEntity): Long
    suspend fun delete(id: Long)
    suspend fun clearBook(bookId: Long)
}

// ---- 映射 ----
fun BookEntity.toModel() = Book(
    id, title, coverPath, MediaType.from(mediaType), sourceLabel, favorite,
    sortField, createdAt, updatedAt, pageCount, collectionId,
)

fun FragmentEntity.toModel() = Fragment(
    id, bookId, runCatching { FragmentKind.valueOf(kind) }.getOrDefault(FragmentKind.FOLDER),
    sourcePath, entryPrefix, orderIndex, pageCount, passwordAlias,
)

fun ProgressEntity.toModel() = ReadProgress(bookId, pageIndex, chapterOffset, mediaTimeMs, extra, updatedAt)

fun ReplaceRuleEntity.toModel() = com.yg.comic.model.ReplaceRule(
    id, name, group, pattern, replacement, scope, scopeTitle, scopeContent,
    excludeScope, isEnabled, isRegex, timeoutMillisecond, order,
)

fun com.yg.comic.model.ReplaceRule.toEntity() = ReplaceRuleEntity(
    id, name, group, pattern, replacement, scope, scopeTitle, scopeContent,
    excludeScope, isEnabled, isRegex, timeoutMillisecond, order,
)
