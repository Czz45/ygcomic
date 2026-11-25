package com.yg.comic.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.*
import com.yg.comic.data.BookRepository
import com.yg.comic.data.SettingsStore
import com.yg.comic.ui.shelf.ShelfScreen
import com.yg.comic.ui.shelf.ShelfViewModel
import com.yg.comic.ui.home.HomeScreen
import com.yg.comic.ui.settings.SettingsScreen
import com.yg.comic.ui.settings.TtsSettingsScreen
import com.yg.comic.ui.reader.ReaderScreen
import com.yg.comic.ui.imports.ImportScreen
import com.yg.comic.ui.placeholder.PlaceholderScreen
import com.yg.comic.ui.detail.BookDetailScreen
import com.yg.comic.ui.detail.BookDetailViewModel
import com.yg.comic.ui.detail.SharedBookDetail
import com.yg.comic.ui.source.SourcedBook
import top.yukonga.miuix.kmp.basic.Scaffold

sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Tab("home", "首页", Icons.Filled.AutoStories)
    data object Shelf : Tab("shelf", "书架", Icons.Filled.LocalLibrary)
    data object Settings : Tab("settings", "设置", Icons.Filled.Settings)
}

private val TABS = listOf(Tab.Home, Tab.Shelf, Tab.Settings)

// 转场：进入页「轻微放大 + 淡入」有推进感；退出页仅淡出不缩放不位移。
// 关键：退出页不位移，才不会在转场期间「推动」身后的页面造成变动感。
private const val NAV_MS = 240
private const val ENTER_SCALE = 0.94f
private fun enterNav() = fadeIn(tween(NAV_MS)) + scaleIn(initialScale = ENTER_SCALE, animationSpec = tween(NAV_MS))
private fun exitNav() = fadeOut(tween(NAV_MS))
private fun popEnterNav() = fadeIn(tween(NAV_MS)) + scaleIn(initialScale = ENTER_SCALE, animationSpec = tween(NAV_MS))
private fun popExitNav() = fadeOut(tween(NAV_MS))

// 阅读页进出用纯淡入淡出：封面展开交给 sharedBounds 共享元素转场，
// 这里若再叠加 scale 会和共享 morph 打架，故去掉缩放。
private fun readerEnter() = fadeIn(tween(NAV_MS))
private fun readerExit() = fadeOut(tween(NAV_MS))
private fun readerPopEnter() = fadeIn(tween(NAV_MS))
private fun readerPopExit() = fadeOut(tween(NAV_MS))

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppRoot(store: SettingsStore) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.yg.comic.YGComicApp
    val repo = remember { app.repo }
    // 首次启动灌入内置默认屏蔽池（库空才灌，不覆盖用户自定义）
    LaunchedEffect(Unit) {
        repo.ensureDefaultReplaceRules()
        // 一次性迁移：把旧版被覆盖成"异次元 (N)"的源名改回真实网站名（仅首次，带标记）
        repo.migrateYiciyuanSourceNames()
    }
    // 书架 VM 提升到 Activity 作用域：合集树层级跨导航（进阅读页/导入页再返回）保留，不被 popBackStack 销毁。
    val shelfVm: ShelfViewModel = viewModel { ShelfViewModel(repo, store) }
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Tab.Home.route
    // 外部共享进来的书源（开源阅读分享 / 文件打开）：一旦到达就跳到书源页，由该页消费并导入。
    val sharedPending by com.yg.comic.ui.source.SharedSource.pending.collectAsState()
    LaunchedEffect(sharedPending) {
        if (sharedPending != null && currentRoute != "book_source") {
            navController.navigate("book_source") { launchSingleTop = true }
        }
    }
    val showTabs = currentRoute in TABS.map { it.route }
    // 关键修复：Scaffold 的 content inset 与 NavHost 的 modifier 必须完全恒定，不依赖 currentRoute。
    // 之前按 fullBleed 切换会在转场瞬间突变，正在 fadeOut 的书架页跟着重排「跑位」。
    // 状态栏/导航栏避让下放到各页面用 systemBarsIgnoringVisibility（不受状态栏显隐影响）。
    // tab 纯悬浮覆盖：不放 Scaffold.floatingToolbar（miuix 该槽是内联占位、会挤压内容高度），
    // 改为外层 Box 叠加在 Scaffold 之上，只悬浮盖住底部、不占布局、不顶内容、不裁切内容。
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
    ) { _ ->
        // 封面展开共享元素转场：书架封面 → 阅读页，用 sharedBounds 做 container transform。
        // NavHost 必须是 SharedTransitionLayout 的内容，两个 scope 才能跨页面配对。
        SharedTransitionLayout(Modifier.fillMaxSize()) {
        // NavHost 恒定 fillMaxSize：padding 由各页面自己按 IgnoringVisibility 处理，
        // 转场期间此 modifier 不变，退场页面不再因 inset 突变而跳动。
        NavHost(
            navController = navController,
            startDestination = Tab.Home.route,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { enterNav() },
            exitTransition = { exitNav() },
            popEnterTransition = { popEnterNav() },
            popExitTransition = { popExitNav() },
        ) {
            composable(Tab.Home.route) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
                    HomeScreen(navController, repo)
                }
            }
            composable(Tab.Shelf.route) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
                    ShelfScreen(
                        repo, store, navController, shelfVm,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable,
                    )
                }
            }
            composable(Tab.Settings.route) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
                    SettingsScreen(repo, store, navController)
                }
            }
            composable(
                route = "reader/{bookId}",
                enterTransition = { readerEnter() },
                exitTransition = { readerExit() },
                popEnterTransition = { readerPopEnter() },
                popExitTransition = { readerPopExit() },
            ) { entry ->
                val id = entry.arguments?.getString("bookId")?.toLongOrNull() ?: 0L
                ReaderScreen(
                    repo, store, id,
                    onBack = { navController.popBackStack() },
                    onOpenBook = { nextId ->
                        navController.navigate("reader/$nextId") {
                            popUpTo("reader/$id") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onOpenTtsSettings = { navController.navigate("tts_settings") },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                )
            }
            composable("import") { ImportScreen(repo, onBack = { navController.popBackStack() }) }
            composable(
                "search?key={key}&sourceType={sourceType}",
                arguments = listOf(
                    navArgument("key") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("sourceType") { type = NavType.IntType; defaultValue = -1 },
                ),
            ) { entry ->
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
                    com.yg.comic.ui.source.SearchScreen(
                        repo = repo,
                        onBack = { navController.popBackStack() },
                        onOpenBook = { id -> navController.navigate("reader/$id") },
                        onOpenDetail = { sb: SourcedBook ->
                            SharedBookDetail.post(sb)
                            navController.navigate("book_detail")
                        },
                        initialKey = entry.arguments?.getString("key"),
                        sourceTypeFilter = entry.arguments?.getInt("sourceType") ?: -1,
                    )
                }
            }
            composable("book_detail") {
                val sb = SharedBookDetail.current
                if (sb != null) {
                    val detailVm: BookDetailViewModel = viewModel { BookDetailViewModel(repo, sb) }
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
                        BookDetailScreen(
                            vm = detailVm,
                            onBack = { navController.popBackStack() },
                            onOpenBook = { id -> navController.navigate("reader/$id") },
                        )
                    }
                }
            }
            composable("book_source") {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
                    com.yg.comic.ui.source.BookSourceScreen(
                        repo = repo,
                        onBack = { navController.popBackStack() },
                        onOpenShelf = {
                            navController.navigate(Tab.Shelf.route) {
                                popUpTo(Tab.Home.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
            composable("replace_rules") {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
                    com.yg.comic.ui.replace.ReplaceRuleScreen(
                        repo = repo,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable("tts_settings") {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
                    TtsSettingsScreen(store = store, onBack = { navController.popBackStack() })
                }
            }
            composable("placeholder/{type}") { entry ->
                PlaceholderScreen(entry.arguments?.getString("type") ?: "NOVEL") {
                    navController.popBackStack()
                }
            }
        }
        } // SharedTransitionLayout
    }
    // 悬浮 tab 覆盖层：与 Scaffold 内容同处一个 Box，measure 时不占内容布局，
    // 直接悬浮在底部；内容能滚到它后面、下方和四周直接看到内容。
    if (showTabs) {
        // 包裹 Box 只 wrapContent 再 align 到底部居中——它是外层 Box 的直接子项，align 生效。
        // 不要给这个 Box 加 fillMaxSize（撑满后 align 失效会让 tab 跑到左上角）。
        Box(Modifier.align(Alignment.BottomCenter)) {
            PillTabBar(
                tabs = TABS,
                currentRoute = currentRoute,
                onSelect = { tab ->
                    navController.navigate(tab.route) {
                        popUpTo(Tab.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
    }
}
