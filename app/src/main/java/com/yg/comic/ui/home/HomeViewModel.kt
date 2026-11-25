package com.yg.comic.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yg.comic.data.BookRepository
import com.yg.comic.data.BookSourceEntity
import com.yg.comic.data.SettingsStore
import com.yg.comic.data.SourceHealthStore
import com.yg.comic.shared.SearchRules
import com.yg.comic.source.SearchBook
import com.yg.comic.source.SourceOrigin
import com.yg.comic.source.SourceRepository
import com.yg.comic.source.mihon.MihonExtensionManager
import com.yg.comic.source.mihon.MihonSourceProvider
import com.yg.comic.ui.source.SourcedBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/** 首页书源切换：分组方式（保留供搜索页/详情页按源浏览用） */
enum class SourceGroupMode(val label: String) {
    TYPE("按类型"), GROUP("按分组"), SUBSCRIBE("按订阅"),
}

/** 书源分类（exploreUrl 的一行「标题::url」） */
data class ExploreCategory(val title: String, val url: String)

/** Mihon 一个插件(APK/packageName)聚合：下面挂多个语言源，首页一张卡、浏览页下拉切语言。 */
data class MihonPlugin(val packageName: String, val displayName: String, val sources: List<BookSourceEntity>)

/** 一个聚合分类下，提供它的某个源及其分类地址 */
data class CategorySource(val source: BookSourceEntity, val url: String)

/** 首页平铺的叶子分类：title 去重，sources 为所有提供它的源 */
data class AggregateCategory(val title: String, val sources: List<CategorySource>, val count: Int)

/** 首页上排订阅分组：key=完整订阅链接（唯一，决定分组），displayName=显示名（订阅名/域名） */
data class SubscribeGroup(val key: String, val displayName: String, val sources: List<BookSourceEntity>)

/**
 * 首页数据源（分类广场）：
 * 把各书源 exploreUrl 里的叶子分类抽出来平铺，点一个分类跨所有提供它的源出书；
 * 源不再套在分类结构里，选源收进搜索/详情页。
 */
class HomeViewModel(private val repo: BookRepository) : ViewModel() {

    /** 全部启用的书源 */
    val enabledSources: StateFlow<List<BookSourceEntity>> =
        repo.bookSources.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 首页书源排序：与搜索页同口径（网络分层 + 健康度降序 + 组内归一化），受智能排序开关控制。
    private val health = SourceHealthStore(repo.appContext)
    private val settings = SettingsStore(repo.appContext)
    val autoSortEnabled: StateFlow<Boolean> =
        settings.autoSortEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * 首页书源列表排序。netMap 由 Screen 传入（实时网站检测结果：1=通 / 2=不通 / 3=存疑），
     * 避免 VM 重复读 SharedPreferences。关闭智能排序时保持原序。
     */
    fun sortedSources(list: List<BookSourceEntity>, netMap: Map<Long, Int>): List<BookSourceEntity> {
        if (!autoSortEnabled.value) return list
        fun tier(id: Long): Int = when (netMap[id]) {
            1 -> 0       // 已测通：最优先
            3 -> 1       // 存疑：其次
            2 -> 3       // 不通：放最后
            else -> 2    // 未检测：介于存疑与不通之间
        }
        val groupAvg = HashMap<String, Double>()
        list.groupBy { SearchRules.groupKeyOf(it.groupName) }.forEach { (g, sub) ->
            groupAvg[g] = sub.map { health.score(it.id) }.average()
        }
        return list.sortedWith(
            compareBy<BookSourceEntity> { tier(it.id) }
                .thenByDescending { health.score(it.id, groupAvg[SearchRules.groupKeyOf(it.groupName)]) }
        )
    }

    private val _groupMode = MutableStateFlow(SourceGroupMode.TYPE)
    val groupMode: StateFlow<SourceGroupMode> = _groupMode.asStateFlow()

    private val _selectedSourceId = MutableStateFlow<Long>(0L)
    val selectedSourceId: StateFlow<Long> = _selectedSourceId.asStateFlow()

    // —— 兼容旧字段：保留供其它页面引用，首页不再主打 ——
    private val _categories = MutableStateFlow<List<ExploreCategory>>(emptyList())
    val categories: StateFlow<List<ExploreCategory>> = _categories.asStateFlow()

    private val _selectedCategory = MutableStateFlow<ExploreCategory?>(null)
    val selectedCategory: StateFlow<ExploreCategory?> = _selectedCategory.asStateFlow()

    // —— 分类广场核心 ——
    private val _allCategories = MutableStateFlow<List<AggregateCategory>>(emptyList())
    val allCategories: StateFlow<List<AggregateCategory>> = _allCategories.asStateFlow()

    /** 有可浏览分类的书源ID集合：首页只展示这类源，没有发现分类的源在首页屏蔽掉 */
    private val _browsableSourceIds = MutableStateFlow<Set<Long>>(emptySet())
    val browsableSourceIds: StateFlow<Set<Long>> = _browsableSourceIds.asStateFlow()

    /** 有可浏览分类的漫画源ID集合：漫画tab同样屏蔽没有分类的漫画源 */
    private val _comicBrowsableSourceIds = MutableStateFlow<Set<Long>>(emptySet())
    val comicBrowsableSourceIds: StateFlow<Set<Long>> = _comicBrowsableSourceIds.asStateFlow()

    private val _selectedCategoryTitle = MutableStateFlow("")
    val selectedCategoryTitle: StateFlow<String> = _selectedCategoryTitle.asStateFlow()

    private val _books = MutableStateFlow<List<SourcedBook>>(emptyList())
    val books: StateFlow<List<SourcedBook>> = _books.asStateFlow()

    private val _exploring = MutableStateFlow(false)
    val exploring: StateFlow<Boolean> = _exploring.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    // —— Mihon 浏览页筛选：描述与当前值都是纯 JSON 数组串，类型由 UI 解析，不碰扩展类型 ——
    private val _filterJson = MutableStateFlow("[]")
    val filterJson: StateFlow<String> = _filterJson.asStateFlow()

    private var exploreJob: Job? = null

    init {
        // 书源列表变化时重建聚合分类（IO 解析 JSON，避免卡主线程）
        viewModelScope.launch(Dispatchers.IO) {
            enabledSources.collect { sources -> rebuildCategories(sources) }
        }
    }

    fun setGroupMode(mode: SourceGroupMode) {
        _groupMode.value = mode
    }

    /** 当前分组方式下的分组列表：(分组名, 该书源集合) —— 保留 */
    fun grouped(sources: List<BookSourceEntity>, mode: SourceGroupMode): List<Pair<String, List<BookSourceEntity>>> {
        val enabled = sources.filter { it.enabled }
        val byKey = when (mode) {
            SourceGroupMode.TYPE -> enabled.groupBy { typeLabel(it.sourceType) }
            SourceGroupMode.GROUP -> enabled.groupBy { it.groupName.ifBlank { "未分组" } }
            // 订阅分组显示名字而非链接：导入时存的名字优先，老数据回退订阅域名，再退"手动添加"。
            SourceGroupMode.SUBSCRIBE -> enabled.groupBy { subscribeDisplayName(it) }
        }
        return byKey.toList().sortedBy { it.first }
    }

    /**
     * 首页上排订阅分组：按完整订阅链接分组（天然唯一），显示名单独算。
     * 修复「导入多个订阅只显示一组」——旧版按显示名分组，多个订阅 host 相同（或都没填名字）
     * 会全部聚成一组；改用 subscribeUrl 作 key 后，每个订阅链接各占一组。
     */
    fun subscribeGroups(sources: List<BookSourceEntity>): List<SubscribeGroup> {
        val enabled = sources.filter { it.enabled }
        return enabled.groupBy { it.subscribeUrl }
            .toList()
            .map { (key, list) -> SubscribeGroup(key, subscribeDisplayName(list.first()), list) }
            .sortedBy { it.displayName }
    }

    /** 订阅显示名：导入时填的名字优先，老数据回退订阅链接域名，都没有则"手动添加"。 */
    private fun subscribeDisplayName(src: BookSourceEntity): String {
        if (src.subscribeName.isNotBlank()) return src.subscribeName
        if (src.subscribeUrl.isNotBlank()) {
            val host = runCatching { java.net.URI(src.subscribeUrl).host }.getOrNull()
            if (!host.isNullOrBlank()) return host
        }
        return "手动添加"
    }

    /**
     * 重命名订阅组：按订阅链接（分组 key）定位该组所有源，统一改显示名。
     * 用 subscribeUrl 而非显示名定位，避免多个订阅显示名相同时误改整批。
     */
    fun renameSubscribe(key: String, newName: String) {
        if (newName.isBlank()) return
        val ids = enabledSources.value.filter { it.subscribeUrl == key }.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { repo.renameSubscribe(ids, newName) }
    }

    fun typeLabel(type: Int): String = when (type) {
        0 -> "文字"
        1 -> "音频"
        2 -> "漫画"
        3 -> "下载"
        4 -> "视频"
        else -> "其他"
    }

    private data class CatBucket(val title: String, val sources: MutableList<CategorySource>)

    /** 按源作者自带的分组标签(groupName)铺开分类：一源可属多类，标签可叠加；小说tab只放文字源 */
    private fun rebuildCategories(sources: List<BookSourceEntity>) {
        val byTitle = LinkedHashMap<String, CatBucket>()
        // 首页只展示「有可浏览分类」的源：逐个解析 exploreUrl，空的源屏蔽掉。
        _browsableSourceIds.value = sources.filter { it.enabled && it.sourceType == 0 }
            .filter { parseExploreCategories(it.json).isNotEmpty() }
            .map { it.id }
            .toSet()
        // 漫画tab：列出所有已启用的漫画源（sourceType==2）。
        // 不再按站名黑名单过滤（用户 m31908 已推翻），也不再按有无分类过滤——
        // 之前只留「有分类」的导致只剩 8 个源（用户 m32950 嫌少）。无分类的源点进去会提示用顶部搜索。
        _comicBrowsableSourceIds.value = sources.filter { it.enabled && it.sourceType == 2 }
            .map { it.id }
            .toSet()
        for (src in sources.filter { it.enabled && it.sourceType == 0 }) {
            val tags = src.groupName.split(Regex("[,;，；]"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf("未分组") }
            for (tag in tags) {
                val bucket = byTitle.getOrPut(tag) { CatBucket(tag, mutableListOf()) }
                if (bucket.sources.none { it.source.id == src.id }) {
                    // url 留空：点分类时再展开该源自己的全部发现分类
                    bucket.sources.add(CategorySource(src, ""))
                }
            }
        }
        _allCategories.value = byTitle.values
            .map { AggregateCategory(it.title, it.sources, it.sources.size) }
            .sortedWith(compareByDescending<AggregateCategory> { it.count }.thenBy { it.title })
    }

    /** 点一个平铺分类：跨所有提供它的源并发 explore，合并去重出书 */
    fun selectCategoryByTitle(title: String) {
        exploreJob?.cancel()
        val cat = _allCategories.value.firstOrNull { it.title == title || normKey(it.title) == normKey(title) }
            ?: return
        _selectedCategoryTitle.value = cat.title
        exploreJob = viewModelScope.launch(Dispatchers.IO) {
            _exploring.value = true
            _message.value = ""
            val sem = Semaphore(12)
            // 分类来自源作者分组标签：每个源 url 留空，展开该源自己的全部分类逐个 explore
            val exploreTasks = mutableListOf<Pair<String, BookSourceEntity>>()
            for (cs in cat.sources) {
                val urls = if (cs.url.isNotBlank()) listOf(cs.url)
                           else parseExploreCategories(cs.source.json).map { it.url }.ifEmpty { listOf("") }
                for (u in urls) exploreTasks.add(u to cs.source)
            }
            val pairs: List<Pair<SearchBook, BookSourceEntity>> = coroutineScope {
                exploreTasks.map { (url, src) ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            runCatching { SourceRepository.explore(src.json, url, 1) }
                                .getOrNull()?.getOrNull().orEmpty()
                                .map { book -> book to src }
                        }
                    }
                }.awaitAll().flatten()
            }
            val merged = mergeBooks(pairs)
            _books.value = merged
            _exploring.value = false
            if (merged.isEmpty()) _message.value = "「${cat.title}」暂未搜到书籍，换一个分类试试"
        }
    }

    /** 按书名+作者去重，同书多源合并为 origins，保留主源用于进详情页 */
    private fun mergeBooks(pairs: List<Pair<SearchBook, BookSourceEntity>>): List<SourcedBook> {
        val groups = LinkedHashMap<String, MutableList<Pair<SearchBook, BookSourceEntity>>>()
        for (pair in pairs) {
            val key = normKey(pair.first.name) + "|" + normKey(pair.first.author)
            if (key == "|") continue
            groups.getOrPut(key) { mutableListOf() }.add(pair)
        }
        return groups.values.map { group ->
            val (primary, primarySource) = group.first()
            val origins = LinkedHashMap<Long, SourceOrigin>()
            for ((book, source) in group) {
                origins.putIfAbsent(
                    source.id,
                    SourceOrigin(source.id, source.json, source.name, book.bookUrl, "")
                )
            }
            SourcedBook(primary, primarySource, origins.values.toList(), 0)
        }
    }

    private fun normKey(s: String): String = s.trim()
        .trim('《', '》', '〈', '〉', '「', '」', '『', '』')
        .replace(Regex("\\s+"), "")
        .lowercase()

    // —— 以下保留旧的按源浏览入口，供搜索页/详情页按源使用 ——

    /** 首页切换项目时清掉钻取态，避免上一个项目选中的源/书串到新项目。 */
    fun clearBrowseState() {
        exploreJob?.cancel()
        _selectedSourceId.value = 0L
        _books.value = emptyList()
        _selectedCategory.value = null
        _selectedCategoryTitle.value = ""
    }

    /** Mihon 扩展源的浏览标签：热门/最近更新走引擎现成接口，筛选点开筛选面板。全部不造数据。 */
    private val mihonBrowseCategories = listOf(
        ExploreCategory("热门", "popular"),
        ExploreCategory("最近更新", "latest"),
        ExploreCategory("筛选", "filter"),
    )

    /** 是否 Mihon 扩展源（其书源 json 里 provider=mihon，无 exploreUrl，不能用普通分类那套）。 */
    fun isMihonSource(src: BookSourceEntity): Boolean =
        runCatching { JSONObject(src.json).optString("provider") }.getOrDefault("") == "mihon"

    /** Mihon 源按插件(APK/packageName)去重计数：一个插件下多个语言源只算一个。 */
    fun mihonPluginCount(sources: List<BookSourceEntity>): Int =
        sources.mapNotNull { src ->
            runCatching { JSONObject(src.json).optString("packageName") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        }.distinct().size

    /** Mihon 源按 packageName 聚合：一个插件一张卡，卡名取组内首个源的 name；顺序跟随入参。 */
    fun mihonPluginGroups(sources: List<BookSourceEntity>): List<MihonPlugin> {
        val groups = LinkedHashMap<String, MutableList<BookSourceEntity>>()
        sources.forEach { src ->
            val pkg = runCatching { JSONObject(src.json).optString("packageName") }.getOrDefault("")
            val key = pkg.ifBlank { "id:${src.id}" } // 无包名的老源各自成卡，保证不丢
            groups.getOrPut(key) { mutableListOf() }.add(src)
        }
        return groups.map { (pkg, list) -> MihonPlugin(pkg, list.first().name, list) }
    }

    /** 语言显示名：读 json.lang（书源工程师写入的显示名 Deutsch/English/中文）；空串表示老源/无字段。 */
    fun mihonLangLabel(src: BookSourceEntity): String =
        runCatching { JSONObject(src.json).optString("lang") }.getOrDefault("")

    /**
     * 语言下拉最终显示名（a 代码兜底）：先读入库 json.lang；老源没这个字段时，
     * 拿 packageName + sourceId 去本地已加载扩展运行时查 CatalogueSource.lang 转显示名；
     * 仍取不到返回空串，UI 回退 src.name。sourceId 在 json 里是字符串数字。
     */
    fun mihonLangResolved(src: BookSourceEntity): String {
        val stored = mihonLangLabel(src)
        if (stored.isNotBlank()) return stored
        val pkg = runCatching { JSONObject(src.json).optString("packageName") }.getOrDefault("")
        val sid = runCatching { JSONObject(src.json).optString("sourceId") }.getOrDefault("")
        if (pkg.isBlank() || sid.isBlank()) return ""
        return MihonExtensionManager.sourceLangName(pkg, sid).orEmpty()
    }

    /** 某个 Mihon 源所在的插件组（供浏览页"切换语言"下拉列出同插件各语言源）。 */
    fun mihonPluginOf(src: BookSourceEntity?, sources: List<BookSourceEntity>): MihonPlugin? {
        if (src == null) return null
        val pkg = runCatching { JSONObject(src.json).optString("packageName") }.getOrDefault("")
        val key = pkg.ifBlank { "id:${src.id}" }
        return mihonPluginGroups(sources).firstOrNull { it.packageName == key }
    }

    fun selectSource(src: BookSourceEntity) {
        exploreJob?.cancel()
        _selectedSourceId.value = src.id
        // 立即清空上一次源的书籍列表，避免异步加载期间残留显示上次的书（用户 m32950）
        _books.value = emptyList()
        val cats = if (isMihonSource(src)) mihonBrowseCategories else parseExploreCategories(src.json)
        _categories.value = cats
        if (cats.isEmpty()) {
            _selectedCategory.value = null
            _books.value = emptyList()
        } else {
            // 点进书源直接加载首个分类的书：上方选分类、下方浏览，不再多一层"选分类"页。
            _selectedCategory.value = cats.first()
            // 筛选标签不触发浏览，交给 UI 展开筛选面板。
            if (cats.first().url != "filter") loadCategory(src, cats.first(), 1)
        }
    }

    fun selectCategory(cat: ExploreCategory) {
        val src = currentSource() ?: return
        _selectedCategory.value = cat
        // 筛选标签只切选中态、不重新浏览：UI 会展开筛选面板，面板里选完再走带筛选的加载。
        if (cat.url == "filter") return
        loadCategory(src, cat, 1)
    }

    /** 打开 Mihon 筛选面板：从扩展取筛选项描述（纯 JSON 数组串）。IO 线程，失败给空数组。 */
    fun loadMihonFilters() {
        val src = currentSource() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _filterJson.value = MihonSourceProvider.exploreFilters(src.json).getOrElse { "[]" }
        }
    }

    /** 应用筛选浏览：把 UI 改完 state 的整个 JSON 数组原样回传引擎，出书。 */
    fun applyMihonFilters(filtersJson: String) {
        val src = currentSource() ?: return
        exploreJob?.cancel()
        exploreJob = viewModelScope.launch(Dispatchers.IO) {
            _exploring.value = true
            _message.value = ""
            val list = MihonSourceProvider.exploreWithFilters(src.json, filtersJson, 1)
                .getOrElse { emptyList() }
            _books.value = list.map { it to src }.let { pairs -> mergeBooks(pairs) }
            _filterJson.value = filtersJson
            _exploring.value = false
            if (list.isEmpty()) _message.value = "没有符合条件的作品，换个筛选试试"
        }
    }

    private fun currentSource(): BookSourceEntity? =
        enabledSources.value.firstOrNull { it.id == _selectedSourceId.value }

    /** 从书源 JSON 的 exploreUrl 字段解析分类列表，对齐 legado exploreKinds 的三种形态 */
    private fun parseExploreCategories(json: String): List<ExploreCategory> = try {
        val obj = JSONObject(json)
        val raw = obj.optString("exploreUrl", "")
            .ifBlank { obj.optString("bookSourceExploreUrl", "") }
        val trimmed = raw.trim()
        when {
            trimmed.isEmpty() -> emptyList()
            trimmed.startsWith("[") -> {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).mapNotNull { i ->
                    val item = arr.optJSONObject(i) ?: return@mapNotNull null
                    val title = item.optString("title", "").ifBlank { item.optString("name", "") }
                    val url = item.optString("url", "")
                    if (url.isBlank()) return@mapNotNull null
                    ExploreCategory(title.ifBlank { url }, url)
                }
            }
            trimmed.startsWith("@js:", true) || trimmed.startsWith("<js", true) -> emptyList()
            else -> trimmed.split(Regex("(&&|\\n)+")).mapNotNull { seg ->
                val s = seg.trim()
                if (s.isEmpty()) return@mapNotNull null
                val idx = s.indexOf("::")
                if (idx >= 0) {
                    val title = s.substring(0, idx).trim()
                    val url = s.substring(idx + 2).trim()
                    if (url.isNotEmpty()) ExploreCategory(title.ifBlank { url }, url) else null
                } else {
                    ExploreCategory("分类", s)
                }
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun loadCategory(src: BookSourceEntity, cat: ExploreCategory, page: Int) {
        exploreJob?.cancel()
        exploreJob = viewModelScope.launch(Dispatchers.IO) {
            _exploring.value = true
            _message.value = ""
            val result = runCatching { SourceRepository.explore(src.json, cat.url, page) }
                .getOrElse { Result.failure(it) }
            result.onSuccess { list ->
                _books.value = list.map { it to src }.let { pairs -> mergeBooks(pairs) }
                if (list.isEmpty()) _message.value = "该分类暂无书籍"
            }.onFailure { e ->
                _books.value = emptyList()
                _message.value = "加载失败：${e.message ?: "未知错误"}"
            }
            _exploring.value = false
        }
    }

    fun clearMessage() { _message.value = "" }
}
