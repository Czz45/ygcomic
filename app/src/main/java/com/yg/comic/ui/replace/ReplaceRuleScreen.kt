package com.yg.comic.ui.replace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yg.comic.data.BookRepository
import com.yg.comic.model.ReplaceRule
import com.yg.comic.shared.TextPurifier
import com.yg.comic.ui.components.DialogActions
import com.yg.comic.ui.components.LargeTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.HttpURLConnection
import java.net.URL

/**
 * 屏蔽池 / 替换规则管理页。
 * - 规则的增删改查、启用禁用、作用域（正文/标题）切换。
 * - 导入别人分享的屏蔽池：粘贴 JSON 或 URL（兼容 legado ReplaceRule 字段）。
 */
@Composable
fun ReplaceRuleScreen(
    repo: BookRepository,
    onBack: () -> Unit,
) {
    val rules by repo.allReplaceRules.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ReplaceRule?>(null) }
    var showImport by remember { mutableStateOf(false) }

    // 编辑器字段
    var fName by remember { mutableStateOf("") }
    var fGroup by remember { mutableStateOf("") }
    var fPattern by remember { mutableStateOf("") }
    var fReplacement by remember { mutableStateOf("") }
    var fScope by remember { mutableStateOf("") }
    var fExclude by remember { mutableStateOf("") }
    var fIsRegex by remember { mutableStateOf(true) }
    var fTitle by remember { mutableStateOf(false) }
    var fContent by remember { mutableStateOf(true) }
    var fEnabled by remember { mutableStateOf(true) }

    // 导入字段
    var importText by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }

    // 从本地文件导入屏蔽池 JSON（SAF）
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val body = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    if (body != null) {
                        importText = body
                        val parsed = parseReplaceRulesJson(body)
                        if (parsed != null && parsed.isNotEmpty()) {
                            repo.addReplaceRules(parsed)
                            importMsg = "从文件成功导入 ${parsed.size} 条规则"
                            showImport = false
                        } else {
                            importMsg = "文件里没解析出规则，请确认是 legado 替换规则 JSON"
                            showImport = true
                        }
                    }
                } catch (e: Exception) {
                    importMsg = "读取文件失败：${e.message}"
                    showImport = true
                }
            }
        }
    }

    fun openEditor(rule: ReplaceRule?) {
        editing = rule
        fName = rule?.name ?: ""
        fGroup = rule?.group ?: ""
        // 旧规则可能存的是 Regex.escape 生成的 \Q...\E 字面量引用：显示时解包成原文，
        // 并当作字面规则处理，避免再次编辑保存又把 \Q\E 写回去。
        fPattern = rule?.pattern?.let { TextPurifier.unwrapQuoted(it) } ?: ""
        fReplacement = rule?.replacement ?: ""
        fScope = rule?.scope ?: ""
        fExclude = rule?.excludeScope ?: ""
        fIsRegex = if (rule?.pattern?.startsWith("\\Q") == true) false else (rule?.isRegex ?: true)
        fTitle = rule?.scopeTitle ?: false
        fContent = rule?.scopeContent ?: true
        fEnabled = rule?.isEnabled ?: true
        showEditor = true
    }

    Scaffold { _ ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                LargeTitle(
                    title = "替换 / 净化",
                    onBack = onBack,
                    actions = {
                        IconButton(onClick = { showImport = true }) {
                            Icon(Icons.Filled.Download, contentDescription = "导入替换规则")
                        }
                        IconButton(onClick = { openEditor(null) }) {
                            Icon(Icons.Filled.Add, contentDescription = "新建规则")
                        }
                    },
                )
            }
            item {
                Text(
                    "去广告、去乱码、替换正文与标题。规则按 legado 格式：pattern 为要匹配的内容，replacement 为替换为；开启正则时用正则表达式。",
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { showImport = true }, modifier = Modifier.weight(1f)) {
                        Text("导入替换规则")
                    }
                    Button(onClick = { openEditor(null) }, modifier = Modifier.weight(1f)) {
                        Text("新建规则")
                    }
                }
                if (rules.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "还没有规则。可导入别人做好的替换规则，或点右上角 ＋ 新建。",
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            items(rules, key = { it.id }) { r ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            val shownName = TextPurifier.unwrapQuoted(r.name).ifBlank { "（未命名）" }
                            val shownPattern = TextPurifier.unwrapQuoted(r.pattern)
                            val isQuotedLegacy = r.name.startsWith("\\Q") || r.pattern.startsWith("\\Q")
                            Text(
                                shownName,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val scopeText = buildString {
                                if (r.scopeContent) append("正文 ")
                                if (r.scopeTitle) append("标题 ")
                                if (isEmpty()) append("无作用域 ")
                                if (r.isRegex && !isQuotedLegacy) append("· 正则")
                                if (isQuotedLegacy) append("· 原文")
                            }
                            Text(
                                "${r.group.ifBlank { "未分组" }} · $scopeText",
                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "匹配：$shownPattern",
                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "替换为：${r.replacement.ifEmpty { "（空）" }}",
                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(checked = r.isEnabled, onCheckedChange = { scope.launch { repo.setReplaceRuleEnabled(r.id, it) } })
                        IconButton(onClick = { openEditor(r) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑")
                        }
                        IconButton(onClick = { scope.launch { repo.removeReplaceRule(r.id) } }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
        }
    }

    // 新建/编辑对话框
    if (showEditor) {
        OverlayDialog(
            show = true,
            title = if (editing == null) "新建替换规则" else "编辑替换规则",
            summary = "pattern 为要匹配的内容，replacement 为替换为。",
            onDismissRequest = { showEditor = false },
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            ) {
                SectionLabel("基本信息")
                TextField(value = fName, onValueChange = { fName = it }, label = "名称", singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                TextField(value = fGroup, onValueChange = { fGroup = it }, label = "分组（可选，如：广告 / 乱码）", singleLine = true, modifier = Modifier.fillMaxWidth())

                Spacer(Modifier.height(18.dp))
                SectionLabel("匹配与替换")
                TextField(value = fPattern, onValueChange = { fPattern = it }, label = "匹配内容 pattern", modifier = Modifier.fillMaxWidth().height(88.dp))
                Spacer(Modifier.height(10.dp))
                TextField(value = fReplacement, onValueChange = { fReplacement = it }, label = "替换为 replacement（留空即删除匹配内容）", singleLine = true, modifier = Modifier.fillMaxWidth())

                Spacer(Modifier.height(18.dp))
                SectionLabel("作用范围（可选，来源 URL 正则）")
                TextField(value = fScope, onValueChange = { fScope = it }, label = "仅在这些来源生效", singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                TextField(value = fExclude, onValueChange = { fExclude = it }, label = "排除这些来源", singleLine = true, modifier = Modifier.fillMaxWidth())

                Spacer(Modifier.height(18.dp))
                SectionLabel("选项")
                SwitchRow("使用正则表达式", fIsRegex) { fIsRegex = it }
                SwitchRow("作用于正文", fContent) { fContent = it }
                SwitchRow("作用于标题", fTitle) { fTitle = it }
                SwitchRow("启用此规则", fEnabled) { fEnabled = it }

                Spacer(Modifier.height(16.dp))
                DialogActions(
                    cancelText = "取消",
                    confirmText = "保存",
                    confirmEnabled = fPattern.isNotBlank(),
                    onCancel = { showEditor = false },
                    onConfirm = {
                        val rule = ReplaceRule(
                            id = editing?.id ?: 0,
                            name = fName, group = fGroup, pattern = fPattern, replacement = fReplacement,
                            scope = fScope, excludeScope = fExclude, isRegex = fIsRegex,
                            scopeTitle = fTitle, scopeContent = fContent, isEnabled = fEnabled,
                            order = editing?.order ?: 0,
                        )
                        scope.launch {
                            repo.addReplaceRule(rule)
                            showEditor = false
                        }
                    },
                )
            }
        }
    }

    // 导入对话框
    if (showImport) {
        OverlayDialog(
            show = true,
            title = "导入替换规则",
            summary = "导入别人做好的替换规则：选本地 JSON 文件、粘贴内容/订阅链接，或读取剪贴板。兼容 legado 替换规则格式。",
            onDismissRequest = { showImport = false },
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { filePicker.launch("*/*") }, modifier = Modifier.weight(1f)) {
                        Text("从文件导入")
                    }
                    Button(
                        onClick = {
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val text = clip.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            if (text.isNotBlank()) importText = text else importMsg = "剪贴板里没有内容"
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("读取剪贴板")
                    }
                }
                Spacer(Modifier.height(14.dp))
                SectionLabel("或粘贴 JSON / 订阅链接")
                TextField(
                    value = importText, onValueChange = { importText = it },
                    label = "JSON 或 URL", modifier = Modifier.fillMaxWidth().height(120.dp),
                )
                if (importMsg.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(importMsg, fontSize = MiuixTheme.textStyles.footnote1.fontSize, color = MiuixTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp))
                DialogActions(
                    cancelText = "取消",
                    confirmText = if (importing) "导入中…" else "导入",
                    confirmEnabled = importText.isNotBlank() && !importing,
                    onCancel = { showImport = false },
                    onConfirm = {
                        importing = true
                        importMsg = "正在导入…"
                        scope.launch {
                            val parsed = parseImport(importText)
                            if (parsed != null && parsed.isNotEmpty()) {
                                repo.addReplaceRules(parsed)
                                importMsg = "成功导入 ${parsed.size} 条规则"
                                showImport = false
                                importText = ""
                            } else {
                                importMsg = "没有解析出规则，请检查 JSON 格式或链接"
                            }
                            importing = false
                        }
                    },
                )
            }
        }
    }
}

/** 解析导入文本：URL 先下载，再按 legado ReplaceRule JSON 解析为 ReplaceRule 列表。 */
private suspend fun parseImport(text: String): List<ReplaceRule>? = withContext(Dispatchers.IO) {
    try {
        val json = text.trim()
        val body = if (json.startsWith("http://", true) || json.startsWith("https://", true)) {
            val conn = URL(json).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.requestMethod = "GET"
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } else json
        parseReplaceRulesJson(body)
    } catch (e: Exception) {
        null
    }
}

/** 兼容 legado 字段：name/group/pattern/replacement/scope/scopeTitle/scopeContent/excludeScope/isEnabled/isRegex/timeoutMillisecond/order|sortOrder */
private fun parseReplaceRulesJson(body: String): List<ReplaceRule>? {
    val trimmed = body.trim()
    val arr = when {
        trimmed.startsWith("[") -> JSONArray(trimmed)
        trimmed.startsWith("{") -> JSONArray().put(JSONObject(trimmed))
        else -> return null
    }
    val out = mutableListOf<ReplaceRule>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        // 支持订阅包裹 {"replaceRules":[...]}
        if (o.has("replaceRules")) {
            val nested = parseReplaceRulesJson(o.getJSONArray("replaceRules").toString())
            if (nested != null) out.addAll(nested)
            continue
        }
        val pattern = o.optString("pattern", "")
        if (pattern.isBlank()) continue
        out.add(
            ReplaceRule(
                name = o.optString("name", ""),
                group = o.optString("group", ""),
                pattern = pattern,
                replacement = o.optString("replacement", ""),
                scope = o.optString("scope", ""),
                scopeTitle = o.optBoolean("scopeTitle", false),
                scopeContent = if (o.has("scopeContent")) o.optBoolean("scopeContent", true) else true,
                excludeScope = o.optString("excludeScope", ""),
                isEnabled = if (o.has("isEnabled")) o.optBoolean("isEnabled", true) else true,
                isRegex = if (o.has("isRegex")) o.optBoolean("isRegex", true) else true,
                timeoutMillisecond = o.optLong("timeoutMillisecond", 3000L),
                order = o.optInt("order", o.optInt("sortOrder", 0)),
            )
        )
    }
    return out
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
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
