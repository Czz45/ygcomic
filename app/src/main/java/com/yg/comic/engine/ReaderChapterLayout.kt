package com.yg.comic.engine

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.max

/**
 * 整块移植自 novel-sound（https://github.com/ch6vip/novel-sound）
 * 文件：lib/widgets/reader/reader_chapter_layout.dart
 * 原项目许可证：GPL-3.0（YGComic 已在 GitHub 开源，按同协议署名保留）。
 *
 * 只把 Flutter 绘图 API 一对一换成 Compose 等价物：
 *   TextPainter.layout + computeLineMetrics + getLineBoundary  ->  TextMeasurer.measure -> TextLayoutResult
 *   PlaceholderSpan(首行缩进)                                  ->  两个全角空格 "　　"（leading=2）
 * 算法、结构、类名、判定条件、分页顺序全部原样保留，未改写。
 *
 * 与旧 FlowReader 方案的本质区别：
 *   FlowReader 把段落按字符二分「重排子串」，跨页会重新断行、重复缩进；
 *   本引擎先把「整段」排版成若干行（ ReaderTextLine 记录每行精确像素边界），
 *   分页只按「行」切片（ ReaderPageFragment 只记 firstLine..lastLine 行区间），
 *   渲染时整段画成一个 Text，用裁剪 + 位移只显示该页的行窗口——段落永不被字符串切割。
 */

/** 移植自 ReaderTextLine：一行的精确像素边界（含该行在整段中的字符区间）。 */
data class ReaderTextLine(
    val start: Int,
    val end: Int,
    val top: Float,
    val bottom: Float,
) {
    val height: Float get() = bottom - top
}

/**
 * 单个字符在整段排版中的精确像素盒（坐标同源核心）。
 * 排版时从 TextLayoutResult 一次性提取，渲染与选字共用这一份；
 * 简繁 / 字体 / 缩进 / 图片回退等任何参数变化两端同时跟随，杜绝两套坐标错位。
 * charIndex 是 measure 文本(shown，含缩进)的 UTF-16 字符下标；charData 直接取自 shown。
 */
data class ReaderCharBox(
    val charIndex: Int,
    val charData: String,
    val left: Float,
    val right: Float,
    val lineIndex: Int,
)

/** 移植自 ReaderContentBlock：整段排版结果，lines 保存每一行；段落不再被拆分重排。 */
data class ReaderContentBlock(
    val index: Int,
    val text: String,
    val start: Int,
    val top: Float,
    val height: Float,
    val align: TextAlign,
    val lines: List<ReaderTextLine>,
    val isTitle: Boolean,
    val indent: Boolean,
    // —— 坐标同源新增（带默认值，兼容原有构造点）——
    val charBoxes: List<ReaderCharBox> = emptyList(),
    /** 首行 top 原始值：渲染端 place(0,-sourceTopPx) 用的是未归一化行 top，选字端需补回同一基准。 */
    val baseTop: Float = 0f,
) {
    val end: Int get() = start + text.length
}

/**
 * 移植自 ReaderPageFragment。
 * A slice retains its original paragraph layout, including justification.
 * Reflowing a substring here would change line breaks and repeat indentation.
 */
data class ReaderPageFragment(
    val block: ReaderContentBlock,
    val firstLine: Int,
    val lastLine: Int,
    val top: Float,
) {
    val sourceTop: Float get() = block.lines[firstLine].top
    val height: Float get() = block.lines[lastLine].bottom - sourceTop
    val start: Int get() = block.start + block.lines[firstLine].start
    val end: Int get() = block.start + block.lines[lastLine].end
    val text: String get() = block.text.substring(start - block.start, end - block.start)
}

/** 移植自 ReaderTextPage：一页由若干「行窗口片段」组成。 */
data class ReaderTextPage(val fragments: List<ReaderPageFragment>) {
    val start: Int get() = fragments.first().start
    val end: Int get() = fragments.last().end
    val height: Float get() = fragments.last().top + fragments.last().height
}

/** 移植自 ReaderChapterLayout：整章排版 = 逐块整段排版 + 按行分页。 */
class ReaderChapterLayout(
    val spec: ReaderLayoutSpec,
    val blocks: List<ReaderContentBlock>,
    val pages: List<ReaderTextPage>,
    val textLength: Int,
    val contentHeight: Float,
) {
    companion object {
        /**
         * 移植自 ReaderChapterLayout.factory。
         * [measure] 由 UI 层用 TextMeasurer 实现，style/约束与渲染端严格一致（measure/render 同源）。
         * [measure] 的 indent 参数表示要不要给首行加两个全角空格；返回的是「已含缩进」文本的布局结果。
         */
        fun build(
            title: String,
            paragraphs: List<String>,
            spec: ReaderLayoutSpec,
            measure: (text: String, isTitle: Boolean, indent: Boolean) -> TextLayoutResult,
            display: (raw: String, indent: Boolean) -> String,
        ): ReaderChapterLayout {
            val blocks = ArrayList<ReaderContentBlock>(paragraphs.size + 1)
            var offset = 0
            var top = 0f
            // 标题是 block 0，随后是各正文段落（与原实现一致：[title, ...body]）。
            val elements = ArrayList<Pair<String, Boolean>>(paragraphs.size + 1)
            elements.add(title to true)
            paragraphs.forEach { elements.add(it to false) }

            for ((index, element) in elements.withIndex()) {
                val text = element.first
                val isTitle = element.second
                val block = measureBlock(
                    text = text,
                    index = index,
                    start = offset,
                    top = top,
                    isTitle = isTitle,
                    spec = spec,
                    measure = measure,
                    display = display,
                )
                blocks.add(block)
                // 空标题既不占文本也不占前导换行（原实现：block.isTitle && text.isEmpty ? 0 : 1）。
                offset += text.length + if (isTitle && text.isEmpty()) 0 else 1
                if (block.lines.isNotEmpty()) {
                    top += block.height + spec.paragraphSpacing
                }
            }
            val pages = paginate(blocks, spec)
            return ReaderChapterLayout(
                spec,
                blocks,
                pages,
                maxOf(0, offset - 1),
                top,
            )
        }

        /** 移植自 _measureBlock：整段 measure 一次，提取每一行的 start/end/top/bottom。 */
        private fun measureBlock(
            text: String,
            index: Int,
            start: Int,
            top: Float,
            isTitle: Boolean,
            spec: ReaderLayoutSpec,
            measure: (text: String, isTitle: Boolean, indent: Boolean) -> TextLayoutResult,
            display: (raw: String, indent: Boolean) -> String,
        ): ReaderContentBlock {
            val indent = !isTitle && spec.firstLineIndent
            // 首行缩进占位：两个全角空格，对应原实现的 WidgetSpan 缩进（leading = 2）。
            val leading = if (indent) 2 else 0
            val align = if (isTitle) spec.titleAlign
            else if (spec.justifyText) TextAlign.Justify else TextAlign.Start

            if (text.isEmpty()) {
                return ReaderContentBlock(index, text, start, top, 0f, align, emptyList(), isTitle, indent)
            }

            val result = measure(text, isTitle, indent)
            val starts = ArrayList<Int>()
            val tops = ArrayList<Float>()
            for (i in 0 until result.lineCount) {
                // 去掉缩进占位，映射回原始 text 的字符坐标。
                starts.add((result.getLineStart(i) - leading).coerceIn(0, text.length))
                // 用真实行 top（getLineTop），不要伪造首行 0：
                // TextLayoutResult 的首行 top 在若干 lineHeight 下并非 0，伪造会让每行高度失真、
                // 分页累加超过实际页高而不切页（表现为段落整段塞进一页后被裁掉大半）。
                tops.add(result.getLineTop(i))
            }
            if (starts.isEmpty()) {
                starts.add(0)
                tops.add(0f)
            }
            // 归一化：把首行 top 平移到 0（仅整体平移，行间相对高度完整保留），
            // 使 lines 坐标 == Text 渲染时相对文本顶部的坐标（渲染端 Text 顶部即第0行）。
            val baseTop = tops[0]
            if (baseTop != 0f) {
                for (i in tops.indices) tops[i] = tops[i] - baseTop
            }

            // grapheme 边界（对应 Dart 的 text.characters）：emoji 等多码点字形不跨行切。
            val boundaries = graphemeBoundaries(text)
            // Some fallback fonts wrap a multi-codepoint emoji onto several lines.
            // Such a cluster, and any indent-only line, must stay on the same page.
            val cuts = ArrayList<Int>()
            cuts.add(0)
            for (i in 1 until starts.size) {
                if (starts[i] > starts[cuts.last()] &&
                    starts[i] < text.length &&
                    boundaries.contains(starts[i])
                ) {
                    cuts.add(i)
                }
            }

            val lines = cuts.mapIndexed { k, cut ->
                ReaderTextLine(
                    starts[cut],
                    if (k + 1 < cuts.size) starts[cuts[k + 1]] else text.length,
                    tops[cut],
                    if (k + 1 < cuts.size) tops[cuts[k + 1]] else result.size.height.toFloat(),
                )
            }
            // 坐标同源：排版阶段就把 TextLayoutResult 里每个字符的真实 box 存下来，
            // 选字层直接复用这一份坐标，不再二次 measure —— 病根（两套坐标靠参数一致凑对齐）就在这里断掉。
            // lineIndex 用 lines 的下标 k，与 buildSelectionIndex 遍历 block.lines[li] 对齐；
            // charIndex 用 shown(显示文本)的 UTF-16 下标，与 getBoundingBox(idx) 同源。
            val shown = display(text, indent)
            val charBoxes = ArrayList<ReaderCharBox>()
            for (k in cuts.indices) {
                val row = cuts[k]
                if (row >= result.lineCount) continue
                val lineStart = result.getLineStart(row)
                val lineEnd = result.getLineEnd(row).coerceAtMost(shown.length)
                for (idx in lineStart until lineEnd) {
                    if (idx >= shown.length) break
                    val box = result.getBoundingBox(idx)
                    charBoxes.add(
                        ReaderCharBox(
                            charIndex = idx,
                            charData = shown[idx].toString(),
                            left = box.left,
                            right = box.right.coerceAtLeast(box.left + 1f),
                            lineIndex = k,
                        )
                    )
                }
            }
            return ReaderContentBlock(
                index, text, start, top, result.size.height.toFloat(), align, lines, isTitle, indent,
                charBoxes, baseTop,
            )
        }

        /** Dart `text.characters` 的等价：grapheme cluster 边界。 */
        private fun graphemeBoundaries(text: String): Set<Int> {
            val out = HashSet<Int>()
            out.add(0)
            val iter = java.text.BreakIterator.getCharacterInstance()
            iter.setText(text)
            var p = iter.first()
            while (p != java.text.BreakIterator.DONE) {
                if (p > 0) out.add(p)
                p = iter.next()
            }
            return out
        }

        /** 移植自 _paginate：段落先整段排成行，分页只按「行」切片，跨页不切词、不重排、不留白。 */
        private fun paginate(
            blocks: List<ReaderContentBlock>,
            spec: ReaderLayoutSpec,
        ): List<ReaderTextPage> {
            val pages = ArrayList<ReaderTextPage>()
            var fragments = ArrayList<ReaderPageFragment>()
            var used = 0f
            fun finishPage() {
                if (fragments.isEmpty()) return
                pages.add(ReaderTextPage(fragments.toList()))
                fragments = ArrayList()
                used = 0f
            }

            for (block in blocks) {
                if (block.lines.isEmpty()) continue
                for (i in block.lines.indices) {
                    val line = block.lines[i]
                    if (fragments.isNotEmpty() && used + line.height > spec.pageHeight + 0.001f) {
                        finishPage()
                    }
                    if (fragments.isNotEmpty() && fragments.last().block === block) {
                        // 同一段的行「延长」上一个 fragment 的 lastLine（整段连续，不被切）。
                        val previous = fragments.removeAt(fragments.lastIndex)
                        fragments.add(
                            ReaderPageFragment(
                                block = block,
                                firstLine = previous.firstLine,
                                lastLine = i,
                                top = previous.top,
                            ),
                        )
                    } else {
                        fragments.add(
                            ReaderPageFragment(
                                block = block,
                                firstLine = i,
                                lastLine = i,
                                top = used,
                            ),
                        )
                    }
                    used += line.height
                }
                used += spec.paragraphSpacing
            }
            finishPage()
            return pages
        }
    }
}

/** 移植自 ReaderLayoutSpec：布局参数（全像素）。 */
data class ReaderLayoutSpec(
    val width: Float,
    val pageHeight: Float,
    val paragraphSpacing: Float,
    val firstLineIndent: Boolean,
    val justifyText: Boolean = true,
    val titleAlign: TextAlign = TextAlign.Start,
)
