package com.yg.comic.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.comic.data.SettingsStore
import com.yg.comic.tts.NetworkTtsRegistry
import com.yg.comic.tts.TtsPreviewPlayer
import com.yg.comic.tts.TtsProfile
import com.yg.comic.ui.components.DialogActions
import com.yg.comic.ui.components.LargeTitle
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 朗读设置独立页：管理多套朗读配置（各带自己的 API Key / 音色 / 风格 / 语速等），
 * 点选一套作为当前生效配置；下面编辑的就是当前这套。书架和小说阅读器都跳这里。
 */
@Composable
fun TtsSettingsScreen(store: SettingsStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val profiles by store.ttsProfiles.collectAsState(emptyList())
    val active by store.activeTtsProfile.collectAsState(TtsProfile())

    // 当前配置的各单项值：getter/setter 都已改为读写 active profile
    val engine by store.ttsEngine.collectAsState("system")
    val providerId by store.ttsProvider.collectAsState(NetworkTtsRegistry.defaultId)
    val apiKey by store.ttsApiKey.collectAsState("")
    val voice by store.ttsVoice.collectAsState("Chloe")
    val style by store.ttsStyle.collectAsState("")
    val format by store.ttsFormat.collectAsState("wav")
    val volume by store.ttsVolume.collectAsState(1f)
    val cacheSegments by store.ttsCacheSegments.collectAsState(-1)
    val rate by store.ttsRate.collectAsState(1f)
    val provider = NetworkTtsRegistry.get(providerId) ?: NetworkTtsRegistry.providers.firstOrNull()

    var showNameEditor by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }

    // 试听播放器：复用与小说阅读器相同的朗读链路，只播一段固定示例文本。
    val context = LocalContext.current
    var previewPlaying by remember { mutableStateOf(false) }
    var previewMessage by remember { mutableStateOf<String?>(null) }
    // 试听文本：用户可粘贴自定义内容，空着就由播放器用内置示例兜底。
    var previewInput by remember { mutableStateOf("") }
    val previewPlayer = remember {
        TtsPreviewPlayer(context, scope, onState = { state, msg ->
            previewPlaying = state == TtsPreviewPlayer.STATE_PLAYING
            previewMessage = msg
        })
    }
    DisposableEffect(Unit) { onDispose { previewPlayer.release() } }

    Scaffold { _ ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            LargeTitle(title = "朗读设置", onBack = onBack)

            Text("朗读配置", fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            Text("点“使用”切换当前配置；下面改的参数只影响当前这套，换套配置各自独立。",
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))

            profiles.forEach { p ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = if (p.id == active.id) FontWeight.Bold else FontWeight.Normal,
                                fontSize = MiuixTheme.textStyles.body1.fontSize,
                                color = MiuixTheme.colorScheme.onSurface)
                            Text(ttsEngineLabel(p.engine) + if (p.id == active.id) " · 当前使用" else "",
                                fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        TextButton(text = if (p.id == active.id) "使用中" else "使用", onClick = {
                            if (p.id != active.id) scope.launch { store.setActiveTtsProfile(p.id) }
                        })
                        IconButton(onClick = { editingId = p.id; nameInput = p.name; showNameEditor = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "重命名")
                        }
                        IconButton(onClick = { scope.launch { store.duplicateTtsProfile(p.id) } }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                        }
                        IconButton(onClick = { scope.launch { store.deleteTtsProfile(p.id) } }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                }
            }

            TextButton(text = "+ 新增配置",
                onClick = { editingId = null; nameInput = "配置 ${profiles.size + 1}"; showNameEditor = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

            Spacer(Modifier.height(8.dp))
            Text("当前配置参数：${active.name}", fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))

            SegRow("引擎", listOf("系统 TTS", "网络 TTS", "本地大模型"),
                listOf("system", "network", "local").indexOf(engine).coerceAtLeast(0)
            ) { i -> scope.launch { store.setTtsEngine(listOf("system", "network", "local")[i]) } }

            run {
                val rates = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f)
                val labels = rates.map { String.format("%.1f×", it) }
                val idx = rates.indexOfFirst { kotlin.math.abs(it - rate) < 0.01f }.coerceAtLeast(0)
                SegRow("朗读语速", labels, idx) { i -> scope.launch { store.setTtsRate(rates[i]) } }
            }

            if (engine == "network") {
                Text("网络 TTS · 参数从引擎能力读取，失败自动回退系统朗读",
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                if (NetworkTtsRegistry.providers.size > 1) {
                    SegRow("服务商", NetworkTtsRegistry.providers.map { it.displayName },
                        NetworkTtsRegistry.providers.indexOfFirst { it.id == providerId }.coerceAtLeast(0)
                    ) { i -> scope.launch { store.setTtsProvider(NetworkTtsRegistry.providers[i].id) } }
                }
                TextField(
                    value = apiKey,
                    onValueChange = { scope.launch { store.setTtsApiKey(it) } },
                    label = if (provider?.capabilities?.requiresApiKey == true) "API Key（必填）" else "API Key（可选）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                if (provider != null) {
                    val caps = provider.capabilities
                    if (caps.voices.isNotEmpty()) {
                        SegRow("音色", caps.voices.map { it.label },
                            caps.voices.indexOfFirst { it.id == voice }.coerceAtLeast(0)
                        ) { i -> scope.launch { store.setTtsVoice(caps.voices[i].id) } }
                    }
                    if (caps.styles.isNotEmpty()) {
                        SegRow("朗读风格", caps.styles.map { it.label },
                            caps.styles.indexOfFirst { it.id == style }.coerceAtLeast(0)
                        ) { i -> scope.launch { store.setTtsStyle(caps.styles[i].id) } }
                    }
                    if (caps.formats.isNotEmpty()) {
                        SegRow("音频格式", caps.formats.map { it.uppercase() },
                            caps.formats.indexOf(format).coerceAtLeast(0)
                        ) { i -> scope.launch { store.setTtsFormat(caps.formats[i]) } }
                    }
                    run {
                        val labels = listOf("不缓存", "全部", "前20段", "前100段")
                        val values = listOf(-1, 0, 20, 100)
                        val idx = values.indexOf(cacheSegments).coerceAtLeast(0)
                        SegRow("缓存段落数", labels, idx) { i -> scope.launch { store.setTtsCacheSegments(values[i]) } }
                    }
                }
                NumSlider("朗读音量", volume, 0f..1f, { "${(it * 100).toInt()}%" }) {
                    scope.launch { store.setTtsVolume(it) }
                }
            } else if (engine == "local") {
                Text("本地大模型朗读尚未开放，当前将使用系统朗读。架构已预留同级选项，后续接入无需改动界面。",
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }

            // 试听：用当前配置的音色/语速/风格/格式朗读一段固定示例文本，调完参数即可当场验证。
            Spacer(Modifier.height(8.dp))
            Text("试听", fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            Text("可粘贴任意文本试听当前音色/语速/风格；留空则用内置示例文本。",
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            TextField(
                value = previewInput,
                onValueChange = { previewInput = it },
                label = "试听文本（可粘贴，留空用示例）",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (previewPlaying) previewPlayer.stop()
                        else previewPlayer.preview(active, previewInput)
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(if (previewPlaying) "停止试听" else "▶ 试听当前配置") }
            }
            if (previewMessage != null) {
                Text(previewMessage!!, fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
        }
    }

    if (showNameEditor) {
        OverlayDialog(
            show = true,
            title = if (editingId == null) "新增朗读配置" else "重命名配置",
            onDismissRequest = { showNameEditor = false },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextField(value = nameInput, onValueChange = { nameInput = it }, label = "配置名称",
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                DialogActions(
                    cancelText = "取消",
                    confirmText = "保存",
                    confirmEnabled = nameInput.isNotBlank(),
                    onCancel = { showNameEditor = false },
                    onConfirm = {
                        val nm = nameInput
                        val eid = editingId
                        scope.launch {
                            if (eid == null) store.addTtsProfile(nm) else store.renameTtsProfile(eid, nm)
                            showNameEditor = false
                        }
                    },
                )
            }
        }
    }
}

private fun ttsEngineLabel(engine: String) = when (engine) {
    "system" -> "系统 TTS"
    "network" -> "网络 TTS"
    "local" -> "本地大模型"
    else -> engine
}

@Composable
private fun NumSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = MiuixTheme.textStyles.body2.fontSize, color = MiuixTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(format(local), fontSize = MiuixTheme.textStyles.body2.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SegRow(label: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, fontSize = MiuixTheme.textStyles.body2.fontSize, color = MiuixTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        // 音色/风格选项可能十来个，单行 weight(1f) 会挤成一团看不清；改成按内容宽度自动换行。
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            labels.forEachIndexed { i, text ->
                Button(onClick = { onSelect(i) },
                    colors = if (i == selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                ) { Text(text, maxLines = 1, fontSize = 12.sp) }
            }
        }
    }
}
