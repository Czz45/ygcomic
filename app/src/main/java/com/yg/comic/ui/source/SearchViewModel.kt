package com.yg.comic.ui.source

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yg.comic.data.BookRepository
import com.yg.comic.data.BookSourceEntity
import com.yg.comic.data.SettingsStore
import com.yg.comic.data.SourceHealthStore
import com.yg.comic.model.MediaType
import com.yg.comic.shared.AggBookIn
import com.yg.comic.shared.AggOrigin
import com.yg.comic.shared.FailKind
import com.yg.comic.shared.SearchAggregator
import com.yg.comic.shared.SearchRules
import com.yg.comic.source.SearchBook
import com.yg.comic.source.SourceOrigin
import com.yg.comic.source.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val aggregatorJson = Json { ignoreUnknownKeys = true }
private val persistJson = Json { ignoreUnknownKeys = true }

private const val PREFS_SEARCH_STATE = "yg_search_state"
private const val KEY_LAST_RESULT = "last_result"
private const val KEY_HISTORY = "history"
private const val KEY_CACHE_SIGNATURE = "cache_signature"
private const val PERSIST_BOOK_MAX = 300

/**
 * 搜索状态持久化的轻量 DTO：存可序列化的 SearchBook + 主源信息 + origins。
 * 恢复时优先按 sourceId 反查当前书源；书源被删/换时用这里存的 sourceJson 兜底重建，
 * 避免整本书丢失。新增字段都带默认值，旧缓存反序列化不会失败。
 */
@Serializable
private data class PersistedBook(
    val book: SearchBook,
    val sourceId: Long,
    val origins: List<SourceOrigin> = emptyList(),
    val sourceJson: String = "",
    val sourceName: String = "",
    val sourceUrl: String = "",
    val sourceType: Int = 0,
)

@Serializable
private data class PersistedResult(
    val keyword: String,
    val books: List<PersistedBook> = emptyList(),
)

// 分组键 / 失败分类 / 相关度 / 命名归一 / 搜索聚合等纯逻辑已抽到 com.yg.comic.shared，
// 供 Android/iOS/Windows 多端复用；这里只做薄委托，行为保持一致。

/** 委托 shared：分组键取逗号分隔多标签的第一个。 */
private fun groupKeyOf(groupName: String): String = SearchRules.groupKeyOf(groupName)

/** 委托 shared：把 JNI 错误文本归类为 S/RATE/NETWORK 三档。 */
private fun classifyFail(msg: String?): FailKind = SearchRules.classifyFail(msg)

/** 搜索页专用 VM：流式搜索（边搜边出）+ 加入书架。 */
class SearchViewModel(
    private val repo: BookRepository,
) : ViewModel() {

    // 书源健康度：失败/连不通减分、命中质量/速度加分，替代原来只会加分的计数排序。
    private val health = SourceHealthStore(repo.appContext)
    // 智能排序总开关（设置页）：关闭时保持书源原始顺序，不做网络分层/健康度排序。
    private val settings = SettingsStore(repo.appContext)

    val sources: StateFlow<List<BookSourceEntity>> =
        repo.bookSources.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _results = MutableStateFlow<List<SourcedBook>>(emptyList())
    val results: StateFlow<List<SourcedBook>> = _results.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /** 搜索进度：已完成源数 to 总源数 */
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    /** 已在书架的书名 key（归一化），供结果页把"加入书架"按钮置为已加入态 */
    private val _addedKeys = MutableStateFlow<Set<String>>(emptySet())
    val addedKeys: StateFlow<Set<String>> = _addedKeys.asStateFlow()

    /** 上次搜索的词，供结果页重建时回填搜索框；从 SharedPreferences 恢复。 */
    private val _savedKeyword = MutableStateFlow("")
    val savedKeyword: StateFlow<String> = _savedKeyword.asStateFlow()

    /** 搜索历史词（最近在前），供结果页快捷点选；从 SharedPreferences 恢复。 */
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private val prefs = repo.appContext.getSharedPreferences(PREFS_SEARCH_STATE, Context.MODE_PRIVATE)

    private var job: Job? = null

    /**
     * 已从首页搜索框的 initialKey 自动搜过的词。
     * 点进书籍详情再返回时，SearchScreen 重新组合会重跑 LaunchedEffect(initialKey)，
     * 这里记住已为该 key 搜过，杜绝重复发网络请求覆盖已恢复的结果。
     */
    private var autoSearchedKey: String? = null

    /**
     * 搜索源类型过滤：-1=全部；0=小说；2=漫画。由入口（首页漫画 tab 搜索）传入，
     * search() 只在该类型的书源里搜，避免漫画搜索混进小说源结果。
     */
    var sourceTypeFilter: Int = -1

    /**
     * 首页搜索框带 key 进入时的幂等自动搜索。
     * 同一个 key 只自动搜一次；返回 false 表示调用方无需再设置关键词/搜索。
     */
    fun autoSearchFromKey(key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        if (autoSearchedKey == key) return false
        autoSearchedKey = key
        search(key)
        return true
    }

    /**
     * 搜索结果内存缓存：同一关键词 + 启用书源集合未变时，TTL 内直接复用，避免每次全量重搜。
     * key=关键词，value=聚合后的结果 + 当时书源签名 + 时间戳。
     */
    private data class SearchCacheEntry(
        val results: List<SourcedBook>,
        val sourceSignature: String,
        val timeMs: Long,
    )
    private val searchCache = LinkedHashMap<String, SearchCacheEntry>()
    /** 书源签名：启用的源 id 有序拼接；书源增删/启停后签名变化，缓存自动失效。 */
    private val cacheSignature: String
        get() = "$sourceTypeFilter|" + sources.value.filter { it.enabled }.joinToString(",") { it.id.toString() }

    companion object {
        private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 分钟
        private const val CACHE_MAX = 40 // 最多缓存 40 个关键词
        private const val HISTORY_MAX = 12 // 最多记住 12 个历史搜索词
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repo.books.collect { books ->
                _addedKeys.value = books.map { shelfKey(it.title) }.toSet()
            }
        }
        // 恢复上次搜索词/历史/结果：等书源加载后按 sourceId 反查重建 SourcedBook（不落盘大 json 的 BookSourceEntity）。
        viewModelScope.launch(Dispatchers.IO) {
            restoreSavedState()
        }
    }

    /** 持久化缓存签名：只反映"启用了哪些源"，与 tab 过滤无关；书源增删/启停后变化。 */
    private fun persistSig(all: List<BookSourceEntity>): String =
        all.filter { it.enabled }.joinToString(",") { it.id.toString() }

    /** 从 SharedPreferences 恢复搜索历史、上次关键词与结果。 */
    private suspend fun restoreSavedState() {
        try {
            prefs.getString(KEY_HISTORY, null)?.let { raw ->
                _history.value = runCatching { persistJson.decodeFromString<List<String>>(raw) }
                    .getOrDefault(emptyList()).take(HISTORY_MAX)
            }
            val allSources = repo.bookSources.first()
            val currentSig = persistSig(allSources)
            val savedSig = prefs.getString(KEY_CACHE_SIGNATURE, null)
            // 书源增删/启停后签名变化：旧结果里的 sourceId 可能已失效，整批清掉等重搜。
            // savedSig==null 视为升级前的旧缓存，不清，尽量恢复。
            if (savedSig != null && savedSig != currentSig) {
                prefs.edit().remove(KEY_LAST_RESULT).remove(KEY_CACHE_SIGNATURE).apply()
                return
            }
            val raw = prefs.getString(KEY_LAST_RESULT, null) ?: return
            val saved = runCatching { persistJson.decodeFromString<PersistedResult>(raw) }.getOrNull() ?: return
            _savedKeyword.value = saved.keyword
            if (saved.books.isEmpty()) return
            val byId = allSources.associateBy { it.id }
            val restored = saved.books.mapNotNull { p ->
                val src = byId[p.sourceId] ?: run {
                    // 主源被删/换：用落盘时存下的 json 兜底重建，避免整本书丢失。
                    val fallbackJson = p.sourceJson.ifBlank { p.origins.firstOrNull()?.sourceJson.orEmpty() }
                    if (fallbackJson.isBlank()) return@mapNotNull null
                    BookSourceEntity(
                        id = p.sourceId,
                        name = p.sourceName.ifBlank { p.origins.firstOrNull()?.sourceName.orEmpty() },
                        url = p.sourceUrl,
                        json = fallbackJson,
                        sourceType = p.sourceType,
                    )
                }
                SourcedBook(p.book, src, p.origins)
            }
            if (restored.isNotEmpty()) _results.value = restored
        } catch (_: Exception) { }
    }

    /** 记录一个搜索历史词（去重、最近在前、限长），落盘。 */
    private fun rememberKeyword(kw: String) {
        val list = _history.value.toMutableList()
        list.remove(kw)
        list.add(0, kw)
        val trimmed = list.take(HISTORY_MAX)
        _history.value = trimmed
        prefs.edit().putString(KEY_HISTORY, runCatching { persistJson.encodeToString(trimmed) }.getOrDefault("")).apply()
    }

    /** 搜索完成后把关键词与结果落盘（轻量 DTO，按书名累积多份）。 */
    private fun saveLastResult(kw: String, results: List<SourcedBook>) {
        val byKey = LinkedHashMap<String, PersistedBook>()
        prefs.getString(KEY_LAST_RESULT, null)?.let { raw ->
            runCatching { persistJson.decodeFromString<PersistedResult>(raw) }.getOrNull()?.books?.forEach { p ->
                byKey[shelfKey(p.book.name)] = p
            }
        }
        results.forEach { sb ->
            val key = shelfKey(sb.book.name)
            val origins = (sb.origins + byKey[key]?.origins.orEmpty())
                .distinctBy { it.sourceId to it.bookUrl }
            byKey[key] = PersistedBook(
                book = sb.book,
                sourceId = sb.source.id,
                origins = origins,
                sourceJson = sb.source.json,
                sourceName = sb.source.name,
                sourceUrl = sb.source.url,
                sourceType = sb.source.sourceType,
            )
        }
        while (byKey.size > PERSIST_BOOK_MAX) {
            val oldest = byKey.keys.firstOrNull() ?: break
            byKey.remove(oldest)
        }
        val dto = PersistedResult(keyword = kw, books = byKey.values.toList())
        prefs.edit()
            .putString(KEY_LAST_RESULT, runCatching { persistJson.encodeToString(dto) }.getOrDefault(""))
            .putString(KEY_CACHE_SIGNATURE, persistSig(sources.value))
            .apply()
    }

    /** 书架 Book 只有书名，用归一化书名判断"已加入"；委托 shared，与聚合分组同规则。 */
    fun shelfKey(name: String): String = SearchRules.shelfKey(name)

    /** 排序漏斗：用户点开某源的结果进详情——记录到该源的健康度。 */
    fun recordClick(sourceId: Long) = health.recordClick(sourceId)

    /** 排序漏斗：用户把结果加入书架——记录到主源及其聚合来源的健康度。 */
    fun recordAdopt(sb: SourcedBook) {
        health.recordAdopt(sb.source.id)
        sb.origins.forEach { if (it.sourceId != sb.source.id) health.recordAdopt(it.sourceId) }
    }

    fun clearMessage() { _message.value = "" }

    fun cancel() {
        job?.cancel()
        _searching.value = false
    }

    /**
     * 流式搜索：每个启用书源一个协程并发抓，先返回的先追加显示，不干等全部跑完。
     */
    fun search(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        // 持久化：记住本次搜索词 + 历史，供进详情返回/重启后回填，不因页面重建清空。
        _savedKeyword.value = kw
        rememberKeyword(kw)
        // 命中缓存：同一关键词 + 启用书源集合未变 + TTL 内，直接回填结果，不重新发网络请求。
        searchCache[kw]?.let { c ->
            if (c.sourceSignature == cacheSignature &&
                System.currentTimeMillis() - c.timeMs < CACHE_TTL_MS
            ) {
                job?.cancel()
                _results.value = c.results
                _progress.value = c.results.size to c.results.size
                _message.value = ""
                _searching.value = false
                saveLastResult(kw, c.results)
                return
            }
        }
        job?.cancel()
        job = viewModelScope.launch {
            _searching.value = true
            _results.value = emptyList()
            _message.value = ""
            // 智能排序：先按网站检测结果分层，层内再按健康度权重排。
            // 分层顺序：已测通(ok) → 存疑(suspect) → 未检测(无记录) → 不通(bad，最后)。
            // 层内按 health.score：命中率高/质量好/响应快/最近还活着的分高排前面。
            val enabledSources = sources.value.filter {
                val typeOk = sourceTypeFilter < 0 || it.sourceType == sourceTypeFilter
                // 漫画tab搜索：剔除冒牌漫画源（笔趣阁等小说站），避免"漫画源搜出小说"。
                val comicOk = if (it.sourceType == 2)
                    com.yg.comic.shared.ComicSourceRule.isComicSource(it.name, it.url) else true
                it.enabled && typeOk && comicOk
            }
            // 读网站检测结果（与书源页/首页共用 source_net_check，与 loadNetCheck 同语义）。
            val netSp = repo.appContext.getSharedPreferences("source_net_check", Context.MODE_PRIVATE)
            val okIds = (netSp.getStringSet("ok", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
            val badIds = (netSp.getStringSet("bad", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
            val suspectIds = (netSp.getStringSet("suspect", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
            fun netTier(id: Long): Int = when (id) {
                in okIds -> 0       // 已测通：最优先
                in suspectIds -> 1  // 存疑：其次
                in badIds -> 3      // 不通：放最后
                else -> 2           // 未检测：按权重，排在存疑与不通之间
            }
            // 分组内相对归一化：用 groupName（逗号分隔标签取第一个）算每组平均分，
            // 让"专精某分类、组内最强"的源不被全局命中率更高的大源埋没。
            val groupAvg = HashMap<String, Double>()
            enabledSources.groupBy { groupKeyOf(it.groupName) }.forEach { (g, list) ->
                groupAvg[g] = list.map { health.score(it.id) }.average()
            }
            // 智能排序总开关：关闭时保持书源原始顺序，不做网络分层与健康度排序。
            val autoSort = settings.autoSortEnabled.first()
            val enabled = if (autoSort) {
                enabledSources.sortedWith(
                    compareBy<BookSourceEntity> { netTier(it.id) }
                        .thenByDescending { health.score(it.id, groupAvg[groupKeyOf(it.groupName)]) }
                )
            } else {
                enabledSources
            }
            if (enabled.isEmpty()) {
                _message.value = "还没有启用的书源，请先到书源页添加"
                _searching.value = false
                return@launch
            }
            _progress.value = 0 to enabled.size
            // 照 legado：固定并发上限（SearchModel 线程数 min(16,9)）——同时只跑 12 个源的网络请求，
            // 避免 900 源一次性 launch 导致协程/连接爆炸卡顿；单源 30s 超时（legado withTimeout(30000)）。
            val semaphore = Semaphore(12)
            val lock = Mutex()
            var done = 0
            val hitIds = ArrayList<Long>()
            // 用于判定"整体网络故障"：一轮里若大量源同时网络失败且零命中，多半是本机没开 VPN/断网，
            // 这时不该把境外源全判死刑；有任一源命中即视为网络正常并自愈。
            var netFailCnt = 0
            var sourceFailCnt = 0
            val jobs = enabled.map { src ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        val t0 = System.currentTimeMillis()
                        // 保留完整结果形态，区分「失败/连不通」「连得上但搜不出」「命中」三类，
                        // 分别记入健康度：失败重罚、空结果轻罚、命中按相关度质量加分。
                        // runCatching 必须在 withTimeout 外层：JNI 同步调用超时抛的
                        // TimeoutCancellationException 若在内层 runCatching 之外逃逸，
                        // 会取消整个父协程，导致后面的 recordSearchStats 永不执行（权重全 0）。
                        val res = runCatching {
                            withTimeout(30_000L) { SourceRepository.search(src.json, kw, 1) }
                        }
                        val latency = System.currentTimeMillis() - t0
                        // 屏蔽池：对搜索结果的书名/作者/简介应用标题作用域替换（scope 用书源名匹配）
                        val books = res.getOrNull()?.getOrNull().orEmpty().map { b ->
                            b.copy(
                                name = repo.purifyTitle(b.name, src.name),
                                author = repo.purifyTitle(b.author, src.name),
                                intro = repo.purifyTitle(b.intro, src.name),
                            )
                        }
                        when {
                            res.isFailure || res.getOrNull()?.isFailure == true -> {
                                // 失败分档：连不通/超时（多为没开 VPN）轻罚且自愈；限流中罚；源站失效重罚熔断。
                                val errMsg = res.exceptionOrNull()?.message
                                    ?: res.getOrNull()?.exceptionOrNull()?.message
                                val kind = classifyFail(errMsg)
                                health.recordFail(src.id, kind, latency)
                                lock.withLock {
                                    if (kind == FailKind.NETWORK) netFailCnt++
                                    else if (kind == FailKind.SOURCE) sourceFailCnt++
                                }
                            }
                            books.isEmpty() -> health.recordEmpty(src.id, latency)
                            else -> {
                                val q = books.maxOf { SearchRules.relevance(it.name, kw) }.toDouble() / 1_000_000.0
                                health.recordSuccess(src.id, latency, q)
                            }
                        }
                        // 逐源实时写权重：本源一搜完就 useCount+1（命中再 hitCount+1），UI 立即看到变化，
                        // 不再等全部 2000 源跑完才批量写；就地刷新内存，不整表 reload，避免卡顿。
                        runCatching { repo.recordStatLive(src.id, books.isNotEmpty()) }
                        if (books.isNotEmpty()) {
                            lock.withLock {
                                hitIds.add(src.id)
                                // 搜书命中即视为该源可用，同步写入连通检测结果（首页/书源页共用同一份 SP，会自动刷新）。
                                // 命中只把该源移入 ok、从 bad/suspect 移除；失败/空结果不改判定，避免误伤。
                                markNetOkLocked(src.id)
                                val merged = _results.value + books.map { SourcedBook(it, src) }
                                // 同名同作者的多源结果合并成一条，携带全部来源；按组内最高相关度排序。
                                _results.value = aggregate(merged, kw)
                            }
                        }
                        lock.withLock {
                            done += 1
                            _progress.value = done to enabled.size
                        }
                    }
                }
            }
            // 子 job 超时/异常时 join() 会重抛，必须逐个容错，否则父协程在此中断、
            // recordSearchStats 永不执行（权重全 0）。
            jobs.forEach { runCatching { it.join() } }
            // 全局网络故障判定：零命中且网络类失败占多数（多于源站失败）→ 判为本机没开 VPN/断网，
            // 期间网络惩罚减免、境外源不被熔断沉底；只要有一源命中就立即解除、全部自愈。
            health.setNetworkDown(
                hitIds.isEmpty() && enabled.size >= 5 &&
                    netFailCnt >= enabled.size * 0.6 && netFailCnt > sourceFailCnt
            )
            // 权重已改为逐源实时写（见上方 recordStatLive），此处不再批量写，避免重复计数。
            if (_results.value.isEmpty()) {
                _message.value = "没有搜到结果"
            } else {
                // 写缓存：非空结果 + 当前书源签名 + 时间戳；超出上限淘汰最早条目（LRU）。
                searchCache[kw] = SearchCacheEntry(_results.value, cacheSignature, System.currentTimeMillis())
                while (searchCache.size > CACHE_MAX) {
                    val oldest = searchCache.keys.firstOrNull() ?: break
                    searchCache.remove(oldest)
                }
            }
            // 持久化最近一次结果与词：进详情预览再返回/页面重建后直接回填，不重新发网络请求。
            saveLastResult(kw, _results.value)
            _searching.value = false
        }
    }

    /**
     * 照搬阅读（legado）SearchModel.mergeItems 的四档分组逻辑。
     * 档位：equal(书名/作者 == 关键词) > tags(分类标签含关键词) > contains(书名/作者含关键词) > other(其余沾字)。
     * 档间按档位顺序排列；同档内同名同作者的书合并 origins，并按来源数降序（来源越多越靠前）。
     * other 档里完全不沾关键词的书丢弃（对应阅读 precision 的兜底）。
     */
    /**
     * 聚合委托给 shared（com.yg.comic.shared.SearchAggregator），Android 端只做 DTO 薄映射。
     * shared 用轻量 DTO 不依赖 app 模型；这里按 (sourceId, bookUrl) 反查原始 SourcedBook，
     * 保留完整 SearchBook 字段（封面/字数/最新章/简介等），行为与抽离前一致。
     */
    private fun aggregate(merged: List<SourcedBook>, kw: String): List<SourcedBook> {
        if (merged.isEmpty()) return emptyList()
        val byKey = LinkedHashMap<Pair<Long, String>, SourcedBook>()
        for (sb in merged) byKey.putIfAbsent(sb.source.id to sb.book.bookUrl, sb)
        val inputs = merged.map { sb ->
            AggBookIn(
                name = sb.book.name,
                author = sb.book.author,
                kind = sb.book.kind,
                sourceId = sb.source.id,
                sourceJson = sb.source.json,
                sourceName = sb.source.name,
                bookUrl = sb.book.bookUrl,
                origins = (sb.origins.ifEmpty { listOf(sourceOriginOf(sb)) }).map { o ->
                    AggOrigin(o.sourceId, o.sourceJson, o.sourceName, o.bookUrl, o.tocUrl, sb.book.coverUrl)
                },
                coverUrl = sb.book.coverUrl,
            )
        }
        val outputs = SearchAggregator.aggregate(inputs, kw)
        return outputs.mapNotNull { out ->
            val base = byKey[out.sourceId to out.bookUrl] ?: return@mapNotNull null
            val origins = out.origins.map { o ->
                SourceOrigin(o.sourceId, o.sourceJson, o.sourceName, o.bookUrl, o.tocUrl)
            }
            base.copy(book = base.book.copy(
                author = out.author,
                coverUrl = out.coverUrl.ifBlank { base.book.coverUrl },
            ), origins = origins)
        }
    }

    private fun sourceOriginOf(sb: SourcedBook) = SourceOrigin(
        sourceId = sb.source.id,
        sourceJson = sb.source.json,
        sourceName = sb.source.name,
        bookUrl = sb.book.bookUrl,
    )

    /**
     * 搜书命中后同步该源的连通判定为"通网"（状态1）。
     * 与 BookSourceViewModel/HomeScreen 共用 SharedPreferences "source_net_check"：
     * 从 bad/suspect 集合移除、加入 ok 集合；书源页/首页下次进入或监听到变化时自动刷新。
     * 必须在 search 的 lock.withLock 内调用（局部变量已串行化）。
     */
    private fun markNetOkLocked(sourceId: Long) {
        runCatching {
            val sp = repo.appContext.getSharedPreferences("source_net_check", android.content.Context.MODE_PRIVATE)
            val id = sourceId.toString()
            val ok = sp.getStringSet("ok", emptySet())!!.toMutableSet()
            val bad = sp.getStringSet("bad", emptySet())!!.toMutableSet()
            val suspect = sp.getStringSet("suspect", emptySet())!!.toMutableSet()
            ok.add(id)
            bad.remove(id)
            suspect.remove(id)
            sp.edit()
                .putStringSet("ok", ok)
                .putStringSet("bad", bad)
                .putStringSet("suspect", suspect)
                .apply()
        }
    }

    /** 加入书架：先取详情拿 tocUrl，再落库（含全部来源 JSON） */
    fun addToShelf(sb: SourcedBook) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val detail = SourceRepository.detail(sb.source.json, sb.book.bookUrl).getOrNull()
                // Rust detail 在 toc_url 为空时会回退到 book_url，所以搜索结果的 bookUrl
                // 本身就是可用的目录地址。detail 失败(源站失效/POST 详情页)时用它回退。
                val tocUrl = detail?.tocUrl?.ifBlank { null } ?: sb.book.bookUrl.ifBlank { null }
                if (tocUrl == null) { _message.value = "无法获取目录地址，加入失败"; return@launch }
                // 主源用刚取到的 tocUrl；其余来源留空，切源时按需再取详情。
                val allOrigins = sb.origins.ifEmpty { listOf(sourceOriginOf(sb)) }
                val withToc = allOrigins.mapIndexed { i, o ->
                    if (i == 0) o.copy(tocUrl = tocUrl) else o
                }
                repo.importNetworkBook(
                    sourceId = sb.source.id,
                    sourceJson = sb.source.json,
                    sourceName = sb.source.name,
                    title = sb.book.name,
                    author = sb.book.author,
                    bookUrl = sb.book.bookUrl,
                    tocUrl = tocUrl,
                    coverUrl = detail?.coverUrl?.ifBlank { null } ?: sb.book.coverUrl,
                    originsJson = aggregatorJson.encodeToString(withToc),
                    mediaType = if (sb.source.sourceType == 2 &&
                        com.yg.comic.shared.ComicSourceRule.isComicSource(sb.source.name, sb.source.url))
                        MediaType.COMIC else MediaType.NOVEL,
                )
                val count = withToc.size
                // 排序漏斗：真正加入书架=采纳信号，给这些源提权。
                recordAdopt(sb)
                // 即时反馈：把书名加入已加入集合，按钮立刻变"已加入"，不等 repo.books Flow 回流。
                _addedKeys.value = _addedKeys.value + shelfKey(sb.book.name)
                _message.value = "已加入书架：${sb.book.name}" + if (count > 1) "（$count 个来源）" else ""
            } catch (e: Exception) {
                _message.value = "加入失败：${e.message}"
            }
        }
    }
}
