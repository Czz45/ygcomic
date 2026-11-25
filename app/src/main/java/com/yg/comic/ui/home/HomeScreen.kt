package com.yg.comic.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.yg.comic.data.BookRepository
import com.yg.comic.data.BookSourceEntity
import com.yg.comic.data.ORIGIN_LEGADO
import com.yg.comic.data.ORIGIN_MIHON
import com.yg.comic.data.ORIGIN_YICIYUAN
import com.yg.comic.data.originTypeOf
import com.yg.comic.source.SearchBook
import com.yg.comic.ui.components.DialogActions
import com.yg.comic.ui.components.LargeTitle
import com.yg.comic.ui.detail.SharedBookDetail
import com.yg.comic.ui.source.SourcedBook
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

// 源图标首字色块调色板（与书源页图源卡一致）。
private val HOME_SOURCE_ICON_PALETTE = listOf(
    androidx.compose.ui.graphics.Color(0xFF5B6EE1), androidx.compose.ui.graphics.Color(0xFF3D8BFD),
    androidx.compose.ui.graphics.Color(0xFF20A4A4), androidx.compose.ui.graphics.Color(0xFFC77DDB),
    androidx.compose.ui.graphics.Color(0xFFE08A3C), androidx.compose.ui.graphics.Color(0xFF54A958),
    androidx.compose.ui.graphics.Color(0xFFD96A8F), androidx.compose.ui.graphics.Color(0xFF7A6FD8),
)

/** 从书源 json 读 packageName（Mihon 扩展用它取真实图标）；普通源为空。 */
private fun homeSourcePkg(src: BookSourceEntity): String =
    runCatching { org.json.JSONObject(src.json).optString("packageName") }.getOrDefault("")

/** 书源是否成人源（json.nsfw）；坏 json 当普通源。 */
private fun homeSourceNsfw(src: BookSourceEntity): Boolean =
    runCatching { org.json.JSONObject(src.json).optBoolean("nsfw", false) }.getOrDefault(false)

/**
 * 首页源列表项图标：Mihon 扩展按 packageName 后台解析真实图标（图源页同款），
 * 普通书源或取不到图标时回退首字色块，绝不留白。
 */
@Composable
private fun HomeSourceIcon(name: String, packageName: String) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, packageName) {
        value = if (packageName.isBlank()) null else withContext(Dispatchers.IO) {
            val dr = com.yg.comic.source.mihon.MihonExtensionManager.iconFor(packageName)
                ?: return@withContext null
            val px = 96
            val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bmp).let { c ->
                dr.setBounds(0, 0, px, px)
                dr.draw(c)
            }
            bmp.asImageBitmap()
        }
    }
    val bm = bitmap
    if (bm != null) {
        androidx.compose.foundation.Image(
            bitmap = bm,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
        )
    } else {
        val color = HOME_SOURCE_ICON_PALETTE[(name.hashCode() and 0x7fffffff) % HOME_SOURCE_ICON_PALETTE.size]
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1), color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 首页：顶部搜索框 + 切换网站的源（按类型/分组/订阅）+ 选中源后按分类预览网站内部小说。
 */
@Composable
fun HomeScreen(
    nav: NavController,
    repo: BookRepository,
    vm: HomeViewModel = viewModel { HomeViewModel(repo) },
) {
    val sources by vm.enabledSources.collectAsState()
    val groupMode by vm.groupMode.collectAsState()
    val selectedSourceId by vm.selectedSourceId.collectAsState()
    val categories by vm.categories.collectAsState()
    val selectedCategory by vm.selectedCategory.collectAsState()
    val allCategories by vm.allCategories.collectAsState()
    val selectedCategoryTitle by vm.selectedCategoryTitle.collectAsState()
    val books by vm.books.collectAsState()
    val exploring by vm.exploring.collectAsState()
    val message by vm.message.collectAsState()
    val filterJson by vm.filterJson.collectAsState()

    var keyword by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    // 首页滚动位置持久化：从详情/搜索返回首页时，恢复离开时的滚动位置，不回到顶部。
    var savedScrollIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    val homeListState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollIndex,
        initialFirstVisibleItemScrollOffset = savedScrollOffset,
    )
    // 滚动时快照保存，composable 被 dispose 后由 rememberSaveable 保留，重建时回放。
    LaunchedEffect(homeListState) {
        snapshotFlow { homeListState.firstVisibleItemIndex to homeListState.firstVisibleItemScrollOffset }
            .collect { (idx, off) -> savedScrollIndex = idx; savedScrollOffset = off }
    }

    // 书源下拉抽屉：970 源不能一次塞进 OverlayDropdownPreference（会把全部 item 一次性渲染→卡飞）。
    // 换成懒加载抽屉：TextField 过滤 + LazyColumn 只渲染可视项 + 边下滑放开条数。
    var showSourceSheet by rememberSaveable { mutableStateOf(false) }
    var sourceFilter by rememberSaveable { mutableStateOf("") }
    var sourceVisibleCount by rememberSaveable { mutableIntStateOf(30) }
    // 抽屉两级钻取：-1=分组列表层；>=0=只看该分组下的源。切换分组方式时重置。
    var selectedGroupIdx by rememberSaveable { mutableIntStateOf(-1) }

    // 首页顶部横条分类：0=小说（现有内容）；1=听书；2=漫画；3=短剧；4=番剧（后四项先做预留页，后续接入真实内容）
    var selectedHomeTab by rememberSaveable { mutableIntStateOf(0) }
    val homeTabs = listOf("小说", "听书", "漫画", "短剧", "番剧")

    // 项目切换（开源阅读/异次元/Mihon）：只切书源列表，书单与分类广场仍全源混着。
    var selectedOrigin by rememberSaveable { mutableStateOf(ORIGIN_LEGADO) }
    val originTabs = listOf(
        ORIGIN_LEGADO to "开源阅读",
        ORIGIN_YICIYUAN to "异次元",
        ORIGIN_MIHON to "Mihon",
    )

    // 小说 tab 三级钻取：0=书源列表；1=选中源的分类；2=分类下的书
    var sourceView by rememberSaveable { mutableIntStateOf(0) }
    var sourceListFilter by rememberSaveable { mutableStateOf("") }
    // 首页二维筛选：上排按订阅源分组、下排按分类标签；空串 = 全部
    var selectedSubscribe by rememberSaveable { mutableStateOf("") }
    var selectedCategoryFilter by rememberSaveable { mutableStateOf("") }
    // 订阅组重命名：点「重命名」按钮弹出输入框，确认后批量改该组所有源的显示名。
    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var renameInput by rememberSaveable { mutableStateOf("") }
    // 分类广场点进来的分类标题（用于列出该分类下的订阅源）
    var plazaCatTitle by rememberSaveable { mutableStateOf("") }
    // 源内分类页（view=1）的返回目标：0=书源列表，5=分类下的订阅源列表
    var bookViewBack by rememberSaveable { mutableIntStateOf(0) }
    // 漫画 tab（selectedHomeTab==2）独立钻取层级：0=漫画源列表；1=选中漫画源的分类+书。
    // 与小说 tab 共用 vm 的 categories/books/selectedSourceId，所以切 tab 时重置到列表层，避免串数据。
    var comicView by rememberSaveable { mutableIntStateOf(0) }
    var comicKeyword by rememberSaveable { mutableStateOf("") }
    // Mihon 浏览页筛选面板开关；切源/切页时清掉，避免上一个源的筛选悬空。
    var showMihonFilter by rememberSaveable { mutableStateOf(false) }
    // Mihon 浏览页「切换语言」下拉开关；同一插件下多个语言源在这里切换。
    var showLangMenu by rememberSaveable { mutableStateOf(false) }
    // 筛选面板的编辑草稿：面板打开时从 VM 描述同步，用户改完点应用再写回引擎。
    var filterDraft by rememberSaveable { mutableStateOf("[]") }
    LaunchedEffect(filterJson) { if (showMihonFilter) filterDraft = filterJson }
    LaunchedEffect(selectedSourceId) { showMihonFilter = false; showLangMenu = false }
    // 切 tab / 切项目时清层级，必须放在点击回调里，不能用 LaunchedEffect(key)。
    // 从 book_detail 返回时 HomeScreen 重新进入 composition，LaunchedEffect 会重新触发，
    // 把 sourceView/comicView 误清成 0 —— 表现就是"点书返回跳回首页顶层"。改到真正点击时才清。
    fun onHomeTabChange(idx: Int) {
        selectedHomeTab = idx
        sourceView = 0
        comicView = 0
    }
    fun onOriginChange(origin: String) {
        selectedOrigin = origin
        sourceView = 0
        comicView = 0
        plazaCatTitle = ""
        bookViewBack = 0
        // 切项目时清掉订阅源/分类筛选选中态，避免上一个项目的选择悬空。
        selectedSubscribe = ""
        selectedCategoryFilter = ""
        vm.clearBrowseState()
    }
    // 层级0只列已启用的小说源（sourceType==0 且 enabled），与书源页屏蔽联动：
    // 书源页里关掉（禁用）的源，首页不再显示。
    val browsableSourceIds by vm.browsableSourceIds.collectAsState()
    val comicBrowsableSourceIds by vm.comicBrowsableSourceIds.collectAsState()
    // 首页只展示有可浏览分类的源；没有发现分类的源屏蔽掉。
    val novelSources = remember(sources, browsableSourceIds, selectedOrigin) {
        sources.filter {
            it.sourceType == 0 && it.enabled && it.id in browsableSourceIds &&
                (it.originType.ifBlank { originTypeOf(it.subscribeUrl) }) == selectedOrigin
        }
    }
    // 漫画源：列出所有已启用、且有可浏览分类的漫画源（sourceType==2）。
    // 没有分类的漫画源不显示（用户 m31727/m31933）；不再按站名黑名单过滤——靠名字识别不准（用户 m31908 已推翻）。
    val comicSources = remember(sources, comicBrowsableSourceIds, selectedOrigin) {
        sources.filter {
            it.sourceType == 2 && it.enabled && it.id in comicBrowsableSourceIds &&
                (it.originType.ifBlank { originTypeOf(it.subscribeUrl) }) == selectedOrigin
        }
    }
    // 上排「订阅源」分组（按订阅链接唯一分组），供首页二维筛选使用。
    // 只按当前项目的源聚合，别让别的体系的订阅组混进来（搜索仍全源一起搜，不受影响）。
    val subscribeGroups = remember(sources, selectedOrigin) {
        vm.subscribeGroups(sources.filter {
            (it.originType.ifBlank { originTypeOf(it.subscribeUrl) }) == selectedOrigin
        })
    }
    // 分类联动：选中订阅后，下排分类只显示该订阅下书源所属的分类
    val visibleCategories = remember(allCategories, selectedSubscribe, subscribeGroups) {
        if (selectedSubscribe.isBlank()) {
            allCategories
        } else {
            val subIds = subscribeGroups.firstOrNull { it.key == selectedSubscribe }
                ?.sources?.map { it.id }?.toSet().orEmpty()
            allCategories.map { cat ->
                val kept = cat.sources.filter { it.source.id in subIds }
                cat.copy(sources = kept, count = kept.size)
            }.filter { it.sources.isNotEmpty() }
        }
    }
    // 下方源列表 = 小说源 ∩ 选中的订阅源分组 ∩ 选中的分类标签，再叠加关键字筛选
    val filteredNovelSources = remember(
        novelSources, sourceListFilter, selectedSubscribe, selectedCategoryFilter, subscribeGroups, visibleCategories
    ) {
        var list = novelSources
        if (selectedSubscribe.isNotBlank()) {
            val ids = subscribeGroups.firstOrNull { it.key == selectedSubscribe }?.sources?.map { it.id }?.toSet().orEmpty()
            list = list.filter { it.id in ids }
        }
        if (selectedCategoryFilter.isNotBlank()) {
            val ids = visibleCategories.firstOrNull { it.title == selectedCategoryFilter }?.sources?.map { it.source.id }?.toSet().orEmpty()
            list = list.filter { it.id in ids }
        }
        if (sourceListFilter.isNotBlank()) {
            list = list.filter {
                it.name.contains(sourceListFilter, true) || it.url.contains(sourceListFilter, true)
            }
        }
        list
    }

    // 网站通网检测结果：与书源页共用 SharedPreferences("source_net_check")，首页实时同步显示。
    val netPrefs = remember { repo.appContext.getSharedPreferences("source_net_check", android.content.Context.MODE_PRIVATE) }
    fun loadNet(): Map<Long, Int> {
        val m = mutableMapOf<Long, Int>()
        netPrefs.getStringSet("ok", emptySet())?.forEach { id -> id.toLongOrNull()?.let { m[it] = 1 } }
        netPrefs.getStringSet("bad", emptySet())?.forEach { id -> id.toLongOrNull()?.let { m[it] = 2 } }
        netPrefs.getStringSet("suspect", emptySet())?.forEach { id -> id.toLongOrNull()?.let { m[it] = 3 } }
        return m
    }
    var netMap by remember { mutableStateOf(loadNet()) }
    DisposableEffect(netPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> netMap = loadNet() }
        netPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { netPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val netOk = netMap.values.count { it == 1 }
    val netSuspect = netMap.values.count { it == 3 }
    val netTested = netMap.values.count { it == 1 || it == 2 || it == 3 }

    // 首页书源列表排序：小说 tab 与漫画 tab 都按「网络分层 + 健康度降序」排列。
    val autoSortEnabled by vm.autoSortEnabled.collectAsState()
    val sortedNovelSources = remember(filteredNovelSources, netMap, autoSortEnabled) {
        vm.sortedSources(filteredNovelSources, netMap)
    }
    val sortedComicSources = remember(comicSources, netMap, autoSortEnabled) {
        vm.sortedSources(comicSources, netMap)
    }
    // Mihon 插件聚合列表：一个 packageName 一张卡，列表层与浏览页"切换语言"下拉共用。
    val mihonPluginGroups = remember(sortedComicSources) { vm.mihonPluginGroups(sortedComicSources) }

    val groups = remember(sources, groupMode) { vm.grouped(sources, groupMode) }
    val selectedSource = remember(sources, selectedSourceId) { sources.firstOrNull { it.id == selectedSourceId } }
    // 扁平化全部分组：仅用于「搜索模式」跨全部源过滤。
    val flatSources = remember(groups) { groups.flatMap { (g, list) -> list.map { g to it } } }
    val filteredSources = remember(flatSources, sourceFilter) {
        if (sourceFilter.isBlank()) flatSources
        else flatSources.filter { (g, s) -> s.name.contains(sourceFilter, true) || g.contains(sourceFilter, true) }
    }
    // 当前选中的分组下的源（两级钻取第二层）。
    val groupSources = remember(groups, selectedGroupIdx) {
        groups.getOrNull(selectedGroupIdx)?.second ?: emptyList()
    }

    // 系统返回键/手势：首页内部钻取层级逐级回退，只有最顶层才交还 NavController（正常退出 APP）。
    // 小说 tab 走 sourceView，漫画 tab（selectedHomeTab==2）走 comicView，按当前 tab 分流。
    // 书源抽屉和重命名弹窗交给它们各自的 onDismissRequest 处理，避免重复消费系统返回。
    androidx.activity.compose.BackHandler(
        enabled = (sourceView != 0 || comicView != 0) && !showSourceSheet && renameTarget == null
    ) {
        if (selectedHomeTab == 2) {
            // 漫画 tab：只有「漫画源列表(0) / 源内分类+书(1)」两层，钻取页返回回列表层。
            comicView = 0
        } else {
            when (sourceView) {
                1 -> sourceView = bookViewBack      // 选中源的分类/书页 → 回书源列表(0)或分类广场订阅源列表(5)
                2 -> sourceView = 1                  // 分类下的书 → 回源内分类
                3 -> sourceView = 0                  // 分类广场选书结果 → 回书源列表
                5 -> sourceView = 0                  // 分类下订阅源列表 → 回书源列表
                else -> sourceView = 0
            }
        }
    }

    Scaffold { _ ->
        Column(Modifier.fillMaxSize()) {
            // 顶部横条分类 Tab：小说/听书/漫画/短剧/番剧，选中态高亮 + 下划线指示。
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                homeTabs.forEachIndexed { idx, tab ->
                    val selected = idx == selectedHomeTab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onHomeTabChange(idx) }
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        Text(
                            tab,
                            fontSize = MiuixTheme.textStyles.title4.fontSize,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier.size(width = 20.dp, height = 3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (selected) MiuixTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                        )
                    }
                }
            }

            if (selectedHomeTab == 0) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().weight(1f),
            state = homeListState,
            // 内容铺满到屏幕底，只为系统手势条留安全距离；tab 是悬浮层不占内容空间。
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item { LargeTitle(title = "首页") }

            // 项目切换：紧跟大标题、在搜索框上方，只切书源列表。
            item {
                OriginSwitchRow(selectedOrigin, originTabs) { onOriginChange(it) }
            }

            // 顶部搜索框
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = "搜索书名 / 作者",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (keyword.isNotBlank()) {
                                nav.navigate("search?key=${android.net.Uri.encode(keyword)}&sourceType=0")
                                keyboard?.hide()
                            }
                        }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (keyword.isNotBlank()) {
                            nav.navigate("search?key=${android.net.Uri.encode(keyword)}&sourceType=0")
                            keyboard?.hide()
                        }
                    }) { Text("搜索") }
                }
            }

            // 三级钻取：0=小说书源列表；1=选中源的分类；2=分类下的书
            when (sourceView) {
                0 -> {
                    // 上排：全量订阅源分组；下排：全量分类标签；选中后下方源列表实时取交集
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "订阅源",
                                fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            if (selectedSubscribe.isNotBlank()) {
                                Text(
                                    "重命名",
                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        renameTarget = selectedSubscribe
                                        renameInput = subscribeGroups.firstOrNull { it.key == selectedSubscribe }?.displayName ?: ""
                                    }.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            subscribeGroups.forEach { grp ->
                                val sel = selectedSubscribe == grp.key
                                Card(
                                    modifier = Modifier.padding(end = 8.dp),
                                    pressFeedbackType = PressFeedbackType.Sink,
                                    onClick = {
                                        selectedSubscribe = if (sel) "" else grp.key
                                        selectedCategoryFilter = ""
                                    },
                                ) {
                                    Text(
                                        (if (sel) "✓" else "") + grp.displayName,
                                        fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "分类",
                            fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            visibleCategories.forEach { cat ->
                                val sel = selectedCategoryFilter == cat.title
                                Card(
                                    modifier = Modifier.padding(end = 8.dp),
                                    pressFeedbackType = PressFeedbackType.Sink,
                                    onClick = { selectedCategoryFilter = if (sel) "" else cat.title },
                                ) {
                                    Text(
                                        (if (sel) "✓" else "") + cat.title + "（${cat.count}）",
                                        fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "小说书源（${filteredNovelSources.size}）",
                            fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    item {
                        TextField(
                            value = sourceListFilter,
                            onValueChange = { sourceListFilter = it },
                            label = "筛选书源名称 / 地址",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    items(sortedNovelSources, key = { it.id }) { src ->
                        val net = netMap[src.id]
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            pressFeedbackType = PressFeedbackType.Sink,
                            onClick = { bookViewBack = 0; vm.selectSource(src); sourceView = 1 },
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                HomeSourceIcon(src.name, homeSourcePkg(src))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(src.name, fontSize = MiuixTheme.textStyles.title4.fontSize,
                                            maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                        if (homeSourceNsfw(src)) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("18+", fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                                color = androidx.compose.ui.graphics.Color(0xFFC62828), maxLines = 1, softWrap = false)
                                        }
                                    }
                                    // 首页不显示订阅链接，改显示订阅名（老数据为空则回退书源分组标签）。
                                    Text(src.subscribeName.ifBlank { src.groupName.ifBlank { "手动添加" } },
                                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
                                }
                                if (net != null) {
                                    Text(if (net == 1) "✓通" else if (net == 3) "存疑" else "✗不通",
                                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                        color = when (net) {
                                            1 -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                            3 -> androidx.compose.ui.graphics.Color(0xFFFFA726)
                                            else -> androidx.compose.ui.graphics.Color(0xFFC62828)
                                        })
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // 点进书源后同一页：顶部选分类、下方直接浏览书（点分类只切书、不跳页）
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text("← 返回书源列表", fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.clickable { sourceView = bookViewBack })
                        }
                        Text(
                            "${selectedSource?.name ?: "书源"} · 书籍（${books.size}）",
                            fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    if (categories.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                categories.forEach { cat ->
                                    val sel = selectedCategory?.url == cat.url
                                    Card(
                                        modifier = Modifier.padding(end = 8.dp),
                                        pressFeedbackType = PressFeedbackType.Sink,
                                        onClick = { vm.selectCategory(cat) },
                                    ) {
                                        Text(
                                            (if (sel) "✓" else "") + cat.title,
                                            fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                                            color = if (sel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (exploring) {
                        item {
                            Text("正在加载…", fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    if (message.isNotBlank()) {
                        item {
                            Text(message, fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    if (categories.isEmpty() && !exploring) {
                        item {
                            Text("该书源没有可发现的分类，可试试顶部搜索框搜书名",
                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    items(books, key = { it.book.bookUrl.ifBlank { it.book.name + "|" + it.book.author } }) { sb ->
                        BookCard(sb.book, sb.source, 0) { clicked ->
                            SharedBookDetail.post(clicked)
                            nav.navigate("book_detail")
                        }
                    }
                }
                2 -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text("← 返回分类", fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.clickable { sourceView = 1 })
                        }
                        Text(
                            "${selectedCategory?.title ?: "书籍"}（${books.size}）",
                            fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    if (exploring) {
                        item {
                            Text("正在加载…", fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    if (message.isNotBlank()) {
                        item {
                            Text(message, fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    items(books, key = { it.book.bookUrl.ifBlank { it.book.name + "|" + it.book.author } }) { sb ->
                        BookCard(sb.book, sb.source, 0) { clicked ->
                            SharedBookDetail.post(clicked)
                            nav.navigate("book_detail")
                        }
                    }
                }
                3 -> {
                    // 分类广场选书结果（跨源按分类标签聚合）
                    item {
                        Text(
                            "← 返回书源列表",
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                .clickable { sourceView = 0 },
                        )
                        Text(
                            "分类广场 · ${selectedCategoryTitle}（${books.size}）",
                            fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    if (exploring) {
                        item {
                            Text("正在加载…", fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    if (message.isNotBlank()) {
                        item {
                            Text(message, fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    items(books, key = { it.book.bookUrl.ifBlank { it.book.name + "|" + it.book.author } }) { sb ->
                        BookCard(sb.book, sb.source, 0) { clicked ->
                            SharedBookDetail.post(clicked)
                            nav.navigate("book_detail")
                        }
                    }
                }
                5 -> {
                    // 分类广场点分类 → 列出该分类下的订阅源
                    val cat = allCategories.firstOrNull { it.title == plazaCatTitle }
                    val catSources = cat?.sources?.map { it.source } ?: emptyList()
                    item {
                        Text(
                            "← 返回分类广场",
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                .clickable { sourceView = 0 },
                        )
                        Text(
                            "分类广场 · ${plazaCatTitle} · 订阅源（${catSources.size}）",
                            fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    items(catSources, key = { it.id }) { src ->
                        val net = netMap[src.id]
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            pressFeedbackType = PressFeedbackType.Sink,
                            onClick = { bookViewBack = 5; vm.selectSource(src); sourceView = 1 },
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(src.name, fontSize = MiuixTheme.textStyles.title4.fontSize, maxLines = 1)
                                    // 首页不显示订阅链接，改显示订阅名（老数据为空则回退书源分组标签）。
                                    Text(src.subscribeName.ifBlank { src.groupName.ifBlank { "手动添加" } },
                                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
                                }
                                if (net != null) {
                                    Text(if (net == 1) "✓通" else if (net == 3) "存疑" else "✗不通",
                                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                        color = when (net) {
                                            1 -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                            3 -> androidx.compose.ui.graphics.Color(0xFFFFA726)
                                            else -> androidx.compose.ui.graphics.Color(0xFFC62828)
                                        })
                                }
                            }
                        }
                    }
                }
            }

            // 快捷入口（书源已移到设置，这里保留本地书架入口）
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    pressFeedbackType = PressFeedbackType.Sink,
                    onClick = { nav.navigate("shelf") },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        Icon(Icons.Filled.LocalLibrary, contentDescription = null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("本地漫画书架", fontSize = MiuixTheme.textStyles.title4.fontSize)
                            Text("阅读已加入书架的本地漫画", fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        } // end LazyColumn (小说)

            // 漫画 tab：列已启用的漫画源进二级页选漫画；搜索框只走漫画源。
            } else if (selectedHomeTab == 2) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    state = homeListState,
                    // 内容铺满到屏幕底，只为系统手势条留安全距离；tab 是悬浮层不占内容空间。
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    item { LargeTitle(title = "漫画") }
                    // 项目切换：紧跟大标题、在搜索框上方，只切漫画源列表。
                    item {
                        OriginSwitchRow(selectedOrigin, originTabs) { onOriginChange(it) }
                    }
                    // 漫画搜索框：navigate 带 sourceType=2，搜索页只走漫画源
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextField(
                                value = comicKeyword,
                                onValueChange = { comicKeyword = it },
                                label = "搜索漫画名 / 作者",
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    if (comicKeyword.isNotBlank()) {
                                        nav.navigate("search?key=${android.net.Uri.encode(comicKeyword)}&sourceType=2")
                                        keyboard?.hide()
                                    }
                                }),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                if (comicKeyword.isNotBlank()) {
                                    nav.navigate("search?key=${android.net.Uri.encode(comicKeyword)}&sourceType=2")
                                    keyboard?.hide()
                                }
                            }) { Text("搜索") }
                        }
                    }
                    when (comicView) {
                        0 -> {
                            item {
                                Text(
                                    if (selectedOrigin == ORIGIN_MIHON) "Mihon 插件（${vm.mihonPluginCount(comicSources)} 个）"
                                    else "漫画书源（${comicSources.size}）",
                                    fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            }
                            if (selectedOrigin == ORIGIN_MIHON) {
                                // Mihon：一个插件一张卡（同 packageName 的多语言源合并），点进直接用首个源浏览，语言在浏览页下拉切。
                                items(mihonPluginGroups, key = { it.packageName }) { plugin ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                        pressFeedbackType = PressFeedbackType.Sink,
                                        onClick = { vm.selectSource(plugin.sources.first()); comicView = 1 },
                                    ) {
                                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            HomeSourceIcon(plugin.displayName, plugin.packageName)
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(plugin.displayName, fontSize = MiuixTheme.textStyles.title4.fontSize,
                                                        maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                                    if (plugin.sources.any { homeSourceNsfw(it) }) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Text("18+", fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                                            color = androidx.compose.ui.graphics.Color(0xFFC62828), maxLines = 1, softWrap = false)
                                                    }
                                                }
                                                Text("${plugin.sources.size} 个语言源",
                                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            } else {
                                items(sortedComicSources, key = { it.id }) { src ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                        pressFeedbackType = PressFeedbackType.Sink,
                                        onClick = { vm.selectSource(src); comicView = 1 },
                                    ) {
                                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            HomeSourceIcon(src.name, homeSourcePkg(src))
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(src.name, fontSize = MiuixTheme.textStyles.title4.fontSize,
                                                        maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                                    if (homeSourceNsfw(src)) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Text("18+", fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                                            color = androidx.compose.ui.graphics.Color(0xFFC62828), maxLines = 1, softWrap = false)
                                                    }
                                                }
                                                Text(src.subscribeName.ifBlank { src.groupName.ifBlank { "手动添加" } },
                                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                            if (comicSources.isEmpty()) {
                                item {
                                    Text("书源页筛「漫画」分类并启用漫画源后，这里就会列出",
                                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                }
                            }
                        }
                        1 -> {
                            val isMihon = selectedSource?.let { vm.isMihonSource(it) } == true
                            item {
                                Text("← 返回漫画源列表", fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { comicView = 0 })
                                Text("${selectedSource?.name ?: "漫画源"} · 漫画（${books.size}）",
                                    fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                            }
                            if (isMihon) {
                                // 「切换语言」下拉：同一插件下多个语言源在这里切，选一个=换源重新加载浏览页。
                                item {
                                    val plugin = remember(selectedSource, comicSources) {
                                        vm.mihonPluginOf(selectedSource, comicSources)
                                    }
                                    val curLabel = selectedSource?.let { src -> vm.mihonLangResolved(src).ifBlank { src.name } }
                                        ?: "切换语言"
                                    Button(
                                        onClick = { showLangMenu = !showLangMenu },
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                                        colors = ButtonDefaults.buttonColors(),
                                    ) { Text("语言：$curLabel  ▾") }
                                    if (showLangMenu && plugin != null) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                                        ) {
                                            Column(Modifier.padding(4.dp)) {
                                                plugin.sources.forEach { src ->
                                                    val label = vm.mihonLangResolved(src).ifBlank { src.name }
                                                    val sel = src.id == selectedSourceId
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth()
                                                            .clickable {
                                                                showLangMenu = false
                                                                if (!sel) vm.selectSource(src)
                                                            }
                                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Text(
                                                            (if (sel) "✓ " else "") + label,
                                                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                                                            color = if (sel) MiuixTheme.colorScheme.primary
                                                                    else MiuixTheme.colorScheme.onSurface,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                // Mihon 浏览页：热门/最近更新/筛选三标签（用插件现成数据，不造假），两列封面网格。
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(horizontal = 12.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        categories.forEach { cat ->
                                            val sel = selectedCategory?.url == cat.url
                                            Button(
                                                onClick = {
                                                    if (cat.url == "filter") {
                                                        vm.loadMihonFilters()
                                                        showMihonFilter = true
                                                    } else vm.selectCategory(cat)
                                                },
                                                modifier = Modifier.padding(end = 8.dp),
                                                colors = if (sel) ButtonDefaults.buttonColorsPrimary()
                                                        else ButtonDefaults.buttonColors(),
                                            ) { Text(cat.title) }
                                        }
                                    }
                                }
                            } else if (categories.isNotEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(horizontal = 12.dp, vertical = 4.dp),
                                    ) {
                                        categories.forEach { cat ->
                                            val sel = selectedCategory?.url == cat.url
                                            Card(
                                                modifier = Modifier.padding(end = 8.dp),
                                                pressFeedbackType = PressFeedbackType.Sink,
                                                onClick = { vm.selectCategory(cat) },
                                            ) {
                                                Text((if (sel) "✓" else "") + cat.title,
                                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                                            }
                                        }
                                    }
                                }
                            }
                            if (exploring) {
                                item {
                                    Text("正在加载…", fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                }
                            }
                            if (message.isNotBlank()) {
                                item {
                                    Text(message, fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                }
                            }
                            if (categories.isEmpty() && !exploring && !isMihon) {
                                item {
                                    Text("该漫画源没有可发现的分类，可试试顶部搜索框",
                                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                }
                            }
                            if (isMihon) {
                                // 两列封面网格：每 2 本书一行。
                                items(books.chunked(2), key = { row -> row.first().book.bookUrl }) { row ->
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                                        row.forEach { sb ->
                                            Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                                                GridBookCard(sb) { clicked ->
                                                    SharedBookDetail.post(clicked)
                                                    nav.navigate("book_detail")
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                items(books, key = { it.book.bookUrl.ifBlank { it.book.name + "|" + it.book.author } }) { sb ->
                                    BookCard(sb.book, sb.source, 0) { clicked ->
                                        SharedBookDetail.post(clicked)
                                        nav.navigate("book_detail")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // 其它分类先做预留占位页，后续接入真实内容（听书/短剧/番剧）。
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val icons = listOf(
                            Icons.Filled.Headphones,
                            Icons.Filled.Star,
                            Icons.Filled.PlayArrow,
                            Icons.Filled.Movie,
                        )
                        val idx = selectedHomeTab - 1
                        val hints = listOf("听书", "漫画", "短剧", "番剧")
                        if (idx in icons.indices) {
                            Icon(icons[idx], contentDescription = null, modifier = Modifier.size(56.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("${hints.getOrNull(idx) ?: "该分类"} · 即将上线",
                            fontSize = MiuixTheme.textStyles.title4.fontSize, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("页面预留中，后续可切换真实内容",
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        } // end Column

        // 书源抽屉：两级钻取（先分组列表→点进某组看该组源）+ 搜索模式跨全部源，
        // 全部用 LazyColumn 只渲染可视项 + 边下滑放开条数，2000+ 源也不卡。
        OverlayBottomSheet(
            show = showSourceSheet,
            title = when {
                sourceFilter.isNotBlank() -> "搜索结果（${filteredSources.size}）"
                groups.getOrNull(selectedGroupIdx) != null -> "${groups[selectedGroupIdx].first}（${groupSources.size}）"
                else -> "切换网站书源 · ${groups.size} 组"
            },
            onDismissRequest = { showSourceSheet = false },
        ) {
            TextField(
                value = sourceFilter,
                onValueChange = { sourceFilter = it; sourceVisibleCount = 30 },
                label = "搜索书源名称 / 分组（跨全部分组）",
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            // 非搜索、非钻取时显示「全部源」入口（搜索时忽略分组层级）。
            if (sourceFilter.isBlank() && groups.getOrNull(selectedGroupIdx) != null) {
                Text(
                    "← 返回分组列表",
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { selectedGroupIdx = -1; sourceVisibleCount = 30 }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            // 三种视图的数据：搜索→filteredSources (g,s)；钻取→该组源 (组名,s)；分组列表→groups。
            val searching = sourceFilter.isNotBlank()
            val inGroup = !searching && groups.getOrNull(selectedGroupIdx) != null
            // 按层级/搜索态重建 listState，返回上一级时归零滚动位置，避免旧大 index 越界闪退
            val listState = remember(selectedGroupIdx, searching) { LazyListState() }
            // 源列表视图（搜索 / 钻取）的可分页数据源。
            val listTotal = if (searching) filteredSources.size else if (inGroup) groupSources.size else 0
            val shownCount = listTotal.coerceAtMost(sourceVisibleCount)
            val shouldLoadMore by remember(selectedGroupIdx, searching, listTotal) {
                derivedStateOf {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= listState.layoutInfo.totalItemsCount - 3 && sourceVisibleCount < listTotal
                }
            }
            LaunchedEffect(shouldLoadMore) {
                if (shouldLoadMore) sourceVisibleCount = (sourceVisibleCount + 30).coerceAtMost(listTotal)
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                state = listState,
            ) {
                if (!searching && !inGroup) {
                    // 第一层：分组列表（按当前切换方式 groupBy 的结果）。
                    itemsIndexed(groups) { idx, (g, list) ->
                        val selected = idx == selectedGroupIdx
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { selectedGroupIdx = idx; sourceVisibleCount = 30 }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    g,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                            Text(
                                "${list.size} 个 ›",
                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (groups.isEmpty()) {
                        item {
                            Text(
                                "没有启用的书源",
                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                } else {
                    // 第二层 / 搜索：源列表。每项取 (显示分组名, 源)。
                    val itemsToShow = if (searching) {
                        filteredSources.take(shownCount)
                    } else {
                        val gName = groups.getOrNull(selectedGroupIdx)?.first ?: ""
                        groupSources.take(shownCount).map { gName to it }
                    }
                    itemsIndexed(itemsToShow) { _, (g, s) ->
                        val selected = s.id == selectedSourceId
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable {
                                    vm.selectSource(s)
                                    showSourceSheet = false
                                }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.name,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MiuixTheme.colorScheme.onBackground,
                                )
                                Text(
                                    g,
                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            when (netMap[s.id]) {
                                1 -> Text(
                                    "通网✓",
                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                    color = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                                )
                                3 -> Text(
                                    "存疑",
                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                    color = androidx.compose.ui.graphics.Color(0xFFFFA726),
                                )
                                2 -> Text(
                                    "不通✗",
                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                    color = androidx.compose.ui.graphics.Color(0xFFC62828),
                                )
                            }
                        }
                    }
                    if (itemsToShow.isEmpty()) {
                        item {
                            Text(
                                "没有匹配的书源",
                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    } else if (sourceVisibleCount < listTotal) {
                        item {
                            Text(
                                "继续下滑加载更多（已显示 ${itemsToShow.size} / ${listTotal}）",
                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // 订阅组重命名弹窗：选中某个订阅源后点「重命名」改显示名。
    if (renameTarget != null) {
        OverlayDialog(
            show = true,
            title = "重命名订阅组",
            summary = "该订阅下所有书源的显示名会一起改成新名字。",
            onDismissRequest = { renameTarget = null },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                TextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = "订阅名称",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                DialogActions(
                    cancelText = "取消",
                    confirmText = "确定",
                    confirmEnabled = renameInput.isNotBlank(),
                    onCancel = { renameTarget = null },
                    onConfirm = {
                        vm.renameSubscribe(renameTarget ?: "", renameInput)
                        // 分组 key 是 subscribeUrl，改名不改变 key，选中项保持不变
                        renameTarget = null
                    },
                )
            }
        }
    }

    // Mihon 筛选面板：解析引擎给的 JSON 描述，按类型渲染控件；应用时把整个数组原样回传。
    if (showMihonFilter) {
        MihonFilterSheet(
            filtersJson = filterDraft,
            onDraftChange = { filterDraft = it },
            onDismiss = { showMihonFilter = false },
            onApply = {
                vm.applyMihonFilters(filterDraft)
                showMihonFilter = false
            },
        )
    }
}

/**
 * Mihon 筛选面板：解析 [filtersJson]（引擎给的纯 JSON 数组串），按 type 渲染控件。
 * 用户改动后整份数组回传 [onDraftChange]，点应用时由上层原样写回引擎。
 */
@Composable
private fun MihonFilterSheet(
    filtersJson: String,
    onDraftChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    // 解析成可变对象的简单模型：直接操作 JSONArray，改完 toString 回传，保证 key/字段原样。
    val arr = remember(filtersJson) { runCatching { org.json.JSONArray(filtersJson) }.getOrElse { org.json.JSONArray() } }
    fun commit(updated: org.json.JSONArray) = onDraftChange(updated.toString())
    fun setField(index: Int, field: String, value: Any) {
        val copy = org.json.JSONArray(arr.toString())
        copy.optJSONObject(index)?.put(field, value)
        commit(copy)
    }
    OverlayBottomSheet(
        show = true,
        title = "筛选",
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (arr.length() == 0) {
                Text("该源没有筛选项", fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp))
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = o.optString("type")
                val name = o.optString("name")
                when (type) {
                    "header" -> Text(name, fontSize = MiuixTheme.textStyles.body2.fontSize,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    "separator" -> androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    "checkbox" -> Row(
                        Modifier.fillMaxWidth().clickable {
                            setField(i, "state", !o.optBoolean("state"))
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(state = if (o.optBoolean("state")) androidx.compose.ui.state.ToggleableState.On
                                 else androidx.compose.ui.state.ToggleableState.Off, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(name, fontSize = MiuixTheme.textStyles.body2.fontSize)
                    }
                    "tristate" -> {
                        val labels = listOf("否", "是", "仅")
                        val st = o.optInt("state", 0).coerceIn(0, 2)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, fontSize = MiuixTheme.textStyles.body2.fontSize, modifier = Modifier.weight(1f))
                            Button(onClick = { setField(i, "state", (st + 1) % 3) }) { Text(labels[st]) }
                        }
                    }
                    "select" -> {
                        val values = o.optJSONArray("values")
                        val count = values?.length() ?: 0
                        val st = o.optInt("state", 0)
                        val text = if (count > 0 && st in 0 until count) values!!.optString(st) else name
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, fontSize = MiuixTheme.textStyles.body2.fontSize, modifier = Modifier.weight(1f))
                            Button(onClick = { if (count > 0) setField(i, "state", (st + 1) % count) }) { Text(text) }
                        }
                    }
                    "sort" -> {
                        val values = o.optJSONArray("values")
                        val count = values?.length() ?: 0
                        val st = o.optInt("state", -1)
                        val asc = o.optBoolean("ascending", true)
                        val text = if (count > 0 && st in 0 until count) values!!.optString(st) else "默认"
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, fontSize = MiuixTheme.textStyles.body2.fontSize, modifier = Modifier.weight(1f))
                            Button(onClick = { if (count > 0) setField(i, "state", if (st < 0) 0 else (st + 1) % count) }) { Text(text) }
                            Spacer(Modifier.width(6.dp))
                            Button(onClick = { setField(i, "ascending", !asc) }) { Text(if (asc) "↑" else "↓") }
                        }
                    }
                    "text" -> TextField(
                        value = o.optString("state"),
                        onValueChange = { setField(i, "state", it) },
                        label = name,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f).padding(end = 4.dp)) { Text("取消") }
                Button(onClick = onApply, modifier = Modifier.weight(1f).padding(start = 4.dp),
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text("应用筛选") }
            }
        }
    }
}

/** Mihon 浏览页两列网格卡片：封面在上、书名在下。 */
@Composable
private fun GridBookCard(sb: SourcedBook, onClick: (SourcedBook) -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { onClick(sb) },
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Column {
            val coverMod = Modifier.fillMaxWidth().height(150.dp)
                .background(MiuixTheme.colorScheme.surfaceVariant)
            if (sb.book.coverUrl.isBlank()) {
                Box(coverMod, contentAlignment = Alignment.Center) {
                    Text("暂无封面", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            } else {
                var failed by remember(sb.book.coverUrl) { mutableStateOf(false) }
                Box(coverMod, contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = sb.book.coverUrl,
                        contentDescription = sb.book.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onError = { failed = true },
                    )
                    if (failed) Text("封面加载失败", fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Text(sb.book.name, fontSize = MiuixTheme.textStyles.footnote1.fontSize, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
        }
    }
}

/** 项目切换：开源阅读/异次元/Mihon 三个小按钮，轻量一行。 */
@Composable
private fun OriginSwitchRow(
    selected: String,
    tabs: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { (origin, label) ->
            val isSelected = origin == selected
            Button(
                onClick = { onSelect(origin) },
                modifier = Modifier.weight(1f).padding(horizontal = 3.dp),
                colors = if (isSelected) ButtonDefaults.buttonColorsPrimary()
                        else ButtonDefaults.buttonColors(),
            ) { Text(label) }
        }
    }
}

@Composable
private fun BookCard(b: SearchBook, source: BookSourceEntity?, categoryIndex: Int = 0, onClick: (SourcedBook) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            val coverClick = Modifier.clickable {
                source?.let { onClick(SourcedBook(b, it, categoryIndex = categoryIndex)) }
            }
            val coverMod = Modifier
                .width(64.dp)
                .height(88.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant)
            if (b.coverUrl.isBlank()) {
                // 书源没给封面地址：明确显示"暂无封面"，区别于加载失败。
                Box(coverMod.then(coverClick), contentAlignment = Alignment.Center) {
                    Text("暂无封面", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center)
                }
            } else {
                var failed by remember(b.coverUrl) { mutableStateOf(false) }
                var loaded by remember(b.coverUrl) { mutableStateOf(false) }
                Box(coverMod.then(coverClick), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = b.coverUrl,
                        contentDescription = b.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onSuccess = { loaded = true; failed = false },
                        onError = { failed = true; loaded = false },
                    )
                    if (failed) {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            Text("加载失败", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                textAlign = TextAlign.Center)
                        }
                    } else if (!loaded) {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surfaceVariant))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable { source?.let { onClick(SourcedBook(b, it, categoryIndex = categoryIndex)) } }) {
                Text(b.name, fontSize = MiuixTheme.textStyles.title4.fontSize, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(b.author.ifBlank { "未知作者" }, fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (b.intro.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(b.intro, fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
