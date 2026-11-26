package com.yg.comic.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yg.comic.data.BookRepository
import com.yg.comic.data.SettingsStore
import com.yg.comic.engine.ReaderChapterLayout
import com.yg.comic.engine.ReaderLayoutSpec
import com.yg.comic.engine.ChineseVariant as VariantEngine
import com.yg.comic.engine.NovelFontResolver
import com.yg.comic.engine.TxtLive
import com.yg.comic.model.ChineseVariant
import com.yg.comic.model.NovelFont
import com.yg.comic.model.NovelSettings
import com.yg.comic.model.NovelThemes
import com.yg.comic.model.NovelWeight
import com.yg.comic.model.PageAnimation
import com.yg.comic.model.ReplaceRule
import com.yg.comic.reader.page.PageSelectionIndex
import com.yg.comic.reader.page.TextPos
import com.yg.comic.reader.page.buildSelectionIndex
import coil3.compose.AsyncImage
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import com.yg.comic.ui.components.DialogActions
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.TextView
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.Gravity
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.app.SearchManager
import android.os.Build
import kotlin.math.roundToInt

/**
 * 小说阅读器（从零重写）：独立 ViewModel，进度=章节索引+字符偏移。
 * 进入时以「已恢复进度页」直接创建翻页容器，零程序化滚动；只有用户手势翻页才保存进度。
 */
@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
@Composable
fun NovelReaderScreen(
    repo: BookRepository,
    store: SettingsStore,
    bookId: Long,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit = {},
    onOpenTtsSettings: () -> Unit = {},
    vm: NovelReaderViewModel = viewModel { NovelReaderViewModel(repo, store, bookId) },
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    // 封面展开 container transform：sharedBounds 的 key 绑在从首帧就在的稳定共享层上，
    // 书架小封面 → 全屏这段由 Compose 的 bounds 插值自动完成。
    // 飞行期发白/发灰的根因：小说端共享层里只有「转圈/透明正文」没有实色内容，
    // 飞行时 overlay 里就透出退场书架和 Scaffold 的浅色底 → 整屏发白；
    // 而之前把阅读主题色画在共享节点上，又变成盖住封面的黑灰/白层。
    // 正解（对齐漫画）：共享层里放一张和书架同 key 的封面图铺满，飞行中 overlay 里就是封面在放大；
    // 正文照常后台组合加载（满足"先加载好"），飞完再淡入盖在封面上。
    val outerNs by vm.settings.collectAsState()
    val outerBg = Color(outerNs.background)
    val outerShared = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = "cover-${bookId}"),
                animatedVisibilityScope = animatedVisibilityScope,
                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(14.dp)),
            )
        }
    } else Modifier
    // 封面的本地路径（和书架 coverSharedModifier 同一个 model，命中同一份 Coil 缓存）
    var coverPath by remember(bookId) { mutableStateOf<String?>(null) }
    LaunchedEffect(bookId) {
        coverPath = repo.books.first()
            .firstOrNull { it.id == bookId }?.coverPath
    }
    // 飞完(≈NAV_MS 240ms)再做交叉淡入：封面图淡出、阅读主题底和正文淡入。
    // 根容器固定不透明黑底（和漫画一致、不随设置变，杜绝浅色 Scaffold 透出的白和深灰→白突变），
    // 它在共享节点之外、不进 overlay，不会盖住飞行中的封面。
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        reveal.animateTo(1f, animationSpec = tween(200))
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 阅读主题底：共享节点之外，飞完淡入，把设置异步读到的「深灰→白」变化藏进淡入过程。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal.value }
                .background(outerBg)
        )
        // 共享层：从首帧绑 key 作封面飞行落点，overlay 里只有它。
        // 对齐漫画那套丝滑做法：enter 端只放不透明黑底，不另画一张封面图。
        // 之前这里额外画了和书架同款的封面图，和 exit 端正在 fadeOut 的书架封面重叠，
        // 看着就是「两个封面一起飞、且有一个不淡化」。现在书架封面飞过来淡出、黑底承接，
        // 正文在黑底上渐显，飞行期只剩一个正确的封面放大动画。
        Box(outerShared.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
            // 正文飞行期照常组合加载、alpha=0 不可见，落位后淡入盖在黑底上。
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value }) {
                NovelReaderContent(repo, store, bookId, onBack, onOpenBook, onOpenTtsSettings, vm)
            }
        }
    }
}

@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
@Composable
private fun NovelReaderContent(
    repo: BookRepository,
    store: SettingsStore,
    bookId: Long,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenTtsSettings: () -> Unit,
    vm: NovelReaderViewModel,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val rawText by vm.rawText.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val title by vm.title.collectAsState()
    val ns by vm.settings.collectAsState()
    val settingsLoaded by vm.settingsLoaded.collectAsState()
    val savedProgress by vm.savedProgress.collectAsState()
    val netStatus by vm.netStatus.collectAsState()
    val origins by vm.origins.collectAsState()
    val sourceIndex by vm.sourceIndex.collectAsState()
    val switchCandidates by vm.switchCandidates.collectAsState()
    val switchLoading by vm.switchLoading.collectAsState()
    val switchMsg by vm.switchMsg.collectAsState()
    // 换源代数/恢复章：换源成功后 epoch 递增，用来强制重置分页状态并定位到恢复章
    val switchEpoch by vm.switchEpoch.collectAsState()
    val resumeIndex by vm.resumeIndex.collectAsState()
    val chapterReversed by vm.chapterReversed.collectAsState()
    val smartTocSort by vm.smartTocSort.collectAsState()
    val tocDisplayOrder by vm.tocDisplayOrder.collectAsState()
    // 每章是否「卷标题」（平行于 chapters 的下标）：卷只当分组标题，不是可读章、不占落点。
    val chapterVolumes by vm.chapterVolumes.collectAsState()
    // 章节显示用：原始章号 → 用户看到的「第几章」（卷不算号），以及可读章总数。
    val chapterNoInfo = remember(chapters, chapterVolumes) {
        var n = 0
        val numbers = ArrayList<Int>(chapters.size)
        for (i in chapters.indices) {
            if (chapterVolumes.getOrNull(i) == true) numbers.add(0) else { n += 1; numbers.add(n) }
        }
        numbers to n
    }
    // 翻章方向：反序展示时用户往下读=原始章号递减，正序时=递增。
    // currentChapterIndex 内部始终是原始章号（saveProgress/page.chapterIndex 语义不变），这里只改导航方向。
    val chapterStep = if (chapterReversed) -1 else 1
    // 书签（字锚点）：Flow 来自 Repository，按 bookId 过滤
    val bookmarks by repo.bookmarks(bookId).collectAsState(emptyList())
    // 朗读逐字高亮：TTS 播放状态与当前朗读到的字符区间
    val aloudPos by vm.aloudPos.collectAsState()
    val aloudPlaying by vm.aloudPlaying.collectAsState()

    var chromeVisible by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var showSwitchSheet by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    // 书签跳转：目标章的字符偏移，分页派生后定位到含该偏移的页
    var pendingBookmarkOffset by remember { mutableStateOf<Int?>(null) }
    // “加为替换”编辑弹窗：预填选中文字为匹配内容，用户可改匹配/替换为
    var showReplaceEditor by remember { mutableStateOf(false) }
    var replacePattern by remember { mutableStateOf("") }
    var replaceReplacement by remember { mutableStateOf("") }
    // 书内搜索：query 高亮当前页命中；命中列表支持上/下一个并跳页
    var showBookSearch by remember { mutableStateOf(false) }
    var bookSearchQuery by remember { mutableStateOf("") }
    // 用于驱动高亮的 query（渲染端按当前简繁转换，未提交时也可实时高亮）
    var activeSearchQuery by remember { mutableStateOf("") }
    // 命中：章内字符绝对偏移列表 + 当前下标；跳转用 pendingSearchOffset
    var searchHits by remember { mutableStateOf<List<Int>>(emptyList()) }
    var currentHitIdx by remember { mutableIntStateOf(0) }
    var pendingSearchOffset by remember { mutableStateOf<Int?>(null) }

    val paged = ns.animation != PageAnimation.SCROLL

    val textColor = Color(ns.textColor)
    val fontWeight = when (ns.fontWeight) {
        NovelWeight.MEDIUM -> FontWeight.Normal
        NovelWeight.BOLD -> FontWeight.Bold
        NovelWeight.LIGHT -> FontWeight.Light
    }
    val fontFamily = NovelFontResolver.resolve(ns.font)

    // 沉浸式全屏：进入阅读器隐藏状态栏和导航条，只剩文字；退出恢复。
    // 点出控制栏(chromeVisible)时临时显示系统栏，便于用户看到时间/信号并正常操作。
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember {
        var ctx: android.content.Context = context
        while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) ctx = ctx.baseContext
        ctx as? android.app.Activity
    }
    val insetsController = remember(activity, view) {
        activity?.let { WindowCompat.getInsetsController(it.window, view) }
    }
    DisposableEffect(chromeVisible) {
        val c = insetsController
        if (chromeVisible) {
            c?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    // 退出阅读器时务必恢复系统栏
    DisposableEffect(Unit) {
        onDispose { insetsController?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // 高度预算：顶部避让摄像头挖孔(displayCutout)；底部预留「版底信息栏」+ 屏幕最底手势安全区，
    // 让正文最后一行与信息栏都抬离屏幕最底那条手势提示，不被压住。
    val footerHeightDp = 30f
    // 屏幕最底安全区：沉浸隐藏导航栏后 systemBars 底为 0，用固定值兜底手势条区域。
    val bottomSafePx = maxOf(WindowInsets.navigationBars.getBottom(density).toFloat(), with(density) { 20.dp.toPx() })
    val cutoutTopPx = WindowInsets.displayCutout.getTop(density)
    // 正文可视高度：必须与「渲染容器真实高度」严格一致，否则分页会把边界行算进本页，
    // 而渲染时该行落到容器外被裁成半行残影（翻页后它又是下一页完整首行 → 视觉上「重复」）。
    // 因此不再手算 screenHeight-cutout-footer-bottomSafe，而是由渲染处 BoxWithConstraints 实测回填。
    // 首帧用保守估值先分页，实测到达后（remember key 含 measuredHeight）自动重分页。
    var measuredContentHeightPx by remember { mutableFloatStateOf(0f) }
    val estimatedHeightPx = (with(density) { configuration.screenHeightDp.dp.toPx() }
        - cutoutTopPx
        - with(density) { footerHeightDp.dp.toPx() }
        - bottomSafePx
        - with(density) { 8.dp.toPx() }
        - with(density) { ns.verticalMargin.dp.toPx() * 2 })
        .coerceAtLeast(240f)
    val usableHeightPx = if (measuredContentHeightPx > 0f) measuredContentHeightPx else estimatedHeightPx
    // 正文排版宽度：先把「屏宽 - 两倍边距」按整数个字符宽向下取整。
    // 关键：Compose 的 TextAlign.Justify 对中文（词间无空格）不做字间距拉伸，若版心宽度不是
    // 字符宽的整数倍，每行末尾会残留「不足一个字的余量」→ 视觉上右侧永远比左侧多一块空白
    // （用户报的「左右留的距离不一致 / 整页偏左上」）。取整到整字宽后，每行恰好排满版心右缘，
    // 再把版心在屏幕内水平居中（PageText 内 leftPad=(容器-版心)/2），左右留白严格相等。
    val rCharW = with(density) { (ns.fontSize.sp.toPx() + ns.letterSpacing.sp.toPx()) }.coerceAtLeast(1f)
    val fontSizePx = with(density) { ns.fontSize.sp.toPx() }
    val usableWidthPx = run {
        val raw = with(density) { (configuration.screenWidthDp.dp - ns.sideMargin.dp * 2).toPx() }.coerceAtLeast(160f)
        val cols = (raw / rCharW).toInt().coerceAtLeast(1)
        (cols * rCharW).coerceAtLeast(160f)
    }
    val paraSpacingPx = with(density) { ns.paragraphSpacing.dp.toPx() }
    val layout = NovelLayout(usableWidthPx, usableHeightPx, fontSizePx, ns.lineHeightMul, paraSpacingPx, ns.indentFirstLine)
    val pageCapacity = run {
        val font = fontSizePx.coerceAtLeast(8f)
        val lineH = (font * ns.lineHeightMul.coerceIn(1.0f, 4.5f)).coerceAtLeast(8f)
        ((usableWidthPx / font).coerceAtLeast(10f) * (usableHeightPx / lineH).coerceAtLeast(6f)).toInt().coerceIn(12, 6000)
    }

    // 用 Compose 真实文本测量分页：屏幕放几个字就是几个字，与渲染逐字一致，永不溢出/串行。
    val textMeasurer = rememberTextMeasurer()
    val measureStyle = remember(ns.font, ns.fontWeight, ns.fontSize, ns.lineHeightMul, ns.letterSpacing) {
        TextStyle(
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            fontSize = ns.fontSize.sp,
            lineHeight = (ns.fontSize * ns.lineHeightMul).sp,
            // 关键：固定 lineHeight 时 Compose 默认把额外行高按 font metrics 分布到行间，
            // 导致 measure 的 getLineTop 行距 != 实际 Text 渲染行距（大字号下越往下偏差越大 → 行重叠/标题叠字）。
            // 用 Trim.None + Center 强制每行行高恰为 lineHeight，measure 与 render 严格一致。
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
            ),
            letterSpacing = ns.letterSpacing.sp,
            textAlign = TextAlign.Justify,
        )
    }
    val pageHeightBudget = usableHeightPx.toInt()

    // 移植自 novel-sound 的整段排版引擎：整段排版成行、分页只按行切片、渲染整段裁剪窗口。
    val gapPx = paraSpacingPx.toInt().coerceAtLeast(0)
    val traditional = ns.variant == ChineseVariant.TRADITIONAL
    val layoutSpec = remember(usableWidthPx, usableHeightPx, paraSpacingPx, ns.indentFirstLine) {
        ReaderLayoutSpec(
            width = usableWidthPx,
            pageHeight = usableHeightPx,
            paragraphSpacing = paraSpacingPx,
            firstLineIndent = ns.indentFirstLine,
            justifyText = true,
        )
    }
    // measure/render 同源：测量回调用真实 TextMeasurer，样式与文本变换跟渲染端 PageText 完全一致，
    // 这样 TextLayoutResult 的每行边界 == 渲染 Text 的每行边界。
    val measureBlock: (String, Boolean, Boolean) -> TextLayoutResult =
        remember(textMeasurer, measureStyle, usableWidthPx, traditional) {
            { raw, isTitle, indent ->
                val shown = novelDisplay(raw, indent, traditional)
                // 关键：与 novel-sound `painter.layout(minWidth: width, maxWidth: width)` 一致——固定宽度约束
                // （minWidth == maxWidth）。只给 maxWidth 时，TextAlign.Justify 对「没排满的行」不会拉满整宽，
                // 而按实际字数缩窄，段落中间行/首行缩进行就会显示成左右缩进、看似居中的窄行（本 bug 根因）。
                val w = usableWidthPx.toInt().coerceAtLeast(1)
                textMeasurer.measure(
                    AnnotatedString(shown),
                    style = novelStyle(measureStyle, isTitle),
                    constraints = Constraints(minWidth = w, maxWidth = w),
                )
            }
        }
    // 只分页「当前章」，避免整书(几十万字)逐段测量卡死。进度用「章节索引 + 章内字符偏移」。
    val chapterCount = chapters.size
    // switchEpoch 作为 key：换源成功后 VM 递增 epoch，这里强制重新定位到 resumeIndex（恢复章）。
    // 不加 epoch 的话，新源章节数与旧源相同时 key 不变、状态不重置，看起来"换源没生效"。
    // savedProgress 加入 key：VM init 里进度(_savedProgress)与目录(_chapters)异步分批到达，
    // 进度晚于目录一帧到达时，旧 key 不含进度 → currentChapterIndex 初值永久停在 0（每次进书回章首）。
    // savedProgress 会话内只在 init 赋值一次，用户翻章 currentChapterIndex+=1 是 var 直接改、不受 remember 重置影响。
    var currentChapterIndex by remember(chapterCount, settingsLoaded, switchEpoch, savedProgress) {
        mutableIntStateOf(
            // 落点校正：进度/恢复章落在卷标题上时挪到最近的可读章，避免开出空章。
            if (switchEpoch > 0) vm.snapReadable(resumeIndex.coerceIn(0, (chapterCount - 1).coerceAtLeast(0)), chapterStep)
            else vm.snapReadable(savedProgress?.pageIndex?.coerceIn(0, (chapterCount - 1).coerceAtLeast(0)) ?: 0, chapterStep)
        )
    }
    // 当前章分页（仅这一章，秒出）
    val pages: List<NovelPage> = remember(rawText, chapters, currentChapterIndex, layoutSpec, measureBlock) {
        if (!settingsLoaded || rawText.isBlank() || chapters.isEmpty()) emptyList()
        else {
            val ch = chapters[currentChapterIndex.coerceIn(0, chapters.size - 1)]
            paginateChapterLaidOut(
                TxtLive.chapterText(rawText, ch), currentChapterIndex, ch.title,
                layoutSpec, measureBlock,
                { raw, ind -> novelDisplay(raw, ind, traditional) },
            )
        }
    }

    if (!loaded || pages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!loaded) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    if (netStatus.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(netStatus, color = textColor, textAlign = TextAlign.Center)
                    }
                }
            } else Text(netStatus.ifBlank { "这本书没有可显示的文本" }, color = textColor, textAlign = TextAlign.Center)
        }
        return
    }

    // 初始页：恢复进度时若同章则按「章内首字符偏移」反查所在页（对齐 legado durChapterPos，
    // 字号/行距/换屏改变页数后仍定位到同一处文本），否则第0页。
    // 换源(epoch 变化)后不从旧 savedProgress 恢复页码，直接从第0页开始看新源这一章。
    val initialPage = remember(currentChapterIndex, pages, switchEpoch) {
        val sp = savedProgress
        if (switchEpoch == 0 && sp != null && sp.pageIndex == currentChapterIndex && sp.chapterOffset > 0) {
            var p = 0
            for (i in pages.indices) {
                if (pages[i].startOffset <= sp.chapterOffset) p = i else break
            }
            p
        } else 0
    }
    // 滚动模式的段落块数量（标题段 + 正文段）。用于把「页号」与「段落索引」互相换算，
    // 保证翻页/滚动两种模式共用同一份进度语义（都存「章内页号」），跨模式恢复不会串位。
    val scrollBlocks = remember(rawText, chapters, currentChapterIndex) {
        if (settingsLoaded && rawText.isNotBlank() && chapters.isNotEmpty()) {
            val ch = chapters[currentChapterIndex.coerceIn(0, chapters.size - 1)]
            val paras = TxtLive.toParagraphs(TxtLive.chapterText(rawText, ch), autoPara = true)
            val tNorm = ch.title.replace("\u3000", " ").trim()
            val firstNorm = paras.firstOrNull()?.replace("\u3000", " ")?.trim()
            val body = if (firstNorm != null && (firstNorm == tNorm || (tNorm.isNotEmpty() && firstNorm.contains(tNorm))))
                paras.drop(1) else paras
            buildList<Pair<String, Boolean>> {
                if (ch.title.isNotBlank()) add(ch.title to true)
                body.forEach { add(it to false) }
            }
        } else emptyList()
    }
    val scrollCount = scrollBlocks.size
    // 页号 ↔ 段落索引 的比例换算（两端口径统一，端点夹紧）。
    fun pageToPara(page: Int): Int {
        if (scrollCount <= 0) return 0
        val n = pages.size.coerceAtLeast(1)
        return if (n <= 1) 0 else ((page.toFloat() / (n - 1)) * (scrollCount - 1)).toInt().coerceIn(0, scrollCount - 1)
    }
    fun paraToPage(para: Int): Int {
        val n = pages.size.coerceAtLeast(1)
        if (scrollCount <= 1 || n <= 1) return 0
        return ((para.toFloat() / (scrollCount - 1)) * (n - 1)).toInt().coerceIn(0, n - 1)
    }
    // 无缝跨章：pager 里在「当前章所有页」之后拼上「下一章第 0 页」作为额外一页。
    // 用户滑过最后一页即直接看到下一章正文（无提示界面），再触发切章重置到新章第 0 页——视觉连续。
    // 翻章方向（chapterStep）在上方随反序状态定义；下一章走「最近的可读章」，卷标题不占落点。
    val nextChapterFirstPage: NovelPage? = remember(pages, currentChapterIndex, chapterStep, chapterCount, chapterVolumes, layoutSpec, measureBlock) {
        val nextIdx = vm.nextReadableChapter(currentChapterIndex, chapterStep)
        if (nextIdx >= 0) {
            val nch = chapters[nextIdx]
            paginateChapterLaidOut(
                TxtLive.chapterText(rawText, nch), nextIdx, nch.title,
                layoutSpec, measureBlock,
                { raw, ind -> novelDisplay(raw, ind, traditional) },
            ).firstOrNull()
        } else null
    }
    // 渲染序列：当前章所有页 + （可选）下一章首屏
    val displayPages: List<NovelPage> = remember(pages, nextChapterFirstPage) {
        if (nextChapterFirstPage != null) pages + nextChapterFirstPage else pages
    }
    // switchEpoch 作为 key：换源后即使页数和旧源相同也重建 pager/list 状态，避免停在旧页看起来"没换源"。
    val pagerState: PagerState = remember(displayPages.size, switchEpoch) { PagerState(currentPage = initialPage.coerceIn(0, (displayPages.size - 1).coerceAtLeast(0))) { displayPages.size } }
    val listState: LazyListState = remember(pages, scrollCount, switchEpoch) { LazyListState(firstVisibleItemIndex = pageToPara(initialPage)) }

    // 章内进度一次性恢复：pagerState/listState 的 remember key 不含 initialPage，
    // 进度异步晚到使 initialPage 变化时 pager 不会重建、停在第0页（用户症状"每次进去都是第一个页"）。
    // 这里在「进度 + 目录 + 分页」都就绪后主动定位一次；restoreKey 守门，避免用户后续翻页被反复拉回。
    val restoreKey = Triple(savedProgress?.pageIndex, savedProgress?.chapterOffset, switchEpoch)
    var progressRestored by remember(vm) { mutableStateOf(false) }
    LaunchedEffect(restoreKey, pages, displayPages.size) {
        if (progressRestored) return@LaunchedEffect
        val sp = savedProgress
        if (sp == null || switchEpoch > 0 || pages.isEmpty() || currentChapterIndex != sp.pageIndex) return@LaunchedEffect
        val target = initialPage
        if (target > 0 && pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
            listState.scrollToItem(pageToPara(target))
        }
        progressRestored = true
    }

    val currentPager = pagerState.currentPage.coerceIn(0, (displayPages.size - 1).coerceAtLeast(0))

    // ===== 朗读（系统 TTS）=====
    // 从整章所有页里取朗读片段（每段=一个段落 block，文本和渲染同源，高亮才对得上）
    fun chapterAloudSegments(): List<NovelReaderViewModel.AloudSegment> {
        val segs = ArrayList<NovelReaderViewModel.AloudSegment>()
        val seen = HashSet<Int>()
        for (p in pages) {
            val rp = p.readerPage ?: continue
            for (frag in rp.fragments) {
                val block = frag.block
                if (!seen.add(block.start)) continue
                val shown = block.charBoxes.sortedBy { it.charIndex }.joinToString("") { it.charData }
                if (shown.isNotBlank()) segs.add(NovelReaderViewModel.AloudSegment(block.start, shown))
            }
        }
        return segs
    }

    var spokenChapter by remember(vm) { mutableIntStateOf(-1) }
    // 朗读语速已纳入朗读配置，由 VM 从当前生效配置派生，这里不再用本地值覆盖
    // 已经在朗读时，换到新章（手动翻章或念完自动翻）就自动接着念，不用用户再按一次
    LaunchedEffect(aloudPlaying, currentChapterIndex, pages.size) {
        if (!aloudPlaying) {
            spokenChapter = -1
            return@LaunchedEffect
        }
        if (spokenChapter == currentChapterIndex || pages.isEmpty()) return@LaunchedEffect
        val segs = chapterAloudSegments()
        if (segs.isEmpty()) {
            vm.stopAloud()
            return@LaunchedEffect
        }
        spokenChapter = currentChapterIndex
        vm.startAloud(segs)
    }

    // 念完当前章的最后一拍 → 自动进入下一章继续念；全书念完就停
    val aloudDone by vm.aloudChapterDone.collectAsState()
    LaunchedEffect(aloudDone) {
        if (aloudDone == 0 || !aloudPlaying) return@LaunchedEffect
        val nextIdx = vm.nextReadableChapter(currentChapterIndex, chapterStep)
        if (nextIdx >= 0) {
            currentChapterIndex = nextIdx
        } else {
            vm.stopAloud()
        }
    }

    // 跟着朗读高亮自动翻页（只翻到当前页所在的章内页，不越过章边界）
    LaunchedEffect(aloudPos?.blockStart) {
        val bs = aloudPos?.blockStart ?: return@LaunchedEffect
        var target = -1
        for (i in pages.indices) {
            val rp = pages[i].readerPage ?: continue
            if (rp.fragments.any { it.block.start == bs }) {
                target = i
                break
            }
        }
        if (target >= 0 && target != pagerState.currentPage) {
            runCatching { pagerState.scrollToPage(target) }
        }
    }

    // TTS 没就绪 / 没中文语音包：冒个泡提示，别静默
    val aloudErr by vm.aloudError.collectAsState()
    LaunchedEffect(aloudErr) {
        val msg = aloudErr ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        vm.clearAloudError()
    }
    // 改字号/重排后 pages.size 会变化，旧页码可能越界——主动把 pager 夹回合法范围，避免中间帧显示「34/11」这类越界页码。
    LaunchedEffect(displayPages.size) {
        val maxIndex = (displayPages.size - 1).coerceAtLeast(0)
        if (pagerState.currentPage > maxIndex) pagerState.scrollToPage(maxIndex)
    }
    val currentSafe = currentPager.coerceIn(0, pages.size - 1)
    val curChapterTitle = pages.getOrNull(currentSafe)?.chapterTitle ?: ""

    // 用户手势翻页结束才保存；滑到「下一章首屏」这一页则切章到下一章第0页（无缝）
    var userDragging by remember(vm) { mutableStateOf(false) }
    fun saveHere(pageInChapter: Int) {
        // 存「章内首字符偏移」而非页号，与 legado durChapterPos 对齐：
        // 翻页/滚动模式都先拿到页号，这里统一换算成该页首字符在章内的绝对偏移再落库。
        val off = pages.getOrNull(pageInChapter.coerceIn(0, pages.lastIndex))?.startOffset ?: 0
        vm.saveProgress(NovelPage(currentChapterIndex, "", "", off))
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }.collect { (scrolling, page) ->
            if (scrolling) userDragging = true
            else if (userDragging) {
                userDragging = false
                if (page >= pages.size && nextChapterFirstPage != null) {
                    // 已滑到下一章首屏 → 切章，重置到新章第0页（新页序列与刚才那屏一致，视觉连续）
                    // 反序展示时往原始章号递减方向翻（chapterStep=-1）；卷标题跳过。
                    val nxt = vm.nextReadableChapter(currentChapterIndex, chapterStep)
                    if (nxt >= 0) {
                        currentChapterIndex = nxt
                        saveHere(0)
                    }
                } else if (page in pages.indices) {
                    saveHere(page)
                }
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }.collect { (scrolling, para) ->
            if (scrolling) userDragging = true
            else if (userDragging) { userDragging = false; saveHere(paraToPage(para)) }
        }
    }

    val scope = rememberCoroutineScope()
    fun flipPage(delta: Int) {
        val target = currentPager + delta
        if (target in 0 until displayPages.size) scope.launch { pagerState.animateScrollToPage(target) }
    }
    fun jumpTo(index: Int) {
        scope.launch { pagerState.animateScrollToPage(index.coerceIn(0, pages.size - 1)) }
    }

    // 书签跳转：分页派生完成后，按字符偏移定位到含该偏移的页（startOffset 最接近且不超过目标）
    LaunchedEffect(pages, pendingBookmarkOffset) {
        val off = pendingBookmarkOffset
        if (off != null && pages.isNotEmpty()) {
            var target = 0
            for (i in pages.indices) {
                if (pages[i].startOffset <= off) target = i else break
            }
            pagerState.scrollToPage(target.coerceIn(0, pages.size - 1))
            pendingBookmarkOffset = null
        }
    }

    // 书内搜索：在当前章正文里找所有命中（章内字符偏移），供计数与跳页。
    // 高亮由各页 PageText 依 activeSearchQuery 用坐标同源独立绘制，与此偏移基准一致（同源分页文本）。
    LaunchedEffect(bookSearchQuery, currentChapterIndex, rawText, chapters) {
        val q = bookSearchQuery
        if (q.isBlank() || currentChapterIndex !in chapters.indices) {
            searchHits = emptyList()
            activeSearchQuery = ""
            currentHitIdx = 0
            return@LaunchedEffect
        }
        val chapterText = TxtLive.chapterText(rawText, chapters[currentChapterIndex])
        val hits = ArrayList<Int>()
        var from = 0
        while (true) {
            val idx = chapterText.indexOf(q, from)
            if (idx < 0) break
            hits.add(idx)
            from = idx + q.length
            if (from >= chapterText.length) break
        }
        searchHits = hits
        activeSearchQuery = q
        currentHitIdx = 0
        if (hits.isNotEmpty()) pendingSearchOffset = hits[0]
    }

    // 书内搜索跳页：与书签跳转同构，按章内字符偏移定位含该命中的页
    LaunchedEffect(pages, pendingSearchOffset) {
        val off = pendingSearchOffset
        if (off != null && pages.isNotEmpty()) {
            var target = 0
            for (i in pages.indices) {
                if (pages[i].startOffset <= off) target = i else break
            }
            pagerState.scrollToPage(target.coerceIn(0, pages.size - 1))
            pendingSearchOffset = null
        }
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { chromeVisible = !chromeVisible })
            },
    ) {
        if (paged) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { pagerIdx ->
                val page = displayPages[pagerIdx]
                val dist = kotlin.math.abs(pagerIdx - pagerState.currentPage).coerceAtMost(1)
                val scale = when (ns.animation) {
                    PageAnimation.SIMULATION -> 1f - dist * 0.06f
                    PageAnimation.COVER -> 1f - dist * 0.03f
                    else -> 1f
                }
                val alpha = when (ns.animation) {
                    PageAnimation.SIMULATION, PageAnimation.COVER -> 1f - dist * 0.35f
                    else -> 1f
                }
                PageText(
                    page = page, ns = ns, textColor = textColor,
                    fontFamily = fontFamily, fontWeight = fontWeight, gapPx = gapPx,
                    contentWidthPx = usableWidthPx,
                    onMeasureContentHeight = { h -> measuredContentHeightPx = h },
                    measureBlock = measureBlock,
                    onCopy = { txt ->
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("选字", txt))
                        android.widget.Toast.makeText(context, "已复制：$txt", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onSearch = { txt ->
                        val intent = android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH)
                        intent.putExtra(android.app.SearchManager.QUERY, txt)
                        runCatching { context.startActivity(intent) }
                    },
                    onAddBlock = { txt ->
                        // 不再直接删字，弹出可编辑的“替换”弹窗，预填选中文字为匹配内容
                        replacePattern = txt
                        replaceReplacement = ""
                        showReplaceEditor = true
                    },
                    onAddBookmark = { txt ->
                        // 书签字锚点：章序号 + 本页首字符章内偏移；选中文字作摘要
                        scope.launch {
                            repo.addBookmark(bookId, page.chapterIndex, page.chapterTitle, page.startOffset, txt)
                        }
                        android.widget.Toast.makeText(context, "已加书签：$txt", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    searchQuery = activeSearchQuery,
                    aloudPos = aloudPos,
                    modifier = Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.displayCutout) // 仅避让摄像头挖孔
                        // 上下边距（可调）：页顶与页底对称留白，正文不贴屏幕顶。
                        // 分页高度预算已同步扣除 2*verticalMargin（estimatedHeightPx），二者一致。
                        .padding(vertical = ns.verticalMargin.dp)
                        // 底部再避让版底信息条+手势安全区。
                        .padding(bottom = (footerHeightDp + 8f).dp + with(density) { bottomSafePx.toDp() })
                        .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha },
                )
            }
        } else {
            // 滚动模式：整章连续段落（不按页切），与翻页模式共用同一套版心取整居中 + 段落样式，
            // 段间用段落间距，容器用「上下边距 + 左右对称边距」。连续滚动无页缝、长篇不会整章一次性测量爆内存。
            ScrollParagraphList(
                blocks = scrollBlocks,
                ns = ns, textColor = textColor, fontFamily = fontFamily, fontWeight = fontWeight,
                usableWidthPx = usableWidthPx,
                paraSpacingPx = paraSpacingPx,
                listState = listState,
                bottomInsetDp = (footerHeightDp + 8f) + with(density) { bottomSafePx.toDp() }.value,
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout),
            )
        }

        // 版底信息栏（常驻）：左页码/进度，右电量。左右边距与正文/额头一致，视觉对齐。
        // chromeVisible 时不显示（让位给底部控制栏）。
        if (!chromeVisible) {
            ChapterFooter(
                chapterTitle = curChapterTitle,
                pageInChapter = currentSafe + 1,
                pageCount = pages.size,
                chapters = chapters,
                currentChapterIndex = currentChapterIndex,
                color = textColor.copy(alpha = 0.5f),
                sideMarginDp = ns.sideMargin,
                heightDp = footerHeightDp,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // 顶部控制栏：黑条背景延伸到屏幕顶（edge-to-edge），只让图标文字避开状态栏和摄像头挖孔。
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
                title = title, color = Color.Transparent, titleColor = Color.White, defaultWindowInsetsPadding = false,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (aloudPlaying) {
                            vm.stopAloud()
                        } else {
                            // 整章朗读：一次把所有页的段落都排进队列，念完自动翻页/翻章
                            val segs = chapterAloudSegments()
                            if (segs.isNotEmpty()) {
                                spokenChapter = currentChapterIndex
                                vm.startAloud(segs)
                            }
                        }
                    }) {
                        Icon(
                            if (aloudPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = if (aloudPlaying) "停止朗读" else "朗读",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = {
                        // 书内搜索：进入时若已有词则保持，聚焦由弹窗内 TextField 处理
                        showBookSearch = true
                    }) {
                        Icon(Icons.Filled.Search, contentDescription = "书内搜索", tint = Color.White)
                    }
                    IconButton(onClick = { vm.searchSwitchSources(); showSwitchSheet = true }) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "换源", tint = Color.White)
                    }
                },
            )
            }
        }

        // 底部控制栏：miuix 悬浮胶囊底栏，只避让手势小白条，不再用整块黑条压到屏幕底。
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
                FloatingToolbar(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xE6000000),
                    shadowElevation = 8.dp,
                    showDivider = false,
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    "第${chapterNoInfo.first.getOrNull(currentChapterIndex) ?: (currentChapterIndex + 1)}/${chapterNoInfo.second.coerceAtLeast(1)}章" +
                        " · $curChapterTitle · ${currentSafe + 1}/${pages.size}",
                    color = Color.White,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                // 进度条单独一行，左右各留 8dp 不顶胶囊边
                Slider(
                    value = (currentSafe + 1).toFloat(),
                    onValueChange = { jumpTo(it.toInt() - 1) },
                    valueRange = 0f..(pages.size.toFloat().coerceAtLeast(1f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
                // 按钮单独一行居中、图标白色清晰、无圆底发闷
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { flipPage(-1) }, enabled = currentSafe > 0) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "上一页", tint = Color.White)
                    }
                    IconButton(onClick = { flipPage(1) }, enabled = currentSafe < pages.size - 1) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "下一页", tint = Color.White)
                    }
                    if (origins.size > 1) {
                        IconButton(onClick = { showSources = true }) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = "换源（${origins.size}）", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { showBookmarks = true }) {
                        Icon(Icons.Filled.Bookmark, contentDescription = "书签", tint = Color.White)
                    }
                    IconButton(onClick = { showChapters = true }) {
                        Icon(Icons.Filled.List, contentDescription = "章节", tint = Color.White)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "阅读设置", tint = Color.White)
                    }
                }
                    }
                }
            }
        }

        // 朗读悬浮迷你条：仅朗读中显示，复用现有朗读控制，不新造播放体系。
        AnimatedVisibility(
            aloudPlaying,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 152.dp),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            FloatingToolbar(
                color = Color(0xE6000000),
                shadowElevation = 8.dp,
                showDivider = false,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "正在朗读 · $curChapterTitle",
                        color = Color.White, fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp).padding(horizontal = 8.dp),
                    )
                    IconButton(onClick = { vm.stopAloud() }) {
                        Icon(Icons.Filled.Stop, contentDescription = "停止朗读", tint = Color.White)
                    }
                }
            }
        }

        NovelSettingsSheet(show = showSettings, ns = ns, store = store, pageCapacity = pageCapacity, totalPages = pages.size,
            onOpenTtsSettings = onOpenTtsSettings,
            onDismiss = { showSettings = false })
        ChapterListSheet(show = showChapters, chapters = chapters, currentChapterIndex = currentChapterIndex,
            reversed = chapterReversed, volumeFlags = chapterVolumes,
            order = tocDisplayOrder, smartSort = smartTocSort,
            onSmartSortChange = { vm.setSmartTocSort(it) },
            onSelect = { ci ->
                showChapters = false
                // 点到的若是卷标题（理论上不可点），也校正到最近的可读章
                currentChapterIndex = vm.snapReadable(ci.coerceIn(0, chapterCount - 1), chapterStep)
                saveHere(0)
            },
            onDismiss = { showChapters = false })
        BookmarkListSheet(show = showBookmarks, bookmarks = bookmarks,
            onSelect = { bm ->
                showBookmarks = false
                val ci = bm.chapterIndex.coerceIn(0, chapterCount - 1)
                currentChapterIndex = ci
                pendingBookmarkOffset = bm.charOffset
                saveHere(0)
            },
            onDelete = { id -> scope.launch { repo.removeBookmark(id) } },
            onDismiss = { showBookmarks = false })
        SourceListSheet(show = showSources, origins = origins, current = sourceIndex,
            onSelect = { idx -> showSources = false; vm.switchSource(idx) },
            onDismiss = { showSources = false })
        SwitchSourceSheet(show = showSwitchSheet, loading = switchLoading, msg = switchMsg,
            candidates = switchCandidates,
            onSelect = { c -> showSwitchSheet = false; vm.applySwitchCandidate(c) },
            onRefresh = { vm.searchSwitchSources() },
            onDismiss = { showSwitchSheet = false })

        // “加为替换”可编辑弹窗：按原文精确匹配，替换为留空即删除匹配文字
        if (showReplaceEditor) {
            OverlayDialog(
                show = true,
                title = "替换",
                summary = "按原文精确匹配（不含正则符号）；替换为留空即删除。",
                onDismissRequest = { showReplaceEditor = false },
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)
                ) {
                    TextField(
                        value = replacePattern,
                        onValueChange = { replacePattern = it },
                        label = "匹配内容（原文）",
                        modifier = Modifier.fillMaxWidth().height(88.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = replaceReplacement,
                        onValueChange = { replaceReplacement = it },
                        label = "替换为（留空即删除）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    DialogActions(
                        cancelText = "取消",
                        confirmText = "保存",
                        confirmEnabled = replacePattern.isNotBlank(),
                        onCancel = { showReplaceEditor = false },
                        onConfirm = {
                            val pat = replacePattern
                            val rep = replaceReplacement
                            scope.launch {
                                repo.addReplaceRule(ReplaceRule(
                                    name = pat.take(20),
                                    pattern = pat,
                                    replacement = rep,
                                    scope = "",
                                    scopeContent = true,
                                    isRegex = false,
                                ))
                                // 规则落盘后立即让已缓存正文重跑替换并刷新页面
                                vm.repurifyLoadedChapters()
                            }
                            showReplaceEditor = false
                        },
                    )
                }
            }
        }

        // 书内搜索面板：输入词 → 当前章命中 → 黄高亮 + 上/下一个跳页
        if (showBookSearch) {
            OverlayDialog(
                show = true,
                title = "书内搜索",
                summary = "在当前章查找，命中处黄色高亮",
                onDismissRequest = { showBookSearch = false },
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    TextField(
                        value = bookSearchQuery,
                        onValueChange = { bookSearchQuery = it },
                        label = "搜索词",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (bookSearchQuery.isBlank()) "输入关键词搜索本章"
                               else "命中 ${if (searchHits.isEmpty()) 0 else currentHitIdx + 1} / ${searchHits.size}",
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            text = "上一个",
                            color = if (searchHits.isNotEmpty()) Color(0xFF1976D2) else Color.Gray,
                            modifier = Modifier.weight(1f).clickable {
                                if (searchHits.isNotEmpty()) {
                                    currentHitIdx = (currentHitIdx - 1 + searchHits.size) % searchHits.size
                                    pendingSearchOffset = searchHits[currentHitIdx]
                                }
                            }.padding(vertical = 10.dp),
                        )
                        Text(
                            text = "下一个",
                            color = if (searchHits.isNotEmpty()) Color(0xFF1976D2) else Color.Gray,
                            modifier = Modifier.weight(1f).clickable {
                                if (searchHits.isNotEmpty()) {
                                    currentHitIdx = (currentHitIdx + 1) % searchHits.size
                                    pendingSearchOffset = searchHits[currentHitIdx]
                                }
                            }.padding(vertical = 10.dp),
                        )
                        Text(
                            text = "关闭",
                            color = Color.White,
                            modifier = Modifier.weight(1f).clickable { showBookSearch = false }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 与 measureBlock 渲染端严格同源的文本变换：简繁 → 首行缩进占位。
 * 新引擎里 measure 回调和 PageText 渲染都走这两个函数，保证行边界一致。
 */
private fun novelDisplay(raw: String, indent: Boolean, traditional: Boolean): String {
    val display = if (traditional) VariantEngine.toTraditional(raw) else raw
    return if (indent && !display.startsWith("　　")) "　　$display" else display
}

/** 标题样式：在正文样式基础上放大 1.35 倍 + 加粗（与 measureBlock / PageText 一致）。
 *  fontSize 与 lineHeight 必须同比放大，否则大字号下 1.35×字号挤在未放大的行高里 → 标题叠字。 */
private fun novelStyle(base: TextStyle, isTitle: Boolean): TextStyle =
    if (isTitle) base.copy(
        fontSize = base.fontSize * 1.35f,
        lineHeight = base.lineHeight * 1.35f,
        fontWeight = FontWeight.Bold,
    ) else base

/**
 * 用移植的 novel-sound 引擎对「一章」分页。
 * 整段先排版成行，分页只按「行」切片，段落永不被字符串切割（见 PageText 整段裁剪渲染）。
 */
private fun paginateChapterLaidOut(
    chapterRaw: String,
    ci: Int,
    title: String,
    spec: ReaderLayoutSpec,
    measure: (String, Boolean, Boolean) -> TextLayoutResult,
    display: (raw: String, indent: Boolean) -> String,
): List<NovelPage> {
    val paragraphs = TxtLive.toParagraphs(chapterRaw, autoPara = true)
    // chapterText 取出的章文本通常已包含「章节标题」这一行，toParagraphs 会把它解析成第 1 段。
    // 若再把 title 单独作为 block0 传入，标题会出现两次（一份放大=title，一份普通=正文首段），
    // 表现为屏幕上同一标题叠两份。这里：首段与 title 实质相同则剔出、仅用 title 作标题块。
    val tNorm = title.replace("\u3000", " ").trim()
    val firstNorm = paragraphs.firstOrNull()?.replace("\u3000", " ")?.trim()
    val body = if (firstNorm != null && (firstNorm == tNorm || (tNorm.isNotEmpty() && firstNorm.contains(tNorm)))) {
        paragraphs.drop(1)
    } else paragraphs
    val layout = ReaderChapterLayout.build(title, body, spec, measure, display)
    if (layout.pages.isEmpty()) {
        // 空章：至少给一页显示标题（readerPage 为空，PageText 走纯文本兜底）。
        return listOf(NovelPage(ci, title, title, 0))
    }
    return layout.pages.mapIndexed { pageIndex, tp ->
        NovelPage(
            chapterIndex = ci,
            chapterTitle = title,
            text = tp.fragments.joinToString("\n") { it.text },
            startOffset = tp.start, // 与 legado durChapterPos 对齐：本页首字符在章内的绝对偏移，排版/字号/换屏变化后仍稳定
            readerPage = tp,
        )
    }
}

/** 版底信息栏：左侧章节/页码/进度，右侧电量。左右边距与正文一致。 */
@Composable
private fun ChapterFooter(
    chapterTitle: String,
    pageInChapter: Int,
    pageCount: Int,
    chapters: List<TxtLive.Chapter>,
    currentChapterIndex: Int,
    color: Color,
    sideMarginDp: Float,
    heightDp: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val battery = rememberBatteryPercent(context)
    // 全书进度：章节起点 + 本章内页占比，归一到全书，避免「第4/11页却显示100%」的困惑。
    val progressPct = if (chapters.isNotEmpty()) {
        val chapterFrac = if (pageCount > 0) pageInChapter.toFloat() / pageCount else 0f
        (((currentChapterIndex + chapterFrac) / chapters.size) * 100f).toInt().coerceIn(0, 100)
    } else 0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .padding(horizontal = sideMarginDp.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${chapterTitle} · ${pageInChapter}/${pageCount} · ${progressPct}%",
            color = color,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = "电量 $battery%", color = color, fontSize = 11.sp, maxLines = 1)
    }
}

/** 读取系统电量百分比（随电量变化刷新）。 */
@Composable
private fun rememberBatteryPercent(context: android.content.Context): Int {
    var pct by remember { mutableIntStateOf(readBattery(context)) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                intent?.let { pct = batteryFrom(it) }
            }
        }
        context.registerReceiver(receiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return pct
}

private fun readBattery(context: android.content.Context): Int {
    val i = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
    return if (i != null) batteryFrom(i) else 0
}

private fun batteryFrom(intent: android.content.Intent): Int {
    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else 0
}

/**
 * 滚动模式：整章连续段落渲染（不按页切）。
 * 与翻页模式共用同一套规则——版心宽度取整到整字宽、在容器内水平居中、段落样式同源，
 * 因此两端对齐 / 首行缩进 / 左右留白与翻页模式完全一致（用户报「滚动模式没适配这轮修复」的根因即此处）。
 * 段间留段落间距，容器四周留「上下边距 + 左右对称边距」，底部再避让信息条安全区。
 */
@Composable
private fun ScrollParagraphList(
    blocks: List<Pair<String, Boolean>>,
    ns: NovelSettings,
    textColor: Color,
    fontFamily: FontFamily,
    fontWeight: FontWeight,
    usableWidthPx: Float,
    paraSpacingPx: Float,
    listState: androidx.compose.foundation.lazy.LazyListState,
    bottomInsetDp: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val traditional = ns.variant == ChineseVariant.TRADITIONAL
    val baseStyle = remember(ns.font, ns.fontWeight, ns.fontSize, ns.lineHeightMul, ns.letterSpacing, textColor) {
        TextStyle(
            fontFamily = fontFamily, fontWeight = fontWeight,
            fontSize = ns.fontSize.sp, lineHeight = (ns.fontSize * ns.lineHeightMul).sp,
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
            ),
            letterSpacing = ns.letterSpacing.sp, textAlign = TextAlign.Justify, color = textColor,
        )
    }
    val textWidthPx = usableWidthPx
    val spacingPx = paraSpacingPx.toInt().coerceAtLeast(0)
    BoxWithConstraints(modifier) {
        val containerWidthPx = constraints.maxWidth
        val leftPadPx = ((containerWidthPx - textWidthPx) / 2f).coerceAtLeast(0f)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .padding(top = ns.verticalMargin.dp, bottom = (ns.verticalMargin.dp.value + bottomInsetDp).dp),
        ) {
            itemsIndexed(blocks) { _, item ->
                val (raw, isTitle) = item
                val shown = if (isTitle) raw else novelDisplay(raw, ns.indentFirstLine, traditional)
                val style = novelStyle(baseStyle, isTitle)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = with(density) { leftPadPx.toDp() })
                        .padding(bottom = with(density) { spacingPx.toDp() }),
                ) {
                    Text(
                        text = shown,
                        style = style,
                        modifier = Modifier.width(with(density) { textWidthPx.toDp() }),
                    )
                }
            }
        }
    }
}

/**
 * 渲染一页（整块移植 novel-sound reader_paged_view.dart 的核心）：
 * 每个 ReaderPageFragment 对应一个「行窗口」——整段 Text 只画一次，用裁剪 + 位移只显示该页的行区间。
 * 段落永不被字符串切割，因此两端对齐/避头尾/缩进不会因为跨页重排而错位（measure/render 严格同源）。
 */
/**
 * 系统原生 TextView 正文页（用户拍板：换系统选字）。
 * 长按交给系统 TextView 原生处理（双选柄 / 松手可拖 / 落点精准），
 * 菜单照 legado ContentSelectConfig 做「复制 / 查询 / 加屏蔽词 / 分享」——YGComic 具备能力的四项。
 * 与 Rust 书源引擎无关，仅替换 Kotlin 阅读显示层。
 */
@Composable
private fun SystemTextPage(
    page: NovelPage,
    ns: NovelSettings,
    textColor: Color,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
    onAddBlock: (String) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val traditional = ns.variant == ChineseVariant.TRADITIONAL
    val bodyText = remember(page.text, traditional) {
        if (traditional) VariantEngine.toTraditional(page.text) else page.text
    }
    val typeface = remember(ns.font, fontWeight) {
        NovelFontResolver.resolveTypeface(ns.font, fontWeight.weight >= FontWeight.Bold.weight)
    }
    val tint = textColor.toArgb()
    val sidePadPx = with(density) { ns.sideMargin.dp.toPx().roundToInt() }
    val letterSpacingEm = ns.letterSpacing
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextIsSelectable(true)
                gravity = Gravity.TOP or Gravity.START
                highlightColor = 0x6633B5E5.toInt()
                setCustomSelectionActionModeCallback(object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                        menu?.clear()
                        menu?.add(0, 1, 0, "复制")
                        menu?.add(0, 2, 1, "查询")
                        menu?.add(0, 3, 2, "加为替换")
                        menu?.add(0, 4, 3, "分享")
                        return true
                    }
                    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                        val s = selectionStart
                        val e = selectionEnd
                        if (s < 0 || e <= s || e > text.length) return false
                        val sel = text.subSequence(s, e).toString()
                        when (item?.itemId) {
                            1 -> {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("选字", sel))
                            }
                            2 -> {
                                val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, sel)
                                runCatching { ctx.startActivity(intent) }
                            }
                            3 -> onAddBlock(sel)
                            4 -> {
                                val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, sel)
                                runCatching { ctx.startActivity(Intent.createChooser(intent, "分享")) }
                            }
                            else -> return false
                        }
                        mode?.finish()
                        return true
                    }
                    override fun onDestroyActionMode(mode: ActionMode?) {}
                })
            }
        },
        update = { tv ->
            tv.text = bodyText
            tv.textSize = ns.fontSize
            tv.setLineSpacing(0f, ns.lineHeightMul)
            tv.typeface = typeface
            tv.setTextColor(tint)
            tv.setPadding(sidePadPx, 0, sidePadPx, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tv.letterSpacing = letterSpacingEm
            }
        },
    )
}

@Composable
private fun PageText(
    page: NovelPage,
    ns: NovelSettings,
    textColor: Color,
    fontFamily: FontFamily,
    fontWeight: FontWeight,
    gapPx: Int,
    modifier: Modifier = Modifier,
    contentWidthPx: Float = 0f,
    onMeasureContentHeight: ((Float) -> Unit)? = null,
    // 长按选字（照搬 legado 逐字坐标内核）：measureBlock 与渲染同源，用于坐标反查。
    // 选中后在本页内直接弹出浮动菜单+可拖手柄，动作通过三个回调上报。
    measureBlock: (String, Boolean, Boolean) -> TextLayoutResult,
    onCopy: (String) -> Unit,
    onSearch: (String) -> Unit,
    onAddBlock: (String) -> Unit,
    onAddBookmark: (String) -> Unit = {},
    // 书内搜索：命中词高亮（坐标同源，与选字蓝框独立），传已按当前简繁转换后的词
    searchQuery: String = "",
    // 朗读逐字高亮：当前朗读到的字符区间（坐标同源，与选字/搜索独立），null 表示未在朗读
    aloudPos: NovelReaderViewModel.AloudPos? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val traditional = ns.variant == ChineseVariant.TRADITIONAL
    val imgRegex = remember { Regex("""(<img\b[^>]*?\bsrc\s*=\s*["']?([^"'\s>]+)["']?[^>]*>)|(!\[[^\]]*\]\(([^)]+)\))""", RegexOption.IGNORE_CASE) }
    val baseStyle = remember(ns.font, ns.fontWeight, ns.fontSize, ns.lineHeightMul, ns.letterSpacing) {
        TextStyle(
            fontFamily = fontFamily, fontWeight = fontWeight,
            fontSize = ns.fontSize.sp, lineHeight = (ns.fontSize * ns.lineHeightMul).sp,
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
            ),
            letterSpacing = ns.letterSpacing.sp, textAlign = TextAlign.Justify,
            color = textColor,
        )
    }
    val readerPage = page.readerPage
    if (readerPage == null || readerPage.fragments.isEmpty()) {
        // 兜底（空章/旧数据）：整页文本渲染
        val text = if (traditional) VariantEngine.toTraditional(page.text) else page.text
        TextRender(text, baseStyle, imgRegex, context, modifier)
        return
    }

    // 忠实复刻 novel-sound ReaderPagedView 的像素直排：
    // 每个 fragment = 该页的行窗口 [firstLine..lastLine]。整段 Text 只画一次（固定像素尺寸 (宽, block.height)），
    // 用「每个 fragment 各自的裁切窗口」只显示该页行区间——ClipRect 到 fragment.height，再平移 -sourceTop。
    // 关键：必须逐 fragment 裁剪（不是靠整页容器裁切）！否则本页末段之后、属于下一页的行仍会画在本页
    // 容器底部、露出半行残影（翻页后它又是下一页首行 → 视觉「重复」，即用户报告的 bug）。
    BoxWithConstraints(modifier.clipToBounds()) {
        val containerWidthPx = constraints.maxWidth
        // 正文排版宽度必须与实测量宽严格一致（否则行边界重排、左右边距不对称、整页偏左上）。
        // 用 contentWidthPx（= 分页时的 usableWidthPx），并把它在容器里水平居中：左右边距相等。
        val textWidthPx = if (contentWidthPx > 0f) contentWidthPx.toInt() else containerWidthPx
        val leftPadPx = ((containerWidthPx - textWidthPx) / 2).coerceAtLeast(0)
        // 实测正文可视高度，回填给分页用（只在翻页模式、首屏测量时上报，避免每页重复写状态）。
        val maxH = constraints.maxHeight
        remember(maxH, containerWidthPx, textWidthPx) {
            if (maxH > 0) onMeasureContentHeight?.invoke(maxH.toFloat())
            Unit
        }
        // 照搬 legado 选字内核：把当前页逐字坐标建成 TextPage，长按命中字、拖动扩展选区。
        // 选区状态必须带页标识做 key：HorizontalPager 换章/翻页会复用同一个 composable，
        // 无 key 时旧选区残留 → 换章后正文仍显示蓝色高亮。带 key 后换页自动重置。
        val selPageKey = Triple(page.chapterIndex, page.startOffset, readerPage)
        var selStart by remember(selPageKey) { mutableStateOf<TextPos?>(null) }
        var selEnd by remember(selPageKey) { mutableStateOf<TextPos?>(null) }
        var showMenu by remember(selPageKey) { mutableStateOf(false) }
        // 坐标同源版：选字索引直接读排版阶段存的 charBoxes，不再二次 measure。
        // 只依赖 readerPage（charBoxes/baseTop 已随排版生成）与 leftPadPx。
        val selIndex = remember(readerPage, leftPadPx) {
            buildSelectionIndex(readerPage, leftPadPx)
        }
        // 当前选中的文本（页内闭环，供手柄拖动与浮动菜单使用）。
        val selText = remember(selStart, selEnd, selIndex) {
            val s = selStart; val e = selEnd
            if (s != null && e != null) selIndex.selectedText(s, e).trim() else ""
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(selIndex) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { off ->
                            showMenu = false
                            val p = selIndex.hit(off.x, off.y)
                            selStart = p; selEnd = p
                        },
                        onDrag = { change, _ ->
                            val p = selIndex.hit(change.position.x, change.position.y)
                            if (p != null) selEnd = p
                            change.consume()
                        },
                        onDragEnd = {
                            // pointerInput 的 key 是 selIndex（拖动中不变），lambda 捕获的 selText 是组合时快照 ""，
                            // 直接读 MutableState 实时值判断，否则 showMenu 永远不置 true、手柄与菜单不出现。
                            val s = selStart
                            val e = selEnd
                            if (s != null && e != null) showMenu = true
                        },
                        onDragCancel = {},
                    )
                }
                .drawBehind {
                    // 书内搜索命中：黄色高亮（与选字蓝框独立，坐标同源）
                    // charBoxes 基于显示层文本（已转繁体），故查询词需同样转换后才能匹配
                    val displayQuery = if (traditional) VariantEngine.toTraditional(searchQuery) else searchQuery
                    if (displayQuery.isNotEmpty()) {
                        for (r in selIndex.searchRects(displayQuery)) {
                            drawRect(
                                color = Color(0x66FFC107),
                                topLeft = Offset(r[0], r[1]),
                                size = Size(r[2] - r[0], r[3] - r[1]),
                            )
                        }
                    }
                    // 朗读逐字高亮：橙色（坐标同源，按段 blockStart 匹配 charBoxes，与选字/搜索独立）
                    if (aloudPos != null) {
                        val ap = aloudPos
                        for (frag in readerPage.fragments) {
                            val block = frag.block
                            if (block.start != ap.blockStart) continue
                            for (box in block.charBoxes) {
                                if (box.charIndex < ap.charStart || box.charIndex >= ap.charEnd) continue
                                val line = block.lines.getOrNull(box.lineIndex) ?: continue
                                val y = frag.top + (line.top - frag.sourceTop) + block.baseTop
                                drawRect(
                                    color = Color(0x66FF9800),
                                    topLeft = Offset(leftPadPx + box.left, y),
                                    size = Size(box.right - box.left, line.height),
                                )
                            }
                        }
                    }
                    val s = selStart; val e = selEnd
                    if (s != null && e != null) {
                        // 按行合并连续字，避免逐字矩形叠加产生的阶梯错层。
                        for (r in selIndex.selectionRowRects(s, e)) {
                            drawRect(
                                color = Color(0x6633B5E5),
                                topLeft = Offset(r[0], r[1]),
                                size = Size(r[2] - r[0], r[3] - r[1]),
                            )
                        }
                    }
                },
        ) {
            for (frag in readerPage.fragments) {
                val block = frag.block
                val shown = novelDisplay(block.text, block.indent, traditional)
                val style = novelStyle(baseStyle, block.isTitle)
                val fragHeightPx = frag.height.toInt().coerceAtLeast(1)
                val sourceTopPx = frag.sourceTop.toInt()
                val topPx = frag.top.toInt()
                val blockHeightPx = block.height.toInt().coerceAtLeast(1)
                // fragment 行窗口：宽度固定 textWidthPx 并水平居中（leftPadPx），高度锁定 = frag.height，裁切。
                Box(
                    Modifier
                        .offset { IntOffset(leftPadPx, topPx) }
                        .width(with(density) { textWidthPx.toDp() })
                        .height(with(density) { fragHeightPx.toDp() })
                        .clipToBounds(),
                ) {
                    Layout(
                        content = { Text(text = shown, style = style) },
                    ) { measurables, _ ->
                        val pl = measurables[0].measure(Constraints.fixed(textWidthPx, blockHeightPx))
                        layout(textWidthPx, fragHeightPx) {
                            pl.place(x = 0, y = -sourceTopPx)
                        }
                    }
                }
            }
            // 选区可拖手柄 + 浮动小菜单（页内坐标闭环，不遮挡整屏、不遮挡正文）。
            val s = selStart; val e = selEnd
            if (showMenu && s != null && e != null) {
                val anchors = selIndex.handleAnchors(s, e)
                if (anchors != null) {
                    val anchorsState = androidx.compose.runtime.rememberUpdatedState(anchors)
                    val handleDp = 16.dp
                    val handlePx = with(density) { handleDp.toPx() }
                    val half = handlePx / 2f
                    // 起点手柄（可拖动改 selStart）。
                    Box(
                        Modifier
                            .offset { IntOffset(anchors[0].roundToInt() - half.roundToInt(), anchors[1].roundToInt() - half.roundToInt()) }
                            .size(handleDp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF1976D2))
                            .pointerInput(selIndex) {
                                detectDragGestures(
                                    onDragStart = {},
                                    onDrag = { change, _ ->
                                        // change.position 相对手柄 Box 左上角；手柄页坐标基准 = anchors - half。
                                        val a = anchorsState.value
                                        val p = selIndex.hit(
                                            a[0] - half + change.position.x,
                                            a[1] - half + change.position.y,
                                        )
                                        if (p != null) selStart = p
                                        change.consume()
                                    },
                                    onDragEnd = {},
                                    onDragCancel = {},
                                )
                            },
                    )
                    // 终点手柄（可拖动改 selEnd）。
                    Box(
                        Modifier
                            .offset { IntOffset(anchors[2].roundToInt() - half.roundToInt(), anchors[3].roundToInt() - half.roundToInt()) }
                            .size(handleDp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF1976D2))
                            .pointerInput(selIndex) {
                                detectDragGestures(
                                    onDragStart = {},
                                    onDrag = { change, _ ->
                                        // change.position 相对手柄 Box 左上角；手柄页坐标基准 = anchors - half。
                                        val a = anchorsState.value
                                        val p = selIndex.hit(
                                            a[2] - half + change.position.x,
                                            a[3] - half + change.position.y,
                                        )
                                        if (p != null) selEnd = p
                                        change.consume()
                                    },
                                    onDragEnd = {},
                                    onDragCancel = {},
                                )
                            },
                    )
                    // 浮动小菜单：优先显示在选区下方，空间不足则上方；水平方向 clamp 在页宽内。
                    // 固定菜单宽度并用真实像素做 clamp，避免按写死的 228f 估算导致右侧越界。
                    val menuWidthDp = 320.dp
                    val menuWidthPx = with(density) { menuWidthDp.toPx() }
                    val menuH = 44f
                    val below = anchors[3] + 12f
                    val menuTop = if (below + menuH <= maxH) below else (anchors[1] - menuH - 12f).coerceAtLeast(0f)
                    val menuLeft = anchors[0].roundToInt().coerceIn(
                        0,
                        (containerWidthPx - menuWidthPx).coerceAtLeast(0f).roundToInt(),
                    )
                    Row(
                        Modifier
                            .width(menuWidthDp)
                            .offset { IntOffset(menuLeft, menuTop.roundToInt()) }
                            .background(Color(0xEE263238), RoundedCornerShape(8.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "复制", color = Color.White, fontSize = 14.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (selText.isNotEmpty()) onCopy(selText)
                                    showMenu = false
                                }
                                .padding(vertical = 8.dp),
                        )
                        Text(
                            text = "查询", color = Color.White, fontSize = 14.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (selText.isNotEmpty()) onSearch(selText)
                                    showMenu = false
                                }
                                .padding(vertical = 8.dp),
                        )
                        Text(
                            text = "加为替换", color = Color.White, fontSize = 14.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (selText.isNotEmpty()) onAddBlock(selText)
                                    showMenu = false
                                }
                                .padding(vertical = 8.dp),
                        )
                        Text(
                            text = "加书签", color = Color.White, fontSize = 14.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (selText.isNotEmpty()) onAddBookmark(selText)
                                    showMenu = false
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 兜底整页渲染（空章/旧数据）：无图片直接一个 Text；含图片则拆图文段（图片本身不参与测量，属罕见边界）。 */
@Composable
private fun TextRender(
    text: String,
    style: TextStyle,
    imgRegex: Regex,
    context: android.content.Context,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (!imgRegex.containsMatchIn(text)) {
            Text(text = text, style = style)
            return@Column
        }
        var last = 0
        for (m in imgRegex.findAll(text)) {
            if (m.range.first > last) {
                val seg = text.substring(last, m.range.first)
                if (seg.isNotBlank()) Text(text = seg, style = style)
            }
            val ref = m.groupValues[2].ifBlank { m.groupValues[4] }
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                coil3.compose.AsyncImage(model = imageModel(context, ref), contentDescription = null, modifier = Modifier.fillMaxWidth())
            }
            last = m.range.last + 1
        }
        if (last < text.length) {
            val seg = text.substring(last)
            if (seg.isNotBlank()) Text(text = seg, style = style)
        }
    }
}

private fun imageModel(context: android.content.Context, ref: String): Any {
    val trimmed = ref.trim().trim('"', '\'')
    return when {
        trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
        trimmed.startsWith("content://", true) -> android.net.Uri.parse(trimmed)
        trimmed.startsWith("file://", true) -> trimmed.removePrefix("file://")
        else -> {
            val f = java.io.File(trimmed)
            if (f.isAbsolute && f.exists()) f else java.io.File(context.filesDir, "library/${trimmed.trimStart('/')}")
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun NovelSettingsSheet(
    show: Boolean,
    ns: NovelSettings,
    store: SettingsStore,
    pageCapacity: Int,
    totalPages: Int,
    onOpenTtsSettings: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 朗读配置改成跳转到独立的朗读设置页；这里只显示当前生效配置名
    val activeTtsProfile by store.activeTtsProfile.collectAsState(com.yg.comic.tts.TtsProfile())
    OverlayBottomSheet(show = show, title = "小说阅读设置", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                "当前排版 · 每页约 $pageCapacity 字 · 全文 $totalPages 页",
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            SegRow("字重", NovelWeight.entries.map { it.label }, NovelWeight.entries.indexOf(ns.fontWeight)) {
                scope.launch { store.setNovelWeight(NovelWeight.entries[it]) }
            }
            SegRow("简繁", ChineseVariant.entries.map { it.label }, ChineseVariant.entries.indexOf(ns.variant)) {
                scope.launch { store.setNovelVariant(ChineseVariant.entries[it]) }
            }
            // 字体网格
            Text("字体", fontSize = MiuixTheme.textStyles.body2.fontSize, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NovelFont.entries.forEach { f ->
                    val selected = f == ns.font
                    Button(
                        onClick = { scope.launch { store.setNovelFont(f) } },
                        colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    ) { Text(f.label) }
                }
            }
            Spacer(Modifier.height(8.dp))
            NumSlider("字号", ns.fontSize, 12f..72f, { "${it.toInt()}" }) { scope.launch { store.setNovelFontSize(it) } }
            NumSlider("字距", ns.letterSpacing, 0f..12f, { String.format("%.1f", it) }) { scope.launch { store.setNovelLetterSpacing(it) } }
            NumSlider("行距", ns.lineHeightMul, 1.0f..4.0f, { String.format("%.1f", it) }) { scope.launch { store.setNovelLineHeight(it) } }
            NumSlider("段距", ns.paragraphSpacing, 0f..80f, { "${it.toInt()}" }) { scope.launch { store.setNovelParaSpacing(it) } }
            NumSlider("左右边距", ns.sideMargin, 0f..80f, { "${it.toInt()}" }) { scope.launch { store.setNovelSideMargin(it) } }
            NumSlider("上下边距", ns.verticalMargin, 0f..80f, { "${it.toInt()}" }) { scope.launch { store.setNovelVerticalMargin(it) } }
            Spacer(Modifier.height(8.dp))
            SegRow("翻页动画", PageAnimation.entries.map { it.label }, PageAnimation.entries.indexOf(ns.animation)) {
                scope.launch { store.setNovelAnimation(PageAnimation.entries[it]) }
            }
            // ===== 朗读：显示当前配置 + 跳转到独立朗读设置页 =====
            Text("朗读", fontSize = MiuixTheme.textStyles.body2.fontSize, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            Text("当前配置：${activeTtsProfile.name}",
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            TextButton(text = "前往朗读设置", onClick = onOpenTtsSettings, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            Spacer(Modifier.height(8.dp))
            Text("文字颜色和背景", fontSize = MiuixTheme.textStyles.body2.fontSize, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            val currentTheme = NovelThemes.match(ns.textColor, ns.background)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                NovelThemes.PRESETS.forEach { theme ->
                    val selected = theme.id == currentTheme
                    Box(
                        Modifier.weight(1f).padding(4.dp).height(40.dp)
                            .background(Color(theme.background), RoundedCornerShape(20.dp))
                            .pointerInput(theme.id) {
                                detectTapGestures(onTap = { scope.launch { store.setNovelTheme(theme.background, theme.textColor) } })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(theme.name, color = Color(theme.textColor), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow("首行缩进", ns.indentFirstLine) { scope.launch { store.setNovelIndent(it) } }
            SwitchRow("自动分段", ns.autoParagraph) { scope.launch { store.setNovelAutoPara(it) } }
        }
    }
}

@Composable
private fun NumSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, onChange: (Float) -> Unit) {
    // 拖动中只更新本地显示值，不触发重排；松手(onValueChangeFinished)才写设置 → 不卡
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = MiuixTheme.textStyles.body2.fontSize, color = MiuixTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(format(local), fontSize = MiuixTheme.textStyles.body2.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
        )
    }
}

@Composable
private fun SegRow(label: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, fontSize = MiuixTheme.textStyles.body2.fontSize, color = MiuixTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, text ->
                Button(
                    onClick = { onSelect(i) },
                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                    colors = if (i == selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                ) { Text(text, maxLines = 1, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f), fontSize = MiuixTheme.textStyles.body2.fontSize)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChapterListSheet(
    show: Boolean,
    chapters: List<TxtLive.Chapter>,
    currentChapterIndex: Int,
    reversed: Boolean,
    volumeFlags: List<Boolean> = emptyList(),
    order: List<Int> = emptyList(),
    smartSort: Boolean = false,
    onSmartSortChange: (Boolean) -> Unit = {},
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 反序/智能排序只改抽屉展示顺序与点选映射：内部 currentChapterIndex 仍是原始章号，saveProgress 语义不变。
    // order 为空（未开智能排序或尚未构建）时退回原来的正反序映射，行为与旧版一致。
    val displayOrder = if (order.size == chapters.size) order else chapters.indices.toList().let { if (reversed) it.reversed() else it }
    // 卷标题只当分组标题：不占「章」数、不可点击；标题里按可读章数统计，用户数得对得上。
    val readableCount = if (volumeFlags.size == chapters.size) volumeFlags.count { !it } else chapters.size
    OverlayBottomSheet(show = show, title = "章节（$readableCount）", onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("智能排序", fontSize = MiuixTheme.textStyles.body2.fontSize, modifier = Modifier.weight(1f))
            Switch(checked = smartSort, onCheckedChange = onSmartSortChange)
        }
        Text(
            "开启后按章号理顺，第一章在前；关闭则保持书源原顺序",
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            itemsIndexed(displayOrder) { displayIdx, chapterIndex ->
                val ch = chapters.getOrNull(chapterIndex) ?: return@itemsIndexed
                val isVolume = volumeFlags.getOrNull(chapterIndex) == true
                val selected = !isVolume && chapterIndex == currentChapterIndex
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .then(
                            if (isVolume) Modifier
                            else Modifier.pointerInput(displayIdx) { detectTapGestures { onSelect(chapterIndex) } }
                        )
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isVolume) "▍${ch.title}" else ch.title,
                        color = when {
                            isVolume -> MiuixTheme.colorScheme.primary
                            selected -> MiuixTheme.colorScheme.primary
                            else -> MiuixTheme.colorScheme.onBackground
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = MiuixTheme.textStyles.body1.fontSize, maxLines = 1,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

/** 书签列表：按章锚点定位，点条目跳转、点右侧删除 */
@Composable
private fun BookmarkListSheet(
    show: Boolean,
    bookmarks: List<com.yg.comic.data.BookmarkEntity>,
    onSelect: (com.yg.comic.data.BookmarkEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(show = show, title = "书签（${bookmarks.size}）", onDismissRequest = onDismiss) {
        if (bookmarks.isEmpty()) {
            Text("还没有书签，长按正文选字后可「加书签」", color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth().padding(16.dp))
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(bookmarks, key = { it.id }) { bm ->
                    Row(
                        Modifier.fillMaxWidth()
                            .pointerInput(bm.id) { detectTapGestures { onSelect(bm) } }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(bm.chapterTitle.ifBlank { "第 ${bm.chapterIndex + 1} 章" },
                                color = MiuixTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Medium, fontSize = MiuixTheme.textStyles.body1.fontSize,
                                maxLines = 1)
                            Text(bm.snippet, color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                fontSize = 13.sp, maxLines = 2)
                        }
                        IconButton(onClick = { onDelete(bm.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除书签", tint = Color.White)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** 换源列表：同一本书来自多个书源时可选其一重新加载目录与正文 */
@Composable
private fun SourceListSheet(
    show: Boolean,
    origins: List<com.yg.comic.source.SourceOrigin>,
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(show = show, title = "换源（${origins.size} 个来源）", onDismissRequest = onDismiss) {
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

/** 联网换源：按书名+作者搜所有启用书源得到的候选，选一个切过去 */
@Composable
private fun SwitchSourceSheet(
    show: Boolean,
    loading: Boolean,
    msg: String,
    candidates: List<NovelReaderViewModel.SwitchCandidate>,
    onSelect: (NovelReaderViewModel.SwitchCandidate) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(show = show, title = "联网换源（同名同作者）", onDismissRequest = onDismiss) {
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

/** 长按选字后的动作菜单：复制 / 查询 / 加为屏蔽词（照搬 legado 选字闭环的落地动作）。 */
@Composable
private fun TextSelectionMenuSheet(
    show: Boolean,
    text: String,
    onCopy: () -> Unit,
    onSearch: () -> Unit,
    onAddBlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(show = show, title = "选中文本", onDismissRequest = onDismiss) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            TextButton(text = "复制", onClick = onCopy)
            TextButton(text = "查询", onClick = onSearch)
            TextButton(text = "加为替换", onClick = onAddBlock)
        }
    }
}

