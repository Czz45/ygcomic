package com.yg.comic.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yg.comic.data.BookRepository
import com.yg.comic.data.BookSourceEntity
import com.yg.comic.ui.components.DialogActions
import com.yg.comic.ui.components.LargeTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 书源管理 + 源内搜索：
 * - 顶部搜索框：输入书名/关键词，在全部启用书源里搜索，结果可「加入书架」。
 * - 「书源」分组：列出已安装书源，可删除、开关；「添加书源」粘贴 legado 书源 JSON。
 */
@Composable
fun BookSourceScreen(
    repo: BookRepository,
    onBack: () -> Unit,
    onOpenShelf: () -> Unit,
    vm: BookSourceViewModel = viewModel { BookSourceViewModel(repo) },
) {
    val sources by vm.sources.collectAsState()
    // 三个项目各自独立的源列表（地基派生流），搜索/详情仍走 vm.sources 全源，不受切换影响。
    val legadoSources by vm.legadoSources.collectAsState()
    val yiciyuanSources by vm.yiciyuanSources.collectAsState()
    val mihonSources by vm.mihonSources.collectAsState()
    val results by vm.results.collectAsState()
    val addedKeys by vm.addedKeys.collectAsState()
    val searching by vm.searching.collectAsState()
    val message by vm.message.collectAsState()
    val netCheck by vm.netCheck.collectAsState()
    val checking by vm.checking.collectAsState()
    val progress by vm.progress.collectAsState()
    val mihonStore by vm.mihonStore.collectAsState()
    val mihonBusy by vm.mihonBusy.collectAsState()
    val mihonLoadedPackages by vm.mihonLoadedPackages.collectAsState()
    val customRepoUrl by vm.customRepoUrl.collectAsState()
    val customRepoItems by vm.customRepoItems.collectAsState()
    val customRepoLoading by vm.customRepoLoading.collectAsState()
    // 双保险：只统计当前仍存在的书源的检测状态，避免 netMap 残留已删/已重导的旧 id 让计数只增不减。
    val liveIds = remember(sources) { sources.map { it.id }.toSet() }
    val badCount = netCheck.count { (id, st) -> id in liveIds && st == 2 }

    var keyword by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var showMihon by remember { mutableStateOf(false) }
    // 自定义仓库：勾选待导入的包名 + 语言筛选（""=全部）
    var repoChecked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var repoLang by remember { mutableStateOf("") }
    var addText by remember { mutableStateOf("") }
    var urlText by remember { mutableStateOf("") }
    // 导入时用户自定义书源名称（可选）；留空则用书源自带的 name 字段。
    var nameInput by remember { mutableStateOf("") }
    // 批量删除：当前勾选的书源 id 集合
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // 分组筛选：照 legado 优先按作者的分组标签(bookSourceGroup)查看；空串=全部。
    var selectedGroup by remember { mutableStateOf("") }
    // 通网筛选：0=全部，1=仅通网，2=仅不通，3=未检测
    var netFilter by remember { mutableStateOf(0) }
    // 顶部「添加书源」二级菜单展开态；多选模式；检测面板折叠态。
    var addMenuExpanded by remember { mutableStateOf(false) }
    // 添加弹窗聚焦的方式：0=网络导入 2=粘贴 JSON（本地文件直接走系统选择器）
    var addMode by remember { mutableIntStateOf(0) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var checkPanelExpanded by remember { mutableStateOf(false) }
    // Mihon 图源页：聚合卡展开态（按 packageName，一个 APK 一张卡）。
    var mihonExpandedPkgs by remember { mutableStateOf(setOf<String>()) }

    // ===== 项目切换：开源阅读 / 异次元 / Mihon，各自独立互不混 =====
    // 0=开源阅读(legado) 1=异次元(yiciyuan) 2=Mihon(扩展)
    val projectTabs = listOf("开源阅读", "异次元", "Mihon")
    var selectedProject by remember { mutableIntStateOf(0) }
    // 当前项目对应的源列表：所有列表/分组/统计/勾选都基于它，切项目即换数据源。
    val projectSources = when (selectedProject) {
        1 -> yiciyuanSources
        2 -> mihonSources
        else -> legadoSources
    }
    // 切项目时把会串味的状态一次性清掉：批量勾选、搜索词、分组筛选、通网筛选、
    // 搜索结果、自定义仓库勾选/语言。绝不能把 A 项目勾的源带到 B 项目去删。
    fun switchProject(next: Int) {
        if (next == selectedProject) return
        selectedProject = next
        selectedIds = emptySet()
        keyword = ""
        selectedGroup = ""
        netFilter = 0
        vm.clearSearch()
        repoChecked = emptySet()
        repoLang = ""
        addMenuExpanded = false
        multiSelectMode = false
        checkPanelExpanded = false
        mihonExpandedPkgs = emptySet()
    }
    val liveProjectIds = remember(projectSources) { projectSources.map { it.id }.toSet() }

    // 从当前项目已安装书源里提取所有分组，并按组名排序；组内也按组名排序，让同一分类聚在一起。
    // groupName 空视为「未分组」。本合集 bookSourceGroup 多为单标签，整串即分组键。
    val allGroups = remember(projectSources) {
        projectSources.map { it.groupName.ifBlank { "未分组" } }.distinct().sorted()
    }
    val visibleSources = remember(projectSources, selectedGroup, netFilter, netCheck) {
        val filtered = if (selectedGroup.isEmpty()) {
            projectSources
        } else {
            projectSources.filter { it.groupName.ifBlank { "未分组" } == selectedGroup }
        }
        val netFiltered = when (netFilter) {
            1 -> filtered.filter { netCheck[it.id] == 1 }
            2 -> filtered.filter { netCheck[it.id] == 2 }
            3 -> filtered.filter { !netCheck.containsKey(it.id) }
            4 -> filtered.filter { netCheck[it.id] == 3 }
            else -> filtered
        }
        // 排序与搜索页一致：先按网站检测状态分层（通网→存疑→未检测→不通最后），
        // 层内再按权重（命中次数优先、使用次数次之）降序，让好用的源直观排在最上面。
        fun netTier(id: Long): Int = when (netCheck[id]) {
            1 -> 0       // 已测通：最优先
            3 -> 1       // 存疑：其次
            2 -> 3       // 不通：放最后
            else -> 2    // 未检测：居中
        }
        netFiltered.sortedWith(
            compareBy<BookSourceEntity> { netTier(it.id) }
                .thenByDescending { it.hitCount }
                .thenByDescending { it.useCount }
        )
    }
    val groupCount = remember(projectSources) {
        projectSources.groupingBy { it.groupName.ifBlank { "未分组" } }.eachCount()
    }
    // Mihon 计数按插件（APK / packageName 去重），不按源数。与插件卡分组 key 保持一致。
    fun pluginCount(list: List<BookSourceEntity>): Int =
        list.map { src ->
            runCatching { org.json.JSONObject(src.json).optString("packageName") }.getOrDefault("")
                .ifBlank { src.subscribeUrl.ifBlank { src.name } }
        }.distinct().size
    val visiblePluginCount = remember(visibleSources) { pluginCount(visibleSources) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 从外部共享进来的书源（开源阅读分享 / 文件打开）：进入本页即消费并导入。
    val sharedPending by SharedSource.pending.collectAsState()
    LaunchedEffect(sharedPending) {
        if (sharedPending != null) {
            val text = SharedSource.consume()
            if (!text.isNullOrBlank()) {
                vm.addSourceFromJson(text)
                showAdd = false
            }
        }
    }

    // 「选择本地文件」：用系统文件选择器打开书源 .json，读文本后导入。
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // 后台读文件内容，避免卡 UI。
            scope.launch(Dispatchers.IO) {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (!text.isNullOrBlank()) {
                        vm.addSourceFromJson(text, nameOverride = nameInput)
                        showAdd = false
                    } else {
                        vm.addSourceFromJson("")
                    }
                }
            }
        }
    }

    // 「安装 APP」：选本地 APK，复制到私有目录后调系统安装器（复用 UpdateManager.install）。
    val pickApk = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val apk = runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching null
                    val f = java.io.File(context.cacheDir, "install_app.apk")
                    f.writeBytes(bytes)
                    f
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (apk != null) {
                        runCatching { com.yg.comic.update.UpdateManager.install(context, apk) }
                            .onFailure { vm.showMessage("安装失败：${it.message ?: "未知错误"}") }
                    } else {
                        vm.showMessage("读取 APK 失败")
                    }
                }
            }
        }
    }

    LaunchedEffect(message) {
        if (message.isNotBlank()) { kotlinx.coroutines.delay(2500); vm.clearMessage() }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold { _ ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                    LargeTitle(title = "书源")
                    Spacer(Modifier.weight(1f))
                    Text("去书架", color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenShelf() }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }

            // 三项目切换：跟首页同款横条 Tab（选中高亮 + 下划线），切页统一清状态做隔离。
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    projectTabs.forEachIndexed { idx, tab ->
                        val selected = idx == selectedProject
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { switchProject(idx) }
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
                                    .background(if (selected) MiuixTheme.colorScheme.primary else Color.Transparent)
                            )
                        }
                    }
                }
            }

            // ===== 统计文字 + 四段进度条：常显，不随检测面板折叠 =====
            // 基于当前项目的源，切项目统计跟着换；netCheck 仍是全局按 id 记录。
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                    val enabledTotal = projectSources.count { it.enabled }
                    val okCount = netCheck.count { (id, st) -> id in liveProjectIds && st == 1 }
                    val suspectCount = netCheck.count { (id, st) -> id in liveProjectIds && st == 3 }
                    val projectBadCount = netCheck.count { (id, st) -> id in liveProjectIds && st == 2 }
                    val pendingCount = (enabledTotal - projectBadCount - okCount - suspectCount).coerceAtLeast(0)
                    Text(
                        "可用 $okCount　存疑 $suspectCount　不可用 $projectBadCount　未检测 $pendingCount" +
                            (if (checking) "（已检测 ${progress.first}/${progress.second}）" else ""),
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                        if (okCount > 0) Box(Modifier.fillMaxHeight().weight(okCount.toFloat()).background(Color(0xFF66BB6A)))
                        if (suspectCount > 0) Box(Modifier.fillMaxHeight().weight(suspectCount.toFloat()).background(Color(0xFFFFA726)))
                        if (projectBadCount > 0) Box(Modifier.fillMaxHeight().weight(projectBadCount.toFloat()).background(Color(0xFFEF5350)))
                        if (pendingCount > 0) Box(Modifier.fillMaxHeight().weight(pendingCount.toFloat()).background(Color(0xFFB0BEC5)))
                    }
                }
            }

            // ===== 主操作区：Mihon 单独一套；开源阅读/异次元只留一个「添加书源」二级菜单 =====
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (selectedProject == 2) {
                        // Mihon：装 APP / 扫描 / 商店，三项各自入口；自定义仓库导入在商店弹窗内承接。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { pickApk.launch("application/vnd.android.package-archive") }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("装 APP", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { vm.scanMihonExtensions() }, enabled = !mihonBusy, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (mihonBusy) "处理中…" else "扫描已装", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = { showMihon = true; vm.loadMihonStore() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Widgets, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("扩展商店 / 导入自定义仓库", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        // 单一主操作：添加书源，点开二级菜单。
                        Button(onClick = { addMenuExpanded = !addMenuExpanded }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (addMenuExpanded) "收起添加菜单" else "添加书源")
                        }
                        if (addMenuExpanded) {
                            Spacer(Modifier.height(4.dp))
                            Button(onClick = {
                                addMenuExpanded = false; pickApk.launch("application/vnd.android.package-archive")
                            }, modifier = Modifier.fillMaxWidth()) { Text("安装 APP（本地 APK）") }
                            Spacer(Modifier.height(4.dp))
                            Button(onClick = { addMenuExpanded = false; addMode = 0; showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("网络导入") }
                            Spacer(Modifier.height(4.dp))
                            Button(onClick = { addMenuExpanded = false; pickFile.launch("application/json") }, modifier = Modifier.fillMaxWidth()) { Text("本地文件") }
                            Spacer(Modifier.height(4.dp))
                            Button(onClick = { addMenuExpanded = false; addMode = 2; showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("粘贴 JSON") }
                        }
                        // 项目专属一键导入：开源阅读=精品源，异次元=异次元源。
                        Spacer(Modifier.height(4.dp))
                        if (selectedProject == 0) {
                            Button(onClick = { vm.importPremiumSources() }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("一键导入精品源（164 个已实测）")
                            }
                        } else {
                            Button(onClick = { vm.importYiciyuanSources() }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("一键导入异次元源（110）")
                            }
                        }
                    }
                }
            }

            // ===== 多选管理开关 + 检测面板开关：常驻一行，具体内容按需展开 =====
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        multiSelectMode = !multiSelectMode
                        if (!multiSelectMode) selectedIds = emptySet()
                    }, modifier = Modifier.weight(1f)) {
                        Text(if (multiSelectMode) "退出多选" else "多选管理", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { checkPanelExpanded = !checkPanelExpanded }, modifier = Modifier.weight(1f)) {
                        Text(if (checkPanelExpanded) "收起检测" else "网站检测", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // 多选模式才浮现的批量管理：全选/删除只作用于当前项目可见源。
            if (multiSelectMode) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            selectedIds = if (selectedIds.size == visibleSources.size && visibleSources.isNotEmpty()) {
                                emptySet()
                            } else {
                                visibleSources.map { it.id }.toSet()
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Text(if (selectedIds.size == visibleSources.size && visibleSources.isNotEmpty()) "取消全选" else "全选",
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = {
                            vm.removeSources(selectedIds.toList())
                            selectedIds = emptySet()
                        }, enabled = selectedIds.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Text("删除所选（${selectedIds.size}）", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // 检测类操作收进折叠面板：断点续检 / 测不可用 / 屏蔽不通 / 筛选 / 精确复检。
            if (checkPanelExpanded) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                if (checking) vm.cancelCheck()
                                else if (vm.remainingCheckCount > 0) vm.checkSources()
                                else vm.checkSources(force = true)
                            }, modifier = Modifier.weight(1.2f)) {
                                Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (checking) "停止 ${progress.first}/${progress.second}"
                                    else if (vm.remainingCheckCount > 0) "续检（剩 ${vm.remainingCheckCount}）"
                                    else "全部重测",
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { if (!checking) vm.checkBadSources() },
                                modifier = Modifier.weight(0.8f), enabled = !checking && badCount > 0) {
                                Text("测不可用（$badCount）", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { vm.blockBadSources() },
                                modifier = Modifier.weight(1f), enabled = badCount > 0) {
                                Text("屏蔽不通（$badCount）", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { netFilter = (netFilter + 1) % 5 }, modifier = Modifier.weight(1f)) {
                                Text(
                                    when (netFilter) {
                                        1 -> "仅通网"; 2 -> "仅不通"; 3 -> "未检测"; 4 -> "仅存疑"; else -> "全部"
                                    },
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { if (!checking) vm.refineCheck() },
                                modifier = Modifier.weight(1.2f), enabled = !checking) {
                                Icon(Icons.Filled.FactCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("精确复检", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = "搜索书名 / 关键词",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { vm.search(keyword) }, enabled = !searching && keyword.isNotBlank()) {
                        Text(if (searching) "搜索中" else "搜索", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            if (message.isNotBlank()) {
                item {
                    Text(
                        message,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }

            if (results.isNotEmpty()) {
                item { SectionTitle("搜索结果（${results.size}）") }
                items(results) { sb ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(sb.book.name, fontSize = MiuixTheme.textStyles.title4.fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    buildString {
                                        if (sb.book.author.isNotBlank()) append(sb.book.author)
                                        if (sb.book.kind.isNotBlank()) { if (isNotEmpty()) append(" · "); append(sb.book.kind) }
                                        if (isNotEmpty()) append(" · ")
                                        append(sb.source.name)
                                    },
                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            // 与 VM 同一套归一规则，避免一处蓝一处不蓝
                            val added = vm.shelfKey(sb.book.name) in addedKeys
                            Button(
                                onClick = { vm.addToShelf(sb) },
                                enabled = !added,
                                colors = if (added) ButtonDefaults.buttonColorsPrimary()
                                    else ButtonDefaults.buttonColors(),
                            ) { Text(if (added) "✓ 已加入" else "加入书架") }
                        }
                    }
                }
            }

            // 分组筛选条：照 legado 优先按作者分类(bookSourceGroup)查看。多于 1 个分组时才显示。
            if (allGroups.size > 1) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            "按分类查看（优先作者分组）",
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(4.dp))
                        // 用横向可换行的 FlowRow 放分组标签，点选切换筛选。
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                        ) {
                            GroupChip(
                                "全部 ${if (selectedProject == 2) visiblePluginCount else sources.size}",
                                selectedGroup.isEmpty(),
                            ) { selectedGroup = "" }
                            allGroups.forEach { g ->
                                GroupChip("$g ${groupCount[g] ?: 0}", selectedGroup == g) { selectedGroup = g }
                            }
                        }
                    }
                }
            }

            item {
                SectionTitle(
                    if (selectedProject == 2) "已安装插件（$visiblePluginCount 个插件）"
                    else "已安装书源（${visibleSources.size}）"
                )
            }
            if (visibleSources.isEmpty()) {
                item {
                    Text(
                        "还没有书源。点上方「添加书源」导入即可。",
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }

            // 单条轻量行：去掉每源一张 Card（900+ 源时合成代价极高、滚动掉帧），普通 Row + 稳定 key。
            // indent=子源（Mihon 插件展开后），Checkbox 只在多选模式显示。
            @Composable
            fun SourceRow(s: BookSourceEntity, indent: Boolean = false) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = if (indent) 30.dp else 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (multiSelectMode) {
                        Checkbox(
                            state = if (selectedIds.contains(s.id)) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                            onClick = {
                                selectedIds = if (selectedIds.contains(s.id)) selectedIds - s.id else selectedIds + s.id
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (s.groupName.isBlank()) s.name else "${s.name} (${s.groupName})",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            when (netCheck[s.id]) {
                                0 -> Text("检测中", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                1 -> Text("通网✓", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = androidx.compose.ui.graphics.Color(0xFF2E7D32))
                                2 -> Text("不通✗", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = androidx.compose.ui.graphics.Color(0xFFC62828))
                                3 -> Text("存疑!", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = androidx.compose.ui.graphics.Color(0xFFE65100))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.url.isNotBlank()) {
                                Text(
                                    s.url,
                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                            Text(
                                "权重 ${s.hitCount}/${s.useCount}",
                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = if (s.hitCount > 0) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1, softWrap = false,
                            )
                        }
                    }
                    Switch(checked = s.enabled, onCheckedChange = { vm.setEnabled(s.id, it) })
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { vm.removeSource(s.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除书源")
                    }
                }
            }

            if (selectedProject == 2) {
                // Mihon 图源页：按 packageName（同一个 APK）聚合成一张卡，点开看组内源，对齐 Mihon 原版。
                fun langOf(pkg: String): String {
                    val parts = pkg.split(".")
                    val i = parts.indexOf("extension")
                    val seg = if (i >= 0 && i + 1 < parts.size) parts[i + 1] else ""
                    return when (seg.lowercase()) {
                        "zh" -> "中文"; "en" -> "英文"; "ja" -> "日文"; "ko" -> "韩文"; "all" -> "多语言"
                        else -> seg.uppercase()
                    }
                }
                val coverPalette = listOf(
                    Color(0xFF5B6EE1), Color(0xFF3D8BFD), Color(0xFF20A4A4), Color(0xFFC77DDB),
                    Color(0xFFE08A3C), Color(0xFF54A958), Color(0xFFD96A8F), Color(0xFF7A6FD8),
                )
                // 按包名分组，一扩展一张卡；组内保持 visibleSources 已排好的顺序。
                val pkgGroups = visibleSources.groupBy { src ->
                    val p = runCatching { org.json.JSONObject(src.json).optString("packageName") }.getOrDefault("")
                    if (p.isBlank()) src.subscribeUrl.ifBlank { src.name } else p
                }.toList().sortedByDescending { grp -> grp.second.maxOf { s -> s.hitCount + s.useCount } }
                items(pkgGroups, key = { it.first }) { grp ->
                    val pkg = grp.first
                    val list = grp.second
                    val expanded = pkg in mihonExpandedPkgs
                    // 友好标题：组内第一条源名（如 3Hentai），不显示一长串 eu.kanade... 全包名。
                    val title = list.first().name
                    val lang = langOf(pkg)
                    // 卡片连通状态：组内任一条通网→通网，否则取第一条状态。
                    val groupNet = list.firstNotNullOfOrNull { netCheck[it.id]?.takeIf { v -> v == 1 } }
                        ?: netCheck[list.first().id]
                    // 18+ 标记：组内任一条源 nsfw=true 即视为成人扩展。
                    val groupNsfw = list.any { src ->
                        runCatching { org.json.JSONObject(src.json).optBoolean("nsfw", false) }.getOrDefault(false)
                    }
                    val allSelected = list.isNotEmpty() && list.all { selectedIds.contains(it.id) }
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x0D000000))
                            .clickable {
                                mihonExpandedPkgs = if (expanded) mihonExpandedPkgs - pkg else mihonExpandedPkgs + pkg
                            },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (multiSelectMode) {
                                Checkbox(
                                    state = if (allSelected) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                                    onClick = {
                                        selectedIds = if (allSelected) selectedIds - list.map { it.id }.toSet()
                                                      else selectedIds + list.map { it.id }.toSet()
                                    },
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            // 真实扩展图标：后台线程解析（系统已装 / 私有 APK），组合期只读结果；取不到回退首字色块。
                            val iconBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                                initialValue = null, pkg
                            ) {
                                value = withContext(Dispatchers.IO) {
                                    val dr = com.yg.comic.source.mihon.MihonExtensionManager.iconFor(pkg) ?: return@withContext null
                                    val px = 96
                                    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
                                    android.graphics.Canvas(bmp).let { c ->
                                        dr.setBounds(0, 0, px, px)
                                        dr.draw(c)
                                    }
                                    bmp.asImageBitmap()
                                }
                            }
                            val bm = iconBitmap
                            if (bm != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bm,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                                )
                            } else {
                                val color = coverPalette[(title.hashCode() and 0x7fffffff) % coverPalette.size]
                                Box(
                                    Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(color),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(title.take(1), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        title,
                                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (lang.isNotBlank()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            lang,
                                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1, softWrap = false,
                                        )
                                    }
                                    if (groupNsfw) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "18+",
                                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                            color = Color(0xFFC62828),
                                            maxLines = 1, softWrap = false,
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "${list.size} 种语言",
                                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1, softWrap = false,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    when (groupNet) {
                                        1 -> Text("通网✓", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = Color(0xFF2E7D32))
                                        2 -> Text("不通✗", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = Color(0xFFC62828))
                                        3 -> Text("存疑!", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = Color(0xFFE65100))
                                        0 -> Text("检测中", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                }
                            }
                            Icon(
                                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (expanded) "收起" else "展开",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (expanded) {
                            list.forEach { s ->
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (multiSelectMode) {
                                        Checkbox(
                                            state = if (selectedIds.contains(s.id)) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                                            onClick = { selectedIds = if (selectedIds.contains(s.id)) selectedIds - s.id else selectedIds + s.id },
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        // 语言名：直接读入库时写入的 lang 显示名（Deutsch/English…），键名与首页下拉统一。
                                        // 不再运行时加载扩展；老源没这个字段会空，回退回源名（去书源页重扫即可带上）。
                                        val rowLang = runCatching { org.json.JSONObject(s.json).optString("lang") }
                                            .getOrDefault("").orEmpty()
                                        Text(
                                            if (rowLang.isBlank()) s.name else rowLang,
                                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                        when (netCheck[s.id]) {
                                            0 -> Text("检测中", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                            1 -> Text("通网✓", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = Color(0xFF2E7D32))
                                            2 -> Text("不通✗", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = Color(0xFFC62828))
                                            3 -> Text("存疑!", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = Color(0xFFE65100))
                                        }
                                    }
                                    Icon(
                                        Icons.Filled.PushPin,
                                        contentDescription = "置顶",
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Switch(checked = s.enabled, onCheckedChange = { vm.setEnabled(s.id, it) })
                                    IconButton(onClick = { vm.removeSource(s.id) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除书源")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // 开源阅读 / 异次元：维持单源一行。
                items(visibleSources, key = { it.id }) { s -> SourceRow(s) }
            }

        }
    }

    if (showAdd) {
        OverlayDialog(
            show = true,
            title = "添加书源",
            summary = if (addMode == 0) "输入书源订阅 URL，拉取并解析后入库（兼容 legado 阅读3.0 格式）。"
                      else "粘贴 legado（阅读3.0）书源 JSON 文本，可一次粘贴多条。",
            onDismissRequest = { showAdd = false },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                if (message.isNotBlank()) {
                    Text(
                        message,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                // 自定义名称（两种方式共用）：留空则用书源自带名称。
                SectionLabel("自定义名称（可选）")
                TextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = "书源名称",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                if (addMode == 0) {
                    // ① 网络导入：填 URL 拉取（本地文件走顶部二级菜单的文件选择器，不在弹窗）。
                    SectionLabel("网络导入")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = urlText,
                            onValueChange = { urlText = it },
                            label = "书源 URL",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { vm.addSourceFromUrl(urlText, nameOverride = nameInput) },
                            enabled = urlText.isNotBlank() && !searching,
                        ) { Text("导入") }
                    }
                } else {
                    // ② 粘贴 JSON（含调试填入）
                    SectionLabel("粘贴 JSON 文本")
                    if (com.yg.comic.BuildConfig.DEBUG) {
                        Button(
                            onClick = { addText = LOCAL_TEST_SOURCE },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("填入本地测试源（调试）") }
                        Spacer(Modifier.height(8.dp))
                    }
                    TextField(
                        value = addText,
                        onValueChange = { addText = it },
                        label = "书源 JSON",
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    DialogActions(
                        cancelText = "取消",
                        confirmText = "添加",
                        confirmEnabled = addText.isNotBlank(),
                        onCancel = { showAdd = false },
                        onConfirm = {
                            vm.addSourceFromJson(addText, nameOverride = nameInput)
                            addText = ""
                            nameInput = ""
                            showAdd = false
                        },
                    )
                }
            }
        }
    }

    if (showMihon) {
        // 扩展商店独立页：盖在主列表之上，插件列表为主，底部「+」导仓库。
        var storeSort by remember { mutableStateOf(0) }   // 0 默认 / 1 最近更新（按版本近似）
        var onlyNotAdded by remember { mutableStateOf(false) }
        var storeLang by remember { mutableStateOf("") }
        var showRepoPanel by remember { mutableStateOf(false) }
        val storeLangs = remember(mihonStore) {
            mihonStore.flatMap { it.sources.map { s -> s.language } }
                .filter { it.isNotBlank() }.distinct().sorted()
        }
        val shownStore = remember(mihonStore, storeSort, onlyNotAdded, storeLang, mihonLoadedPackages) {
            var list = mihonStore
            if (storeLang.isNotBlank()) list = list.filter { it.sources.any { s -> s.language == storeLang } }
            if (onlyNotAdded) list = list.filter { it.packageName !in mihonLoadedPackages }
            // 清单没有真实更新时间/热度字段；「最近更新」只能按版本号降序近似，不造假数据。
            if (storeSort == 1) list = list.sortedByDescending { it.versionCode.toIntOrNull() ?: 0 }
            list
        }

        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "‹ 返回",
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showMihon = false }.padding(end = 12.dp),
                )
                Text("扩展商店", fontSize = MiuixTheme.textStyles.title4.fontSize, fontWeight = FontWeight.Bold)
            }
            if (message.isNotBlank()) {
                Text(
                    message,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                )
            }
            // 排序 + 筛选
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                GroupChip("默认", storeSort == 0) { storeSort = 0 }
                GroupChip("最近更新", storeSort == 1) { storeSort = 1 }
                GroupChip("全部", !onlyNotAdded) { onlyNotAdded = false }
                GroupChip("未加入", onlyNotAdded) { onlyNotAdded = true }
                if (storeLangs.size > 1) {
                    GroupChip("所有语言", storeLang.isEmpty()) { storeLang = "" }
                    storeLangs.forEach { l -> GroupChip(l, storeLang == l) { storeLang = l } }
                }
            }
            Text(
                "商店共 ${mihonStore.size} 个插件，当前显示 ${shownStore.size} 个",
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            // 插件列表为主：懒加载，导入后原地变「已加入」，不自动跳走。
            val storePalette = listOf(
                Color(0xFF5B6EE1), Color(0xFF3D8BFD), Color(0xFF20A4A4), Color(0xFFC77DDB),
                Color(0xFFE08A3C), Color(0xFF54A958), Color(0xFFD96A8F), Color(0xFF7A6FD8),
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(shownStore, key = { it.packageName }) { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 插件图标：网络 URL，加载失败露首字色块，与书架/详情一致。
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                                .background(storePalette[(item.name.hashCode() and 0x7fffffff) % storePalette.size]),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(item.name.take(1), color = Color.White, fontWeight = FontWeight.Bold)
                            AsyncImage(
                                model = item.iconUrl.ifBlank { null },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                item.versionName +
                                    if (item.contentWarning.isNotBlank()) " · 18+" else "",
                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        val added = item.packageName in mihonLoadedPackages
                        if (added) {
                            Box(
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(MiuixTheme.colorScheme.primary)
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "✓ 已加入",
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = MiuixTheme.colorScheme.onPrimary,
                                )
                            }
                        } else {
                            Button(
                                onClick = { vm.importMihonExtension(item) },
                                enabled = !mihonBusy,
                            ) { Text("导入", maxLines = 1, softWrap = false) }
                        }
                    }
                }
            }
            // 底部「+」导仓库 + 一键操作，固定在列表下方。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { showRepoPanel = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导仓库")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.scanMihonExtensions() },
                    enabled = !mihonBusy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (mihonBusy) "处理中…" else "扫描已装") }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                Button(
                    onClick = { vm.importBuiltinMihonExtensions() },
                    enabled = !mihonBusy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (mihonBusy) "处理中…" else "导入内置(4)") }
            }
        }

        if (showRepoPanel) {
            // 自定义仓库子面板（Mihon 原生 index.json），导入后回到商店页、不自动跳转。
            OverlayDialog(
                show = true,
                title = "导入自定义仓库",
                summary = "填入仓库 index.json 地址，拉取后勾选要导入的插件。",
                onDismissRequest = { showRepoPanel = false },
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    if (message.isNotBlank()) {
                        Text(
                            message,
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    TextField(
                        value = customRepoUrl,
                        onValueChange = { vm.setCustomRepoUrl(it) },
                        label = "仓库 index.json 地址",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { repoChecked = emptySet(); repoLang = ""; vm.loadCustomRepo(customRepoUrl) },
                        enabled = !customRepoLoading && customRepoUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (customRepoLoading) "拉取中…" else "拉取仓库列表")
                    }
                    val repoLangs = remember(customRepoItems) {
                        customRepoItems.flatMap { it.sources.map { s -> s.language } }
                            .filter { it.isNotBlank() }.distinct().sorted()
                    }
                    val visibleRepoItems = if (repoLang.isEmpty()) customRepoItems
                        else customRepoItems.filter { item -> item.sources.any { it.language == repoLang } }
                    if (repoLangs.size > 1) {
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                        ) {
                            GroupChip("全部", repoLang.isEmpty()) { repoLang = "" }
                            repoLangs.forEach { lang -> GroupChip(lang, repoLang == lang) { repoLang = lang } }
                        }
                    }
                    if (customRepoItems.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "仓库共 ${customRepoItems.size} 个扩展，当前显示 ${visibleRepoItems.size} 个；勾选后导入。",
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        LazyColumn(Modifier.fillMaxWidth().height(220.dp)) {
                            items(visibleRepoItems, key = { it.packageName }) { item ->
                                val added = item.packageName in mihonLoadedPackages
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        state = if (item.packageName in repoChecked)
                                            androidx.compose.ui.state.ToggleableState.On
                                        else androidx.compose.ui.state.ToggleableState.Off,
                                        enabled = !added,
                                        onClick = {
                                            repoChecked = if (item.packageName in repoChecked) repoChecked - item.packageName
                                            else repoChecked + item.packageName
                                        },
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            item.name,
                                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${item.sources.size} 种语言 · ${item.versionName}" +
                                                if (item.contentWarning.isNotBlank()) " · 18+" else "",
                                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (added) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "✓ 已加入",
                                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                            color = MiuixTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        val pendingRepo = customRepoItems.filter { it.packageName in repoChecked && it.packageName !in mihonLoadedPackages }
                        Button(
                            onClick = {
                                vm.importCustomRepoExtensions(pendingRepo)
                                repoChecked = emptySet()
                            },
                            enabled = !customRepoLoading && pendingRepo.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (pendingRepo.isEmpty()) "勾选要导入的插件" else "导入选中（${pendingRepo.size} 个）")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    DialogActions(
                        cancelText = "关闭",
                        confirmText = "完成",
                        onCancel = { showRepoPanel = false },
                        onConfirm = { showRepoPanel = false },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = MiuixTheme.textStyles.subtitle.fontSize,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

// 分组筛选标签：选中态在文字前加勾，避免依赖 Miuix Button 的 colors 签名。
@Composable
private fun GroupChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.heightIn(min = 30.dp)) {
        Text(
            (if (selected) "✓ " else "") + text,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            maxLines = 1,
            color = if (selected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// 调试用：指向宿主机本地测试服务器（模拟器经 10.0.2.2 访问宿主 127.0.0.1:8910）
private val LOCAL_TEST_SOURCE = """
{
  "bookSourceName": "本地测试源",
  "bookSourceUrl": "http://10.0.2.2:8910",
  "searchUrl": "http://10.0.2.2:8910/search.html?keyword={{key}}",
  "ruleSearch": {
    "bookList": "@css:.book-list .book",
    "name": "@css:.name@text",
    "author": "@css:.author@text",
    "kind": "@css:.kind@text",
    "bookUrl": "@css:.name@href"
  },
  "ruleBookInfo": {
    "name": "@css:h1.book-name@text",
    "author": "@css:.info .a@text",
    "kind": "@css:.info .k@text",
    "intro": "@css:.intro@text",
    "tocUrl": "@css:.toc-link@href"
  },
  "ruleToc": {
    "chapterList": "@css:.chlist a",
    "chapterName": "@css:a@text",
    "chapterUrl": "@css:a@href"
  },
  "ruleContent": {
    "content": "@css:.content",
    "title": "@css:h1.title@text"
  }
}
""".trim()
