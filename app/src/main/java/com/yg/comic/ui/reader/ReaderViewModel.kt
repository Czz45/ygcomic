package com.yg.comic.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yg.comic.data.BookRepository
import com.yg.comic.data.ChapterOrderStore
import com.yg.comic.data.SettingsStore
import com.yg.comic.model.Book
import com.yg.comic.model.MediaType
import com.yg.comic.model.ReadProgress
import com.yg.comic.model.ReaderSettings
import com.yg.comic.ui.detail.SharedTempRead
import com.yg.comic.source.SourceRepository
import com.yg.comic.source.SourceChapter
import com.yg.comic.source.SourceOrigin
import com.yg.comic.shared.SwitchBookIn
import com.yg.comic.shared.SwitchMatcher
import com.yg.comic.shared.ComicSourceRule
import com.yg.comic.sr.preload.SrPageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReferenceArray

/** 阅读页目录项：标题 + 该章第一张图在扁平页列表中的起点 + 本章页数 + 该章在目录里的下标。 */
data class ReaderChapterRef(
    val title: String,
    val startPage: Int,
    val pageCount: Int = 0,
    val tocIndex: Int = -1,
)

class ReaderViewModel(
    private val repo: BookRepository,
    private val store: SettingsStore,
    private val bookId: Long,
) : ViewModel() {

    val settings: StateFlow<ReaderSettings> =
        store.globalReader.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    private val _pages = MutableStateFlow<List<String>>(emptyList())
    val pages: StateFlow<List<String>> = _pages.asStateFlow()

    // 与 _pages 同序的“页引用”：扁平 index -> 哪本书哪一章哪一页、从哪取原图。
    // 后台预超分写入侧与阅读器读取侧共用这一个映射，key 才不会错位（差一章整批命不中）。
    private val _pageRefs = MutableStateFlow<List<SrPageRef>>(emptyList())
    val pageRefs: StateFlow<List<SrPageRef>> = _pageRefs.asStateFlow()

    fun pageRefAt(flatIndex: Int): SrPageRef? = _pageRefs.value.getOrNull(flatIndex)

    // 阅读页目录：标题 + 起始页，顺序和当前阅读顺序一致。
    private val _chapterRefs = MutableStateFlow<List<ReaderChapterRef>>(emptyList())
    val chapterRefs: StateFlow<List<ReaderChapterRef>> = _chapterRefs.asStateFlow()

    private val _chapterReversed = MutableStateFlow(false)
    val chapterReversed: StateFlow<Boolean> = _chapterReversed.asStateFlow()

    private var chapterTitles: List<String> = emptyList()
    private var chapterImages: List<List<String>> = emptyList()
    private var orderSourceJson: String = ""
    private var orderBookUrl: String = ""

    /** 阅读器内目录用：完整目录标题（按当前显示顺序），网络漫画也只显示已加载窗口那几章会更省，但这里给全量。 */
    private val _tocTitles = MutableStateFlow<List<String>>(emptyList())
    val tocTitles: StateFlow<List<String>> = _tocTitles.asStateFlow()

    /** 智能排序开关：默认关=按书源原始顺序；开=按章号理顺（第一章在前）。
     *  只影响目录展示与点选映射，基准数组/正文顺序/进度一律不动。 */
    private val _smartTocSort = MutableStateFlow(false)
    val smartTocSort: StateFlow<Boolean> = _smartTocSort.asStateFlow()

    /** 目录展示位 -> 基准下标（chapterTitles / chapterRefs / 进度的下标恒定不动）。
     *  正序+不智能=[0,1,2..]；反序=整体倒序；智能排序=按章号升序，再按正反序整体翻转。 */
    private val _tocDisplayOrder = MutableStateFlow<List<Int>>(emptyList())
    val tocDisplayOrder: StateFlow<List<Int>> = _tocDisplayOrder.asStateFlow()

    /** 依据当前 chapterTitles + 正反序 + 智能排序，重算目录展示序列（只动展示层）。 */
    private fun rebuildTocDisplay() {
        if (chapterTitles.isEmpty()) {
            _tocDisplayOrder.value = emptyList()
            _tocTitles.value = emptyList()
            return
        }
        val base = if (_smartTocSort.value) {
            SourceRepository.smartTocOrder(chapterTitles)
        } else {
            chapterTitles.indices.toList()
        }
        val order = if (_chapterReversed.value) base.reversed() else base
        _tocDisplayOrder.value = order
        _tocTitles.value = order.map { chapterTitles[it] }
    }

    /** 阅读器目录弹窗里的智能排序开关：即时重排目录，不重开窗口、不翻页、不动进度。 */
    fun setSmartTocSort(enabled: Boolean) {
        _smartTocSort.value = enabled
        rebuildTocDisplay()
        viewModelScope.launch { store.setSmartTocSort(enabled) }
    }

    // 网络漫画窗口状态：只加载当前章 + 后面两章，避免整本几百章一起拉
    private var netChapters: List<SourceChapter> = emptyList()
    private var netSourceJson: String = ""
    private var netDisplayOrder: List<Int> = emptyList()
    private val netWindow = mutableListOf<Int>()
    private val netWindowImages = mutableListOf<List<String>>()
    private var netWindowPos = 0
    private var netLoading = false
    /** 目录还没加载完就点了正/反序：先记下来，加载时以它为准，别被旧存储值盖回去。 */
    private var pendingReversed: Boolean? = null

    private val _current = MutableStateFlow(0)
    val current: StateFlow<Int> = _current.asStateFlow()

    private val _title = MutableStateFlow("漫画")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    // 网络漫画加载错误/提示：目录失败、逐章全失败时给 UI 明确文案，杜绝无限转圈
    private val _loadMessage = MutableStateFlow<String?>(null)
    val loadMessage: StateFlow<String?> = _loadMessage.asStateFlow()

    // 网络漫画图片防盗链 Referer：从书源主域名推导，Coil 加载图片时带此请求头
    private val _referer = MutableStateFlow("")
    val referer: StateFlow<String> = _referer.asStateFlow()

    // PDF 实时阅读：不落到图片目录，按页索引实时渲染源 PDF
    private val _isPdf = MutableStateFlow(false)
    val isPdf: StateFlow<Boolean> = _isPdf.asStateFlow()
    private val _pdfPath = MutableStateFlow("")
    val pdfPath: StateFlow<String> = _pdfPath.asStateFlow()

    // TXT 小说：仅作为类型判定（真正的小说阅读走独立 NovelReaderViewModel）
    private val _isTxt = MutableStateFlow(false)
    val isTxt: StateFlow<Boolean> = _isTxt.asStateFlow()

    // 网络漫画：图片章节的 URL 由书源实时解析，PageImage 走 Coil 网络加载而非本地 ImageCache
    private val _isNetworkComic = MutableStateFlow(false)
    val isNetworkComic: StateFlow<Boolean> = _isNetworkComic.asStateFlow()

    // 上一本/下一本：同合集的漫画按自然排序，找不到则退回全库漫画
    private val _siblings = MutableStateFlow<List<Book>>(emptyList())
    val siblings: StateFlow<List<Book>> = _siblings.asStateFlow()

    // ===== 换源：本地多源列表 + 联网同名同作者搜索（照搬小说阅读器） =====
    private val originJson = Json { ignoreUnknownKeys = true }

    /** 本书来源列表（含主源），来自导入书架时保存的 originsJson；临时阅读来自 temp.originsJson。 */
    private val _origins = MutableStateFlow<List<SourceOrigin>>(emptyList())
    val origins: StateFlow<List<SourceOrigin>> = _origins.asStateFlow()

    private val _sourceIndex = MutableStateFlow(-1)
    val sourceIndex: StateFlow<Int> = _sourceIndex.asStateFlow()

    /** 一个联网换源候选（搜索结果 + 它所属的源） */
    data class SwitchCandidate(
        val sourceId: Long,
        val sourceJson: String,
        val sourceName: String,
        val bookUrl: String,
        val author: String,
        val coverUrl: String,
        val name: String = "",
        val kind: String = "",
        val wordCount: String = "",
        val lastChapter: String = "",
        val intro: String = "",
        /** 同一本书来自其他源时一并挂这里，点一次把全部来源加入可切换列表 */
        val moreSources: List<SwitchSourceRef> = emptyList(),
    )

    /** 聚合候选里的一个附属来源 */
    data class SwitchSourceRef(
        val sourceId: Long,
        val sourceJson: String,
        val sourceName: String,
        val bookUrl: String,
    )

    private val _switchCandidates = MutableStateFlow<List<SwitchCandidate>>(emptyList())
    val switchCandidates: StateFlow<List<SwitchCandidate>> = _switchCandidates.asStateFlow()
    private val _switchLoading = MutableStateFlow(false)
    val switchLoading: StateFlow<Boolean> = _switchLoading.asStateFlow()
    private val _switchMsg = MutableStateFlow("")
    val switchMsg: StateFlow<String> = _switchMsg.asStateFlow()

    /** 最近阅读章（onPageChanged 持续更新），换源后恢复到这一章 */
    private var lastChapterIndex: Int = 0

    /**
     * 换源代数：每次切源重载正文后 +1。
     * Screen 把它加进分页 remember key，强制重置分页、重新定位，
     * 避免「切源后停在旧页码看起来没生效」。
     */
    private val _switchEpoch = MutableStateFlow(0)
    val switchEpoch: StateFlow<Int> = _switchEpoch.asStateFlow()

    private val switchCache = ConcurrentHashMap<String, List<SwitchCandidate>>()
    private var switchSearchJob: Job? = null

    /**
     * 网络漫画（COMIC）：走可插拔 Provider（当前 Legado），拉目录→逐章 content 拿 images
     * →扁平成页 URL，让 ReaderScreen 用 Coil 按网络图加载（PageImage 本地 ImageCache 不认 URL）。
     * @param progressBookId 落库书用 bookId 读进度；临时阅读传 null，进度不保存、从 0 开始。
     * @return 是否成功拉到图片。
     */
    /**
     * 从书源域名推导图片请求的 Referer（防盗链）。
     * 优先取书源 bookSourceUrl，退而取目录 URL；只保留 scheme://host/，去掉路径与查询。
     */
    private fun deriveReferer(sourceJson: String, tocUrl: String): String = runCatching {
        val src = runCatching { org.json.JSONObject(sourceJson).optString("bookSourceUrl", "") }.getOrDefault("")
        val candidate = src.ifBlank { tocUrl }
        val uri = java.net.URI.create(candidate)
        val scheme = uri.scheme
        val host = uri.host
        if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) "$scheme://$host/" else ""
    }.getOrDefault("")

    /**
     * 网络漫画图片加载（流式）：
     * - 目录拿到即声明为网络漫画并给出 Referer；目录失败给明确文案，不再无限转圈。
     * - 逐章并发（上限 6），每章单独 withTimeout + runCatching 兜底，单章卡住/失败不影响其它章。
     * - 每完成一章就按章序合并已到图的槽位发布到 _pages，首批图到达即 _loaded=true。
     */
    /** 网络漫画一次加载的章节数：当前章 + 后面 2 章。整本几百章一起拉会长时间「加载中」并让页码乱跳。 */
    private val comicWindowSize = 3

    private suspend fun loadNetworkComicPages(
        sourceJson: String,
        tocUrl: String,
        progressBookId: Long? = null,
        bookUrl: String = "",
        initialChapterIndex: Int = 0,
    ): Boolean {
        // 必须按源 JSON 选 provider：Mihon 源不能落到 Legado 的 Rust 引擎。
        val provider = SourceRepository.providerFor(sourceJson)
        _isNetworkComic.value = true
        _referer.value = deriveReferer(sourceJson, tocUrl)
        // 目录优先读本地缓存（详情页加载时已落库，key=sourceJson|bookUrl）。
        // 这样即使传进来的 tocUrl 是兜底的详情页地址，也能用已存的目录直接开读，
        // 不会因联网 toc 拉不到而弹「目录获取失败」。缓存空才联网拉取。
        val cachedChapters = if (bookUrl.isNotBlank()) {
            withContext(Dispatchers.IO) {
                runCatching { repo.chaptersFor(repo.chapterCacheKey(sourceJson, bookUrl)) }
                    .getOrDefault(emptyList())
            }
        } else emptyList()
        val chapters = if (cachedChapters.isNotEmpty()) {
            com.yg.comic.source.SourceRepository.orderAscending(
                com.yg.comic.source.SourceRepository.normalizeToc(
                    cachedChapters.map {
                        SourceChapter(
                            title = it.title, url = it.url,
                            isVip = it.isVip, isVolume = it.isVolume,
                        )
                    },
                ),
            )
        } else {
            com.yg.comic.source.SourceRepository.orderAscending(
                com.yg.comic.source.SourceRepository.normalizeToc(
                    provider.toc(sourceJson, tocUrl).getOrNull().orEmpty(),
                ),
            )
        }
        if (chapters.isEmpty()) {
            _loadMessage.value = "目录获取失败，书源可能失效，可换源重试"
            return false
        }

        val reversed = pendingReversed ?: ChapterOrderStore.isReversed(sourceJson, bookUrl)
        _chapterReversed.value = reversed
        orderSourceJson = sourceJson
        orderBookUrl = bookUrl
        // 加载前点过的顺序以内存这次选择为准，顺手补落库，避免「点了反序、加载完又跳回正序」。
        pendingReversed?.let {
            ChapterOrderStore.setReversed(sourceJson, bookUrl, it)
            pendingReversed = null
        }
        chapterTitles = chapters.map { it.title }
        rebuildTocDisplay()
        netSourceJson = sourceJson
        netChapters = chapters
        android.util.Log.i(
            "YGRead",
            "start: idx=$initialChapterIndex size=${chapters.size} reversed=$reversed " +
                "target=${chapters.getOrNull(initialChapterIndex)?.title} " +
                "first=${chapters.firstOrNull()?.title} last=${chapters.lastOrNull()?.title}",
        )

        // 落章：临时阅读点哪章读哪章；正式阅读按上次存的章号（ReadProgress.chapterOffset）。
        val saved = progressBookId?.let { repo.progress(it).firstOrNull() }
        val target = when {
            initialChapterIndex in 1..chapters.lastIndex -> initialChapterIndex
            saved != null && saved.chapterOffset in 0..chapters.lastIndex -> saved.chapterOffset
            else -> 0
        }
        val savedPage = if (saved != null && saved.chapterOffset == target) saved.pageIndex else 0
        lastChapterIndex = target
        loadComicWindow(target, savedPage)
        return _pages.value.isNotEmpty()
    }

    /**
     * 只加载「目标章 + 紧随其后的两章」，按显示顺序一章一章往后追加。
     *
     * 以前的写法是把整本所有章节并发拉完、再拼成一条扁平页列表：先到的章排前面、后到的章插进中间，
     * 页码指向的图在加载过程中不停变位置——点第1话实际看到第2话内容就是这么来的。
     * 现在页表只会往后长，前面已经显示的页不会被插队，章名和正文永远对得上。
     */
    private suspend fun loadComicWindow(targetTocIndex: Int, savedPage: Int = 0) {
        val chapters = netChapters
        if (chapters.isEmpty()) return
        val reversed = _chapterReversed.value
        netDisplayOrder = if (reversed) chapters.indices.reversed().toList() else chapters.indices.toList()
        val pos = netDisplayOrder.indexOf(targetTocIndex).let { if (it < 0) 0 else it }
        val end = (pos + comicWindowSize).coerceAtMost(netDisplayOrder.size)
        val window = netDisplayOrder.subList(pos, end).toList()
        netWindow.clear()
        netWindow.addAll(window)
        netWindowPos = end
        netWindowImages.clear()
        netLoading = true
        _pages.value = emptyList()
        _pageRefs.value = emptyList()
        _chapterRefs.value = emptyList()
        _loadMessage.value = "正在加载图片…"
        var startPage = 0
        for ((i, idx) in window.withIndex()) {
            val images = fetchChapterImages(chapters[idx])
            netWindowImages += images
            val pageCount = images.size
            _pages.value = _pages.value + images
            _pageRefs.value = com.yg.comic.sr.preload.buildNetworkRefs(
                bookId,
                _referer.value,
                netWindowImages.toList(),
                chapterIndices = netWindow.toList(),
            )
            _chapterRefs.value = _chapterRefs.value + ReaderChapterRef(chapters[idx].title, startPage, pageCount, idx)
            startPage += pageCount
            if (i == 0) {
                val cap = (pageCount - 1).coerceAtLeast(0)
                _current.value = savedPage.coerceIn(0, cap)
                _loaded.value = true
            }
            android.util.Log.i("YGRead", "window ch=$idx title=${chapters[idx].title} pages=$pageCount start=$startPage")
        }
        netLoading = false
        _loadMessage.value = if (_pages.value.isEmpty()) "本章图片加载失败，可换源重试" else null
    }

    companion object {
        /**
         * 进程级图址缓存：ViewModel 每次进阅读器都重建，此前每次都联网解析图片地址 → "每次进去都重新加载"。
         * key 含源 JSON + 章节 URL，换源不会串；只缓存解析成功的非空结果，失败不固化以便下次重试。
         * 进程被杀即清空，不会永久陈旧。此缓存只存图址字符串，超分落盘缓存 SrDiskCache 不受影响。
         */
        private val imageUrlCache = ConcurrentHashMap<String, List<String>>()
    }

    private suspend fun fetchChapterImages(ch: SourceChapter): List<String> = withContext(Dispatchers.IO) {
        val cacheKey = "$netSourceJson|${ch.url}"
        imageUrlCache[cacheKey]?.let { return@withContext it }
        val images = runCatching {
            withTimeout(25_000L) {
                SourceRepository.providerFor(netSourceJson).content(netSourceJson, ch.url).getOrNull()?.images.orEmpty()
            }
        }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        if (images.isNotEmpty()) imageUrlCache[cacheKey] = images
        images
    }

    /** 滑到已加载章节末尾时往后追加两章（只往后长，当前位置不会跳）。 */
    private fun maybeExtendComicWindow(page: Int) {
        if (netChapters.isEmpty() || netLoading) return
        if (_pages.value.size - page > 2) return
        if (netWindowPos >= netDisplayOrder.size) return
        netLoading = true
        viewModelScope.launch {
            try {
                repeat(2) {
                    if (netWindowPos >= netDisplayOrder.size) return@repeat
                    val idx = netDisplayOrder[netWindowPos]
                    netWindowPos += 1
                    val images = fetchChapterImages(netChapters[idx])
                    val startPage = _pages.value.size
                    netWindow += idx
                    netWindowImages += images
                    _pages.value = _pages.value + images
                    _pageRefs.value = com.yg.comic.sr.preload.buildNetworkRefs(
                        bookId,
                        _referer.value,
                        netWindowImages.toList(),
                        chapterIndices = netWindow.toList(),
                    )
                    _chapterRefs.value = _chapterRefs.value +
                        ReaderChapterRef(netChapters[idx].title, startPage, images.size, idx)
                }
            } finally {
                netLoading = false
            }
        }
    }

    /** 阅读器内目录点章：展示位先映射回基准下标，网络漫画重开窗口，本地漫画按基准下标跳页。 */
    fun openTocChapter(displayIndex: Int) {
        val tocIndex = _tocDisplayOrder.value.getOrNull(displayIndex)
            ?: if (displayIndex in chapterTitles.indices) displayIndex else return
        if (netChapters.isNotEmpty()) {
            viewModelScope.launch { loadComicWindow(tocIndex, 0) }
            return
        }
        val ref = _chapterRefs.value.firstOrNull { it.tocIndex == tocIndex } ?: return
        _current.value = ref.startPage
    }

    private fun publishChapterRefs(reversed: Boolean) {
        if (chapterTitles.isEmpty()) {
            _chapterRefs.value = emptyList()
            return
        }
        val order = if (reversed) chapterTitles.indices.reversed().toList() else chapterTitles.indices.toList()
        var start = 0
        _chapterRefs.value = order.map { idx ->
            val count = chapterImages.getOrElse(idx) { emptyList() }.size
            val ref = ReaderChapterRef(chapterTitles[idx], start, count, idx)
            start += count
            ref
        }
        _chapterReversed.value = reversed
        rebuildTocDisplay()
    }

    /** 阅读页内切换目录顺序；本地漫画只重排已加载的页，网络漫画按新顺序重开窗口。 */
    fun setChapterOrder(reversed: Boolean) {
        if (chapterTitles.isEmpty()) {
            // 目录还没加载完：先记住这次选择，加载时以它为准（否则会被存储里的旧值盖回去）
            _chapterReversed.value = reversed
            pendingReversed = reversed
            return
        }
        val curToc = _chapterRefs.value.lastOrNull { it.startPage <= _current.value }?.tocIndex
            ?: if (_chapterReversed.value) chapterTitles.size - 1 else 0
        if (orderSourceJson.isNotBlank() || orderBookUrl.isNotBlank()) {
            ChapterOrderStore.setReversed(orderSourceJson, orderBookUrl, reversed)
        }
        pendingReversed = null
        _chapterReversed.value = reversed
        rebuildTocDisplay()
        if (netChapters.isNotEmpty()) {
            // 网络漫画：按新顺序重新开窗口，从正在看的那一章开始
            viewModelScope.launch { loadComicWindow(curToc, 0) }
            return
        }
        if (chapterImages.any { it.isNotEmpty() }) {
            val order = if (reversed) chapterTitles.indices.reversed().toList() else chapterTitles.indices.toList()
            _pages.value = order.flatMap { chapterImages.getOrElse(it) { emptyList() } }
            _pageRefs.value = com.yg.comic.sr.preload.buildNetworkRefs(
                bookId,
                _referer.value,
                order.map { chapterImages.getOrElse(it) { emptyList() } },
                chapterIndices = order,
            )
            publishChapterRefs(reversed)
            val newDisplay = if (reversed) chapterTitles.size - 1 - curToc else curToc
            _current.value = _chapterRefs.value.getOrNull(newDisplay)?.startPage
                ?.coerceIn(0, (_pages.value.size - 1).coerceAtLeast(0)) ?: 0
            lastSavedPage = -1
        } else {
            _chapterReversed.value = reversed
            rebuildTocDisplay()
        }
    }

    fun toggleChapterOrder() = setChapterOrder(!_chapterReversed.value)

    // ===== 换源 =====

    /**
     * 还原来源列表：优先用导入书架/临时阅读时存的 originsJson，老书(空)用本书单源兜底。
     * 只保留真正的漫画源（sourceType==2 && 过 ComicSourceRule），避免列表里混入小说源切过去拿不到图。
     * originsJson 里未必带 sourceType，无法逐条校验时保留原始来源（用户导入时已按漫画筛过）。
     */
    private fun restoreOrigins(originsJson: String, fallback: SourceOrigin) {
        val parsed = runCatching {
            if (originsJson.isBlank()) emptyList()
            else originJson.decodeFromString<List<SourceOrigin>>(originsJson)
        }.getOrDefault(emptyList())
        _origins.value = parsed.ifEmpty { listOf(fallback) }
    }

    /** 本地已有来源之间切换：用该来源的 sourceJson+tocUrl 重新拉目录与正文，保持当前章。 */
    fun switchSource(index: Int) {
        val origin = _origins.value.getOrNull(index) ?: return
        if (index == _sourceIndex.value) return
        val toc = origin.tocUrl.ifBlank { origin.bookUrl }
        if (toc.isBlank() || origin.sourceJson.isBlank()) {
            _loadMessage.value = "该来源缺少目录地址，无法切换"
            return
        }
        _sourceIndex.value = index
        pendingReversed = null
        _switchEpoch.value++
        viewModelScope.launch(Dispatchers.IO) {
            loadNetworkComicPages(
                sourceJson = origin.sourceJson,
                tocUrl = toc,
                progressBookId = if (bookId < 0L) null else bookId,
                bookUrl = origin.bookUrl,
                initialChapterIndex = lastChapterIndex,
            )
        }
    }

    /**
     * 联网搜源：用当前书名并发搜所有启用的漫画书源，同名同作者才算同一本。
     * 与小说的区别：只搜 sourceType==2 且过 ComicSourceRule 的源，否则会切到小说源拿不到图片。
     */
    fun searchSwitchSources() {
        val name = _title.value.trim()
        if (name.isBlank()) {
            _switchMsg.value = "书名未知，无法换源"
            return
        }
        val norm: (String) -> String = { SwitchMatcher.norm(it) }
        val cacheKey = norm(name)
        val cached = switchCache[cacheKey]
        if (!cached.isNullOrEmpty()) {
            _switchCandidates.value = cached
            _switchMsg.value = "找到 ${cached.size} 个来源（缓存）"
        } else {
            _switchMsg.value = "正在搜索同名同作者的其他漫画来源…"
            _switchCandidates.value = emptyList()
        }
        _switchLoading.value = true

        switchSearchJob?.cancel()
        switchSearchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val sources = repo.bookSources.firstOrNull().orEmpty().filter {
                    it.enabled && it.sourceType == 2 &&
                        ComicSourceRule.isComicSource(it.name, it.url)
                }
                val sem = Semaphore(20)
                val target = cacheKey
                val seen = ConcurrentHashMap.newKeySet<String>()
                val live = CopyOnWriteArrayList<SwitchCandidate>()
                var done = 0
                coroutineScope {
                    sources.map { src ->
                        async(Dispatchers.IO) {
                            sem.withPermit {
                                runCatching {
                                    withTimeout(30_000) {
                                        SourceRepository.search(src.json, name, 1).getOrElse { emptyList() }
                                    }
                                }.getOrDefault(emptyList())
                                    .filter { SwitchMatcher.matchesName(it.name, target) }
                                    .forEach { sb ->
                                        val key = src.id.toString() + "|" + sb.bookUrl
                                        if (seen.add(key)) {
                                            live.add(SwitchCandidate(
                                                sourceId = src.id, sourceJson = src.json, sourceName = src.name,
                                                bookUrl = sb.bookUrl, author = sb.author.trim(), coverUrl = sb.coverUrl,
                                                name = sb.name, kind = sb.kind, wordCount = sb.wordCount,
                                                lastChapter = sb.lastChapter, intro = sb.intro,
                                            ))
                                        }
                                    }
                            }
                            done++
                            if (!currentCoroutineContext().isActive) return@async
                            val snapshot = live.toList()
                            _switchCandidates.value = snapshot
                            _switchMsg.value = "已搜 $done/${sources.size} 个漫画源，找到 ${snapshot.size} 个同名结果"
                        }
                    }.awaitAll()
                }
                val refAuthor = SwitchMatcher.pickRefAuthor(live.map { it.author })
                val inputs = live.toList().map { c ->
                    SwitchBookIn(
                        name = c.name, author = c.author, sourceId = c.sourceId, sourceJson = c.sourceJson,
                        sourceName = c.sourceName, bookUrl = c.bookUrl, coverUrl = c.coverUrl,
                        kind = c.kind, wordCount = c.wordCount, lastChapter = c.lastChapter, intro = c.intro,
                    )
                }
                val refinedKeys = SwitchMatcher.refineByAuthor(inputs, refAuthor)
                    .map { it.sourceId to it.bookUrl }.toSet()
                val finalCands = live.toList().filter { it.sourceId to it.bookUrl in refinedKeys }
                val groups = LinkedHashMap<String, MutableList<SwitchCandidate>>()
                for (c in finalCands) {
                    val key = SwitchMatcher.norm(c.name) + "|" + refAuthor
                    groups.getOrPut(key) { ArrayList() }.add(c)
                }
                fun infoScore(c: SwitchCandidate): Int =
                    (if (c.coverUrl.isNotBlank()) 4 else 0) +
                        (if (c.intro.isNotBlank()) 2 else 0) +
                        (if (c.wordCount.isNotBlank()) 1 else 0) +
                        (if (c.lastChapter.isNotBlank()) 1 else 0)
                val merged = groups.values.map { g ->
                    val primary = g.maxByOrNull { infoScore(it) } ?: g.first()
                    val others = g.filter { it !== primary }.map {
                        SwitchSourceRef(it.sourceId, it.sourceJson, it.sourceName, it.bookUrl)
                    }
                    primary.copy(moreSources = others)
                }
                switchCache[cacheKey] = merged
                _switchCandidates.value = merged
                _switchMsg.value = if (merged.isEmpty()) "没有找到同名同作者的其他漫画来源"
                else "找到 ${merged.size} 本同名漫画（共 ${finalCands.size} 个来源）"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                _switchMsg.value = "搜索失败：${e.message}"
            } finally {
                _switchLoading.value = false
            }
        }
    }

    /** 应用联网候选：主来源+同组附属来源一起 detail 拿目录加入列表 → 切到主来源并保持当前章。 */
    fun applySwitchCandidate(c: SwitchCandidate) {
        viewModelScope.launch(Dispatchers.IO) {
            _loadMessage.value = "正在切换到「${c.sourceName}」…"
            val refs = listOf(SwitchSourceRef(c.sourceId, c.sourceJson, c.sourceName, c.bookUrl)) + c.moreSources
            val list = _origins.value.toMutableList()
            var primaryTarget = -1
            refs.forEachIndexed { i, ref ->
                val toc = runCatching {
                    SourceRepository.detail(ref.sourceJson, ref.bookUrl).getOrNull()?.tocUrl
                }.getOrNull().takeUnless { it.isNullOrBlank() } ?: ref.bookUrl
                if (toc.isBlank()) return@forEachIndexed
                val origin = SourceOrigin(
                    sourceId = ref.sourceId, sourceJson = ref.sourceJson,
                    sourceName = ref.sourceName, bookUrl = ref.bookUrl, tocUrl = toc,
                )
                val idx = list.indexOfFirst { it.sourceId == ref.sourceId && it.bookUrl == ref.bookUrl }
                val target = if (idx >= 0) { list[idx] = origin; idx } else { list.add(origin); list.lastIndex }
                if (i == 0) primaryTarget = target
            }
            if (primaryTarget < 0) {
                _loadMessage.value = "该来源缺少目录地址，无法切换"
                return@launch
            }
            _origins.value = list
            _sourceIndex.value = -1
            switchSource(primaryTarget)
            _switchCandidates.value = emptyList()
        }
    }

    init {
        ChapterOrderStore.init(repo.appContext)
        // 智能排序开关：持久化恢复；目录加载/切序时 rebuildTocDisplay 会读到最新值
        viewModelScope.launch {
            store.smartTocSort.collect { enabled ->
                if (_smartTocSort.value != enabled) {
                    _smartTocSort.value = enabled
                    rebuildTocDisplay()
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // 临时阅读（bookId=-1L）：未加入书架，无 books/network_books 记录。
            // 从 SharedTempRead 拿临时数据；漫画走网络漫画分支，小说走 NovelReaderViewModel。
            if (bookId < 0L) {
                val temp = SharedTempRead.current
                if (temp != null && temp.mediaType == MediaType.COMIC &&
                    temp.sourceJson.isNotBlank() && temp.tocUrl.isNotBlank()
                ) {
                    _title.value = temp.title
                    restoreOrigins(
                        temp.originsJson,
                        SourceOrigin(
                            temp.sourceId, temp.sourceJson, temp.sourceName,
                            temp.bookUrl, temp.tocUrl,
                        ),
                    )
                    // 命中漫画就始终留在漫画阅读器：加载失败由 loadMessage 提示并允许换源，
                    // 绝不回落成小说（_isTxt）或无限转圈。
                    loadNetworkComicPages(
                        temp.sourceJson,
                        temp.tocUrl,
                        bookUrl = temp.bookUrl,
                        initialChapterIndex = temp.initialChapterIndex,
                    )
                    _loaded.value = true
                    return@launch
                }
                _isTxt.value = true
                _loaded.value = true
                return@launch
            }
            val allBooks = repo.books.firstOrNull().orEmpty()
            val thisBook = allBooks.firstOrNull { it.id == bookId }
            thisBook?.title?.let { _title.value = it }
            // 上一本/下一本在同类型书籍间切换（小说只在小说间切，漫画只在漫画间切）
            val sameTypePool = allBooks.filter { it.mediaType == (thisBook?.mediaType ?: MediaType.COMIC) }
            val pool = thisBook?.collectionId?.let { cid ->
                val sameCollection = sameTypePool.filter { it.collectionId == cid }
                if (sameCollection.size >= 2) sameCollection else sameTypePool
            } ?: sameTypePool
            _siblings.value = pool.sortedWith(compareBy<Book> { it.sortField }.thenBy { it.title })
            val frags = repo.fragments(bookId)

            // TXT 小说：本 ViewModel 只负责判定类型让 ReaderScreen 路由到独立小说阅读器，
            // 实际加载/分页/进度由 NovelReaderViewModel 处理。
            val txtFrag = frags.firstOrNull { it.kind == com.yg.comic.model.FragmentKind.TXT }
            if (txtFrag != null && txtFrag.sourcePath.isNotBlank()) {
                _isTxt.value = true
                _loaded.value = true
                return@launch
            }

            // 网络书（NOVEL + network_books 记录）：同样路由到小说阅读器
            if (thisBook?.mediaType == MediaType.NOVEL) {
                val net = runCatching { repo.networkBook(bookId) }.getOrNull()
                if (net != null) {
                    _isTxt.value = true
                    _loaded.value = true
                    return@launch
                }
            }

            // 网络漫画（COMIC + network_books 记录）：书源实时解析图片章节。
            // 走可插拔 Provider（当前 Legado），拉目录→逐章 content 拿 images→扁平成页 URL，
            // 让 ReaderScreen 用 Coil 按网络图加载（PageImage 本地 ImageCache 不认 URL）。
            if (thisBook?.mediaType == MediaType.COMIC) {
                val net = runCatching { repo.networkBook(bookId) }.getOrNull()
                if (net != null && net.sourceJson.isNotBlank() && net.tocUrl.isNotBlank()) {
                    restoreOrigins(
                        net.originsJson,
                        SourceOrigin(
                            net.sourceId, net.sourceJson, net.sourceName,
                            net.bookUrl, net.tocUrl,
                        ),
                    )
                    // 命中漫画就始终留在漫画阅读器：加载失败由 loadMessage 提示并允许换源，
                    // 绝不掉到下面的 PDF/本地目录逻辑导致空白页。
                    loadNetworkComicPages(net.sourceJson, net.tocUrl, bookId, bookUrl = net.bookUrl)
                    _loaded.value = true
                    return@launch
                }
            }

            // PDF 实时：片段 sourcePath 指向原文件引用（content:// Uri 或绝对路径），不复制；按页索引渲染
            val pdfFrag = frags.firstOrNull { it.kind == com.yg.comic.model.FragmentKind.PDF }
            if (pdfFrag != null && pdfFrag.sourcePath.isNotBlank()) {
                // 打开时实时读一次真实页数；读不到用落库兜底值；再保底 1，绝不让阅读页 0 页
                val liveCount = com.yg.comic.engine.PdfLive.pageCount(repo.appContext, pdfFrag.sourcePath)
                val count = (if (liveCount > 0) liveCount else pdfFrag.pageCount).coerceAtLeast(1)
                _isPdf.value = true
                _pdfPath.value = pdfFrag.sourcePath
                // pages 用页索引字符串占位，PageImage 会按 index 实时渲染
                _pages.value = (0 until count).map { it.toString() }
                _pageRefs.value = (0 until count).map { SrPageRef.Pdf(bookId, 0, it, _pdfPath.value) }
                val progress = repo.progress(bookId).firstOrNull()
                _current.value = (progress?.pageIndex ?: 0).coerceIn(0, (count - 1).coerceAtLeast(0))
                _loaded.value = true
                return@launch
            }

            val paths = mutableListOf<String>()
            for (f in frags) {
                var dir = java.io.File(f.sourcePath)
                if (!dir.isDirectory) {
                    dir = repo.libraryDir().resolve("book_${bookId}_f${f.id}")
                }
                if (!dir.isDirectory) continue
                dir.listFiles()?.filter { it.isFile }
                    ?.sortedBy { it.name }?.forEach { paths += it.absolutePath }
            }
            _pages.value = paths
            _pageRefs.value = paths.mapIndexed { i, p -> SrPageRef.Local(bookId, 0, i, p) }
            val progress = repo.progress(bookId).firstOrNull()
            _current.value = (progress?.pageIndex ?: 0).coerceIn(0, (paths.size - 1).coerceAtLeast(0))
            _loaded.value = true
        }
    }

    // 漫画/PDF 进度落库用独立单线程，退出也不丢最后一次
    private val progressExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ygcomic-progress").apply { isDaemon = true }
    }
    @Volatile private var lastSavedPage = -1

    /** 漫画/PDF：存章号 + 章内页号（网络漫画页表是按窗口加载的，存全局页号会错位） */
    fun onPageChanged(index: Int) {
        if (index == lastSavedPage) return
        lastSavedPage = index
        _current.value = index
        maybeExtendComicWindow(index)
        val ref = _chapterRefs.value.lastOrNull { it.startPage <= index }
        val chapterOffset = ref?.tocIndex ?: 0
        lastChapterIndex = chapterOffset
        val pageInChapter = if (ref != null) (index - ref.startPage).coerceAtLeast(0) else index
        progressExecutor.execute {
            try {
                kotlinx.coroutines.runBlocking {
                    repo.saveProgress(
                        ReadProgress(
                            bookId = bookId,
                            pageIndex = pageInChapter,
                            chapterOffset = chapterOffset,
                            extra = ReadProgress.KIND_COMIC,
                        ),
                    )
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        progressExecutor.shutdown()
        super.onCleared()
    }
}
