package com.yg.comic.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yg.comic.data.BookRepository
import com.yg.comic.data.ChapterEntity
import com.yg.comic.data.ChapterOrderStore
import com.yg.comic.data.SettingsStore
import com.yg.comic.data.SourceHealthStore
import com.yg.comic.model.MediaType
import com.yg.comic.shared.FailKind
import com.yg.comic.sr.SRModel
import com.yg.comic.sr.preload.PreloadSrQueue
import com.yg.comic.sr.preload.buildNetworkRefs
import com.yg.comic.source.BookDetail
import com.yg.comic.source.SourceChapter
import com.yg.comic.source.SourceOrigin
import com.yg.comic.source.SourceRepository
import com.yg.comic.ui.source.SourcedBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 书籍详情页：展示封面/作者/字数/分类/简介/章节目录，
 * 支持在多个来源之间切换（换源），加入书架时带上封面与全部来源。
 */
class BookDetailViewModel(
    private val repo: BookRepository,
    private val sb: SourcedBook,
) : ViewModel() {

    private val aggregatorJson = Json { ignoreUnknownKeys = true }
    private val health = SourceHealthStore(repo.appContext)
    private val netPrefs = repo.appContext.getSharedPreferences("source_net_check", Context.MODE_PRIVATE)

    private fun keyOf(o: SourceOrigin): String = o.sourceJson + "|" + o.bookUrl

    /** 与搜索页/书源页一致的网络状态分层：通网0 / 存疑1 / 未检测2 / 不通3（不通排最后）。 */
    private fun netTier(id: Long): Int {
        val s = id.toString()
        val ok = netPrefs.getStringSet("ok", emptySet()) ?: emptySet()
        val suspect = netPrefs.getStringSet("suspect", emptySet()) ?: emptySet()
        val bad = netPrefs.getStringSet("bad", emptySet()) ?: emptySet()
        return when {
            ok.contains(s) -> 0
            suspect.contains(s) -> 1
            bad.contains(s) -> 3
            else -> 2
        }
    }

    /** 各来源已缓存章数（sourceJson|bookUrl -> 章数），用于跨源章数异常降权。 */
    private val chapterCountByKey = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 内容质量差被降权的来源 key（无章节 / 章数异常 / 正文全空），排序时沉底。 */
    private val qualityDemotedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 排序：内容质量降权沉底 → 网络状态分层 → 健康度（权重）降序。 */
    private val sourceOrder = compareBy<SourceOrigin> { keyOf(it) in qualityDemotedKeys }
        .thenBy { netTier(it.sourceId) }
        .thenByDescending { health.score(it.sourceId) }

    /**
     * 聚合来源：去重并补全空字段后，按「网络状态分层 + 权重」排序。
     * 该列表可变：某来源经加载确认无章节时会动态沉底（见 demote）。
     */
    private val _origins = MutableStateFlow<List<SourceOrigin>>(run {
        val main = SourceOrigin(
            sourceId = sb.source.id,
            sourceJson = sb.source.json,
            sourceName = sb.source.name,
            bookUrl = sb.book.bookUrl,
        )
        val map = LinkedHashMap<String, SourceOrigin>()
        map[keyOf(main)] = main
        sb.origins.forEach { o ->
            val json = o.sourceJson.ifBlank { main.sourceJson }
            val url = o.bookUrl.ifBlank { main.bookUrl }
            val key = json + "|" + url
            map.putIfAbsent(
                key,
                o.copy(
                    sourceId = if (o.sourceId == 0L) main.sourceId else o.sourceId,
                    sourceJson = json,
                    sourceName = o.sourceName.ifBlank { main.sourceName },
                    bookUrl = url,
                ),
            )
        }
        map.values.sortedWith(sourceOrder)
    })
    val origins: StateFlow<List<SourceOrigin>> = _origins.asStateFlow()

    /**
     * 记录某来源的已缓存章数，按跨源中位数判定「章数异常」：
     * 章数 < 50 且不足有效来源中位数的 1/10（典型坏源 0/1 章）→ 内容质量降权沉底。
     * 有效来源（章数 > 0）不足 2 个时不判定，避免样本太少误伤。
     */
    private fun recordChapterCount(key: String, count: Int) {
        chapterCountByKey[key] = count
        // 当前选中的来源不参与降权排序：用户刚点开的源即使暂时 0 章，也不能被甩到最后。
        val currentKey = keyOf(_origins.value.getOrNull(_originIndex.value) ?: return)
        if (key == currentKey) return
        val counts = chapterCountByKey.values.filter { it > 0 }.sorted()
        if (counts.size < 2) return
        val median = counts[counts.size / 2]
        if (count < 50 && count * 10 < median) {
            if (qualityDemotedKeys.add(key)) refreshOrder()
        }
    }

    /** 按当前降权集合重排来源，并保持当前选中项跟随移动。 */
    private fun refreshOrder() {
        val currentKey = keyOf(_origins.value.getOrNull(_originIndex.value) ?: return)
        val sorted = _origins.value.sortedWith(sourceOrder)
        _origins.value = sorted
        val newIdx = sorted.indexOfFirst { keyOf(it) == currentKey }
        if (newIdx >= 0) _originIndex.value = newIdx
    }

    /** 某来源经加载确认无章节 → 并入内容质量降权集合并沉底。 */
    private fun demote(origin: SourceOrigin) {
        val key = keyOf(origin)
        // 用户当前正在看的来源即使暂时 0 章也不能当场沉底，否则点击书源会突然跳到最后一个。
        val current = _origins.value.getOrNull(_originIndex.value)
        if (current != null && keyOf(current) == key) return
        if (qualityDemotedKeys.add(key)) refreshOrder()
    }

    private val _originIndex = MutableStateFlow(0)
    val originIndex: StateFlow<Int> = _originIndex.asStateFlow()

    private val _detail = MutableStateFlow<BookDetail?>(null)
    val detail: StateFlow<BookDetail?> = _detail.asStateFlow()

    private val _chapters = MutableStateFlow<List<SourceChapter>>(emptyList())
    val chapters: StateFlow<List<SourceChapter>> = _chapters.asStateFlow()

    /** 原始目录顺序（始终正序），用于反序展示和按原始章号预超分。 */
    private var rawChapters: List<SourceChapter> = emptyList()

    private val _chapterReversed = MutableStateFlow(false)
    val chapterReversed: StateFlow<Boolean> = _chapterReversed.asStateFlow()

    private fun publishChapters(list: List<SourceChapter>) {
        rawChapters = list
        _chapters.value = if (_chapterReversed.value) list.asReversed() else list
    }

    fun setChapterOrder(reversed: Boolean) {
        // 不能因为「来源列表还没回来」就直接 return：那样点反序会毫无反应（用户症状「有时候反不过来」）。
        // 来源没就位时用这本书自己的 sourceJson/bookUrl 兜底落库，展示态照样立刻切。
        val origin = _origins.value.getOrNull(_originIndex.value)
        val sj = origin?.sourceJson?.takeIf { it.isNotBlank() } ?: sb.source.json
        val bu = origin?.bookUrl?.takeIf { it.isNotBlank() } ?: sb.book.bookUrl
        _chapterReversed.value = reversed
        ChapterOrderStore.setReversed(sj, bu, reversed)
        _chapters.value = if (reversed) rawChapters.asReversed() else rawChapters
    }

    fun toggleChapterOrder() = setChapterOrder(!_chapterReversed.value)

    /** 反序展示时，把列表里的显示下标换回原始章号（长按预超分要用原始目录）。 */
    fun originalChapterIndex(displayedIndex: Int): Int =
        if (_chapterReversed.value) (rawChapters.size - 1 - displayedIndex).coerceAtLeast(0)
        else displayedIndex

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * 当前来源的目录是否已就绪（本地缓存命中或网络目录已拉到）。
     * 切源后秒显阶段目录为空、仍在后台加载，此时「开始阅读」不可用——
     * 否则 prepareTempRead 会拿兜底的 bookUrl 当目录地址冲进阅读器，报「目录获取失败」。
     * 之后目录拉到（哪怕确认 0 章）也置 true，避免永久禁用。
     */
    private val _tocReady = MutableStateFlow(false)
    val tocReady: StateFlow<Boolean> = _tocReady.asStateFlow()

    /**
     * 当前来源真正用来拉目录的地址（detail 补全后的 tocUrl / origin.tocUrl）。
     * 用它代替 bookUrl 兜底传给阅读器，避免把详情页地址当目录地址。
     */
    private val _resolvedTocUrl = MutableStateFlow("")
    val resolvedTocUrl: StateFlow<String> = _resolvedTocUrl.asStateFlow()

    /** 当前书是否已加入书架（按归一书名匹配），用于「加入书架」按钮变蓝且不可重复点。 */
    private val _isAdded = MutableStateFlow(false)
    val isAdded: StateFlow<Boolean> = _isAdded.asStateFlow()
    private val _adding = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /** 本页对每个来源的赞(1)/踩(-1)状态，key=keyOf；再次点同项可取消(0)。仅本页视觉反馈。 */
    private val _thumbState = MutableStateFlow<Map<String, Int>>(emptyMap())
    val thumbState: StateFlow<Map<String, Int>> = _thumbState.asStateFlow()

    fun thumbOf(o: SourceOrigin): Int = _thumbState.value[keyOf(o)] ?: 0

    init {
        ChapterOrderStore.init(repo.appContext)
        loadOrigin(0)
        // 已在书架则「加入书架」按钮直接变蓝、不可重复添加；书架变化实时同步。
        viewModelScope.launch(Dispatchers.IO) {
            val key = com.yg.comic.shared.SearchRules.shelfKey(sb.book.name)
            repo.books.collect { books ->
                _isAdded.value = books.any { com.yg.comic.shared.SearchRules.shelfKey(it.title) == key }
            }
        }
        // 内容质量降权：后台预扫各来源本地缓存章数 + 空正文标记，按跨源中位数判定坏源沉底。
        // 只读本地 chapters 索引与 SharedPreferences，不触发网络。
        viewModelScope.launch(Dispatchers.IO) {
            val origins = _origins.value
            for (o in origins) {
                val sj = o.sourceJson.ifBlank { sb.source.json }
                val bu = o.bookUrl.ifBlank { sb.book.bookUrl }
                val ck = repo.chapterCacheKey(sj, bu)
                val count = runCatching { repo.chaptersFor(ck).size }.getOrDefault(0)
                if (repo.hasEmptyContent(sj, bu)) qualityDemotedKeys.add(keyOf(o))
                recordChapterCount(keyOf(o), count)
            }
            refreshOrder()
        }
    }

    fun selectOrigin(index: Int) {
        if (index !in _origins.value.indices) return
        _originIndex.value = index
        loadOrigin(index)
    }

    /**
     * 对整个书源点赞/点踩（作用于该来源所属书源的全部书，非仅当前这本）。
     * 赞=记一次高质量命中；踩=记一次源站失败，健康度下降后来源自动沉底。
     */
    fun recordThumb(index: Int, up: Boolean) {
        val origin = _origins.value.getOrNull(index) ?: return
        if (up) health.recordSuccess(origin.sourceId, 0L, 1.0)
        else health.recordFail(origin.sourceId, FailKind.SOURCE)
        // 视觉反馈：同一项再次点击可取消；赞/踩互斥。健康度照记，但本页不立即重排，
        // 新顺序下次进入本书（重建本 VM）时才按更新后的权重生效。
        val key = keyOf(origin)
        val want = if (up) 1 else -1
        _thumbState.value = _thumbState.value.toMutableMap().also { m ->
            if (m[key] == want) m.remove(key) else m[key] = want
        }
    }

    /** 换源/重复加载时递增，丢弃过期协程的回填，避免旧源结果覆盖新源 */
    @Volatile
    private var loadGen = 0

    /**
     * 加载某来源的详情与目录（照 legado 的「秒显 + 本地目录优先 + 后台补全」模式）：
     * 1. 先用搜索结果里已有的字段立即填充详情、结束 loading —— 用户零等待看到书名/封面/简介；
     * 2. 目录优先读本地缓存，命中即显示（后续进本源目录零网络）；
     * 3. 后台并发拉 detail 与 toc：detail 补全元数据并回填保存用的 tocUrl，
     *    toc 拉到后整组落库，供下次秒显。网络失败不影响已展示的内容。
     */
    private fun loadOrigin(index: Int) {
        val origin = _origins.value.getOrNull(index) ?: return
        val gen = ++loadGen
        val sourceJson = origin.sourceJson.ifBlank { sb.source.json }
        val bookUrl = origin.bookUrl.ifBlank { sb.book.bookUrl }
        val cacheKey = repo.chapterCacheKey(sourceJson, bookUrl)

        // 1) 秒显：搜索结果字段立即填充，不等待任何网络
        _detail.value = BookDetail(
            name = sb.book.name,
            author = sb.book.author,
            kind = sb.book.kind,
            wordCount = sb.book.wordCount,
            lastChapter = sb.book.lastChapter,
            intro = sb.book.intro,
            coverUrl = sb.book.coverUrl,
            tocUrl = origin.tocUrl,
        )
        _chapterReversed.value = ChapterOrderStore.isReversed(sourceJson, bookUrl)
        publishChapters(emptyList())
        _loading.value = false
        // 目录进入加载态：秒显的空目录不算就绪，禁用「开始阅读」直到本地/网络目录到位。
        _tocReady.value = false
        // 清掉上一源的目录地址，避免切源瞬间误用旧地址
        _resolvedTocUrl.value = origin.tocUrl

        viewModelScope.launch(Dispatchers.IO) {
            // 2) 本地目录优先：命中即秒显
            val cached = runCatching { repo.chaptersFor(cacheKey) }.getOrDefault(emptyList())
            if (gen != loadGen) return@launch
            if (cached.isNotEmpty()) {
                // 屏蔽池：章节标题也过标题作用域替换
                val scope = origin.sourceName
                val out = ArrayList<SourceChapter>(cached.size)
                for (it in cached) {
                    out.add(SourceChapter(title = repo.purifyTitle(it.title, scope), url = it.url, isVip = it.isVip, isVolume = it.isVolume))
                }
                // 本地目录也过目录收口：老库里可能存着「镜像重复/碎片 URL」的坏目录，
                // 不在读的时候收一遍，章号会一直是错的（用户按目录点章对不上正文）。
                val normToc = com.yg.comic.source.SourceRepository.orderAscending(
                    com.yg.comic.source.SourceRepository.normalizeToc(out),
                )
                publishChapters(normToc)
                _tocReady.value = true
            }
            // 内容质量降权：记录该源本地缓存章数，供跨源中位数判定章数异常
            recordChapterCount(keyOf(origin), _chapters.value.size)

            // 3) 后台补全 detail（拿更精确的字段与 tocUrl）
            val d = runCatching { SourceRepository.detail(sourceJson, bookUrl).getOrNull() }.getOrNull()
            if (gen != loadGen) return@launch
            if (d != null) {
                // 屏蔽池：详情的书名/作者/分类/最新章/简介套标题作用域替换（scope 用当前源名）
                val scope = origin.sourceName
                // 回填 detail 解析出的真实 tocUrl：秒显时 _detail.tocUrl 只是搜索结果里的
                // origin.tocUrl（常为空），若不回填，点阅读会用 bookUrl 兜底当目录地址而报错。
                val realToc = d.tocUrl.ifBlank { origin.tocUrl }
                _detail.value = d.copy(
                    name = repo.purifyTitle(d.name, scope),
                    author = repo.purifyTitle(d.author, scope),
                    kind = repo.purifyTitle(d.kind, scope),
                    lastChapter = repo.purifyTitle(d.lastChapter, scope),
                    intro = repo.purifyTitle(d.intro, scope),
                    tocUrl = realToc,
                )
                if (realToc.isNotBlank()) _resolvedTocUrl.value = realToc
            }

            // 4) 目录：本地没有才发网络，拉到后整组落库
            if (cached.isEmpty()) {
                val tocUrl = d?.tocUrl?.ifBlank { null }
                    ?: origin.tocUrl.ifBlank { null }
                    ?: bookUrl
                val toc = if (tocUrl.isNotBlank()) {
                    runCatching { SourceRepository.toc(sourceJson, tocUrl).getOrDefault(emptyList()) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                if (gen != loadGen) return@launch
                // 屏蔽池：网络目录章节标题也过标题作用域替换
                val scope = origin.sourceName
                val cleanToc = toc.map { it.copy(title = repo.purifyTitle(it.title, scope)) }
                publishChapters(cleanToc)
                // 目录已处理完（有章节或确认 0 章）：允许「开始阅读」，不再拿兜底地址冲进阅读器。
                _tocReady.value = true
                if (cleanToc.isNotEmpty()) {
                    runCatching {
                        repo.saveChapters(
                            cacheKey,
                            cleanToc.mapIndexed { i, ch ->
                                ChapterEntity(
                                    cacheKey = cacheKey, url = ch.url, title = ch.title,
                                    isVip = ch.isVip, isVolume = ch.isVolume, orderIndex = i,
                                )
                            },
                        )
                    }
                    // 内容质量降权：记录该源网络目录章数，供跨源中位数判定章数异常
                    recordChapterCount(keyOf(origin), cleanToc.size)
                } else {
                    // 确认无章节（本地无缓存且网络目录为空）→ 该来源沉底
                    recordChapterCount(keyOf(origin), 0)
                    demote(origin)
                }
            }
        }
    }

    /**
     * 加入书架。返回 bookId 供「开始阅读」跳转；失败返回 null 并写入 message。
     * 每个来源的 tocUrl 都回填：当前源用详情拿到的 tocUrl（回退 bookUrl），
     * 其余源用各自已有的 tocUrl（回退各自 bookUrl），供后续阅读页换源。
     */
    suspend fun addToShelf(): Long? = withContext(Dispatchers.IO) {
        // 防重入：已在书架或正在加入时直接复用现有结果，避免连点重复添加。
        if (_isAdded.value || _adding.getAndSet(true)) {
            return@withContext repo.books.first().firstOrNull {
                com.yg.comic.shared.SearchRules.shelfKey(it.title) ==
                    com.yg.comic.shared.SearchRules.shelfKey(sb.book.name)
            }?.id
        }
        try {
            val list = _origins.value
            val idx = _originIndex.value.coerceIn(0, list.lastIndex)
            val main = list[idx]
            val d = _detail.value
            val resolvedToc = _resolvedTocUrl.value
            val withToc = list.mapIndexed { i, o ->
                val url = o.bookUrl.ifBlank { sb.book.bookUrl }
                val toc = if (i == idx) {
                    resolvedToc.ifBlank { null }
                        ?: d?.tocUrl?.ifBlank { null }
                        ?: o.tocUrl.ifBlank { null }
                        ?: url
                } else {
                    o.tocUrl.ifBlank { null } ?: url
                }
                o.copy(tocUrl = toc)
            }
            val bookUrl = main.bookUrl.ifBlank { sb.book.bookUrl }
            val id = repo.importNetworkBook(
                sourceId = main.sourceId,
                sourceJson = main.sourceJson.ifBlank { sb.source.json },
                sourceName = main.sourceName.ifBlank { sb.source.name },
                title = d?.name?.ifBlank { null } ?: sb.book.name,
                author = d?.author?.ifBlank { null } ?: sb.book.author,
                bookUrl = bookUrl,
                tocUrl = withToc[idx].tocUrl,
                coverUrl = d?.coverUrl?.ifBlank { null } ?: sb.book.coverUrl,
                originsJson = aggregatorJson.encodeToString(withToc),
                categoryIndex = sb.categoryIndex,
                mediaType = if (sb.source.sourceType == 2 &&
                    com.yg.comic.shared.ComicSourceRule.isComicSource(sb.source.name, sb.source.url))
                    MediaType.COMIC else MediaType.NOVEL,
            )
            _isAdded.value = true
            id
        } catch (e: Throwable) {
            _message.value = "加入书架失败：${e.message ?: e::class.simpleName}"
            null
        } finally {
            _adding.set(false)
        }
    }

    /**
     * 「开始阅读」用：带上当前选中源及全部来源的目录地址，但【不加入书架】。
     * 阅读器以 bookId=-1L 识别临时态，只读不落库、进度不保存。
     */
    fun prepareTempRead(initialChapterIndex: Int = 0): SharedTempRead.TempReadBook? {
        val list = _origins.value
        if (list.isEmpty()) return null
        val idx = _originIndex.value.coerceIn(0, list.lastIndex)
        val main = list[idx]
        val d = _detail.value
        val resolvedToc = _resolvedTocUrl.value
        val withToc = list.mapIndexed { i, o ->
            val url = o.bookUrl.ifBlank { sb.book.bookUrl }
            val toc = if (i == idx) {
                resolvedToc.ifBlank { null }
                    ?: d?.tocUrl?.ifBlank { null }
                    ?: o.tocUrl.ifBlank { null }
                    ?: url
            } else {
                o.tocUrl.ifBlank { null } ?: url
            }
            o.copy(tocUrl = toc)
        }
        return SharedTempRead.TempReadBook(
            sourceId = main.sourceId,
            sourceJson = main.sourceJson.ifBlank { sb.source.json },
            sourceName = main.sourceName.ifBlank { sb.source.name },
            bookUrl = main.bookUrl.ifBlank { sb.book.bookUrl },
            tocUrl = withToc[idx].tocUrl,
            originsJson = aggregatorJson.encodeToString(withToc),
            title = d?.name?.ifBlank { null } ?: sb.book.name,
            author = d?.author?.ifBlank { null } ?: sb.book.author,
            coverUrl = d?.coverUrl?.ifBlank { null } ?: sb.book.coverUrl,
            categoryIndex = sb.categoryIndex,
            initialChapterIndex = initialChapterIndex,
            initialChapterUrl = rawChapters.getOrNull(initialChapterIndex)?.url.orEmpty(),
            initialChapterTitle = rawChapters.getOrNull(initialChapterIndex)?.title.orEmpty(),
            mediaType = if (sb.source.sourceType == 2 &&
                com.yg.comic.shared.ComicSourceRule.isComicSource(sb.source.name, sb.source.url))
                MediaType.COMIC else MediaType.NOVEL,
        )
    }

    /**
     * 长按章节 → 后台批量预超分本章。只处理已入库书：稳定 bookId 才有稳定缓存 key。
     * 章节图片走 content 异步取，未入库/取图失败都给用户明确提示。
     */
    fun enqueueChapterPreload(chapterIndex: Int) {
        val chapter = rawChapters.getOrNull(chapterIndex) ?: return
        if (chapter.isVolume) return
        val settings = SettingsStore(repo.appContext)
        viewModelScope.launch {
            // 按归一书名匹配已入库书，拿稳定 bookId；未入库直接提示
            val bookId = repo.books.first().firstOrNull {
                com.yg.comic.shared.SearchRules.shelfKey(it.title) ==
                    com.yg.comic.shared.SearchRules.shelfKey(sb.book.name)
            }?.id
            if (bookId == null) {
                _message.value = "请先加入书架再预超分"
                return@launch
            }
            val model = SRModel.fromKey(settings.srModelKey.first())
            val autoCrop = settings.autoCropEnabled.first()
            val origin = _origins.value.getOrNull(_originIndex.value) ?: return@launch
            val sourceJson = origin.sourceJson.ifBlank { sb.source.json }
            val images = withContext(Dispatchers.IO) {
                SourceRepository.content(sourceJson, chapter.url)
                    .getOrNull()?.images.orEmpty()
                    .map { it.trim() }.filter { it.isNotBlank() }
            }
            if (images.isEmpty()) {
                _message.value = "本章未取到图片，无法预超分"
                return@launch
            }
            // 防盗链 referer 与阅读侧 deriveReferer 同款，保证取到同样的图
            val referer = runCatching {
                val src = runCatching {
                    org.json.JSONObject(sourceJson).optString("bookSourceUrl", "")
                }.getOrDefault("")
                val uri = java.net.URI.create(src.ifBlank { origin.tocUrl })
                val scheme = uri.scheme
                val host = uri.host
                if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) "$scheme://$host/" else ""
            }.getOrDefault("")
            val refs = buildNetworkRefs(bookId, referer, listOf(images), startChapterIndex = chapterIndex)
            PreloadSrQueue.enqueue(refs, model, autoCrop)
            _message.value = "已把本章 ${images.size} 页加入预超分队列"
        }
    }

    fun setMessage(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = ""
    }
}
