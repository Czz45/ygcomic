package com.yg.comic.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.yg.comic.data.BookRepository
import com.yg.comic.data.SettingsStore
import com.yg.comic.model.*
import com.yg.comic.sr.SRModel
import com.yg.comic.sr.SuperResolutionManager
import com.yg.comic.ui.components.DialogActions
import com.yg.comic.ui.components.LargeTitle
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(
    repo: BookRepository,
    store: SettingsStore,
    nav: NavController,
    vm: SettingsViewModel = viewModel { SettingsViewModel(repo, store) },
) {
    val context = LocalContext.current
    val theme by vm.theme.collectAsState()
    val direction by vm.direction.collectAsState()
    val dual by vm.dual.collectAsState()
    val scale by vm.scale.collectAsState()
    val sensitivity by vm.sensitivity.collectAsState()
    val cacheLimit by vm.cacheLimit.collectAsState()
    val betaChannel by vm.betaChannel.collectAsState()
    val sources by vm.sources.collectAsState()
    val cacheSize by vm.cacheSize.collectAsState()
    val updateState by vm.updateState.collectAsState()
    val autoSortEnabled by vm.autoSortEnabled.collectAsState()
    val readStats by store.readStats.collectAsState(initial = SettingsStore.ReadStats(0L, 0L))
    val srEnabled by store.srEnabled.collectAsState(initial = false)
    val srModelKey by store.srModelKey.collectAsState(initial = "realesrgan_anime_fast")
    val autoCropEnabled by store.autoCropEnabled.collectAsState(initial = false)
    val preloadSrEnabled by store.preloadSrEnabled.collectAsState(initial = false)
    val srWifiOnly by store.srWifiOnly.collectAsState(initial = true)
    val srCacheQuotaMb by store.srCacheQuotaMb.collectAsState(initial = 2048)
    val srModel = remember(srModelKey) { SRModel.fromKey(srModelKey) }
    val srManager = remember(context) { SuperResolutionManager(context, store) }
    val scope = rememberCoroutineScope()
    var srDownloadMsg by remember { mutableStateOf("") }

    var showClearConfirm by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            vm.addSource(uri.toString(), Uri.decode(uri.toString()).substringAfterLast('/'))
        }
    }

    val themeLabels = ThemeMode.entries.map { it.label }
    val directionLabels = PageDirection.entries.map { it.label }
    val srModels = SRModel.allBySpeed()
    val srModelLabels = srModels.map { "${it.label}（${it.tier}）" }
    val srQuotaOptions = listOf(512, 1024, 2048, 4096)
    val srQuotaLabels = srQuotaOptions.map { "$it MB" }

    Scaffold { _ ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { LargeTitle(title = "设置") }

            item { SectionTitle("朗读") }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ArrowPreference(
                        title = "朗读设置",
                        summary = "管理多套朗读配置：系统 / 网络 / 本地大模型，各自独立 API Key、音色、语速",
                        startAction = { PrefIcon(Icons.Filled.RecordVoiceOver) },
                        onClick = { nav.navigate("tts_settings") },
                    )
                }
            }

            item { SectionTitle("书源") }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ArrowPreference(
                        title = "书源管理",
                        summary = "添加/删除书源，按分组与类型整理在线小说来源",
                        startAction = { PrefIcon(Icons.Filled.Cloud) },
                        onClick = { nav.navigate("book_source") },
                    )
                }
            }

            item { SectionTitle("净化") }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ArrowPreference(
                        title = "替换 / 净化规则",
                        summary = "去广告、去乱码、替换正文与标题；支持导入分享的替换规则",
                        startAction = { PrefIcon(Icons.Filled.FilterAlt) },
                        onClick = { nav.navigate("replace_rules") },
                    )
                }
            }

            item { SectionTitle("外观") }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    OverlayDropdownPreference(
                        items = themeLabels,
                        selectedIndex = ThemeMode.entries.indexOf(theme).coerceAtLeast(0),
                        title = "主题",
                        summary = "跟随系统 / 浅色 / 深色",
                        startAction = { PrefIcon(Icons.Filled.Palette) },
                        onSelectedIndexChange = { vm.setTheme(ThemeMode.entries[it]) },
                    )
                }
            }

            item { SectionTitle("阅读") }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    OverlayDropdownPreference(
                        items = directionLabels,
                        selectedIndex = PageDirection.entries.indexOf(direction).coerceAtLeast(0),
                        title = "翻页方向",
                        summary = "从左往右 / 从右往左 / 上下滚动",
                        startAction = { PrefIcon(Icons.Filled.Swipe) },
                        onSelectedIndexChange = { vm.setDirection(PageDirection.entries[it]) },
                    )
                    SwitchPreference(
                        checked = dual,
                        onCheckedChange = { vm.setDual(it) },
                        title = "双页模式",
                        summary = "横屏时并排显示两页",
                        startAction = { PrefIcon(Icons.Filled.ImportContacts) },
                    )
                    SwitchPreference(
                        checked = autoSortEnabled,
                        onCheckedChange = { vm.setAutoSortEnabled(it) },
                        title = "智能排序",
                        summary = "根据书源质量自动排序（网络 / 章节 / 正文）",
                        startAction = { PrefIcon(Icons.Filled.FilterAlt) },
                    )
                    SliderPreference(
                        title = "缩放",
                        value = scale,
                        valueRange = 0.5f..2.5f,
                        format = { "${String.format("%.1f", it)}x" },
                        onValueChange = { vm.setScale(it) },
                    )
                    SliderPreference(
                        title = "手势灵敏度",
                        value = sensitivity,
                        valueRange = 0.5f..2.0f,
                        format = { "${String.format("%.1f", it)}x" },
                        onValueChange = { vm.setSensitivity(it) },
                    )
                }
            }

            item { SectionTitle("来源文件夹") }
            items(sources.size) { idx ->
                val s = sources[idx]
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ArrowPreference(
                        title = s.displayName,
                        summary = s.uri,
                        startAction = { PrefIcon(Icons.Filled.Folder) },
                        onClick = { vm.removeSource(s.id) },
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ArrowPreference(
                        title = "添加来源文件夹",
                        summary = "点击选择",
                        startAction = { PrefIcon(Icons.Filled.Add) },
                        onClick = { pickFolder.launch(null) },
                    )
                }
            }

            item { SectionTitle("缓存") }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    SliderPreference(
                        title = "磁盘缓存上限",
                        value = cacheLimit.toFloat(),
                        valueRange = 128f..2048f,
                        steps = 6,
                        format = { "${it.toInt()} MB" },
                        onValueChange = { vm.setCacheLimit(it.toInt()) },
                    )
                    Text(
                        "当前缓存 ${cacheSize / 1024 / 1024} MB（不含原始导入文件）",
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    ArrowPreference(
                        title = "清空缓存",
                        summary = "仅清理临时/解码缓存，不删除原始文件",
                        startAction = { PrefIcon(Icons.Filled.Delete) },
                        onClick = { showClearConfirm = true },
                    )
                }
            }

            item { SectionTitle("漫画超级分辨率") }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    SwitchPreference(
                        checked = srEnabled,
                        onCheckedChange = { scope.launch { store.setSrEnabled(it) } },
                        title = "超级分辨率",
                        summary = "对所有漫画图生效（网络 / 本地图片 / PDF），可开关对比效果",
                        startAction = { PrefIcon(Icons.Filled.AutoAwesome) },
                    )
                    SwitchPreference(
                        checked = preloadSrEnabled,
                        onCheckedChange = { scope.launch { store.setPreloadSrEnabled(it) } },
                        title = "预超分（后台批量）",
                        summary = "在后台把整章图片超分好存到磁盘，看时直接读取，避免边看边算卡顿。关闭则退回实时超分。",
                        startAction = { PrefIcon(Icons.Filled.AutoAwesome) },
                    )
                    SwitchPreference(
                        checked = srWifiOnly,
                        onCheckedChange = { scope.launch { store.setSrWifiOnly(it) } },
                        title = "仅 WiFi 预超分",
                        summary = "只在 WiFi 下进行后台预超分，避免消耗移动流量",
                        startAction = { PrefIcon(Icons.Filled.Wifi) },
                    )
                    OverlayDropdownPreference(
                        items = srQuotaLabels,
                        selectedIndex = srQuotaOptions.indexOf(srCacheQuotaMb).coerceAtLeast(0),
                        title = "预超分缓存上限",
                        summary = "当前：$srCacheQuotaMb MB（磁盘缓存超过后自动清理最久未用的）",
                        startAction = { PrefIcon(Icons.Filled.Storage) },
                        onSelectedIndexChange = { index ->
                            scope.launch { store.setSrCacheQuotaMb(srQuotaOptions[index]) }
                        },
                    )
                    SwitchPreference(
                        checked = autoCropEnabled,
                        onCheckedChange = { scope.launch { store.setAutoCropEnabled(it) } },
                        title = "自动裁剪白边",
                        summary = "裁掉漫画页四周的纯白或纯黑边，默认关闭避免误裁",
                        startAction = { PrefIcon(Icons.Filled.CropFree) },
                    )
                    OverlayDropdownPreference(
                        items = srModelLabels,
                        selectedIndex = srModels.indexOf(srModel).coerceAtLeast(0),
                        title = "模型挡位",
                        summary = "当前：${srModel.label}（${srModel.tier}）",
                        startAction = { PrefIcon(Icons.Filled.Tune) },
                        onSelectedIndexChange = { index ->
                            val m = srModels[index]
                            scope.launch {
                                store.setSrModelKey(m.key)
                                srManager.switchModel(m)
                            }
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    srDownloadMsg = "下载中…"
                                    val ok = srManager.downloadModel(srModel) { p, msg -> srDownloadMsg = "$p% $msg" }
                                    srDownloadMsg = if (ok) "已就绪" else "下载失败，请检查网络"
                                }
                            },
                        ) {
                            Text(if (srManager.isModelReady(srModel)) "重新下载模型" else "下载模型")
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            srDownloadMsg,
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item { SectionTitle("阅读统计") }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ArrowPreference(
                        title = "阅读时长",
                        summary = "今日 ${formatDuration(readStats.todayMs)} · 累计 ${formatDuration(readStats.totalMs)}",
                        startAction = { PrefIcon(Icons.Filled.Schedule) },
                        onClick = {},
                    )
                }
            }

            item { SectionTitle("关于与更新") }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    SwitchPreference(
                        checked = betaChannel,
                        onCheckedChange = { vm.setBetaChannel(it) },
                        title = "测试版更新通道",
                        summary = if (betaChannel) "已开启：检测最新测试版（含 prerelease）"
                        else "已关闭：只检测正式版本",
                        startAction = { PrefIcon(Icons.Filled.Science) },
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ArrowPreference(
                        title = "检查更新",
                        summary = "当前 v${vm.versionName} (${vm.versionCode}) · 检测${if (betaChannel) "测试版" else "正式版"} · 点击检测",
                        startAction = { PrefIcon(Icons.Filled.SystemUpdate) },
                        onClick = { vm.checkUpdate() },
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ArrowPreference(
                        title = "导出日志",
                        summary = "导出最近一次崩溃日志，发给我排查闪退",
                        startAction = { PrefIcon(Icons.Filled.BugReport) },
                        onClick = { exportLastCrash(context) },
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ArrowPreference(
                        title = "复制日志到剪贴板",
                        summary = "直接复制最近一次崩溃日志，粘贴发给我就行",
                        startAction = { PrefIcon(Icons.Filled.ContentCopy) },
                        onClick = { copyLastCrashToClipboard(context) },
                    )
                }
            }
        }
    }

    // 更新流程：检测中/已是最新/发现新版本/下载中/失败
    when (val st = updateState) {
        is SettingsViewModel.UpdateState.Checking -> OverlayDialog(
            show = true, title = "检查更新", summary = "正在检测最新版本…",
            onDismissRequest = { vm.resetUpdate() },
        ) {}
        is SettingsViewModel.UpdateState.UpToDate -> OverlayDialog(
            show = true, title = "已是最新版本", summary = "当前 v${vm.versionName} 已是最新。",
            onDismissRequest = { vm.resetUpdate() },
        ) {}
        is SettingsViewModel.UpdateState.Failed -> OverlayDialog(
            show = true, title = "检查更新", summary = st.message,
            onDismissRequest = { vm.resetUpdate() },
        ) {}
        is SettingsViewModel.UpdateState.Found -> OverlayDialog(
            show = true,
            title = "发现新版本 v${st.release.version}",
            summary = "当前 v${vm.versionName} → ${st.release.version} · ${String.format("%.1f", st.release.sizeMb)} MB\n\n${st.release.notes.take(300)}",
            onDismissRequest = { vm.resetUpdate() },
        ) {
            DialogActions(
                cancelText = "稍后",
                confirmText = "下载并安装",
                primaryOnLeft = true,
                onCancel = { vm.resetUpdate() },
                onConfirm = { vm.downloadUpdate(st.release, context) },
            )
        }
        is SettingsViewModel.UpdateState.Downloading -> OverlayDialog(
            show = true, title = "正在下载", summary = "已下载 ${st.progress}%",
            onDismissRequest = {},
        ) {}
        is SettingsViewModel.UpdateState.NoneAvailable -> OverlayDialog(
            show = true,
            title = if (st.beta) "暂无可用测试版" else "暂无正式版",
            summary = if (st.beta) "这个通道里目前没有任何版本。"
            else "目前还没有发布正式版。想第一时间拿到新版本，可以把上面的「测试版更新通道」打开。",
            onDismissRequest = { vm.resetUpdate() },
        ) {}
        is SettingsViewModel.UpdateState.ReadyToInstall,
        is SettingsViewModel.UpdateState.Idle -> {}
    }

    OverlayDialog(
        show = showClearConfirm,
        title = "清空缓存",
        summary = "仅删除本地库内的临时/解码缓存，不会删除你原始导入的文件。",
        onDismissRequest = { showClearConfirm = false },
    ) {
        TextButton(text = "确认清空", onClick = { vm.clearCache(); showClearConfirm = false })
    }
}

/** 把 YGComicApp 记录的 last_crash.txt 通过系统分享面板导出。 */
private fun exportLastCrash(context: android.content.Context) {
    val src = java.io.File(context.filesDir, "last_crash.txt")
    if (!src.exists()) {
        Toast.makeText(context, "还没有崩溃日志，先复现一次闪退", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val dst = java.io.File(context.cacheDir, "update/last_crash.txt")
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dst)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出日志"))
    }.onFailure {
        Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 把 YGComicApp 记录的 last_crash.txt 直接复制到系统剪贴板。 */
private fun copyLastCrashToClipboard(context: android.content.Context) {
    val src = java.io.File(context.filesDir, "last_crash.txt")
    if (!src.exists()) {
        Toast.makeText(context, "还没有崩溃日志，先复现一次闪退", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val text = src.readText()
        val clip = android.content.ClipData.newPlainText("YGComic 崩溃日志", text)
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(clip)
        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "复制失败：${it.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 毫秒 → "X小时Y分" / "X分" / "不足1分" */
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0 分"
    val minutes = ms / 60_000L
    val hours = minutes / 60L
    return if (hours > 0) "$hours 小时 ${minutes % 60} 分" else "$minutes 分"
}

@Composable
private fun PrefIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp),
        tint = MiuixTheme.colorScheme.onBackground)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = MiuixTheme.textStyles.subtitle.fontSize,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SliderPreference(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(format(value), fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Spacer(Modifier.height(6.dp))
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
    }
}
