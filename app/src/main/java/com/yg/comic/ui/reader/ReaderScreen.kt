package com.yg.comic.ui.reader

import android.graphics.Bitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.BitmapImage
import coil3.imageLoader
import com.yg.comic.data.BookRepository
import com.yg.comic.data.SettingsStore
import com.yg.comic.engine.ImageCache
import com.yg.comic.engine.PdfLive
import com.yg.comic.engine.TxtLive
import com.yg.comic.model.PageDirection
import com.yg.comic.sr.BitmapCropper
import com.yg.comic.sr.SRModel
import com.yg.comic.sr.SuperResolutionManager
import com.yg.comic.sr.preload.SrDiskCache
import com.yg.comic.sr.preload.SrProgressStore
import com.yg.comic.sr.preload.SrPageRef
import com.yg.comic.sr.preload.PreloadSrQueue
import com.yg.comic.sr.preload.SrTrace
import com.yg.comic.sr.preload.logicalKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

/**
 * 阅读页加载稳定性改造总闸：
 * true = 走新逻辑（稳定 key + 固定占位 + 状态去抖 + 单图替换）；
 * false = 一键回到改造前的图片显示逻辑。
 * 只控制阅读页图片显示，不影响超分/书源/排序等其他模块。
 */
internal const val READER_LOAD_STABILITY_V2 = true

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    repo: BookRepository,
    store: SettingsStore,
    bookId: Long,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit = {},
    onOpenTtsSettings: () -> Unit = {},
    vm: ReaderViewModel = viewModel { ReaderViewModel(repo, store, bookId) },
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val imageCache = remember { ImageCache(context) }

    val pages by vm.pages.collectAsState()
    val current by vm.current.collectAsState()
    val settings by vm.settings.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val title by vm.title.collectAsState()
    val siblings by vm.siblings.collectAsState()
    val isPdf by vm.isPdf.collectAsState()
    val pdfPath by vm.pdfPath.collectAsState()
    val isTxt by vm.isTxt.collectAsState()
    val isNetworkComic by vm.isNetworkComic.collectAsState()
    val loadMessage by vm.loadMessage.collectAsState()
    val referer by vm.referer.collectAsState()
    val chapterRefs by vm.chapterRefs.collectAsState()
    val chapterReversed by vm.chapterReversed.collectAsState()
    val tocTitles by vm.tocTitles.collectAsState()
    val smartTocSort by vm.smartTocSort.collectAsState()
    val tocDisplayOrder by vm.tocDisplayOrder.collectAsState()
    // 换源状态
    val origins by vm.origins.collectAsState()
    val sourceIndex by vm.sourceIndex.collectAsState()
    val switchCandidates by vm.switchCandidates.collectAsState()
    val switchLoading by vm.switchLoading.collectAsState()
    val switchMsg by vm.switchMsg.collectAsState()
    val switchEpoch by vm.switchEpoch.collectAsState()
    val activeChapterIndex = chapterRefs.indexOfLast { it.startPage <= current }
    // 当前正在看的那一章在完整目录里的下标（网络漫画只加载了一个窗口，不能拿 chapterRefs 下标当章号）
    val activeTocIndex = chapterRefs.getOrNull(activeChapterIndex)?.tocIndex ?: -1

    var chromeVisible by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var showSwitchSheet by remember { mutableStateOf(false) }
    var localDirection by remember(settings.direction) { mutableStateOf(settings.direction) }
    var localScale by remember(settings.scale) { mutableStateOf(settings.scale) }
    var localDual by remember(settings.dualPage) { mutableStateOf(settings.dualPage) }
    var localBackground by remember { mutableLongStateOf(settings.background) }
    var localImmersive by remember(settings.immersive) { mutableStateOf(settings.immersive) }

    // 小说：走独立的小说阅读器（独立 ViewModel、独立排版设置、字符偏移进度）。
    if (isTxt) {
        NovelReaderScreen(repo, store, bookId, onBack = onBack, onOpenBook = onOpenBook,
            onOpenTtsSettings = onOpenTtsSettings,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope)
        return
    }

    // 超级分辨率：对所有漫画类型（网络 / 本地图片 / PDF）生效，叠加渐进替换，失败回原图。
    val srEnabled by store.srEnabled.collectAsState(initial = false)
    val srModelKey by store.srModelKey.collectAsState(initial = "realesrgan_anime_fast")
    val autoCropEnabled by store.autoCropEnabled.collectAsState(initial = false)
    val preloadSrEnabled by store.preloadSrEnabled.collectAsState(initial = false)
    val srModel = remember(srModelKey) { SRModel.fromKey(srModelKey) }
    val srManager = remember(context) { SuperResolutionManager(context, store) }
    // 扁平页→页引用映射，与后台预超分写入侧共用同一份 key 算法，保证读到的正是落盘的那张。
    val pageRefs by vm.pageRefs.collectAsState()

    // 全类型后台预超分入口：三分支 pageRefs（网络/PDF/本地）已带稳定 bookId，直接整本入队。
    // 队列内部串行+限内存+跑完释放，开关关时不影响阅读（阅读页只会读磁盘缓存，没有就显原图）。
    val enqueuePreload: () -> Unit = {
        val refs = pageRefs
        if (refs.isEmpty()) {
            android.widget.Toast.makeText(context, "这本书没有可超分的页面", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            PreloadSrQueue.enqueue(refs, srModel, autoCropEnabled)
            android.widget.Toast.makeText(context, "已将 ${refs.size} 页加入后台预超分", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 就地缩放：两指捏合放大、放大态单指平移、点中间复位并弹菜单；非放大态单指不消费留给翻页。
    // 缩放量提升到这里（原来在放大 Dialog 的局部 remember 里，弹窗删掉后由内容区 graphicsLayer 使用）。
    var zoomScale by remember(bookId) { mutableFloatStateOf(1f) }
    var zoomOffset by remember(bookId) { mutableStateOf(Offset.Zero) }
    val isZoomed = zoomScale > 1.01f
    // 超分预览：按住看原图（临时隐藏超分叠加层），松开恢复超分，不改持久开关。
    var previewOriginal by remember { mutableStateOf(false) }

    // 切页复位缩放：翻到新页时回到 1 倍，避免带着放大状态看下一章/下一页。
    LaunchedEffect(current) {
        zoomScale = 1f
        zoomOffset = Offset.Zero
    }

    // 阅读时保持屏幕常亮
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // 沉浸模式：隐藏系统状态栏与手势导航条；退出阅读页时恢复
    val insetsController = remember {
        var ctx = context
        while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) {
            ctx = ctx.baseContext
        }
        (ctx as? android.app.Activity)?.let { WindowCompat.getInsetsController(it.window, view) }
    }
    DisposableEffect(localImmersive, insetsController) {
        val c = insetsController
        if (localImmersive) {
            c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { c?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // 滚动/翻页状态提升到外层，供底部进度条拖动跳转。
    // initialFirstVisibleItemIndex 只在首次 composition 生效，而 VM 的进度是异步加载的（首帧 current 仍为 0），
    // 所以加载完成后必须再显式 jumpTo(current) 一次，否则每次进书都从第 0 页开始。
    // 双页模式：横向翻页时每屏并排放两张，页数按「屏」算；纵向条漫不支持双页。
    val dualActive = localDual && localDirection != PageDirection.VERTICAL
    val pagesPerView = if (dualActive) 2 else 1
    val viewCount = (pages.size + pagesPerView - 1) / pagesPerView
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = current)
    val pagerState = rememberPagerState(
        initialPage = (current / pagesPerView).coerceAtMost((viewCount - 1).coerceAtLeast(0)),
    ) { viewCount }

    // 进度恢复标志：pages 加载完成前，禁止 snapshotFlow 把初始位置 0 当成用户翻页写回，覆盖掉已恢复的进度。
    // 换源(switchEpoch)变化也重置：新源页表重建后必须重新定位到恢复章，否则停在旧页号看着像没生效。
    var restored by remember(bookId, switchEpoch) { mutableStateOf(false) }
    LaunchedEffect(loaded, pages.size, bookId, switchEpoch) {
        if (loaded && pages.isNotEmpty() && !restored) {
            val target = current.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            if (localDirection == PageDirection.VERTICAL) listState.scrollToItem(target)
            else pagerState.scrollToPage((target / pagesPerView).coerceIn(0, (viewCount - 1).coerceAtLeast(0)))
            restored = true
        }
    }

    LaunchedEffect(listState, localDirection) {
        if (localDirection == PageDirection.VERTICAL) {
            snapshotFlow { listState.firstVisibleItemIndex }.collect { if (restored) vm.onPageChanged(it) }
        }
    }
    LaunchedEffect(pagerState, localDirection, pagesPerView) {
        if (localDirection != PageDirection.VERTICAL) {
            snapshotFlow { pagerState.currentPage }.collect { view ->
                if (restored) vm.onPageChanged((view * pagesPerView).coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
            }
        }
    }

    fun jumpTo(index: Int) {
        val i = index.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        scope.launch {
            if (localDirection == PageDirection.VERTICAL) listState.scrollToItem(i)
            else pagerState.scrollToPage((i / pagesPerView).coerceIn(0, (viewCount - 1).coerceAtLeast(0)))
        }
    }

    val bookIndex = siblings.indexOfFirst { it.id == bookId }
    val prevBookId = if (bookIndex > 0) siblings[bookIndex - 1].id else null
    val nextBookId = if (bookIndex >= 0 && bookIndex < siblings.size - 1) siblings[bookIndex + 1].id else null

    // 封面展开共享元素：与书架封面绑同一个 key，返回自动收回。
    // sharedBounds 是 SharedTransitionScope 的成员扩展，必须在 with(scope) 内调用，不能顶层 import。
    val readerSharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = "cover-${bookId}"),
                animatedVisibilityScope = animatedVisibilityScope,
                // 飞行全程保持封面圆角：sharedBounds 默认在 overlay 里按直角矩形裁剪，
                // 必须显式给 clipInOverlayDuringTransition 一个圆角 Shape，中途才不变直角。
                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(14.dp)),
            )
        }
    } else Modifier

    Box(
        readerSharedModifier
            .fillMaxSize()
            // 封面展开全程保持圆角。
            .clip(RoundedCornerShape(14.dp))
            // 整页背景写死纯黑（用户要求）：图片之间的缝隙、加载/失败占位都露黑底。
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    // 放大态点一下：先复位缩放再弹菜单（一次点击完成两件事）；普通态只切菜单。
                    // 这里读 zoomScale 是 state 委托、始终取最新值，不受 pointerInput(Unit) 不重组影响。
                    onTap = {
                        if (zoomScale > 1.01f) {
                            zoomScale = 1f
                            zoomOffset = Offset.Zero
                        }
                        chromeVisible = !chromeVisible
                    },
                    onDoubleTap = { /* 预留 */ },
                    // 放大态拖图时不要误触预加载；普通态长按照常入队。
                    onLongPress = { if (zoomScale <= 1.01f) enqueuePreload() },
                )
            }
            .pointerInput(Unit) {
                // 就地缩放手势：双指捏合缩放+中心平移；放大态单指拖动平移；
                // 非放大态单指完全不消费，留给列表滚动 / tap / 长按（边界处不翻页靠 userScrollEnabled）。
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    var pinchPrevDist = 0f
                    var pinchPrevCentroid = Offset.Zero
                    var lastPoint = firstDown.position
                    var dragStarted = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.size >= 2) {
                            val a = active[0].position
                            val b = active[1].position
                            val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                            val dist = kotlin.math.hypot(a.x - b.x, a.y - b.y)
                            if (pinchPrevDist > 0f && dist > 0f) {
                                val next = (zoomScale * (dist / pinchPrevDist)).coerceIn(1f, 6f)
                                zoomScale = next
                                if (pinchPrevCentroid != Offset.Zero) {
                                    zoomOffset = Offset(
                                        (zoomOffset.x + centroid.x - pinchPrevCentroid.x).coerceIn(-next * 2000f, next * 2000f),
                                        (zoomOffset.y + centroid.y - pinchPrevCentroid.y).coerceIn(-next * 2000f, next * 2000f),
                                    )
                                }
                            }
                            pinchPrevDist = dist
                            pinchPrevCentroid = centroid
                            lastPoint = centroid
                            active.forEach { it.consume() }
                        } else if (active.size == 1) {
                            val cur = active[0].position
                            if (zoomScale > 1.01f && (dragStarted || lastPoint != Offset.Zero && kotlin.math.hypot(cur.x - lastPoint.x, cur.y - lastPoint.y) > 2f)) {
                                dragStarted = true
                                zoomOffset = Offset(
                                    (zoomOffset.x + cur.x - lastPoint.x).coerceIn(-zoomScale * 2000f, zoomScale * 2000f),
                                    (zoomOffset.y + cur.y - lastPoint.y).coerceIn(-zoomScale * 2000f, zoomScale * 2000f),
                                )
                                active[0].consume()
                            }
                            lastPoint = cur
                        } else {
                            break
                        }
                    }
                }
            },
    ) {
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (pages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    loadMessage ?: "这本书没有可显示的图片",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            // 就地缩放作用在这一层：只缩放漫画内容，不影响外层菜单/进度条等 chrome。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoomScale,
                        scaleY = zoomScale,
                        translationX = zoomOffset.x,
                        translationY = zoomOffset.y,
                    ),
            ) {
            when (localDirection) {
                PageDirection.VERTICAL -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !isZoomed,
                    ) {
                        itemsIndexed(
                            items = pages,
                            // 稳定 key：以"哪本书哪一章哪一页"为身份，流式追加章时前面页的 key 不变，
                            // Compose 只回收复用、不会把已加载页按新位置重新下载（闪/卡的根因之一）。
                            // pageRef 还没到位时用 path 兜底；gate 关时传 null 回到按 index 的老行为。
                            key = if (READER_LOAD_STABILITY_V2) {
                                { idx: Int, path: String ->
                                    pageRefs.getOrNull(idx)?.logicalKey(srModel, autoCropEnabled) ?: "path:$path"
                                }
                            } else null,
                        ) { idx, path ->
                            PageImage(context, path, idx, imageCache, localScale, isPdf, pdfPath, isNetworkComic, referer, srEnabled, srManager, srModel, autoCropEnabled, previewOriginal, pageRefs.getOrNull(idx), preloadSrEnabled)
                        }
                    }
                }
                else -> {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = localDirection == PageDirection.RTL,
                        beyondViewportPageCount = 1,
                        userScrollEnabled = !isZoomed,
                    ) { view ->
                        // 每屏按 pagesPerView 张并排：双页 2 张、单页 1 张。
                        Row(Modifier.fillMaxSize()) {
                            for (k in 0 until pagesPerView) {
                                val pageIndex = view * pagesPerView + k
                                if (pageIndex < pages.size) {
                                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                        PageImage(context, pages[pageIndex], pageIndex, imageCache, localScale, isPdf, pdfPath, isNetworkComic, referer, srEnabled, srManager, srModel, autoCropEnabled, previewOriginal, pageRefs.getOrNull(pageIndex), preloadSrEnabled)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            } // graphicsLayer 内容 Box
        }

        // 超分预览按钮：按住看原图（临时隐藏超分叠加层），松开恢复超分；仅超分开启时显示。
        if (srEnabled) {
            Box(
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 84.dp)
                    .background(Color(0xAA000000))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                previewOriginal = true
                                tryAwaitRelease()
                                previewOriginal = false
                            },
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(if (previewOriginal) "原图" else "超分预览", color = Color.White,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize)
            }
        }

        // 顶部控制栏：黑条延伸到状态栏区域（沉浸时铺到屏幕顶），图标文字避开状态栏和摄像头挖孔。
        AnimatedVisibility(
            chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
            ) {
                SmallTopAppBar(
                    title = title,
                    color = Color.Transparent,
                    titleColor = Color.White,
                    defaultWindowInsetsPadding = false,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                        }
                    },
                    actions = {
                        if (isNetworkComic) {
                            IconButton(onClick = { vm.searchSwitchSources(); showSwitchSheet = true }) {
                                Icon(Icons.Filled.SwapHoriz, contentDescription = "换源", tint = Color.White)
                            }
                        }
                        IconButton(onClick = enqueuePreload) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = "后台预超分", tint = Color.White)
                        }
                    },
                )
            }
        }

        // 底部控制栏：黑条延伸到导航栏/手势条区域（沉浸时铺到屏幕底），只让按钮文字避开。
        // 布局：进度条单独一行靠上，按钮（上一本/下一本/目录/设置）放到进度条下面一行。
        AnimatedVisibility(
            chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // miuix 悬浮胶囊底栏：只避让手势小白条，不再用整块黑条压到屏幕底。
                FloatingToolbar(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xE6000000),
                    shadowElevation = 8.dp,
                    showDivider = false,
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                val chapterIndexNow = chapterRefs.indexOfLast { it.startPage <= current }
                val chapterNow = chapterRefs.getOrNull(chapterIndexNow)
                val chapterPageNow = if (chapterNow != null) current - chapterNow.startPage + 1 else current + 1
                val chapterTotalNow = chapterNow?.pageCount ?: 0
                // 章节显示：章名优先（章名里本来就有「第N话」，比显示序号更可信），
                // 加载中也照样显示章名，否则反序时只看得到「第1章」根本认不出是哪一话。
                val chapterLabel = chapterNow?.title.orEmpty()
                    .ifBlank { "第${(chapterIndexNow + 1).coerceAtLeast(1)}/${chapterRefs.size}章" }
                val pageLabel = if (chapterTotalNow > 0) "$chapterPageNow / $chapterTotalNow"
                else "${current + 1} / ${pages.size}"
                Text(
                    when {
                        loadMessage != null ->
                            listOf(title, chapterLabel, "加载中…")
                                .filter { it.isNotBlank() }.joinToString(" · ")
                        else -> listOf(chapterLabel, pageLabel)
                            .filter { it.isNotBlank() }.joinToString(" · ")
                    },
                    color = Color.White, fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                // 进度条上移：单独占一行，左右各留 8dp 不顶胶囊边
                Slider(
                    value = (current + 1).toFloat(),
                    onValueChange = { jumpTo(it.toInt() - 1) },
                    // Miuix Slider 要求 start < end；单页时范围退化为 0f..1f 避免崩溃
                    valueRange = 0f..(pages.size.toFloat().coerceAtLeast(1f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
                // 按钮放到进度条下面一行，用圆形独立按钮风格
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { prevBookId?.let(onOpenBook) }, enabled = prevBookId != null,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "上一本", tint = Color.White)
                    }
                    IconButton(
                        onClick = { nextBookId?.let(onOpenBook) }, enabled = nextBookId != null,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "下一本", tint = Color.White)
                    }
                    if (tocTitles.isNotEmpty()) {
                        IconButton(
                            onClick = { showChapters = true },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(Icons.Filled.List, contentDescription = "目录", tint = Color.White)
                        }
                    }
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Filled.Tune, contentDescription = "阅读设置", tint = Color.White)
                    }
                }
                    }
                }
            }
        }

        // 设置底部弹窗
        OverlayBottomSheet(
            show = showSettings,
            title = "阅读设置",
            onDismissRequest = { showSettings = false },
        ) {
            Text("翻页方向", fontSize = MiuixTheme.textStyles.body2.fontSize)
            Row(Modifier.fillMaxWidth()) {
                PageDirection.entries.forEach { d ->
                    Button(
                        onClick = {
                            localDirection = d
                            scope.launch { store.setDirection(d) }
                        },
                        modifier = Modifier.weight(1f).padding(4.dp),
                    ) { Text(d.label) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("双页模式", fontSize = MiuixTheme.textStyles.body2.fontSize)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = localDual, onCheckedChange = {
                    localDual = it
                    scope.launch { store.setDual(it) }
                })
                Spacer(Modifier.width(8.dp))
                Text(if (localDual) "开启" else "关闭")
            }
            Spacer(Modifier.height(8.dp))
            Text("缩放 ${String.format("%.1f", localScale)}x", fontSize = MiuixTheme.textStyles.body2.fontSize)
            Slider(value = localScale, onValueChange = {
                localScale = it
                scope.launch { store.setScale(it) }
            }, valueRange = 0.5f..2.5f)
            Spacer(Modifier.height(8.dp))
            Text("沉浸模式（隐藏状态栏与手势条）", fontSize = MiuixTheme.textStyles.body2.fontSize)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = localImmersive, onCheckedChange = {
                    localImmersive = it
                    scope.launch { store.setImmersive(it) }
                })
                Spacer(Modifier.width(8.dp))
                Text(if (localImmersive) "开启" else "关闭")
            }
            Spacer(Modifier.height(8.dp))
            // 超级分辨率：对所有漫画类型生效；模型选择/下载已移至「设置 → 超级分辨率」。
            Text("超级分辨率（对所有漫画生效）", fontSize = MiuixTheme.textStyles.body2.fontSize)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = srEnabled, onCheckedChange = { scope.launch { store.setSrEnabled(it) } })
                Spacer(Modifier.width(8.dp))
                Text(if (srEnabled) "开启" else "关闭")
            }
        }

        // 目录底部弹窗：正序/反序 + 点章节跳页；顺序和详情页共用 ChapterOrderStore。
        OverlayBottomSheet(
            show = showChapters,
            title = "目录（${tocTitles.size} 章）",
            onDismissRequest = { showChapters = false },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("阅读顺序", fontSize = MiuixTheme.textStyles.body2.fontSize, modifier = Modifier.weight(1f))
                Button(
                    onClick = { vm.setChapterOrder(false) },
                    colors = if (!chapterReversed) ButtonDefaults.buttonColorsPrimary()
                    else ButtonDefaults.buttonColors(),
                    modifier = Modifier.padding(end = 6.dp),
                ) { Text("正序") }
                Button(
                    onClick = { vm.setChapterOrder(true) },
                    colors = if (chapterReversed) ButtonDefaults.buttonColorsPrimary()
                    else ButtonDefaults.buttonColors(),
                ) { Text("反序") }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("智能排序", fontSize = MiuixTheme.textStyles.body2.fontSize, modifier = Modifier.weight(1f))
                Switch(checked = smartTocSort, onCheckedChange = { vm.setSmartTocSort(it) })
            }
            Text(
                "开启后按章号理顺，第一章在前；关闭则保持书源原顺序",
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            val activeDisplayPos = tocDisplayOrder.indexOf(activeTocIndex)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                itemsIndexed(tocTitles) { idx, tocTitle ->
                    Text(
                        tocTitle,
                        color = if (idx == activeDisplayPos) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showChapters = false
                                vm.openTocChapter(idx)
                            }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                    )
                }
            }
        }

        // 换源：本地已有多源之间切换（origins>1 时从联网 sheet 里的入口点开）
        ComicSourceListSheet(
            show = showSources, origins = origins, current = sourceIndex,
            onSelect = { idx -> showSources = false; vm.switchSource(idx) },
            onRefresh = { vm.searchSwitchSources() },
            onDismiss = { showSources = false },
        )
        // 换源：联网搜同名同作者漫画来源
        ComicSwitchSourceSheet(
            show = showSwitchSheet, loading = switchLoading, msg = switchMsg,
            candidates = switchCandidates, hasLocalSources = origins.size > 1,
            onSelect = { c -> showSwitchSheet = false; vm.applySwitchCandidate(c) },
            onShowLocal = { showSwitchSheet = false; showSources = true },
            onRefresh = { vm.searchSwitchSources() },
            onDismiss = { showSwitchSheet = false },
        )
    }

}

@Composable
private fun PageImage(
    context: android.content.Context,
    path: String,
    pageIndex: Int,
    cache: ImageCache,
    scale: Float,
    isPdf: Boolean,
    pdfPath: String,
    isNetwork: Boolean = false,
    referer: String = "",
    srEnabled: Boolean = false,
    srManager: SuperResolutionManager? = null,
    srModel: SRModel = SRModel.REALESRGAN_ANIME_FAST,
    autoCropEnabled: Boolean = false,
    previewOriginal: Boolean = false,
    pageRef: SrPageRef? = null,
    preloadSrEnabled: Boolean = false,
) {
    // 网络漫画：path 是书源解析出的图片 URL，交给 Coil 直接网络加载（本地 ImageCache 只认文件路径）。
    // 带书源主域 Referer 应对图片防盗链；无 Referer 时用空请求（部分站点要求 Referer 与图片域同源）。
    if (isNetwork) {
        // 网络漫画多为条漫（竖长图）：优先左右铺满，高度按比例自然延伸，不留白、不裁切。
        // 忽略 scale 的宽度占比缩放（条漫缩小只会让两侧留白更明显）。
        var imageFailed by remember(path) { mutableStateOf(false) }
        // 锁存"已成功显示过"：Coil 在重组/列表复用/切章时会重发 Loading，若据此回退占位就是用户看到的一闪一闪。
        // 成功一次后钉死，后续 Loading 一律忽略；只有显式重试（retryToken 变化）才重新计时。
        var imageReady by remember(path) { mutableStateOf(false) }
        var retryToken by remember(path) { mutableStateOf(0) }
        val request = remember(path, referer, retryToken) {
            ImageRequest.Builder(context)
                .data(path)
                .httpHeaders(
                    NetworkHeaders.Builder()
                        .apply {
                            if (referer.isNotBlank()) set("Referer", referer)
                        }
                        .build()
                )
                .build()
        }
        // 超分覆盖层：预超分命中磁盘缓存时直接"替换"原图（不再叠加第二张 Image），杜绝两张图宽高比打架导致的闪。
        // key 只留真正影响结果的几项：pageIndex/referer 由 path 唯一决定，去掉可避免同页反复重算。
        var srBmp by remember(path, srEnabled, srModel, autoCropEnabled, preloadSrEnabled) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(path, srEnabled, srModel, autoCropEnabled, pageRef, preloadSrEnabled, retryToken) {
            if (!srEnabled || srManager == null) {
                srBmp = null
                return@LaunchedEffect
            }
            if (preloadSrEnabled) {
                // 预超分模式：只读磁盘缓存（后台批量已落盘的那张），命中即替换，未命中保持原图——绝不在看时跑 ncnn。
                // key 必须与写入侧 PreloadSrQueue 完全一致，故用共用的 logicalKey。
                if (pageRef == null) {
                    srBmp = null
                    return@LaunchedEffect
                }
                srBmp = withContext(Dispatchers.IO) {
                    SrDiskCache.get(srModel.key, pageRef.logicalKey(srModel, autoCropEnabled))
                }
            } else {
                // 后台预超分正在跑时暂停实时推理：两条路共用一把 native 锁，叠加只会排队发烫卡顿。
                if (SrProgressStore.isAnyRunning()) {
                    SrTrace.mark("REALTIME skip(bg running) path=$path p=$pageIndex")
                    srBmp = null
                    return@LaunchedEffect
                }
                // 回退：总开关关闭时走原实时超分。
                val cacheKey = "$path|${srModel.key}|$autoCropEnabled"
                srManager.cached(cacheKey)?.let { srBmp = it } ?: run {
                    val result = withContext(Dispatchers.IO) { context.imageLoader.execute(request) }
                    val raw = (result.image as? BitmapImage)?.bitmap
                    val src = if (raw != null && autoCropEnabled) BitmapCropper.crop(raw) else raw
                    if (src != null) {
                        val out = srManager.process(src, cacheKey, srModel)
                        if (out !== src) srBmp = out
                    }
                }
            }
        }
        // 用户定稿：不按屏宽比例预留整页高度（长条漫会被固定高度限制住）。
        // 加载中只给一条很矮的提示条；图到了按真实宽高比（ContentScale.FillWidth）在自己位置往下自然延伸。
        // 已加载好的上方页高度锁死（稳定 key + imageReady 锁存 + 攒批发布），新图只在自己位置往下长，不动上面的页。
        val pendingHeight = 96.dp
        val showSr = srBmp != null && !previewOriginal
        // 图片内容的透明度：gate 开时，未成功前保持 0（提示条在下面占位），成功后淡入，不再反复横跳。
        // gate 关时恒为 1f，回到改造前的"图直接铺、无占位"行为。
        val contentAlpha by animateFloatAsState(
            targetValue = if (!READER_LOAD_STABILITY_V2 || imageReady || showSr) 1f else 0f,
            animationSpec = tween(if (READER_LOAD_STABILITY_V2) 180 else 0),
            label = "networkPageAlpha",
        )
        Box(
            Modifier.fillMaxWidth().wrapContentHeight()
                // 点图只用于失败重试；单图放大已改为外层两指捏合就地缩放。
                .then(if (imageFailed) Modifier.clickable {
                    imageFailed = false
                    imageReady = false
                    retryToken++
                } else Modifier),
            contentAlignment = Alignment.TopCenter,
        ) {
            // 加载中提示条：只占很矮一条，不按屏宽比例预留整页高度；图到了在自己位置按真实比例往下长。
            if (READER_LOAD_STABILITY_V2 && !imageReady && !imageFailed && !showSr) {
                Box(
                    Modifier.fillMaxWidth().height(pendingHeight)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "加载中…",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                    )
                }
            }
            if (imageFailed) {
                // 失败块与加载提示条同高（gate 开时），避免"失败→320dp→重试→真实高度"来回顶。
                Box(
                    Modifier.fillMaxWidth()
                        .height(if (READER_LOAD_STABILITY_V2) pendingHeight else 320.dp)
                        .background(Color.Black)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "这一页加载失败\n轻触重试",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp,
                    )
                }
            }
            // gate 开且已有超分图：只画这一张，替换原图，避免两张 Image 叠加时宽高比不一致互相顶。
            if (READER_LOAD_STABILITY_V2 && showSr) {
                srBmp?.let { enhanced ->
                    Image(
                        bitmap = enhanced.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = contentAlpha },
                    )
                }
            } else {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    onState = { state ->
                        if (READER_LOAD_STABILITY_V2) {
                            // 去抖：只认成功/失败这两个终态；Loading 不再把已失败/已成功的页打回去。
                            when (state) {
                                is AsyncImagePainter.State.Success -> {
                                    imageReady = true
                                    imageFailed = false
                                }
                                is AsyncImagePainter.State.Error -> if (!imageReady) imageFailed = true
                                else -> Unit
                            }
                        } else {
                            if (state is AsyncImagePainter.State.Error) imageFailed = true
                            if (state is AsyncImagePainter.State.Loading) imageFailed = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = contentAlpha },
                )
                // 回滚分支（gate 关）：恢复改造前的"原图上叠加超分图"行为，保证一键回滚不丢超分。
                if (!READER_LOAD_STABILITY_V2 && !previewOriginal) {
                    srBmp?.let { enhanced ->
                        Image(
                            bitmap = enhanced.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        return
    }
    var bmp by remember(path, pageIndex, isPdf, pdfPath) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(path, pageIndex) { mutableStateOf(false) }
    LaunchedEffect(path, pageIndex, isPdf, pdfPath) {
        bmp = withContext(Dispatchers.IO) {
            if (isPdf && pdfPath.isNotBlank()) {
                // PDF 实时：直接打开原文件，只渲染当前这一页，绝不整本预渲染；页索引来自 path
                PdfLive.render(context, pdfPath, pageIndex, 1600, 2400)
            } else {
                cache.bitmap(path, 1600, 2400)
            }
        }
        failed = bmp == null
    }
    // 本地图片 / PDF 漫画：超分覆盖层同样叠加，和漫画网络图共用同一套模型与开关。
    var srBmp by remember(path, pageIndex, isPdf, pdfPath, srEnabled, srModel, autoCropEnabled, preloadSrEnabled) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(bmp, path, pageIndex, isPdf, pdfPath, srEnabled, srModel, autoCropEnabled, pageRef, preloadSrEnabled) {
        if (!srEnabled || srManager == null) {
            srBmp = null
            return@LaunchedEffect
        }
        if (preloadSrEnabled) {
            // 预超分模式：只读磁盘缓存，命中替换，未命中保持原图，不在看时跑 ncnn。
            if (pageRef == null) {
                srBmp = null
                return@LaunchedEffect
            }
            srBmp = withContext(Dispatchers.IO) {
                SrDiskCache.get(srModel.key, pageRef.logicalKey(srModel, autoCropEnabled))
            }
        } else {
            // 后台预超分正在跑时暂停实时推理，避免叠加抢锁把手机跑满。
            if (SrProgressStore.isAnyRunning()) {
                SrTrace.mark("REALTIME skip(bg running) path=$path p=$pageIndex")
                srBmp = null
                return@LaunchedEffect
            }
            // 回退：总开关关闭时走原实时超分。
            val src = bmp ?: run {
                srBmp = null
                return@LaunchedEffect
            }
            val cacheKey = "$path|$pdfPath|${srModel.key}|$autoCropEnabled"
            srManager.cached(cacheKey)?.let { srBmp = it } ?: run {
                val cropped = if (autoCropEnabled) BitmapCropper.crop(src) else src
                val out = srManager.process(cropped, cacheKey, srModel)
                if (out !== cropped) srBmp = out
            }
        }
    }
    Box(Modifier.fillMaxWidth().wrapContentHeight(), contentAlignment = Alignment.Center) {
        val image = bmp
        if (image != null) {
            // 比例锁死：用 bitmap 真实宽高比计算高度，ContentScale.Fit 保证完整显示不裁切不压缩。
            // 缩放只改变显示宽度占比，不动宽高比，故任何 scale 下都不会变形。
            val aspect = image.width.toFloat().coerceAtLeast(1f) / image.height.toFloat().coerceAtLeast(1f)
            val widthFraction = (if (scale >= 1f) 1f else scale).coerceIn(0.5f, 1f)
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(widthFraction).aspectRatio(aspect),
            )
            if (!previewOriginal) {
                srBmp?.let { enhanced ->
                    Image(
                        bitmap = enhanced.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(widthFraction).aspectRatio(aspect),
                    )
                }
            }
        } else {
            // 加载中 / 失败占位：不崩溃
            Box(
                Modifier.fillMaxWidth().height(320.dp).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (failed) "这一页加载失败，轻触重试" else "加载中…",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                )
            }
        }
    }
}

/** 换源-本地来源：同一本漫画来自多个书源时点选其一重新加载目录与正文 */
@Composable
private fun ComicSourceListSheet(
    show: Boolean,
    origins: List<com.yg.comic.source.SourceOrigin>,
    current: Int,
    onSelect: (Int) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(show = show, title = "换源（${origins.size} 个来源）", onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("点选来源即可重新加载目录和正文", color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize, modifier = Modifier.weight(1f))
            TextButton(text = "联网搜源", onClick = onRefresh)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            itemsIndexed(origins) { i, origin ->
                val selected = i == current
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .pointerInput(i) { detectTapGestures { onSelect(i) } }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            origin.sourceName.ifBlank { "来源 ${i + 1}" },
                            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = MiuixTheme.textStyles.body1.fontSize, maxLines = 1,
                        )
                        Text(
                            origin.bookUrl,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize, maxLines = 1,
                        )
                    }
                    if (selected) {
                        Text("当前", color = MiuixTheme.colorScheme.primary,
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** 换源-联网：按书名+作者搜所有启用漫画源的同名结果，点选后加入来源列表并切换 */
@Composable
private fun ComicSwitchSourceSheet(
    show: Boolean,
    loading: Boolean,
    msg: String,
    candidates: List<ReaderViewModel.SwitchCandidate>,
    hasLocalSources: Boolean,
    onSelect: (ReaderViewModel.SwitchCandidate) -> Unit,
    onShowLocal: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(show = show, title = "联网换源（同名同作者漫画）", onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (loading) "正在搜索…" else msg,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                modifier = Modifier.weight(1f),
            )
            if (hasLocalSources) {
                TextButton(text = "已收录来源", onClick = onShowLocal)
            }
            TextButton(text = if (loading) "搜索中" else "重新搜索", onClick = { if (!loading) onRefresh() })
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            itemsIndexed(candidates) { _, c ->
                Card(
                    Modifier.fillMaxWidth()
                        .pointerInput(c.bookUrl) { detectTapGestures { onSelect(c) } }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            AsyncImage(
                                model = c.coverUrl.ifBlank { null },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(64.dp)
                                    .height(90.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MiuixTheme.colorScheme.surfaceVariant),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.name.ifBlank { "未命名" },
                                    color = MiuixTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = MiuixTheme.textStyles.title4.fontSize,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                if (c.author.isNotBlank()) Text(
                                    "作者：${c.author}",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val meta = buildString {
                                    if (c.kind.isNotBlank()) append(c.kind)
                                    if (c.wordCount.isNotBlank()) {
                                        if (isNotBlank()) append("  ·  ")
                                        append(c.wordCount)
                                    }
                                }
                                if (meta.isNotBlank()) Text(
                                    meta,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (c.lastChapter.isNotBlank()) Text(
                                    "最新：${c.lastChapter}",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = MiuixTheme.textStyles.footnote1.fontSize, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (c.intro.isNotBlank()) Text(
                            c.intro,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        val originLabel = if (c.moreSources.isNotEmpty()) {
                            "来源：${c.sourceName.ifBlank { "未知源" }} 等 ${c.moreSources.size + 1} 个源"
                        } else {
                            "来源：${c.sourceName.ifBlank { "未知源" }}"
                        }
                        Text(
                            originLabel,
                            color = MiuixTheme.colorScheme.primary,
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
