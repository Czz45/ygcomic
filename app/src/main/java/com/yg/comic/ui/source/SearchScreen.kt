package com.yg.comic.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.yg.comic.data.BookRepository
import com.yg.comic.ui.components.LargeTitle
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单独搜索页：顶部搜索框，输入书名列关键词，在全部启用书源里「流式」搜索——
 * 每个源一返回就立刻出现在列表（边搜边出），底部显示 已搜 N/总 M 进度。
 * 结果可直接「加入书架」；点击结果进入书籍详情页。
 */
@Composable
fun SearchScreen(
    repo: BookRepository,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenDetail: (SourcedBook) -> Unit = {},
    initialKey: String? = null,
    sourceTypeFilter: Int = -1,
    vm: SearchViewModel = viewModel { SearchViewModel(repo) },
) {
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    val message by vm.message.collectAsState()
    val progress by vm.progress.collectAsState()
    val sources by vm.sources.collectAsState()
    val addedKeys by vm.addedKeys.collectAsState()
    val history by vm.history.collectAsState()
    val savedKeyword by vm.savedKeyword.collectAsState()

    var keyword by remember { mutableStateOf("") }

    // 点击结果进详情：给该源记一次"点击"偏好信号，再交由外部打开详情。
    val openDetail: (SourcedBook) -> Unit = { sb ->
        vm.recordClick(sb.source.id)
        onOpenDetail(sb)
    }

    // 首页搜索框带 key 跳转过来：预填关键词并自动搜索一次（幂等，点进详情返回不重搜）。
    // 先把漫画 tab 传入的 sourceTypeFilter 设进 VM，再触发搜索，确保只搜该类型书源。
    LaunchedEffect(initialKey, sourceTypeFilter) {
        vm.sourceTypeFilter = sourceTypeFilter
        if (vm.autoSearchFromKey(initialKey)) {
            keyword = initialKey.orEmpty()
        }
    }

    // 回填上次搜索词：结果已由 VM 从 SP 恢复，进详情返回后 keyword 状态丢失也要回填，不重复发网络请求。
    LaunchedEffect(savedKeyword) {
        if (savedKeyword.isNotBlank() && keyword.isBlank()) {
            keyword = savedKeyword
        }
    }

    // 照 legado：列表只渲染可视项，本地分页逐步放开条数（每源仍只搜 page1，不重复请求网络）。
    val listState = rememberLazyListState()
    var visibleCount by remember { mutableIntStateOf(20) }
    // 每次新搜索开始（searching 由 false→true）重置展示条数为第一页。
    LaunchedEffect(searching) { if (searching) visibleCount = 20 }
    val shown = results.take(visibleCount)
    // 滚到底部（倒数第 3 条可见）时自动加载下一页。
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= listState.layoutInfo.totalItemsCount - 3 && visibleCount < results.size
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) visibleCount = (visibleCount + 20).coerceAtMost(results.size)
    }

    LaunchedEffect(message) {
        if (message.isNotBlank()) { kotlinx.coroutines.delay(2500); vm.clearMessage() }
    }

    Scaffold { _ ->
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            state = listState,
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                    LargeTitle(title = "搜索")
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
                        label = "搜索书名 / 作者 / 关键词",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.search(keyword) }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (searching) {
                        Button(onClick = { vm.cancel() }) { Text("停止") }
                    } else {
                        Button(onClick = { vm.search(keyword) }, enabled = keyword.isNotBlank()) { Text("搜索") }
                    }
                }
            }

            // 进度条：边搜边出时实时显示
            if (searching && progress.second > 0) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(
                            "正在搜索… 已搜 ${progress.first}/${progress.second} 个书源，已找到 ${results.size} 本",
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = MiuixTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = if (progress.second == 0) 0f else progress.first.toFloat() / progress.second,
                            modifier = Modifier.fillMaxWidth(),
                        )
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

            if (sources.none { it.enabled }) {
                item {
                    Text(
                        "还没有启用的书源。请先到「书源」页添加并启用书源。",
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }

            if (results.isNotEmpty()) {
                item {
                    Text(
                        "搜索结果（${results.size}）",
                        fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(shown, key = { it.book.bookUrl.ifBlank { it.book.name + "|" + it.book.author } }) { sb ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                // 封面（照 legado 搜索结果：有封面图，圆角卡片，点击进详情）
                                AsyncImage(
                                    model = sb.book.coverUrl.ifBlank { null },
                                    contentDescription = sb.book.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(72.dp)
                                        .height(100.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MiuixTheme.colorScheme.surfaceVariant)
                                        .clickable { openDetail(sb) },
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f).clickable { openDetail(sb) }) {
                                    // 书名
                                    Text(
                                        sb.book.name,
                                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                    // 作者
                                    if (sb.book.author.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            "作者：${sb.book.author}",
                                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    // 分类 + 字数
                                    val meta = buildString {
                                        if (sb.book.kind.isNotBlank()) append(sb.book.kind)
                                        if (sb.book.wordCount.isNotBlank()) {
                                            if (isNotEmpty()) append(" · ")
                                            append(sb.book.wordCount)
                                        }
                                    }
                                    if (meta.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(meta, fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    // 最新章节
                                    if (sb.book.lastChapter.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text("最新：${sb.book.lastChapter}", fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            // 简介（照 legado：最多 2 行）
                            if (sb.book.intro.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    sb.book.intro,
                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { openDetail(sb) },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            // 来源 + 加入书架
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    buildString {
                                        append(sb.source.name)
                                        if (sb.origins.size > 1) append(" 等${sb.origins.size}源")
                                    },
                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                val isAdded = addedKeys.contains(vm.shelfKey(sb.book.name))
                                if (isAdded) {
                                    // 已加入：蓝色实心不可点，明确反馈，避免重复导入。
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
                                    Button(onClick = { vm.addToShelf(sb) }) { Text("加入书架") }
                                }
                            }
                        }
                    }
                }
                // 分页提示：还有更多则提示下滑加载，已全部展示则显示到底。
                item {
                    Text(
                        if (visibleCount < results.size) "已显示 $shown 条 / 共 ${results.size} 本，继续下滑加载更多…"
                        else "—— 已显示全部 ${results.size} 本 ——",
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
