package com.yg.comic.ui.reader

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.yg.comic.tts.NetworkTtsRegistry
import java.util.Locale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yg.comic.data.BookRepository
import com.yg.comic.data.ChapterOrderStore
import com.yg.comic.data.SettingsStore
import com.yg.comic.engine.ChapterPaginator
import com.yg.comic.engine.ReaderTextPage
import com.yg.comic.model.NovelSettings
import com.yg.comic.model.ReadProgress
import com.yg.comic.source.SearchBook
import com.yg.comic.source.SourceOrigin
import com.yg.comic.source.SourceRepository
import com.yg.comic.shared.SwitchBookIn
import com.yg.comic.shared.SwitchMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 一页小说：
 * - fragments：本页的渲染片段（来自 FlowReader ChapterPaginator），逐段单独渲染，measure/render 同源。
 * - text：fragments 拼接的纯文本（供简繁转换/图片检测等整页逻辑用）。
 * - startOffset：本页首字符在章内的绝对偏移（进度定位，等价 legado durChapterPos）。
 */
data class NovelPage(
    val chapterIndex: Int,
    val chapterTitle: String,
    val text: String,
    val startOffset: Int,
    val fragments: List<ChapterPaginator.Fragment> = emptyList(),
    /** novel-sound 整段排版页：按「行窗口」渲染，段落永不被字符串切割。 */
    val readerPage: ReaderTextPage? = null,
)

/** legado 引擎的排版参数（全像素） */
data class NovelLayout(
    val widthPx: Float,
    val heightPx: Float,
    val fontSizePx: Float,
    val lineHeightMul: Float,
    val paragraphSpacingPx: Float,
    val indent: Boolean,
)

/**
 * 小说阅读器 ViewModel：只做「数据源」——读全文、切章、读/存进度。
 * 分页排版在 UI 层用 remember 同步派生（对齐 legado 的做法），不在此处异步重排，
 * 避免高频改字号/行距时并发重排导致的抖动与崩溃。
 *
 * 网络书采用「滑动窗口缓冲」：目录一次性拉全，正文只预加载当前章 + 后 19 章，
 * 翻章时滚动补新章、已缓存章不重抓；既不整本下载（慢+风控），也不用一章一章卡住翻页。
 * 对外仍以 rawText + chapters 偏移的形式提供数据，Screen 层分页/进度/滚动全部零改动。
 */
class NovelReaderViewModel(
    private val repo: BookRepository,
    private val store: SettingsStore,
    private val bookId: Long,
) : ViewModel() {

    val settings: StateFlow<NovelSettings> =
        store.novelReader.stateIn(viewModelScope, SharingStarted.Eagerly, NovelSettings())

    /** DataStore 真值是否已读到（UI 等它为真再排版，避免用默认值排一次又重排） */
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    private val _rawText = MutableStateFlow("")
    val rawText: StateFlow<String> = _rawText.asStateFlow()

    private val _chapters = MutableStateFlow<List<com.yg.comic.engine.TxtLive.Chapter>>(emptyList())
    val chapters: StateFlow<List<com.yg.comic.engine.TxtLive.Chapter>> = _chapters.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 已保存的阅读进度（首次加载后读取一次；chapterOffset 为章内字符偏移，pageIndex 为章节索引） */
    private val _savedProgress = MutableStateFlow<ReadProgress?>(null)
    val savedProgress: StateFlow<ReadProgress?> = _savedProgress.asStateFlow()

    // 进度落库用独立单线程，退出也不丢最后一次
    private val progressExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ygcomic-novel-progress").apply { isDaemon = true }
    }
    @Volatile private var lastSavedKey = ""

    // ===== 网络书滑动窗口缓冲状态 =====
    /** 当前网络书书源 JSON（抓正文用） */
    private var netSourceJson: String = ""
    /** 当前网络书 bookUrl：与 netSourceJson 一起构成 ChapterOrderStore 的 key，读反序状态用 */
    private var netBookUrl: String = ""
    /** 目录展示是否反序（详情页 ChapterOrderStore 记的状态；正文内部仍是原始章号序） */
    private val _chapterReversed = MutableStateFlow(false)
    val chapterReversed: StateFlow<Boolean> = _chapterReversed.asStateFlow()
    /** 正文缓存 key：书源 JSON + 详情页 URL（与目录缓存同源隔离，二次进入免重抓） */
    private var netContentKey: String = ""
    /**
     * 目录条目：标题 + url + 是否「卷标题」。
     * 卷标题不是可读章，但必须保留在下标序列里——详情页的 originalChapterIndex /
     * 落库目录都按这份完整目录算章号，这里若剔掉会让两边章号错位。
     * 阅读器侧只是「不落到卷上」：翻章跳过、抽屉当分组标题显示。
     */
    private data class TocEntry(val title: String, val url: String, val isVolume: Boolean)
    /** 目录：按章序号索引（含卷标题占位，下标与详情页一致） */
    private var netToc: List<TocEntry> = emptyList()
    /** 每章是否卷标题的平行标记（供 UI 把卷当分组标题渲染、按可读章数统计） */
    private val _chapterVolumes = MutableStateFlow<List<Boolean>>(emptyList())
    val chapterVolumes: StateFlow<List<Boolean>> = _chapterVolumes.asStateFlow()

    /** 智能排序开关：默认关=书源原顺序；开=按章号理顺（第一章在前）。只影响目录展示/点选映射，正文与进度不动。 */
    private val _smartTocSort = MutableStateFlow(false)
    val smartTocSort: StateFlow<Boolean> = _smartTocSort.asStateFlow()

    /** 目录展示位 -> 基准下标（chapters/currentChapterIndex/进度下标恒定不动）。 */
    private val _tocDisplayOrder = MutableStateFlow<List<Int>>(emptyList())
    val tocDisplayOrder: StateFlow<List<Int>> = _tocDisplayOrder.asStateFlow()

    /** 依据 chapters + 正反序 + 智能排序重算目录展示序列（只动展示层）。 */
    private fun rebuildTocDisplay() {
        val titles = _chapters.value.map { it.title }
        if (titles.isEmpty()) {
            _tocDisplayOrder.value = emptyList()
            return
        }
        val base = if (_smartTocSort.value) SourceRepository.smartTocOrder(titles) else titles.indices.toList()
        _tocDisplayOrder.value = if (_chapterReversed.value) base.reversed() else base
    }

    /** 阅读器目录弹窗里的智能排序开关：即时重排，不重排正文、不动进度。 */
    fun setSmartTocSort(enabled: Boolean) {
        _smartTocSort.value = enabled
        rebuildTocDisplay()
        viewModelScope.launch { store.setSmartTocSort(enabled) }
    }
    /** 已缓冲的章正文（展示用，按当前替换规则净化后）：key=章序号，value=标题行 + "\n" + 正文（含结尾 \n\n） */
    private val netLoaded = ConcurrentHashMap<Int, String>()
    /** 已缓冲的章正文原文（净化前）：规则开关/内容变化后从原文重新净化，关闭规则能恢复被删掉的字 */
    private val netLoadedRaw = ConcurrentHashMap<Int, String>()
    /** 缓冲代次：快速翻章时丢弃过期加载结果，避免旧窗口覆盖新窗口 */
    @Volatile private var netLoadGen = 0
    /** 当前窗口中心章（saveProgress 检测翻章用，-1 表示未初始化） */
    @Volatile private var lastCenter = -1
    /** 窗口大小：当前章 + 后 19 章 */
    private val windowSize = 20
    /** 本书初始进度章序号（切源后恢复到同一章） */
    private var netStartIndex: Int = 0
    /** 未加载章的占位正文（目录可跳、chapters 结构完整） */
    private fun placeholder(title: String) = title + "\n（本章尚未加载，翻到时自动获取…）\n\n"

    // ===== 多源聚合：本书的全部来源 + 当前选中的来源 =====
    /** 本书来源列表（含主源），来自导入书架时保存的 originsJson */
    private val _origins = MutableStateFlow<List<SourceOrigin>>(emptyList())
    val origins: StateFlow<List<SourceOrigin>> = _origins.asStateFlow()
    /** 当前正在使用的来源下标 */
    private val _sourceIndex = MutableStateFlow(0)
    val sourceIndex: StateFlow<Int> = _sourceIndex.asStateFlow()
    private val originJson = Json { ignoreUnknownKeys = true }

    // ===== 朗读逐字高亮 =====
    // 坐标同源：TTS 逐段朗读 block.shown 文本；onRangeStart 给的 start/end 是该段 shown 内的字符下标，
    // 与 ReaderCharBox.charIndex（measure 文本 shown 的下标）同源，直接用它匹配高亮，不做二次映射。
    data class AloudSegment(val blockStart: Int, val shown: String)
    data class AloudPos(val blockStart: Int, val charStart: Int, val charEnd: Int)
    private val _aloudPos = MutableStateFlow<AloudPos?>(null)
    val aloudPos: StateFlow<AloudPos?> = _aloudPos.asStateFlow()
    private val _aloudPlaying = MutableStateFlow(false)
    val aloudPlaying: StateFlow<Boolean> = _aloudPlaying.asStateFlow()
    // 本章念完一拍：界面收到后自动翻到下一章、接着念（不然一页念完就哑了）
    private val _aloudChapterDone = MutableStateFlow(0)
    val aloudChapterDone: StateFlow<Int> = _aloudChapterDone.asStateFlow()
    // 系统 TTS 没就绪/没中文语音包时给用户一句话，别像以前那样静默不动
    private val _aloudError = MutableStateFlow<String?>(null)
    val aloudError: StateFlow<String?> = _aloudError.asStateFlow()
    private var aloudRate: Float = 1.0f
    private var tts: TextToSpeech? = null
    private var aloudSegments: List<AloudSegment> = emptyList()
    private var aloudIndex = 0

    // ===== 朗读引擎：system / network / local(预留) =====
    // 设置值走 DataStore，这里缓存成字段供同步读取（startAloud 是同步入口，不能挂起读 flow）
    private var ttsEngine = "system"
    private var ttsProviderId = NetworkTtsRegistry.defaultId
    private var ttsApiKey = ""
    private var ttsVoice = "Chloe"
    private var ttsStyle = ""
    private var ttsFormat = "wav"
    private var ttsVolume = 1f
    private var ttsCacheSegments = -1
    private var ttsUseFallback = false // 本次朗读是否已从网络回退到系统（网络失败自动回退）
    private var networkPlayer: MediaPlayer? = null
    private var networkSpeakJob: Job? = null
    /** 网络 TTS 合成结果缓存，key=音色/风格/格式/语速/文本，条数受 ttsCacheSegments 限制 */
    private val networkAudioCache = LinkedHashMap<String, ByteArray>()

    fun setAloudRate(rate: Float) {
        aloudRate = rate.coerceIn(0.5f, 2.5f)
        tts?.setSpeechRate(aloudRate)
    }

    /** 逐段朗读入口：按当前引擎分发，网络失败内部自动回退系统。 */
    fun startAloud(segments: List<AloudSegment>) {
        if (segments.isEmpty()) return
        stopAloud()
        aloudSegments = segments
        aloudIndex = 0
        _aloudPlaying.value = true
        _aloudError.value = null
        ttsUseFallback = false
        when (ttsEngine) {
            "network" -> startNetworkAloud(0)
            "local" -> {
                // 本地大模型 TTS 架构预留，尚未接入：明确提示，别静默不出声
                _aloudPlaying.value = false
                _aloudError.value = "本地大模型朗读尚未开放，请在朗读设置里改用系统或网络朗读"
            }
            else -> startSystemAloud(0)
        }
    }

    // ---------- 系统 TTS ----------
    private fun startSystemAloud(fromIndex: Int) {
        val engine = tts
        if (engine != null) {
            speakAloudAt(fromIndex, engine)
            return
        }
        var created: TextToSpeech? = null
        created = TextToSpeech(repo.appContext) { status ->
            // 注意：某些引擎会在构造返回前就回调，这里 created 可能还是 null，必须用 tts 兜底
            val engine2 = tts ?: created
            if (status == TextToSpeech.SUCCESS && engine2 != null) {
                val lang = runCatching { engine2.setLanguage(Locale.CHINESE) }
                    .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // 没中文语音包：退回系统默认语言，至少能出声，并提示用户去装语音数据
                    runCatching { engine2.language = Locale.getDefault() }
                    _aloudError.value = "系统 TTS 缺少中文语音包，已用默认语音朗读（可在系统设置→无障碍→文字转语音里下载）"
                }
                runCatching { engine2.setSpeechRate(aloudRate) }
                engine2.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { nextAloudSegment() }
                    override fun onError(utteranceId: String?) { stopAloud() }
                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        val seg = aloudSegments.getOrNull(aloudIndex) ?: return
                        _aloudPos.value = AloudPos(seg.blockStart, start, end)
                    }
                })
                tts = engine2
                speakAloudAt(fromIndex, engine2)
            } else {
                _aloudPlaying.value = false
                _aloudError.value = "系统文字转语音不可用，请在系统设置里检查 TTS 引擎和中文语音包"
            }
        }
        tts = created
    }

    private fun speakAloudAt(index: Int, engine: TextToSpeech) {
        val seg = aloudSegments.getOrNull(index)
        if (seg == null) {
            // 本章念完：保持“朗读中”，发一拍让界面自动切下一章继续念
            _aloudPos.value = null
            _aloudChapterDone.value += 1
            return
        }
        aloudIndex = index
        val bundle = Bundle()
        engine.speak(seg.shown, TextToSpeech.QUEUE_FLUSH, bundle, "yg_aloud_$index")
    }

    // ---------- 网络 TTS（通用接口）----------
    private fun startNetworkAloud(index: Int) {
        val seg = aloudSegments.getOrNull(index)
        if (seg == null) {
            _aloudPos.value = null
            _aloudChapterDone.value += 1
            return
        }
        aloudIndex = index
        val provider = NetworkTtsRegistry.get(ttsProviderId)
        if (provider == null) {
            networkSpeakJob?.cancel()
            fallbackToSystemAloud(index, "网络朗读引擎未找到，已自动切回系统朗读")
            return
        }
        if (provider.capabilities.requiresApiKey && ttsApiKey.isBlank()) {
            networkSpeakJob?.cancel()
            fallbackToSystemAloud(index, "未填写网络朗读 API Key，已自动切回系统朗读")
            return
        }
        // 网络 TTS 拿不到逐字时间戳：退化成整段高亮，播放期间整段保持高亮
        _aloudPos.value = AloudPos(seg.blockStart, 0, seg.shown.length)
        networkSpeakJob?.cancel()
        networkSpeakJob = viewModelScope.launch {
            val voice = ttsVoice.ifBlank { provider.capabilities.defaultVoiceId }
            val format = if (ttsFormat in provider.capabilities.formats) ttsFormat else provider.capabilities.defaultFormat
            val cacheKey = listOf(provider.id, voice, ttsStyle, format, "%.2f".format(aloudRate), seg.shown).joinToString("|")
            val audioBytes = try {
                // -1 时缓存恒为空，读不到自然重新合成；0/正数都正常命中缓存
                networkAudioCache[cacheKey] ?: provider.synthesize(seg.shown, ttsApiKey, voice, aloudRate, ttsStyle, format).bytes.also { bytes ->
                    storeCacheIfAllowed(cacheKey, bytes)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    fallbackToSystemAloud(index, "网络朗读失败（${e.message ?: "请检查网络或 API Key"}），已自动切回系统朗读")
                }
                return@launch
            }
            withContext(Dispatchers.Main) { playNetworkBytes(audioBytes, index, format) }
        }
    }

    private fun storeCacheIfAllowed(key: String, bytes: ByteArray) {
        // -1=不缓存；0=全缓存（不淘汰）；>0=只留前 N 条，超出淘汰最旧
        if (ttsCacheSegments == -1) return
        networkAudioCache[key] = bytes
        if (ttsCacheSegments > 0) {
            while (networkAudioCache.size > ttsCacheSegments) {
                val oldest = networkAudioCache.keys.firstOrNull() ?: break
                networkAudioCache.remove(oldest)
            }
        }
    }

    private fun playNetworkBytes(bytes: ByteArray, index: Int, format: String) {
        if (!_aloudPlaying.value || ttsEngine != "network" || ttsUseFallback) return
        try {
            val file = java.io.File(repo.appContext.cacheDir, "tts_aloud_${index}.${format}")
            file.writeBytes(bytes)
            val mp = MediaPlayer()
            networkPlayer = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setVolume(ttsVolume, ttsVolume)
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener {
                releaseNetworkPlayer()
                nextAloudSegment()
            }
            mp.setOnErrorListener { _, _, _ ->
                releaseNetworkPlayer()
                fallbackToSystemAloud(index, "网络音频播放失败，已自动切回系统朗读")
                true
            }
            mp.start()
        } catch (e: Exception) {
            releaseNetworkPlayer()
            fallbackToSystemAloud(index, "网络音频播放失败（${e.message ?: "格式不支持"}），已自动切回系统朗读")
        }
    }

    /** 网络失败 → 本次朗读剩余段落改用系统 TTS 接着念（不改用户的持久设置）。 */
    private fun fallbackToSystemAloud(fromIndex: Int, message: String) {
        // 不在这里 cancel networkSpeakJob：回退可能是从该协程内部(catch/onError)触发的，
        // 取消自己会连带中断回退后的系统朗读。旧 job 由 startNetworkAloud 入口 / 同步失败分支负责取消。
        releaseNetworkPlayer()
        ttsUseFallback = true
        _aloudError.value = message
        startSystemAloud(fromIndex)
    }

    private fun releaseNetworkPlayer() {
        runCatching { networkPlayer?.stop() }
        runCatching { networkPlayer?.release() }
        networkPlayer = null
    }

    private fun nextAloudSegment() {
        val next = aloudIndex + 1
        if (ttsEngine == "network" && !ttsUseFallback) {
            startNetworkAloud(next)
            return
        }
        val engine = tts ?: run { stopAloud(); return }
        speakAloudAt(next, engine)
    }

    fun stopAloud() {
        _aloudPlaying.value = false
        _aloudPos.value = null
        aloudSegments = emptyList()
        aloudIndex = 0
        networkSpeakJob?.cancel()
        networkSpeakJob = null
        releaseNetworkPlayer()
        tts?.stop()
    }

    fun clearAloudError() {
        _aloudError.value = null
    }

    // ===== 阅读时间统计：每 30 秒把本页停留时长累计到 SettingsStore，离开阅读页(onCleared)结算尾差 =====
    private var readTickMs = System.currentTimeMillis()
    private fun flushReadTime() {
        val now = System.currentTimeMillis()
        val delta = now - readTickMs
        readTickMs = now
        if (delta > 0) {
            try { runBlocking(Dispatchers.IO) { store.addReadMs(delta) } } catch (_: Exception) {}
        }
    }

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(30_000L)
                flushReadTime()
            }
        }
        viewModelScope.launch { store.novelReader.collect { _settingsLoaded.value = true } }
        // 智能排序开关：持久化恢复，目录重建时 rebuildTocDisplay 读到最新值
        viewModelScope.launch {
            store.smartTocSort.collect { enabled ->
                if (_smartTocSort.value != enabled) {
                    _smartTocSort.value = enabled
                    rebuildTocDisplay()
                }
            }
        }
        // 朗读设置缓存到字段：startAloud 是同步入口，不能在这里挂起读 flow。
        // 多配置改造：只读「当前生效配置」一条 flow，派生到全部字段；用户切配置后这里自动跟进。
        viewModelScope.launch {
            store.activeTtsProfile.collect { p ->
                ttsEngine = p.engine
                ttsProviderId = p.providerId
                ttsApiKey = p.apiKey
                ttsVoice = p.voice
                ttsStyle = p.style
                ttsFormat = p.format
                ttsVolume = p.volume
                ttsCacheSegments = p.cacheSegments
                setAloudRate(p.rate)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // 临时阅读（bookId=-1L）：从 SharedTempRead 拿来源数据，构造临时网络书，
            // 只读、不查 books/network_books、不落进度、不加书架。
            if (bookId < 0L) {
                val t = com.yg.comic.ui.detail.SharedTempRead.current
                if (t != null) {
                    _title.value = t.title
                    val tempNet = com.yg.comic.data.NetworkBookEntity(
                        bookId = -1L,
                        sourceId = t.sourceId,
                        sourceJson = t.sourceJson,
                        bookUrl = t.bookUrl,
                        tocUrl = t.tocUrl,
                        sourceName = t.sourceName,
                        originsJson = t.originsJson,
                        categoryIndex = t.categoryIndex,
                    )
                    // 从详情页点的那一章开始读（originalChapterIndex 已换回原始目录下标）。
                    // netToc 此时为空，coerce 上界用 Int.MAX，loadFromSource 内会按真实目录再夹一次。
                    loadNetworkBook(tempNet, t.initialChapterIndex.coerceAtLeast(0), t.initialChapterUrl, t.initialChapterTitle)
                }
                _loaded.value = true
                return@launch
            }
            val allBooks = repo.books.firstOrNull().orEmpty()
            _title.value = allBooks.firstOrNull { it.id == bookId }?.title ?: ""

            // 先读进度，网络书要用它决定窗口从哪一章开始（否则永远从第 0 章预加载）
            var saved = runCatching { repo.progress(bookId).firstOrNull() }.getOrNull()
            // 进度字段串味防护：pageIndex 在小说里是章节号、在漫画/PDF 里是图片页号(可达几千)，
            // 共用同一张 progress 表。若这本书最后一次是漫画模式读的(带 comic 标记)，pageIndex
            // 是图片页号，绝不能当章号——否则 clamp 到目录末章，表现为「点开跳到一千多章」。
            // 只有在确认是漫画进度时才丢弃；小说进度(novel 标记或老数据空 extra)一律照常采信，
            // 不误伤正常小说续读。
            val savedIndex: Int
            if (saved?.extra == ReadProgress.KIND_COMIC) {
                // 漫画进度：页号当章号会跳到末章，丢弃并把章内偏移也清掉，整条进度作废。
                savedIndex = 0
                saved = saved.copy(pageIndex = 0, chapterOffset = 0)
            } else {
                savedIndex = (saved?.pageIndex ?: 0).coerceAtLeast(0)
            }
            _savedProgress.value = saved

            val netBook = runCatching { repo.networkBook(bookId) }.getOrNull()
            if (netBook != null) {
                loadNetworkBook(netBook, savedIndex)
            } else {
                val frags = repo.fragments(bookId)
                val txtFrag = frags.firstOrNull { it.kind == com.yg.comic.model.FragmentKind.TXT }
                if (txtFrag != null && txtFrag.sourcePath.isNotBlank()) {
                    val text = com.yg.comic.engine.TxtLive.readAll(repo.appContext, txtFrag.sourcePath).orEmpty()
                    _rawText.value = text
                    _chapters.value = com.yg.comic.engine.TxtLive.splitChapters(text)
                    rebuildTocDisplay()
                }
            }
            _loaded.value = true
        }
        // 替换/净化规则变化（设置页开关/编辑/导入）时，从原文重新净化已加载章节，
        // 让设置页的改动即时反映到正在读的正文；首次发射时 netToc 为空会安全跳过。
        viewModelScope.launch(Dispatchers.IO) {
            repo.replaceRules.collect { repurifyLoadedChapters() }
        }
    }

    /** 网络书状态：加载中/失败信息（供 UI 显示） */
    private val _netStatus = MutableStateFlow("")
    val netStatus: StateFlow<String> = _netStatus.asStateFlow()

    // ===== 联网换源：按当前书名搜所有启用书源，同名同作者才算同一本 =====
    /** 一个换源候选（搜索结果 + 它所属的源） */
    data class SwitchCandidate(
        val sourceId: Long,
        val sourceJson: String,
        val sourceName: String,
        val bookUrl: String,
        val author: String,
        val coverUrl: String,
        // 详情字段（照搜索页展示，帮用户判断是不是同一本）
        val name: String = "",
        val kind: String = "",
        val wordCount: String = "",
        val lastChapter: String = "",
        val intro: String = "",
        // 聚合：同一本书（同名同作者）来自其他源时，一并挂在这里，点击一次即可全部加入可切换列表
        val moreSources: List<SwitchSourceRef> = emptyList(),
    )
    /** 聚合候选里的一个附属来源（换源时连同主来源一起 detail 拿目录） */
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
    /** 最近阅读章（saveProgress 持续更新），换源后恢复到这一章 */
    private var lastChapterIndex: Int = 0
    /**
     * 换源代数：每次 loadFromSource 成功重建正文后 +1。
     * Screen 把它加进 currentChapterIndex / pagerState 的 remember key，
     * 这样即使新源章节数/页数与旧源相同，切源也会强制重置分页状态、重新定位到恢复章，
     * 解决"切源后停在旧页码看起来没生效"的问题。
     */
    private val _switchEpoch = MutableStateFlow(0)
    val switchEpoch: StateFlow<Int> = _switchEpoch.asStateFlow()
    /** 切源/重建正文后要恢复到的章序号（=本次 loadFromSource 的 startIndex 夹取结果） */
    private val _resumeIndex = MutableStateFlow(0)
    val resumeIndex: StateFlow<Int> = _resumeIndex.asStateFlow()
    /** 换源搜索缓存：key=规范化书名，命中直接显示，后台静默刷新（照 legado 的 DB 缓存命中不重搜） */
    private val switchCache = ConcurrentHashMap<String, List<SwitchCandidate>>()
    /** 当前换源搜索任务，重复点击时取消旧的，避免多批结果互相覆盖 */
    private var switchSearchJob: Job? = null

    /**
     * 联网搜源：用当前书名并发搜所有启用书源，边搜边出（照 legado 的 callbackFlow 逐源推 UI）。
     * 判定标准（用户要求）：书名规范化后完全相等；作者取命中结果的众数为参考，
     * 候选作者为空或与参考作者相等才保留（本地书没有 author 字段，靠搜索结果推断）。
     */
    fun searchSwitchSources() {
        val name = _title.value.trim()
        if (name.isBlank()) {
            _switchMsg.value = "书名未知，无法换源"
            return
        }
        val norm: (String) -> String = { SwitchMatcher.norm(it) }
        val cacheKey = norm(name)

        // 缓存命中：立即显示历史结果，再后台静默刷新（用户点开换源秒出）
        val cached = switchCache[cacheKey]
        if (!cached.isNullOrEmpty()) {
            _switchCandidates.value = cached
            _switchMsg.value = "找到 ${cached.size} 个来源（缓存）"
        } else {
            _switchMsg.value = "正在搜索同名同作者的其他来源…"
            _switchCandidates.value = emptyList()
        }
        _switchLoading.value = true

        switchSearchJob?.cancel()
        switchSearchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val sources = repo.bookSources.firstOrNull().orEmpty().filter { it.enabled && it.sourceType == 0 }
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
                            if (!isActive) return@async
                            // 边搜边出：StateFlow 赋值线程安全，IO 直接推；作者尚未收敛先展示全部同名结果
                            val snapshot = live.toList()
                            _switchCandidates.value = snapshot
                            _switchMsg.value = "已搜 $done/${sources.size} 源，找到 ${snapshot.size} 个同名结果"
                        }
                    }.awaitAll()
                }
                // 收尾：作者众数精修（过滤掉明显不同作者的结果），写缓存
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
                // 同书名同作者的候选聚合成一条：主条目取展示信息最全的，其余作为附属来源挂在 moreSources。
                // 这样同一本书只显示一张卡，点一次把它的所有来源都加入可切换列表。
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
                _switchMsg.value = if (merged.isEmpty()) "没有找到同名同作者的其他来源" else "找到 ${merged.size} 本同名书（共 ${finalCands.size} 个来源）"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                _switchMsg.value = "搜索失败：${e.message}"
            } finally {
                _switchLoading.value = false
            }
        }
    }

    /** 应用换源候选：把主来源 + 同组附属来源一次性拿目录加入列表 → 切到主来源并保持当前章 */
    fun applySwitchCandidate(c: SwitchCandidate) {
        viewModelScope.launch(Dispatchers.IO) {
            _netStatus.value = "正在切换到「${c.sourceName}」…"
            val refs = listOf(
                SwitchSourceRef(c.sourceId, c.sourceJson, c.sourceName, c.bookUrl)
            ) + c.moreSources
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
                _netStatus.value = "该来源缺少目录地址，无法切换"
                return@launch
            }
            _origins.value = list
            netStartIndex = lastChapterIndex // 保持当前章
            _sourceIndex.value = -1          // 绕过 switchSource 的 index 相等判断
            switchSource(primaryTarget)
            _switchCandidates.value = emptyList()
        }
    }

    /**
     * 拉取网络书：目录一次性拉全 → 先同步抓当前章（避免占位→正文跳变）→ 后台预加载后 19 章。
     * 章节结构沿用 TxtLive.Chapter(title,start,end)：正文（含标题行）拼成 rawText，
     * 章节起始偏移即在该 rawText 中的位置，后续分页/进度/滚动全部零改动复用。
     */
    private fun loadNetworkBook(
        net: com.yg.comic.data.NetworkBookEntity,
        startIndex: Int,
        startUrl: String = "",
        startTitle: String = "",
    ) {
        // 还原多源列表：优先用导入时存的 originsJson，老书(空)则用本书自身的单源兜底
        val parsed = runCatching {
            if (net.originsJson.isBlank()) emptyList()
            else originJson.decodeFromString<List<SourceOrigin>>(net.originsJson)
        }.getOrDefault(emptyList())
        _origins.value = parsed.ifEmpty {
            listOf(SourceOrigin(
                sourceId = net.sourceId, sourceJson = net.sourceJson,
                sourceName = net.sourceName, bookUrl = net.bookUrl, tocUrl = net.tocUrl,
            ))
        }
        netStartIndex = startIndex
        lastChapterIndex = startIndex // 初始进度章，换源恢复到此处
        loadFromSource(net.sourceJson, net.tocUrl, net.bookUrl, startIndex, startUrl, startTitle)
    }

    /** 用指定来源的 sourceJson + tocUrl 重新拉目录并重建缓冲窗口（切源入口） */
    fun switchSource(index: Int) {
        val origin = _origins.value.getOrNull(index) ?: return
        if (index == _sourceIndex.value) return
        // 其他来源若没存 tocUrl，回退到 bookUrl（Rust 引擎在 toc_url 空时也会这么回退）
        val toc = origin.tocUrl.ifBlank { origin.bookUrl }
        if (toc.isBlank() || origin.sourceJson.isBlank()) {
            _netStatus.value = "该来源缺少目录地址，无法切换"
            return
        }
        _sourceIndex.value = index
        netLoadGen++ // 作废旧窗口的后台加载
        loadFromSource(origin.sourceJson, toc, origin.bookUrl, netStartIndex)
    }

    /** 真正拉取目录 + 同步抓当前章 + 后台预加载窗口 */
    private fun loadFromSource(
        sourceJson: String,
        tocUrl: String,
        bookUrl: String,
        startIndex: Int,
        startUrl: String = "",
        startTitle: String = "",
    ) {
        try {
            // 优先读详情页已落库的目录缓存（key=sourceJson|bookUrl），命中即免联网，
            // 解决「每次进书都要重新拉目录、一直显示加载中」。缓存空才联网。
            var tocEntries = if (bookUrl.isNotBlank()) {
                runBlocking(Dispatchers.IO) {
                    repo.chaptersFor(repo.chapterCacheKey(sourceJson, bookUrl))
                }.map { TocEntry(it.title, it.url, it.isVolume) }
            } else emptyList()
            // 本地缓存目录同样过一遍收口（镜像重复/碎片 URL 的老缓存会直接让章号错位）
            if (tocEntries.isNotEmpty()) {
                val norm = com.yg.comic.source.SourceRepository.orderAscending(
                    com.yg.comic.source.SourceRepository.normalizeToc(
                        tocEntries.map { com.yg.comic.source.SourceChapter(it.title, it.url, isVolume = it.isVolume) },
                    ),
                )
                tocEntries = norm.map { TocEntry(it.title, it.url, it.isVolume) }
            }
            if (tocEntries.isEmpty()) {
                _netStatus.value = "正在获取目录…"
                // 与详情页落库目录口径一致：联网目录标题也过 purifyTitle（按书源名作用域），
                // 否则详情页缓存走净化、这里走原始，两边标题/条数漂移会让按章号定位错位。
                val scope = _origins.value.firstOrNull { it.sourceJson == sourceJson }?.sourceName
                tocEntries = com.yg.comic.source.SourceRepository.toc(sourceJson, tocUrl).getOrElse {
                    _netStatus.value = "目录加载失败：${it.message}"
                    return
                }.map {
                    TocEntry(
                        runBlocking(Dispatchers.IO) { repo.purifyTitle(it.title.trim(), scope) },
                        it.url,
                        it.isVolume,
                    )
                }
            }
            // 目录缓存过期自救：点章进来带的是章 URL，缓存里没有这条 URL，说明本地缓存跟详情页
            // 正在显示的那份目录已经不是同一份（源改版/目录翻新/缓存残缺），此时按章号落点会整体错位。
            // 联网重拉一次目录并按 URL 定位，顺便把新目录落库，让详情页和阅读器口径重新对齐。
            if (tocEntries.isNotEmpty() && startUrl.isNotBlank() && tocEntries.none { it.url == startUrl }) {
                val scope = _origins.value.firstOrNull { it.sourceJson == sourceJson }?.sourceName
                val fresh = runCatching {
                    com.yg.comic.source.SourceRepository.toc(sourceJson, tocUrl).getOrNull()
                }.getOrNull().orEmpty()
                if (fresh.isNotEmpty()) {
                    tocEntries = fresh.map {
                        TocEntry(
                            runBlocking(Dispatchers.IO) { repo.purifyTitle(it.title.trim(), scope) },
                            it.url,
                            it.isVolume,
                        )
                    }
                    if (bookUrl.isNotBlank()) {
                        val ck = repo.chapterCacheKey(sourceJson, bookUrl)
                        runCatching {
                            runBlocking(Dispatchers.IO) {
                                repo.saveChapters(ck, fresh.mapIndexed { i, ch ->
                                    com.yg.comic.data.ChapterEntity(
                                        cacheKey = ck, url = ch.url, title = tocEntries[i].title,
                                        isVip = ch.isVip, isVolume = ch.isVolume, orderIndex = i,
                                    )
                                })
                            }
                        }
                    }
                }
            }
            if (tocEntries.isEmpty()) { _netStatus.value = "目录为空"; return }

            netSourceJson = sourceJson
            netBookUrl = bookUrl
            // 反序状态与详情页同源：详情页翻了，阅读器目录抽屉/翻章方向也跟着翻。
            // 注意只改展示与导航方向，正文 rawText/chapters 内部保持原始章号序。
            _chapterReversed.value = ChapterOrderStore.isReversed(sourceJson, bookUrl)
            netContentKey = "$sourceJson|$tocUrl"
            netToc = tocEntries
            netLoaded.clear()
            // 从本地缓存装载已抓过的正文（第二次进入免重新加载）
            val cached = runBlocking(Dispatchers.IO) { repo.allCachedChapterContents(netContentKey) }
            if (cached.isNotEmpty()) {
                val urlToIndex = HashMap<String, Int>()
                for (i in netToc.indices) urlToIndex[netToc[i].url] = i
                for ((url, text) in cached) {
                    val i = urlToIndex[url] ?: continue
                    // 落盘的是未净化原文：只进原文缓存。展示层 netLoaded 一律由
                    // fetchChapterIntoCache/repurifyLoadedChapters 从原文按当前规则净化生成，
                    // 否则缓存章会永久展示加规则前的旧文本（替换规则翻章不生效的根因）。
                    if (text.isNotEmpty()) netLoadedRaw[i] = text
                }
            }

            // 当前章同步抓一次：让首次渲染看到的直接是正文，不会先闪一下占位。
            // 落点：优先按「点的那一章的 URL」定位。详情页和阅读器的目录是两份（详情页可能来自网络、
            // 阅读器优先读缓存），条数/顺序一旦不一致，纯章号就会整体错位、甚至每次进来落点都变。
            // URL 是章的唯一标识，命中就以它为准；找不到才退回章号 + 卷标题校正。
            val idx = resolveStartIndex(startIndex, startUrl, startTitle)
            // 排查用：点章进来时把「要的那一章 / 实际落到哪一章」打出来，出问题抓 logcat 就能对。
            android.util.Log.i(
                "YGNovel",
                "订章 startIndex=$startIndex startUrl=${startUrl.take(120)} -> idx=$idx/${netToc.size} " +
                    "title=${netToc.getOrNull(idx)?.title} url=${netToc.getOrNull(idx)?.url?.take(120)}",
            )
            fetchChapterIntoCache(idx)
            rebuildNetRaw()

            // 通知 Screen 换源/重开：递增代数并给出恢复章。
            // 必须在 rawText/chapters 更新之后递增（上面 rebuildNetRaw 已完成），
            // 保证 Screen 一次重组里同时拿到新正文 + 新 epoch，从而重置分页、定位到恢复章。
            // 注意：不能放进 rebuildNetRaw，因为后台窗口预加载也会调它，那样会在翻页中途重置页面。
            _resumeIndex.value = idx
            _switchEpoch.value = _switchEpoch.value + 1

            // 后台预加载窗口（当前章已缓存，会跳过；实际加载 idx+1 .. idx+19）
            ensureWindow(idx)
        } catch (e: Exception) {
            _netStatus.value = "加载失败：${e.message}"
        }
    }

    /**
     * 确保单章的展示文本就绪；已就绪则跳过。
     * 展示层 netLoaded 只有这一个生产入口：无论原文来自缓存装载、磁盘缓存还是网络，
     * 一律从 netLoadedRaw 的原文按当前替换规则净化生成，杜绝旧净化结果残留。
     * 返回是否成功拿到（失败留空正文但不反复重抓）。
     */
    private fun fetchChapterIntoCache(index: Int): Boolean {
        // 展示文本已就绪才跳过；只有原文（netLoadedRaw）不算就绪，必须继续净化。
        if (netLoaded.containsKey(index)) return true
        val entry = netToc.getOrNull(index) ?: return false
        // 卷标题不是正文：不抓、不缓存，抽屉里只当分组标题渲染。
        if (entry.isVolume) return false

        // 原文可能已由 loadNetworkBook 的缓存装载填入 netLoadedRaw，直接用它净化，不重抓。
        if (!netLoadedRaw.containsKey(index)) {
            // 先查磁盘缓存（落盘的是净化前原文）
            val cached = runBlocking(Dispatchers.IO) { repo.cachedChapterContent(netContentKey, entry.url) }
            if (!cached.isNullOrEmpty()) {
                netLoadedRaw[index] = cached
            } else {
                val content = com.yg.comic.source.SourceRepository.content(netSourceJson, entry.url).getOrNull()
                val rawBody = (content?.text ?: "")
                    .replace("\r\n", "\n").replace("\r", "\n").trim()
                // 内容质量降权：源成功返回但正文为空 → 标记该源；恢复非空 → 清除标记。
                // content==null 属网络失败不算源质量，不标记。
                if (content != null) {
                    val curOrigin = _origins.value.getOrNull(_sourceIndex.value)
                    val sj = curOrigin?.sourceJson?.ifBlank { netSourceJson } ?: netSourceJson
                    val bu = curOrigin?.bookUrl
                    if (!bu.isNullOrBlank()) {
                        if (rawBody.isBlank()) repo.markEmptyContent(sj, bu) else repo.clearEmptyContent(sj, bu)
                    }
                }
                // 原文（净化前）= 原标题 + 原始正文，落盘与 netLoadedRaw 都存它
                val rawText = entry.title + "\n" + rawBody + "\n\n"
                netLoadedRaw[index] = rawText
                // 抓到正文落盘原文，下次进入免重抓；空正文不缓存，避免把失败结果固化
                if (content != null && rawBody.isNotBlank()) {
                    runBlocking(Dispatchers.IO) { repo.saveChapterContent(netContentKey, entry.url, rawText) }
                }
                if (content == null) return false
            }
        }

        // 唯一出口：从原文按当前规则净化生成展示文本
        netLoaded[index] = purifyRawText(netLoadedRaw[index] ?: return false, entry.title)
        return true
    }

    /** 对原文（标题行 + 正文）按当前替换规则净化，得到展示文本 */
    private fun purifyRawText(rawText: String, title: String): String {
        val nl = rawText.indexOf('\n')
        val bodyRaw = if (nl >= 0) rawText.substring(nl + 1) else ""
        // scopeKey 拼书名 + 当前源名 + 源配置 JSON（含书源 URL），
        // 让替换规则的「替换范围」既能填书名也能填书源 URL（legado 语义）
        val origin = _origins.value.getOrNull(_sourceIndex.value)
        val scopeKey = listOf(_title.value, origin?.sourceName.orEmpty(), origin?.sourceJson?.ifBlank { netSourceJson } ?: netSourceJson)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val body = runBlocking(Dispatchers.IO) { repo.purifyContent(bodyRaw, scopeKey) }
        val cleanTitle = runBlocking(Dispatchers.IO) { repo.purifyTitle(title, scopeKey) }
        return cleanTitle + "\n" + body
    }

    /**
     * 用 netToc + netLoaded 重建整本 rawText 与章节偏移。
     * rawText/chapters 变化会触发 Screen 的 remember 重算，正文一到位就刷新页面。
     */
    private fun rebuildNetRaw() {
        val sb = StringBuilder()
        val list = ArrayList<com.yg.comic.engine.TxtLive.Chapter>(netToc.size)
        for (i in netToc.indices) {
            val title = netToc[i].title
            // 卷标题不是正文：不占正文（start==end 的空章），只留下标占位让章号与详情页一致；
            // 导航层跳过卷，用户永远落不到这个空章，也不会在抽屉里被当成「一章」。
            val body = if (netToc[i].isVolume) "" else (netLoaded[i] ?: placeholder(title))
            val start = sb.length
            sb.append(body)
            val end = sb.length
            list.add(com.yg.comic.engine.TxtLive.Chapter(title, start, end))
        }
        _rawText.value = sb.toString()
        _chapters.value = list
        _chapterVolumes.value = netToc.map { it.isVolume }
        rebuildTocDisplay()
    }

    /** 展示序翻章：返回 from 沿 step 方向最近的可读章号；没有了返回 -1。卷标题不占落点。 */
    fun nextReadableChapter(from: Int, step: Int): Int {
        if (netToc.isEmpty()) return -1
        var i = from + step
        while (i in netToc.indices) {
            if (!netToc[i].isVolume) return i
            i += step
        }
        return -1
    }

    /** 落点校正：index 落在卷标题上时按 step 方向挪到最近的可读章，找不到反向找，兜底 0。 */
    fun snapReadable(index: Int, step: Int = 1): Int {
        if (netToc.isEmpty()) return index.coerceAtLeast(0)
        val start = index.coerceIn(0, netToc.lastIndex)
        var i = start
        while (i in netToc.indices && netToc[i].isVolume) i += step
        if (i in netToc.indices) return i
        i = start
        while (i in netToc.indices && netToc[i].isVolume) i -= step
        return if (i in netToc.indices) i else 0
    }

    /**
     * 起始章定位：URL 精确匹配优先（章的唯一标识），其次标题匹配，最后才按章号 + 卷标题校正。
     * 详情页显示的是它自己那份目录（网络或缓存），阅读器优先读本地缓存，两份条数/顺序不一致时
     * 纯章号会整体错位——用户症状「点目录第一章，进去却是别的章，而且每次不一样」。
     * 有 URL 就按 URL 找，跟两边目录条数怎么变都无关；URL 里挂着卷标题的，跳过卷落到正文章。
     */
    private fun resolveStartIndex(fallback: Int, url: String, title: String): Int {
        if (netToc.isEmpty()) return fallback.coerceAtLeast(0)
        if (url.isNotBlank()) {
            val hit = netToc.indices.firstOrNull { netToc[it].url == url && !netToc[it].isVolume }
                ?: netToc.indices.firstOrNull { netToc[it].url == url }
            if (hit != null) return hit
        }
        if (title.isNotBlank()) {
            val hit = netToc.indices.firstOrNull { netToc[it].title == title && !netToc[it].isVolume }
                ?: netToc.indices.firstOrNull { netToc[it].title == title }
            if (hit != null) return hit
        }
        return snapReadable(fallback.coerceIn(0, netToc.lastIndex), 1)
    }

    /**
     * 替换/净化规则变更后调用：从原文（netLoadedRaw）按当前规则重新净化所有已加载章节。
     * 关键：netLoadedRaw 存的是未净化原文，所以关闭规则后重新净化能把之前被删掉的字恢复回来；
     * 若只在净化后文本上再跑规则，删掉的字永远加不回来。无需重新联网抓正文。
     * 落盘缓存已经存原文，这里只更新内存展示缓存并重建正文。
     */
    suspend fun repurifyLoadedChapters() = withContext(Dispatchers.IO) {
        if (netToc.isEmpty()) return@withContext
        for (i in netLoadedRaw.keys()) {
            val raw = netLoadedRaw[i] ?: continue
            val title = netToc.getOrNull(i)?.title ?: ""
            netLoaded[i] = purifyRawText(raw, title)
        }
        rebuildNetRaw()
    }

    /**
     * 滑动窗口预加载：center..center+19，已缓存的跳过。
     * 往前翻章不重抓（缓存一直在）；往后翻章滚动补新窗口。
     */
    private fun ensureWindow(center: Int) {
        if (netToc.isEmpty()) return
        if (center == lastCenter) return
        lastCenter = center
        val from = center.coerceIn(0, netToc.lastIndex)
        val to = (center + windowSize - 1).coerceAtMost(netToc.lastIndex)
        val targets = (from..to).filter { !netLoaded.containsKey(it) && !netToc[it].isVolume }
        if (targets.isEmpty()) return

        val gen = ++netLoadGen
        viewModelScope.launch(Dispatchers.IO) {
            var done = 0
            for (i in targets) {
                if (gen != netLoadGen) return@launch   // 过期窗口，丢弃
                fetchChapterIntoCache(i)
                done++
                if (gen == netLoadGen) {
                    _netStatus.value = "已缓冲 $done/${targets.size} 章…"
                    rebuildNetRaw()
                }
            }
            if (gen == netLoadGen) _netStatus.value = ""
        }
    }

    /** 用户翻页后保存进度（章节索引 + 章内字符偏移） */
    fun saveProgress(page: NovelPage) {
        val key = "${page.chapterIndex}:${page.startOffset}"
        if (key == lastSavedKey) return
        lastSavedKey = key
        lastChapterIndex = page.chapterIndex // 换源后恢复到最近阅读章
        // 临时阅读（bookId=-1L）不落库，避免脏进度写进 db；窗口滚动照常
        if (bookId >= 0L) {
            val p = ReadProgress(bookId = bookId, pageIndex = page.chapterIndex, chapterOffset = page.startOffset, extra = ReadProgress.KIND_NOVEL)
            progressExecutor.execute {
                try { runBlocking { repo.saveProgress(p) } } catch (_: Exception) {}
            }
        }
        // 翻章触发窗口滚动（loadNetworkBook 阶段 netToc 为空时不会走到这里）
        if (netToc.isNotEmpty() && page.chapterIndex != lastCenter) {
            ensureWindow(page.chapterIndex)
        }
    }

    override fun onCleared() {
        flushReadTime()
        runCatching { stopAloud() }
        tts?.shutdown()
        tts = null
        networkAudioCache.clear()
        progressExecutor.shutdown()
        super.onCleared()
    }
}
