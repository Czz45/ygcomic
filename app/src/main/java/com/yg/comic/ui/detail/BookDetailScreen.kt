package com.yg.comic.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yg.comic.ui.components.LargeTitle
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 书籍详情页：封面 / 书名 / 作者 / 字数 / 分类 / 简介 / 章节目录，
 * 支持在多个来源间切换（换源），加入书架带封面，开始阅读直接跳转阅读页。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookDetailScreen(
    vm: BookDetailViewModel,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    val detail by vm.detail.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val origins by vm.origins.collectAsState()
    val originIndex by vm.originIndex.collectAsState()
    val loading by vm.loading.collectAsState()
    val tocReady by vm.tocReady.collectAsState()
    val isAdded by vm.isAdded.collectAsState()
    val message by vm.message.collectAsState()
    val thumbState by vm.thumbState.collectAsState()
    val chapterReversed by vm.chapterReversed.collectAsState()
    val scope = rememberCoroutineScope()
    val startTempRead: (Int) -> Unit = { chapterIndex ->
        if (!tocReady) {
            vm.setMessage("目录加载中，请稍候再点阅读")
        } else {
            val temp = vm.prepareTempRead(chapterIndex)
            if (temp != null) {
                SharedTempRead.post(temp)
                vm.clearMessage()
                onOpenBook(-1L)
            }
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 顶部返回栏
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                    LargeTitle(title = "书籍详情")
                }
            }

            if (loading && detail == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("正在加载书籍信息…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    val cover = detail?.coverUrl
                    AsyncImage(
                        model = cover,
                        contentDescription = detail?.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(112.dp)
                            .height(156.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MiuixTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            detail?.name ?: "",
                            fontWeight = FontWeight.Bold,
                            fontSize = MiuixTheme.textStyles.title3.fontSize,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "作者：${detail?.author?.ifBlank { "未知" } ?: "未知"}",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            "分类：${detail?.kind?.ifBlank { "未分类" } ?: "未分类"}",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            "字数：${detail?.wordCount?.ifBlank { "未知" } ?: "未知"}",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            "最新：${detail?.lastChapter?.ifBlank { "未知" } ?: "未知"}",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // 换源：主源 + 其它来源；每个来源旁可赞/踩，作用于整个书源
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        "来源（${origins.size}）",
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        "点赞 / 点踩是对整个书源的操作，会影响其它书",
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        origins.forEachIndexed { i, origin ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(end = 10.dp),
                            ) {
                                Button(
                                    onClick = { if (i != originIndex) vm.selectOrigin(i) },
                                    colors = if (i == originIndex) ButtonDefaults.buttonColorsPrimary()
                                    else ButtonDefaults.buttonColors(),
                                ) {
                                    Text(origin.sourceName.ifBlank { "来源 ${i + 1}" })
                                }
                                Row {
                                    val thumb = thumbState[origin.sourceJson + "|" + origin.bookUrl] ?: 0
                                    IconButton(onClick = { vm.recordThumb(i, true) }) {
                                        Icon(
                                            Icons.Filled.ThumbUp,
                                            contentDescription = "赞整个书源",
                                            tint = if (thumb == 1) Color(0xFF2196F3) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                    IconButton(onClick = { vm.recordThumb(i, false) }) {
                                        Icon(
                                            Icons.Filled.ThumbDown,
                                            contentDescription = "踩整个书源",
                                            tint = if (thumb == -1) Color(0xFF2196F3) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 简介
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("简介", fontWeight = FontWeight.Bold, fontSize = MiuixTheme.textStyles.body1.fontSize)
                    Spacer(Modifier.height(4.dp))
                    val intro = detail?.intro?.ifBlank { "暂无简介" } ?: "暂无简介"
                    SelectionContainer {
                        Text(intro, fontSize = MiuixTheme.textStyles.body2.fontSize)
                    }
                }
            }

            // 底部操作：放在章节目录之前，避免翻两百章才看到「开始阅读」
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val id = vm.addToShelf()
                                if (id != null) vm.clearMessage()
                            }
                        },
                        enabled = !isAdded,
                        colors = if (isAdded) ButtonDefaults.buttonColorsPrimary()
                            else ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (isAdded) "✓ 已加入" else "加入书架") }
                    Button(
                        onClick = {
                            // 临时阅读：不加入书架，进度不落库；用 bookId=-1L 标识
                            // 「开始阅读」从当前显示序的第一条正文开始：反序时列表第一条是最新章，
                            // 必须过 originalChapterIndex 换成原始章号，否则永远跳到最旧的第 0 章。
                            // 跳过开头可能存在的卷标题(volume)，与目录项点击口径一致。
                            val firstReadable = chapters.indexOfFirst { !it.isVolume }.coerceAtLeast(0)
                            startTempRead(vm.originalChapterIndex(firstReadable))
                        },
                        enabled = tocReady,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (tocReady) "开始阅读" else "目录加载中…") }
                }
            }

            // 章节目录（在「开始阅读」按钮下方）
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "目录（${chapters.size} 章）",
                        fontWeight = FontWeight.Bold,
                        fontSize = MiuixTheme.textStyles.body1.fontSize,
                        modifier = Modifier.weight(1f),
                    )
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
            }
            itemsIndexed(chapters.take(200)) { idx, ch ->
                Text(
                    "${if (ch.isVolume) "▍" else ""}${ch.title}",
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = if (ch.isVolume) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!ch.isVolume) Modifier.combinedClickable(
                                onClick = { startTempRead(vm.originalChapterIndex(idx)) },
                                onLongClick = { vm.enqueueChapterPreload(vm.originalChapterIndex(idx)) },
                            ) else Modifier
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (chapters.size > 200) {
                item {
                    Text(
                        "… 其余 ${chapters.size - 200} 章进入阅读后查看",
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }

            if (message.isNotBlank()) {
                item {
                    Text(
                        message,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
