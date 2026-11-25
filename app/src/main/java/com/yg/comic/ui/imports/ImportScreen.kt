package com.yg.comic.ui.imports

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yg.comic.data.BookRepository
import com.yg.comic.engine.SourceKind
import com.yg.comic.ui.components.DialogActions
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun ImportScreen(
    repo: BookRepository,
    onBack: () -> Unit,
    vm: ImportViewModel = viewModel { ImportViewModel(repo) },
) {
    val context = LocalContext.current
    val step by vm.step.collectAsState()
    val candidates by vm.candidates.collectAsState()
    val progress by vm.progress.collectAsState()
    val error by vm.error.collectAsState()
    val displayName by vm.displayName.collectAsState()
    val collections by vm.collections.collectAsState()
    val categoryMode by vm.categoryMode.collectAsState()
    val existingCollectionId by vm.existingCollectionId.collectAsState()
    val customName by vm.customName.collectAsState()

    var password by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Int>() }
    var showCategory by remember { mutableStateOf(false) }
    // 分类弹窗内的暂存选择：点选项只改暂存，按「确定」才提交，避免输入法/点击导致浮层抖动
    var draftMode by remember { mutableStateOf(categoryMode) }
    var draftExistingId by remember { mutableStateOf(existingCollectionId) }
    LaunchedEffect(showCategory, categoryMode, existingCollectionId) {
        if (showCategory) { draftMode = categoryMode; draftExistingId = existingCollectionId }
    }

    // 换了一批候选后默认全选（显式维护选中集合，空集=一个都不选）
    LaunchedEffect(candidates) {
        selected.clear()
        selected.addAll(candidates.indices)
    }

    val categoryLabel = when (categoryMode) {
        CategoryMode.ROOT -> "不归类 · 放书架顶层"
        CategoryMode.AUTO_NEW -> "自动新建「$displayName」"
        CategoryMode.CUSTOM_NEW -> "新建「${customName.ifBlank { "…" }}」"
        CategoryMode.EXISTING -> collections.firstOrNull { it.id == existingCollectionId }?.name?.let { "归入「$it」" } ?: "选择分类"
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // PDF/TXT 直接读原文件引用，必须持久化 URI 权限，否则重启后打不开
        uris.forEach { u ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        if (uris.isNotEmpty()) vm.onSourcesPicked(uris, context)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            vm.onSourcesPicked(listOf(uri), context)
        }
    }

    Scaffold { _ ->
        Column(Modifier.fillMaxSize()) {
            SmallTopAppBar(
                title = "导入漫画",
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") } },
            )

            // 分类选择常驻条：选择前/确认时都能一眼看到并修改（用户要的"快速分类"）
            if (step == ImportStep.PICK || step == ImportStep.CONFIRM) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    onClick = { showCategory = true },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("归入分类", fontSize = MiuixTheme.textStyles.body2.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(categoryLabel, fontSize = MiuixTheme.textStyles.body1.fontSize, maxLines = 1)
                        }
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }

            when (step) {
                ImportStep.PICK -> {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("选择要导入的内容", fontSize = MiuixTheme.textStyles.title4.fontSize)
                        Text(
                            "支持 ZIP / 7z / RAR / PDF / TXT 小说与图片文件夹，可多选。PDF、TXT 不复制、直接打开原文件实时阅读。",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Card(modifier = Modifier.fillMaxWidth(), onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                                Icon(Icons.Filled.InsertDriveFile, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("选择文件", fontSize = MiuixTheme.textStyles.body1.fontSize)
                                    Text("压缩包 / PDF / TXT / 图片", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        Card(modifier = Modifier.fillMaxWidth(), onClick = { pickFolder.launch(null) }) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("选择文件夹", fontSize = MiuixTheme.textStyles.body1.fontSize)
                                    Text("图片 / PDF / 压缩包，混合也行", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        Text(
                            "图片原样复制进本地库，PDF 直接读原文件不动它；带密码的压缩包密码会永久保存在系统 keystore。",
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                ImportStep.CONFIRM -> {
                    Column(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize().weight(1f), contentPadding = PaddingValues(16.dp)) {
                            item {
                                Text("$displayName · 发现 ${candidates.size} 个可拆分单元", fontSize = MiuixTheme.textStyles.body2.fontSize)
                                Text("勾选要作为一本书导入的片段。", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                Spacer(Modifier.height(10.dp))
                            }
                            itemsIndexed(candidates) { idx, c ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    onClick = {
                                        if (selected.contains(idx)) selected.remove(idx) else selected.add(idx)
                                    },
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            state = if (selected.contains(idx)) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                                            onClick = {
                                                if (selected.contains(idx)) selected.remove(idx) else selected.add(idx)
                                            },
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(c.label, fontSize = MiuixTheme.textStyles.body1.fontSize, maxLines = 1)
                                            Text(
                                                if (c.kind == SourceKind.PDF) "PDF · 打开即实时渲染" else "约 ${c.pageCountHint} 页 · ${c.kind.name}",
                                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                            item {
                                Spacer(Modifier.height(14.dp))
                                TextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = "压缩包密码（可选，将永久记住）",
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        // 底部一键按类型选择 + 导入（常驻，不随列表滚走）
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("一键选择", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            // 2×2 网格：每个按钮有足够宽度，文字不会换行
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(text = "全选", modifier = Modifier.weight(1f), onClick = {
                                        selected.clear(); selected.addAll(candidates.indices)
                                    })
                                    TextButton(text = "仅 PDF", modifier = Modifier.weight(1f), onClick = {
                                        selected.clear(); candidates.forEachIndexed { i, c -> if (c.kind == SourceKind.PDF) selected.add(i) }
                                    })
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(text = "仅压缩包", modifier = Modifier.weight(1f), onClick = {
                                        selected.clear(); candidates.forEachIndexed { i, c ->
                                            if (c.kind == SourceKind.ZIP || c.kind == SourceKind.SEVENZ || c.kind == SourceKind.RAR) selected.add(i)
                                        }
                                    })
                                    TextButton(text = "清空", modifier = Modifier.weight(1f), onClick = { selected.clear() })
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(text = "返回", modifier = Modifier.weight(1f), onClick = { vm.reset() })
                                Button(
                                    modifier = Modifier.weight(2f),
                                    enabled = selected.isNotEmpty(),
                                    // 用户要求「导入选中」用蓝色按钮：走项目现成的 primary/onPrimary 主题色 token，不硬编码。
                                    colors = ButtonDefaults.buttonColors(
                                        color = MiuixTheme.colorScheme.primary,
                                        contentColor = MiuixTheme.colorScheme.onPrimary,
                                    ),
                                    onClick = {
                                        vm.startImport(selected.toList(), password, context)
                                    },
                                ) { Text("导入选中 (${selected.size})") }
                            }
                        }
                    }
                }
                ImportStep.IMPORTING -> {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("正在导入…", fontSize = MiuixTheme.textStyles.title4.fontSize)
                        Spacer(Modifier.height(12.dp))
                        Text("${progress.first} / ${progress.second}", fontSize = MiuixTheme.textStyles.body1.fontSize)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = if (progress.second > 0) progress.first.toFloat() / progress.second else 0f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { vm.cancel() }) { Text("取消") }
                    }
                }
                ImportStep.DONE -> {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(64.dp), tint = MiuixTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text("导入完成", fontSize = MiuixTheme.textStyles.title2.fontSize)
                        error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text("部分文件已跳过：$it", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = MiuixTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onBack) { Text("回到书架") }
                    }
                }
            }
        }
    }

    if (showCategory) {
        OverlayDialog(
            show = true,
            title = "导入后归入哪个分类",
            onDismissRequest = { showCategory = false },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                // 不归类
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    onClick = { draftMode = CategoryMode.ROOT; draftExistingId = null },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                        Text(if (draftMode == CategoryMode.ROOT) "◉" else "○", color = MiuixTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("不归类（放书架顶层）", fontSize = MiuixTheme.textStyles.body1.fontSize)
                    }
                }
                // 自动按来源名新建
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    onClick = { draftMode = CategoryMode.AUTO_NEW; draftExistingId = null },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                        Text(if (draftMode == CategoryMode.AUTO_NEW) "◉" else "○", color = MiuixTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("自动新建「$displayName」", fontSize = MiuixTheme.textStyles.body1.fontSize, maxLines = 1)
                    }
                }
                // 自定义名字：输入框 + 选中该项
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    onClick = { if (customName.isNotBlank()) { draftMode = CategoryMode.CUSTOM_NEW; draftExistingId = null } },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (draftMode == CategoryMode.CUSTOM_NEW) "◉" else "○", color = MiuixTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text("自定义分类名字", fontSize = MiuixTheme.textStyles.body1.fontSize)
                        }
                        Spacer(Modifier.height(10.dp))
                        TextField(
                            value = customName,
                            onValueChange = { vm.setCustomName(it); draftMode = CategoryMode.CUSTOM_NEW; draftExistingId = null },
                            label = "输入新分类名字",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // 已有分类
                if (collections.isNotEmpty()) {
                    Text("已有分类", fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp))
                    Column(Modifier.heightIn(max = 260.dp)) {
                        androidx.compose.foundation.lazy.LazyColumn {
                            itemsIndexed(collections) { _, c ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    onClick = { draftMode = CategoryMode.EXISTING; draftExistingId = c.id },
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                                        Text(if (draftMode == CategoryMode.EXISTING && draftExistingId == c.id) "◉" else "○", color = MiuixTheme.colorScheme.primary)
                                        Spacer(Modifier.width(12.dp))
                                        Text(c.name, fontSize = MiuixTheme.textStyles.body1.fontSize, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                DialogActions(
                    cancelText = "取消",
                    confirmText = "确定",
                    onCancel = { showCategory = false },
                    onConfirm = {
                        vm.setCategory(draftMode, if (draftMode == CategoryMode.EXISTING) draftExistingId else null)
                        showCategory = false
                    },
                )
            }
        }
    }
}
