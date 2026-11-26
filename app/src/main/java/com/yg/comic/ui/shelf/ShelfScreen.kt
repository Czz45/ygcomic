package com.yg.comic.ui.shelf

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.yg.comic.data.BookRepository
import com.yg.comic.data.SettingsStore
import com.yg.comic.engine.NaturalOrder
import com.yg.comic.model.*
import com.yg.comic.sr.preload.SrBookProgress
import com.yg.comic.sr.preload.SrDiskCache
import com.yg.comic.sr.preload.PreloadSrQueue
import com.yg.comic.sr.preload.SrProgressStore
import com.yg.comic.sr.preload.SrState
import com.yg.comic.ui.components.DialogActions
import com.yg.comic.ui.components.LargeTitle
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.window.WindowCascadingListPopup
import top.yukonga.miuix.kmp.window.WindowDialog

private val FILTERS = listOf("全部", "漫画", "小说", "短剧", "音乐")

/**
 * 书架网格的一层快照。作为文件夹飞出 AnimatedContent 的 targetState：
 * 切换文件夹时退出端拿到旧快照（旧合集卡片还在）、进入端拿到新快照（新书列表），
 * 两端同 key 的封面才能配对飞行。data class 的 equals 保证进度等高频更新不触发整页转场。
 */
private data class ShelfLayer(
    val folderId: Long?,
    val collections: List<ComicCollection>,
    val books: List<Book>,
)

@Composable
fun ShelfScreen(
    repo: BookRepository,
    store: SettingsStore,
    nav: NavController,
    vm: ShelfViewModel = viewModel { ShelfViewModel(repo, store) },
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val collections by vm.collections.collectAsState()
    val books by vm.books.collectAsState()
    val path by vm.path.collectAsState()
    val filter by vm.filter.collectAsState()
    val sort by vm.sort.collectAsState()
    val globalBooks by vm.globalBooks.collectAsState()
    val progressMap by vm.progressMap.collectAsState()
    val networkBookIds by vm.networkBookIds.collectAsState()
    // 超分进度：章节队列写入后触发重组，封面进度块/完成角标随之刷新
    val srProgressMap by SrProgressStore.progress.collectAsState()

    // 文件夹飞出的层级快照：把当前层的数据打包成 targetState。
    // AnimatedContent 退出端持有旧快照、进入端持有新快照，同 key 封面才能配对飞到新位置；
    // 用 derivedStateOf（而非每次重组新建）保证 progress 等高频刷新（list 引用不变）不触发整页转场。
    val layer by remember {
        derivedStateOf { ShelfLayer(path.lastOrNull()?.first, collections, books) }
    }

    var showFilter by remember { mutableStateOf(false) }
    // 弹窗展开时漏斗按钮淡出隐藏，关闭后恢复原样（回去一样）。
    // 淡出要赶在弹窗放大盖住按钮之前完成（弹窗 spring 展开约 300ms），否则看不到过程；
    // 90ms 在弹窗展开早期就淡完，能看见按钮先消失。
    val filterBtnAlpha by animateFloatAsState(
        targetValue = if (showFilter) 0f else 1f,
        animationSpec = tween(90),
        label = "filterBtnAlpha",
    )
    // 弹窗定位器：让弹窗右上角盖住漏斗按钮本身（顶边贴按钮顶、右缘贴按钮右缘），
    // 不再落在按钮下方。只用位置，不碰库内部动画。
    val filterPopupPositioner = object : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowBounds: IntRect,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize,
            popupMargin: IntRect,
            alignment: PopupPositionProvider.Align,
        ): IntOffset {
            val x = anchorBounds.right - popupContentSize.width - popupMargin.right
            val y = anchorBounds.top - popupMargin.top
            return IntOffset(x, y)
        }
        override fun getMargins(): PaddingValues = PaddingValues(0.dp)
    }
    var longPressBook by remember { mutableStateOf<Book?>(null) }
    var longPressCollection by remember { mutableStateOf<ComicCollection?>(null) }
    var renameCollection by remember { mutableStateOf<ComicCollection?>(null) }
    var renameText by remember { mutableStateOf("") }
    var mergeMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }

    val anyDialog = longPressBook != null || longPressCollection != null || renameCollection != null
    // 系统返回键：优先逐级退出合集树；到顶层时才交还给 NavController（不拦截）。
    androidx.activity.compose.BackHandler(enabled = path.isNotEmpty() && !anyDialog && !mergeMode && !showFilter) {
        vm.navigateTo(path.size - 2)
    }

    Scaffold { _ ->
        Column(Modifier.fillMaxSize()) {
            LargeTitle(
                title = path.lastOrNull()?.second ?: "书架",
                onBack = if (path.isNotEmpty()) ({ vm.navigateTo(path.size - 2) }) else null,
                actions = {
                    IconButton(onClick = { nav.navigate("tts_settings") }) {
                        Icon(Icons.Filled.RecordVoiceOver, contentDescription = "朗读设置")
                    }
                    // 筛选按钮：按压往展开方向（下方）轻微位移 + 以按钮中心为圆心的圆形揭示反馈。
                    // 弹窗作为本 Box 的子节点，锚点即按钮本身，弹窗挂在按钮正下方。
                    Box(contentAlignment = Alignment.Center) {
                        IconButton(
                            onClick = { showFilter = true },
                            modifier = Modifier.graphicsLayer(alpha = filterBtnAlpha),
                        ) {
                            Icon(Icons.Filled.FilterList, contentDescription = "筛选排序")
                        }
                        // 换回 miuix 原生级联弹窗：展开/收起动画由库内部驱动（spring 缩放 + clip 揭示 + 淡入），
                        // 与澎湃一致。Window 版走 Dialog 窗口层，不依赖 Scaffold/MiuixPopupHost，绕开旧版贴底坑；
                        // 锚点即本 Box（按钮位置），Align.End 贴按钮右侧、向左下展开。
                        WindowCascadingListPopup(
                            show = showFilter,
                            entries = listOf(
                                DropdownEntry(items = FILTERS.map { f ->
                                    DropdownItem(text = f, selected = f == filter, onClick = {
                                        vm.setFilter(f); showFilter = false
                                    })
                                }),
                                DropdownEntry(items = SortMode.entries.map { m ->
                                    DropdownItem(text = m.label, selected = m == sort, onClick = {
                                        vm.setSort(m); showFilter = false
                                    })
                                }),
                            ),
                            onDismissRequest = { showFilter = false },
                            popupPositionProvider = filterPopupPositioner,
                            alignment = PopupPositionProvider.Align.End,
                            enableWindowDim = false,
                        )
                    }
                    IconButton(onClick = { nav.navigate("import") }) {
                        Icon(Icons.Filled.Add, contentDescription = "导入")
                    }
                },
            )

            if (mergeMode) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已选 ${selected.size} 本", modifier = Modifier.weight(1f))
                    TextButton(text = "取消", onClick = { mergeMode = false; selected.clear() })
                    Button(onClick = {
                        if (selected.size >= 2) vm.merge(selected.toList(), "合并漫画") { id ->
                            mergeMode = false; selected.clear()
                        }
                    }) { Text("合并") }
                }
            }

            Box(Modifier.fillMaxSize().weight(1f)) {
                if (collections.isEmpty() && books.isEmpty()) {
                    EmptyShelf(onImport = { nav.navigate("import") })
                } else {
                    // 文件夹飞出：以当前文件夹 id（顶层为 null）作 AnimatedContent 的 key。
                    // 切换时旧文件夹的合集卡片与新文件夹的书卡短暂同场、各有 scope，
                    // 同 book id 的封面用 folder-* key 自动算轨迹飞到对应位置，返回收回。
                    AnimatedContent(
                        targetState = layer,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                        // 只在文件夹层级真正切换时才转场：books/collections 是异步加载的，
                        // 先到的那个让 layer 内容变化、folderId 却没变（首次进 APP 就是这种），
                        // 若按整个 layer 判等会误触发整页淡入 + tilt 旋转的"小动画"。
                        contentKey = { it.folderId },
                        label = "folderFly",
                    ) { targetLayer ->
                        val acScope = this  // AnimatedContent 的 scope，供 folder-* sharedBounds 配对
                        // 用快照里的数据，退出端保留了进文件夹前的旧合集卡片，旧封面才存在、才飞得起来。
                        val layerCollections = targetLayer.collections
                        val layerBooks = targetLayer.books
                        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
                            initialFirstVisibleItemIndex = vm.gridIndex,
                            initialFirstVisibleItemScrollOffset = vm.gridOffset,
                        )
                        // 离开书架（进阅读器/导入）时记下滚动位置，返回后按此恢复，避免回跳。
                        androidx.compose.runtime.DisposableEffect(Unit) {
                            onDispose {
                                vm.gridIndex = gridState.firstVisibleItemIndex
                                vm.gridOffset = gridState.firstVisibleItemScrollOffset
                            }
                        }
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 116.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(layerCollections, key = { "c${it.id}" }) { c ->
                                // 预览书用与进入文件夹后书列表完全相同的 filter + sort，
                                // 保证合集中的前 3 本 == 进入后列表的前 3 本，key 配对得上、飞到正确位置。
                                val previewBooks = globalBooks.filter { it.collectionId == c.id }
                                    .let { l -> if (filter == "全部") l else l.filter { b -> b.mediaType.label == filter } }
                                    .let { l -> when (sort) {
                                        SortMode.NATURAL -> l.sortedWith(compareBy(NaturalOrder) { it.title.lowercase() })
                                        SortMode.FILE_NAME -> l.sortedBy { it.title }
                                    } }
                                    .take(3)
                                CollectionCard(
                                    c = c, previewBooks = previewBooks,
                                    onClick = { vm.enter(c.id, c.name) },
                                    onLongPress = { longPressCollection = c },
                                    folderScope = acScope,
                                    folderSharedScope = sharedTransitionScope,
                                )
                            }
                            itemsIndexed(layerBooks, key = { _, b -> "b${b.id}" }) { index, b ->
                                BookCard(
                                    book = b,
                                    progress = progressMap[b.id],
                                    srProgress = SrProgressStore.bookProgress(b.id),
                                    isNetwork = networkBookIds.contains(b.id),
                                    selected = selected.contains(b.id),
                                    mergeMode = mergeMode,
                                    onClick = {
                                        if (mergeMode) {
                                            if (selected.contains(b.id)) selected.remove(b.id) else selected.add(b.id)
                                        } else if (b.mediaType == MediaType.COMIC || b.mediaType == MediaType.NOVEL) {
                                            nav.navigate("reader/${b.id}")
                                        } else {
                                            nav.navigate("placeholder/${b.mediaType.name}")
                                        }
                                    },
                                    onLongPress = { longPressBook = b },
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    // 只有文件夹卡片最上面那张主封面参与飞出/收回，与文件夹内第一项配对；
                                    // 底下两张不绑 folder-* key，避免同一张图挂两套 sharedBounds 抢 overlay。
                                    folderScope = if (index < 1) acScope else null,
                                    folderSharedScope = if (index < 1) sharedTransitionScope else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    longPressBook?.let { b ->
        val shelfContext = androidx.compose.ui.platform.LocalContext.current
        val srProg = SrProgressStore.bookProgress(b.id)
        BookActionSheet(
            book = b,
            srTouched = srProg.total > 0 || srProg.state != SrState.IDLE,
            srState = srProg.state,
            onDismiss = { longPressBook = null },
            onFavorite = { vm.toggleFavorite(b.id, !b.favorite); longPressBook = null },
            onMerge = { mergeMode = true; selected.add(b.id); longPressBook = null },
            onPreload = {
                vm.preloadBook(b.id) { msg ->
                    android.widget.Toast.makeText(shelfContext, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                longPressBook = null
            },
            onPauseBook = {
                if (PreloadSrQueue.isPausedBook(b.id)) {
                    PreloadSrQueue.resumeBook(b.id)
                    android.widget.Toast.makeText(shelfContext, "已继续超分", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    PreloadSrQueue.pauseBook(b.id)
                    android.widget.Toast.makeText(shelfContext, "已暂停超分", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onStopBook = {
                PreloadSrQueue.stopBook(b.id)
                android.widget.Toast.makeText(shelfContext, "已停止超分", android.widget.Toast.LENGTH_SHORT).show()
                longPressBook = null
            },
            onDeleteCache = {
                SrDiskCache.clearBook(b.id)
                SrProgressStore.clearBook(b.id)
                android.widget.Toast.makeText(shelfContext, "已删除本书超分缓存", android.widget.Toast.LENGTH_SHORT).show()
                longPressBook = null
            },
            onRestart = {
                // 重新超分：先清掉旧缓存与进度，避免队列命中已完成页而跳过
                SrDiskCache.clearBook(b.id)
                SrProgressStore.clearBook(b.id)
                vm.preloadBook(b.id) { msg ->
                    android.widget.Toast.makeText(shelfContext, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                longPressBook = null
            },
            onDelete = { vm.deleteBook(b.id); longPressBook = null },
        )
    }

    longPressCollection?.let { c ->
        CollectionActionSheet(
            collection = c,
            onDismiss = { longPressCollection = null },
            onRename = { renameCollection = c; renameText = c.name; longPressCollection = null },
            onDelete = { vm.deleteCollection(c.id); longPressCollection = null },
        )
    }

    renameCollection?.let { c ->
        WindowDialog(
            show = true,
            title = "重命名文件夹",
            onDismissRequest = { renameCollection = null },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = "文件夹名称",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                DialogActions(
                    cancelText = "取消",
                    confirmText = "保存",
                    confirmEnabled = renameText.isNotBlank(),
                    onCancel = { renameCollection = null },
                    onConfirm = {
                        val name = renameText.trim()
                        if (name.isNotBlank()) vm.renameCollection(c.id, name)
                        renameCollection = null
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyShelf(onImport: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.LocalLibrary, contentDescription = null, modifier = Modifier.size(64.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(16.dp))
        Text("书架还是空的", fontSize = MiuixTheme.textStyles.title4.fontSize)
        Spacer(Modifier.height(8.dp))
        Text("添加来源文件夹或导入漫画开始阅读", fontSize = MiuixTheme.textStyles.body2.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onImport) { Text("导入漫画") }
    }
}

/** 长按漫画的操作弹窗：图标 + 文字的宽行按钮，间距舒适不拥挤 */
@Composable
private fun BookActionSheet(
    book: Book,
    srTouched: Boolean,
    srState: String,
    onDismiss: () -> Unit,
    onFavorite: () -> Unit,
    onMerge: () -> Unit,
    onPreload: () -> Unit,
    onPauseBook: () -> Unit,
    onStopBook: () -> Unit,
    onDeleteCache: () -> Unit,
    onRestart: () -> Unit,
    onDelete: () -> Unit,
) {
    // 超分管理二级菜单：点标题才展开，不再一点就开跑
    var srExpanded by remember { mutableStateOf(false) }
    // WindowDialog 走独立 Dialog 窗口层，画在悬浮 tab 之上，不被底部 tab 盖住。
    WindowDialog(show = true, title = book.title, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            ActionRow(
                icon = if (book.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                label = if (book.favorite) "取消收藏" else "收藏",
                onClick = onFavorite,
            )
            HorizontalDivider(Modifier.padding(horizontal = 4.dp))
            ActionRow(icon = Icons.Filled.CallMerge, label = "加入合并", onClick = onMerge)
            HorizontalDivider(Modifier.padding(horizontal = 4.dp))
            if (!srTouched) {
                // 从未超分过：单个「超分」按钮，点了直接开始
                ActionRow(icon = Icons.Filled.AutoAwesome, label = "超分（整本后台）", onClick = onPreload)
            } else {
                // 超分过：可展开菜单，按当前状态列操作
                ActionRow(
                    icon = Icons.Filled.AutoAwesome,
                    label = "超分管理",
                    trailingIcon = if (srExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    onClick = { srExpanded = !srExpanded },
                )
                AnimatedVisibility(
                    visible = srExpanded,
                    enter = expandVertically(animationSpec = tween(SrMotion.MENU_EXPAND_MS)) + fadeIn(),
                    exit = shrinkVertically(animationSpec = tween(SrMotion.MENU_EXPAND_MS)) + fadeOut(),
                ) {
                    Column {
                        when (srState) {
                            SrState.RUNNING -> {
                                HorizontalDivider(Modifier.padding(horizontal = 4.dp))
                                ActionRow(icon = Icons.Filled.Pause, label = "暂停超分", onClick = onPauseBook)
                                HorizontalDivider(Modifier.padding(horizontal = 4.dp))
                                ActionRow(icon = Icons.Filled.Stop, label = "终止超分", onClick = onStopBook)
                            }
                            SrState.PAUSED -> {
                                HorizontalDivider(Modifier.padding(horizontal = 4.dp))
                                ActionRow(icon = Icons.Filled.PlayArrow, label = "继续超分", onClick = onPauseBook)
                                HorizontalDivider(Modifier.padding(horizontal = 4.dp))
                                ActionRow(icon = Icons.Filled.Stop, label = "终止超分", onClick = onStopBook)
                            }
                            else -> {
                                // 已完成 / 已取消 / 出错：重新超分 + 删缓存（红字最底）
                                HorizontalDivider(Modifier.padding(horizontal = 4.dp))
                                ActionRow(icon = Icons.Filled.Refresh, label = "重新超分", onClick = onRestart)
                            }
                        }
                        // 删缓存任何「超分过」状态都给，红字放最底
                        HorizontalDivider(Modifier.padding(horizontal = 4.dp))
                        ActionRow(
                            icon = Icons.Filled.DeleteSweep,
                            label = "删除本书超分缓存",
                            tint = MiuixTheme.colorScheme.error,
                            onClick = onDeleteCache,
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 4.dp))
            ActionRow(icon = Icons.Filled.DeleteOutline, label = "移出书架", tint = MiuixTheme.colorScheme.error, onClick = onDelete)
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.onSurface,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, fontSize = MiuixTheme.textStyles.body1.fontSize, color = tint, modifier = Modifier.weight(1f))
        if (trailingIcon != null) {
            Icon(trailingIcon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

// 预超分进行中的封面环绕微光：时长集中在此，避免散落裸字面量。
private object SrMotion {
    const val ORBIT_MS = 3200   // 彗尾绕边框一圈
    const val BREATH_MS = 2000  // 底光呼吸周期
    const val COMET_SPAN = 0.12f // 亮弧占周长比例
    val AURORA = listOf(
        Color(0xFF4DD0E1), Color(0xFFB388FF), Color(0xFFFF8FB1), Color(0xFFFFB74D), Color(0xFF4DD0E1),
    )
    const val ALPHA_MAX = 0.85f
    const val MENU_EXPAND_MS = 200 // 超分管理二级菜单展开/收起
}

// 沿周长采样：固定彩彩虹带 + 一个随 phase 移动的高亮窗口；reduceMotion 时整圈只留极淡底光。
private fun srAuraStops(phaseDeg: Float, breath: Float, reduceMotion: Boolean): Array<Pair<Float, Color>> {
    val n = 48
    val head = ((phaseDeg / 360f) % 1f + 1f) % 1f
    return Array(n + 1) { i ->
        val t = i.toFloat() / n
        val seg = t * (SrMotion.AURORA.size - 1)
        val idx = seg.toInt().coerceAtMost(SrMotion.AURORA.size - 2)
        val frac = seg - idx
        val base = androidx.compose.ui.graphics.lerp(SrMotion.AURORA[idx], SrMotion.AURORA[idx + 1], frac)
        val alpha = if (reduceMotion) breath else {
            var d = kotlin.math.abs(t - head)
            d = minOf(d, 1f - d)
            val bright = 1f - (d / SrMotion.COMET_SPAN).coerceAtMost(1f)
            breath + bright * (SrMotion.ALPHA_MAX - breath)
        }
        t to base.copy(alpha = alpha)
    }
}

// 彗尾微光：只画描边不叠模糊，phase 在 draw lambda 内读取，零重组。
@Composable
private fun BoxScope.SrAuraGlow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val reduceMotion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        }.getOrDefault(false)
    }
    val infinite = rememberInfiniteTransition(label = "srAura")
    val phaseState = infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SrMotion.ORBIT_MS, easing = LinearEasing)),
        label = "srOrbit",
    )
    val breathState = infinite.animateFloat(
        initialValue = 0.12f, targetValue = 0.18f,
        animationSpec = infiniteRepeatable(tween(SrMotion.BREATH_MS / 2, easing = EaseInOut), RepeatMode.Reverse),
        label = "srBreath",
    )
    Canvas(modifier = Modifier.matchParentSize()) {
        val phase = phaseState.value
        val breath = if (reduceMotion) 0.15f else breathState.value
        val strokePx = 2.5.dp.toPx()
        val inset = strokePx / 2f + 1.dp.toPx()
        drawRoundRect(
            brush = Brush.sweepGradient(colorStops = srAuraStops(phase, breath, reduceMotion)),
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - inset * 2f, size.height - inset * 2f),
            cornerRadius = CornerRadius(15.dp.toPx()),
            style = Stroke(width = strokePx),
        )
    }
}

@Composable
private fun BookCard(
    book: Book,
    progress: ReadProgress?,
    srProgress: SrBookProgress,
    isNetwork: Boolean,
    selected: Boolean,
    mergeMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    // 文件夹飞出：用 AnimatedContent 自己的 scope，与合集卡片封面按 book id 配对
    folderScope: AnimatedVisibilityScope? = null,
    folderSharedScope: SharedTransitionScope? = null,
) {
    // 封面展开共享元素：书架封面与阅读页根容器绑同一个 key，返回自动收回。
    // sharedBounds 是 SharedTransitionScope 的成员扩展，必须在 with(scope) 内调用，不能顶层 import。
    val coverSharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = "cover-${book.id}"),
                animatedVisibilityScope = animatedVisibilityScope,
                // 封面展开飞行全程保持圆角，与阅读页端一致（两端都要设才生效）。
                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(14.dp)),
            )
        }
    } else Modifier
    // 文件夹飞出的第二组共享元素：与合集卡片里同 id 封面配对，用 AnimatedContent 的 scope。
    // 与进阅读器的 cover-* 是两套独立 key/scope，互不干扰。
    val folderSharedModifier = if (folderScope != null && folderSharedScope != null) {
        with(folderSharedScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = "folder-${book.id}"),
                animatedVisibilityScope = folderScope,
                // 首次进 APP 时 sharedBounds 单独出现（无配对端）会播默认 enter 缩放/淡入 → "小动画"，
                // 关掉 enter/exit 只留 bounds 飞行；zIndexInOverlay 抬高，飞行封面盖在普通书卡之上，
                // 落位转场结束即不再渲染进 overlay，自动恢复正常层级。只作用于 folder-* 这套。
                enter = EnterTransition.None,
                exit = ExitTransition.None,
                zIndexInOverlay = 10f,
            )
        }
    } else Modifier
    // 取消外层圆角矩形底框：封面直接显示，整块可点击/长按；合并模式用细边框表示选中。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(if (selected) 2.dp else 0.dp, MiuixTheme.colorScheme.primary, RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() })
            },
    ) {
        Box {
            AsyncImage(
                model = book.coverPath,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = coverSharedModifier
                    .then(folderSharedModifier)
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant),
            )
            // 超分进行中：封面边缘环绕彗尾微光（绘于封面之上、角标之下；完成即销毁）
            if (srProgress.state == SrState.RUNNING) {
                SrAuraGlow()
            }
            // 网络书左上角"网"角标，和右下角媒体类型字形区分开
            if (isNetwork) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Color(0xFF2E7D32).copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("网", color = androidx.compose.ui.graphics.Color.White, fontSize = MiuixTheme.textStyles.footnote2.fontSize)
                }
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(24.dp).clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(typeGlyph(book.mediaType), color = MiuixTheme.colorScheme.onPrimary, fontSize = MiuixTheme.textStyles.footnote2.fontSize)
            }
            if (book.favorite) {
                Icon(Icons.Filled.Star, contentDescription = "收藏", tint = androidx.compose.ui.graphics.Color(0xFFFFD54F),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
            }
            // 超分进行中：封面底部白色进度方块，显示已超分百分比（与右下角媒体字形、左上角"网"错开，放 BottomStart）
            val srRunning = srProgress.state == SrState.RUNNING
            val srPaused = srProgress.state == SrState.PAUSED
            val srDone = srProgress.state == SrState.DONE && srProgress.total > 0
            if (srRunning || srPaused) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.88f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (srPaused) "超分已暂停 ${srProgress.percent}%" else "超分 ${srProgress.percent}%",
                        color = androidx.compose.ui.graphics.Color(0xFF1565C0),
                        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            // 整本超分完成：右上角小三角角标。收藏星也在 TopEnd，二者水平错开避免叠在一起。
            if (srDone) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = if (book.favorite) 30.dp else 6.dp)
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(androidx.compose.ui.graphics.Color(0xFF1565C0).copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow, contentDescription = "已超分",
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            Spacer(Modifier.height(6.dp))
            Text(
                book.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            )
            // 进度区固定显示：已读显示百分比，未读显示「未读」占位，保证所有书卡片高度一致、网格对齐。
            val total = book.pageCount
            val read = progress?.pageIndex ?: 0
            // 只要有进度记录就算已读（第1章 chapterIndex=0 也应显示已读，而非「未读」）。
            val hasRead = progress != null && total > 0
            val pct = if (total > 0) (((read + 1).toFloat() / total * 100).toInt()).coerceIn(0, 100) else 0
            Spacer(Modifier.height(4.dp))
            Text(
                if (hasRead) "已读 ${read + 1}/$total 页 · $pct%" else "未读",
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(MiuixTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(if (hasRead) pct / 100f else 0f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(MiuixTheme.colorScheme.primary))
            }
        }
    }
}

private fun typeGlyph(t: MediaType) = when (t) {
    MediaType.COMIC -> "漫"
    MediaType.NOVEL -> "书"
    MediaType.SHORT_DRAMA -> "剧"
    MediaType.AUDIO -> "音"
}

/** 长按合集的操作弹窗：重命名 / 删除 */
@Composable
private fun CollectionActionSheet(
    collection: ComicCollection,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    // WindowDialog 走独立 Dialog 窗口层，画在悬浮 tab 之上，不被底部 tab 盖住。
    WindowDialog(show = true, title = collection.name, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            ActionRow(icon = Icons.Filled.Edit, label = "重命名", onClick = onRename)
            HorizontalDivider(Modifier.padding(horizontal = 4.dp))
            ActionRow(
                icon = Icons.Filled.DeleteOutline, label = "删除文件夹（书移到顶层）",
                tint = MiuixTheme.colorScheme.error, onClick = onDelete,
            )
        }
    }
}

@Composable
private fun CollectionCard(
    c: ComicCollection,
    previewBooks: List<Book>,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    folderScope: AnimatedVisibilityScope? = null,
    folderSharedScope: SharedTransitionScope? = null,
) {
    // 取消外层圆角矩形底框：只保留封面与标题，整块仍可点击/长按。
    // 外层不再 clip：斜叠封面收回落位时可略微超出网格边界，
    // 否则飞行结束回到布局树那一刻会被裁到格子边框、看着像突然被切掉。
    // 每张封面自己仍有圆角 clip，外观不受影响。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() })
            },
    ) {
        // 文件夹 cell 撑满网格、跟普通封面一样大（aspectRatio 0.72），三张预览封面叠放。
        // 每张封面各自绑 folder-${book.id} 共享元素（用 AnimatedContent 的 scope），
        // 与进入文件夹后书列表前三项的同 key 封面配对，点击时飞到对应位置。
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f)) {
            // 最底层第三张：旋转最大、偏移最多。
            // 斜角随 folder 转场进度从 16° 平滑收正，和 sharedBounds 绑在同一个图片节点上，
            // 飞出时 overlay 才能带着斜角一起飞，到落位（Visible→PostExit 走完）才归 0。
            if (previewBooks.size >= 3) {
                // 底层/中层不参与 sharedBounds 飞行：只做收正（tilt 动画）+ 随层淡入淡出。
                // 三张同时飞会让 overlay 里出现多个同尺寸副本互抢层级，落位瞬间撤副本就会"乱一下再跳"。
                val m = Modifier
                // 退出（进文件夹）Visible→PostExit：16°→0；进入（返回）PreEnter→Visible：0→16°。
                val tilt3 = if (folderScope != null) {
                    folderScope.transition.animateFloat(
                        transitionSpec = { tween(220) }, label = "folderTilt3",
                    ) { st -> if (st == EnterExitState.Visible) 16f else 0f }
                } else null
                Box(
                    m.matchParentSize()
                        .graphicsLayer {
                            rotationZ = tilt3?.value ?: 16f
                            translationX = 6f
                            translationY = 6f
                        }
                        .shadow(8.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black.copy(alpha = 0.3f))
                        .clip(RoundedCornerShape(14.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant),
                ) {
                    AsyncImage(
                        model = previewBooks[2].coverPath, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // 中层第二张：斜角同样随转场进度从 8° 平滑收正，与 sharedBounds 同节点。
            if (previewBooks.size >= 2) {
                // 中层同样只做收正 + 淡出，不飞。
                val m = Modifier
                val tilt2 = if (folderScope != null) {
                    folderScope.transition.animateFloat(
                        transitionSpec = { tween(220) }, label = "folderTilt2",
                    ) { st -> if (st == EnterExitState.Visible) 8f else 0f }
                } else null
                Box(
                    m.matchParentSize()
                        .graphicsLayer { rotationZ = tilt2?.value ?: 8f }
                        .shadow(8.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black.copy(alpha = 0.3f))
                        .clip(RoundedCornerShape(14.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant),
                ) {
                    AsyncImage(
                        model = previewBooks[1].coverPath, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // 上层主图：内缩露出底层边缘形成"一叠"感
            if (previewBooks.isNotEmpty()) {
                val m = if (folderScope != null && folderSharedScope != null) {
                    with(folderSharedScope) {
                        Modifier.sharedBounds(
                            rememberSharedContentState(key = "folder-${previewBooks[0].id}"),
                            animatedVisibilityScope = folderScope,
                            enter = EnterTransition.None,
                            exit = ExitTransition.None,
                            // 只留一张飞：层级固定高于任何其它共享元素，绝不打平（打平就是"层级乱"的来源）
                            zIndexInOverlay = 11f,
                            // 飞行途中也保持圆角，落位时不会突然从直角变圆角
                            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(14.dp)),
                        )
                    }
                } else Modifier
                Box(
                    Modifier.fillMaxSize().padding(end = 3.dp, bottom = 3.dp)
                        .shadow(10.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black.copy(alpha = 0.35f))
                        .clip(RoundedCornerShape(14.dp)),
                ) {
                    AsyncImage(
                        model = previewBooks[0].coverPath, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = m.fillMaxSize().background(MiuixTheme.colorScheme.surfaceVariant),
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surfaceVariant))
            }
            Icon(Icons.Filled.Folder, contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(20.dp))
        }
        Text(c.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = MiuixTheme.textStyles.body2.fontSize,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 6.dp))
    }
}

