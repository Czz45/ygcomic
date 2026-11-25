package com.yg.comic.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 直接移植自 legado 官方排版引擎（io.legado.desktop.engine.TextPagingEngine）。
 * 保留其原始算法：视觉宽度加权折行 + 中文避头尾 + 按行聚页，不做自造的字符偏移逻辑。
 *
 * 视觉宽度：CJK/全角 = 1.0，半角/ASCII = 0.55；
 * 每行容量 = 视口宽 / 字号；每页行数 = 视口高 / 行高（字号×行距）。
 * 字号变大 → 每行更短、每页更少行 → 页数必然变多，一页精确一屏。
 */
data class PagedChapter(
    val pages: List<String>,
    val totalCharCount: Int,
    val totalPages: Int = pages.size,
)

object TextPagingEngine {

    /** Measure visual width of a character: CJK/Full-width is 1.0, Half-width is 0.55 */
    private fun getCharWeight(c: Char): Float {
        return if (c.code in 0..127) 0.55f else 1.0f
    }

    val HEAD_FORBIDDEN_CHARS = setOf(
        '，', '。', '、', '；', '：', '？', '！',
        '）', ']', '}', '’', '”', '』', '」', '〉', '》', '】', '〕',
        ',', '.', ';', ':', '?', '!', ')', '\'', '"', '%', '…', '—',
    )

    val TAIL_FORBIDDEN_CHARS = setOf(
        '（', '[', '{', '‘', '“', '『', '「', '〈', '《', '【', '〔',
        '(', '\'', '"',
    )

    /** Splits a single paragraph into lines with Chinese Kinsoku Shori. */
    fun wrapParagraph(
        paragraph: String,
        maxVisualWidthUnits: Float,
        enableKinsoku: Boolean = true,
    ): List<String> {
        if (paragraph.isBlank()) return listOf("")
        if (!enableKinsoku) {
            val lines = mutableListOf<String>()
            val currentLine = StringBuilder()
            var currentWeight = 0f
            for (ch in paragraph) {
                val w = getCharWeight(ch)
                if (currentWeight + w > maxVisualWidthUnits && currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                    currentLine.clear()
                    currentWeight = 0f
                }
                currentLine.append(ch)
                currentWeight += w
            }
            if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
            return lines
        }

        val lines = mutableListOf<String>()
        val currentLine = StringBuilder()
        var currentWeight = 0f
        val chars = paragraph.toCharArray()
        var i = 0

        while (i < chars.size) {
            val ch = chars[i]
            val w = getCharWeight(ch)

            if (currentWeight + w > maxVisualWidthUnits && currentLine.isNotEmpty()) {
                // Rule 1: Punctuation Hanging
                if (ch in HEAD_FORBIDDEN_CHARS && currentWeight <= maxVisualWidthUnits + 0.1f) {
                    currentLine.append(ch)
                    lines.add(currentLine.toString())
                    currentLine.clear()
                    currentWeight = 0f
                    i++
                    continue
                }

                // Rule 2: Tail-forbidden check on the end of currentLine
                var poppedChar: Char? = null
                if (currentLine.isNotEmpty() && currentLine.last() in TAIL_FORBIDDEN_CHARS) {
                    poppedChar = currentLine.last()
                    currentLine.deleteCharAt(currentLine.length - 1)
                }

                // Rule 3: Head-forbidden check for next line's first char
                if (poppedChar == null && ch in HEAD_FORBIDDEN_CHARS && currentLine.length > 1) {
                    poppedChar = currentLine.last()
                    currentLine.deleteCharAt(currentLine.length - 1)
                }

                lines.add(currentLine.toString())
                currentLine.clear()
                currentWeight = 0f

                if (poppedChar != null) {
                    currentLine.append(poppedChar)
                    currentWeight += getCharWeight(poppedChar)
                }

                currentLine.append(ch)
                currentWeight += w
                i++
                continue
            }

            currentLine.append(ch)
            currentWeight += w
            i++
        }

        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return lines
    }

    /**
     * 与 paginate 相同排版，但额外返回每页首字符在原文 content 中的绝对偏移。
     * 用于进度定位（等价 legado durChapterPos），算法本体不变，只在分页循环里累计原文位置。
     */
    fun paginateWithOffsets(
        content: String,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        fontSizePx: Float,
        lineSpacingMultiplier: Float = 1.75f,
        paragraphSpacingPx: Float = 16f,
        firstLineIndent: Boolean = true,
        enableKinsoku: Boolean = true,
    ): List<PagedPage> {
        if (content.isBlank()) return listOf(PagedPage("", 0))

        val effectiveWidth = viewportWidthPx.coerceAtLeast(300f)
        val effectiveHeight = viewportHeightPx.coerceAtLeast(400f)
        val maxUnitsPerLine = max(10f, effectiveWidth / fontSizePx.coerceAtLeast(12f))
        val lineHeightPx = fontSizePx * lineSpacingMultiplier.coerceIn(1.0f, 4.5f)
        val maxLinesPerPage = max(6, floor(effectiveHeight / lineHeightPx).toInt())
        val paragraphExtraLines = (paragraphSpacingPx / lineHeightPx).coerceIn(0f, 2f)
        val maxUnitsPerLineSafe = max(6f, maxUnitsPerLine * 0.96f)

        val result = mutableListOf<PagedPage>()
        val currentPageText = StringBuilder()
        var currentLinesOnPage = 0f
        var pageStartOffset = 0
        var pageOffsetSet = false
        // 当前扫描位置在原文 content 中的绝对偏移
        var srcPos = 0

        // 保留原始行，以便精确计算每段/每行在原 content 中的位置
        val lines = content.split("\n")
        var lineStart = 0
        for (rawPara in lines) {
            val rawLineStart = lineStart
            lineStart += rawPara.length + 1 // + '\n'
            val trimmed = rawPara.trim()
            if (trimmed.isEmpty()) continue

            val leadTrim = rawPara.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            val paraSrcStart = rawLineStart + leadTrim

            val formattedPara = if (firstLineIndent) {
                if (!trimmed.startsWith("　　") && !trimmed.startsWith("  ")) "　　$trimmed" else trimmed
            } else trimmed

            val wrapped = wrapParagraph(formattedPara, maxUnitsPerLineSafe, enableKinsoku)
            // 整段作为一个整体分页：一个自然段的全部行绝不跨页拆分（否则读者看到"一段变多段"）。
            // 段末多算段间距成本。
            val paraCost = wrapped.size.toFloat() +
                (if (paragraphSpacingPx > 4f) paragraphExtraLines else 0f)

            // 当前页放不下整段 → 先结算当前页，整段移到新页（除非当前页为空，那这段独占一页）
            if (currentPageText.isNotEmpty() && currentLinesOnPage + paraCost > maxLinesPerPage) {
                result.add(PagedPage(currentPageText.toString().trimEnd(), pageStartOffset))
                currentPageText.clear()
                currentLinesOnPage = 0f
                pageOffsetSet = false
            }

            if (!pageOffsetSet) { pageStartOffset = paraSrcStart; pageOffsetSet = true }
            for (line in wrapped) {
                currentPageText.append(line).append("\n")
            }
            if (paragraphSpacingPx > 4f) currentPageText.append("\n")
            currentLinesOnPage += paraCost

            // 若这段已经把整页撑满（超长段），立即结算，避免段后内容继续堆在同一页
            if (currentLinesOnPage >= maxLinesPerPage) {
                result.add(PagedPage(currentPageText.toString().trimEnd(), pageStartOffset))
                currentPageText.clear()
                currentLinesOnPage = 0f
                pageOffsetSet = false
            }
        }
        if (currentPageText.isNotEmpty()) result.add(PagedPage(currentPageText.toString().trimEnd(), pageStartOffset))
        if (result.isEmpty()) result.add(PagedPage(content, 0))
        return result
    }

    /** 一页：文本 + 页首在原文中的字符偏移 */
    data class PagedPage(val text: String, val startOffset: Int)

    /** Splits chapter content into pages based on viewport dimensions and typography parameters. */
    fun paginate(
        content: String,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        fontSizePx: Float,
        lineSpacingMultiplier: Float = 1.75f,
        paragraphSpacingPx: Float = 16f,
        firstLineIndent: Boolean = true,
        enableKinsoku: Boolean = true,
    ): PagedChapter {
        val totalChars = content.length
        if (content.isBlank()) {
            return PagedChapter(pages = listOf(""), totalCharCount = 0)
        }

        val effectiveWidth = viewportWidthPx.coerceAtLeast(300f)
        val effectiveHeight = viewportHeightPx.coerceAtLeast(400f)

        val maxUnitsPerLine = max(10f, effectiveWidth / fontSizePx.coerceAtLeast(12f))

        val lineHeightPx = fontSizePx * lineSpacingMultiplier.coerceIn(1.0f, 4.5f)
        val maxLinesPerPage = max(6, floor(effectiveHeight / lineHeightPx).toInt())
        val paragraphExtraLines = (paragraphSpacingPx / lineHeightPx).coerceIn(0f, 2f)
        val maxUnitsPerLineSafe = max(6f, maxUnitsPerLine * 0.96f)

        val paragraphs = content.lines()
        val pages = mutableListOf<String>()
        val currentPageText = StringBuilder()
        var currentLinesOnPage = 0f

        for (rawPara in paragraphs) {
            val trimmed = rawPara.trim()
            if (trimmed.isEmpty()) continue

            val formattedPara = if (firstLineIndent) {
                if (!trimmed.startsWith("　　") && !trimmed.startsWith("  ")) "　　$trimmed" else trimmed
            } else trimmed

            val wrappedLines = wrapParagraph(formattedPara, maxUnitsPerLine, enableKinsoku)

            for (i in wrappedLines.indices) {
                val line = wrappedLines[i]
                val isLastLineOfPara = (i == wrappedLines.size - 1)
                val lineCost = 1f + (if (isLastLineOfPara) paragraphExtraLines else 0f)

                if (currentLinesOnPage + lineCost > maxLinesPerPage && currentPageText.isNotEmpty()) {
                    pages.add(currentPageText.toString().trimEnd())
                    currentPageText.clear()
                    currentLinesOnPage = 0f
                }

                currentPageText.append(line).append("\n")
                if (isLastLineOfPara && paragraphSpacingPx > 4f) currentPageText.append("\n")
                currentLinesOnPage += lineCost
            }
        }

        if (currentPageText.isNotEmpty()) pages.add(currentPageText.toString().trimEnd())
        if (pages.isEmpty()) pages.add(content)

        return PagedChapter(pages = pages, totalCharCount = totalChars)
    }
}
