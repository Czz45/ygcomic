package com.yg.comic.reader.page

import androidx.compose.ui.text.TextLayoutResult
import com.yg.comic.engine.ReaderContentBlock
import com.yg.comic.engine.ReaderTextPage

/**
 * 适配层（非 legado 源码，YGComic 自有）：把现有 Compose 排版产出的 [ReaderTextPage]
 * 逐字切成 legado 内核的 [TextColumn]/[TextLine]/[TextPage]，供坐标反查与选区高亮使用。
 *
 * 坐标统一到"页面内容区"坐标系（与 PageText 内长按手势拿到的坐标同源）：
 *   - 列 x = leftPadPx + TextLayoutResult.getHorizontalPosition(offset)
 *   - 行 y = fragment.top + (行.top - fragment.sourceTop)
 *
 * 排版算法本身不在这里，完全复用 NovelReaderScreen 的 measureBlock（同源），
 * 选区判定/区间比较完全走照搬的 legado 内核（TextLine.isTouch / TextPos.compare）。
 */
class PageSelectionIndex(val textPage: TextPage) {

    /**
     * 命中屏幕坐标 → TextPos（relativePagePos 恒 0，line/column 为本页行/列下标）。
     *
     * 照搬 legado ContentTextView.touchRough 的鲁棒反查：不要求 y 严格落在行内、
     * 也不要求 x 严格落在列内，而是取「距离最近的行 + 距离最近的列」。
     * 手指拖到行间隙 / 段间距 / 字间隙时仍能命中最近的字，选区才能连续扩展；
     * 旧的严格 isTouch 判定在这些间隙会返回 null，被 onDrag 的 if(p != null) 吞掉，
     * 于是 selEnd 永远停在起点——这正是「只能选一个字」的根因。
     */
    fun hit(x: Float, y: Float): TextPos? {
        if (!x.isFinite() || !y.isFinite()) return null
        val lines = textPage.lines
        var nearestLine = -1
        var nearestDistance = Float.MAX_VALUE
        for (li in lines.indices) {
            val line = lines[li]
            if (textCols(line).isEmpty()) continue
            val top = line.lineTop
            val bottom = line.lineBottom
            val distance = maxOf(top - y, y - bottom).coerceAtLeast(0f)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestLine = li
            }
        }
        if (nearestLine < 0) return null
        // 只在真实非空字列里找最近列，返回原始列下标（占位/缩进空白列不参与命中）。
        var charIndex = -1
        var distanceX = Float.MAX_VALUE
        for ((ci, col) in textCols(lines[nearestLine])) {
            val dx = maxOf(col.start - x, x - col.end).coerceAtLeast(0f)
            if (dx < distanceX) {
                distanceX = dx
                charIndex = ci
            }
        }
        if (charIndex < 0) return null
        return TextPos(0, nearestLine, charIndex)
    }

    private fun ordered(a: TextPos, b: TextPos): Pair<TextPos, TextPos> =
        if (a.compare(b) <= 0) a to b else b to a

    /**
     * 一行的「有效字列」：(原始列下标, TextColumn)，只保留真正对应一个可见字符的列。
     * 行首缩进空白列、修②补的整行宽占位列（charData 为空串）全部排除。
     *
     * selectedText 复制时占位列 charData="" 不贡献字符，所以「复制是对的」；
     * 但视觉矩形若照单全收这些空白/占位列，就会把缩进空白涂蓝、把占位行画成整行蓝、
     * 手柄锚到左缘。命中/高亮/手柄统一走这里，与复制内容取同一批字，视觉才对齐。
     */
    private fun textCols(line: TextLine): List<Pair<Int, TextColumn>> =
        line.columns.mapIndexed { i, c -> i to c }
            .filter { (_, c) -> c is TextColumn && c.charData.isNotBlank() }
            .map { (i, c) -> i to (c as TextColumn) }

    /** 选中文本：按 TextPos 区间（含端点）逐列拼 charData，跨行以换行分隔。 */
    fun selectedText(p1: TextPos, p2: TextPos): String {
        val (a, b) = ordered(p1, p2)
        val sb = StringBuilder()
        for (li in a.lineIndex..b.lineIndex) {
            val line = textPage.getLine(li)
            val from = if (li == a.lineIndex) a.columnIndex else Int.MIN_VALUE
            val to = if (li == b.lineIndex) b.columnIndex else Int.MAX_VALUE
            for ((ci, col) in textCols(line)) {
                if (ci < from || ci > to) continue
                sb.append(col.charData)
            }
            if (li < b.lineIndex) sb.append('\n')
        }
        return sb.toString()
    }

    /** 选中区每个字的矩形 (left, top, right, bottom)，供 Compose Canvas 自绘高亮。 */
    fun selectionRects(p1: TextPos, p2: TextPos): List<FloatArray> {
        val (a, b) = ordered(p1, p2)
        val out = ArrayList<FloatArray>()
        for (li in a.lineIndex..b.lineIndex) {
            val line = textPage.getLine(li)
            val from = if (li == a.lineIndex) a.columnIndex else Int.MIN_VALUE
            val to = if (li == b.lineIndex) b.columnIndex else Int.MAX_VALUE
            for ((ci, col) in textCols(line)) {
                if (ci < from || ci > to) continue
                out.add(floatArrayOf(col.start, line.lineTop, col.end, line.lineBottom))
            }
        }
        return out
    }

    /**
     * 选中区按行合并后的矩形条 (left, top, right, bottom)：每一行只产出一个连续长条，
     * 消除逐字小方块因行距/字高差叠成"阶梯状"的问题（用户反馈的选区叠层）。
     * 只取真实非空字列：起点行取 columnIndex 之后的首个有效列，终点行取 columnIndex 之前的
     * 末个有效列，中间行取整行有效列范围；没有有效列的行（占位行/纯缩进行）不画。
     */
    fun selectionRowRects(p1: TextPos, p2: TextPos): List<FloatArray> {
        val (a, b) = ordered(p1, p2)
        val out = ArrayList<FloatArray>()
        for (li in a.lineIndex..b.lineIndex) {
            val line = textPage.getLine(li)
            val cols = textCols(line)
            if (cols.isEmpty()) continue
            val fromCi = if (li == a.lineIndex) a.columnIndex else Int.MIN_VALUE
            val toCi = if (li == b.lineIndex) b.columnIndex else Int.MAX_VALUE
            val inRange = cols.filter { (ci, _) -> ci >= fromCi && ci <= toCi }
            if (inRange.isEmpty()) continue
            out.add(floatArrayOf(inRange.first().second.start, line.lineTop, inRange.last().second.end, line.lineBottom))
        }
        return out
    }

    /**
     * 选区两端"水滴手柄"锚点 [startX, startY, endX, endY]（ordered 后的首字左边 / 末字右边）。
     * y 取所在行下沿，便于手柄画在选区下方不遮字；无有效列返回 null。
     * 起点取起点行 columnIndex 之后的首个有效列左边，终点取终点行 columnIndex 之前的末个有效列右边。
     */
    fun handleAnchors(p1: TextPos, p2: TextPos): FloatArray? {
        val (a, b) = ordered(p1, p2)
        val la = textPage.getLine(a.lineIndex)
        val lb = textPage.getLine(b.lineIndex)
        val ca = textCols(la).filter { (ci, _) -> ci >= a.columnIndex }
        val cb = textCols(lb).filter { (ci, _) -> ci <= b.columnIndex }
        if (ca.isEmpty() || cb.isEmpty()) return null
        val sx = ca.first().second.start
        val ex = cb.last().second.end
        return floatArrayOf(sx, la.lineBottom, ex, lb.lineBottom)
    }

    /**
     * 书内搜索：在本页可见字列里查找 query，返回每处命中的按行矩形 [l, t, r, b]。
     * 与选字共用同一份 charBox 坐标，故高亮与文字严丝合缝；调用方应按当前简繁
     * 把 query 转换后再传入，避免繁体正文匹配不到简体输入。
     */
    fun searchRects(query: String): List<FloatArray> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>()
        for (li in textPage.lines.indices) {
            val line = textPage.getLine(li)
            val cols = textCols(line)
            if (cols.isEmpty()) continue
            val sb = StringBuilder()
            for ((_, c) in cols) sb.append(c.charData)
            val text = sb.toString()
            var from = 0
            while (true) {
                val idx = text.indexOf(query, from)
                if (idx < 0) break
                val first = cols[idx].second
                val last = cols[idx + query.length - 1].second
                out.add(floatArrayOf(first.start, line.lineTop, last.end, line.lineBottom))
                from = idx + query.length
                if (from >= text.length) break
            }
        }
        return out
    }
}

/**
 * 从一页的排版结果构造选字索引（坐标同源版）。
 *
 * 病根修复：过去这里拿 measureBlock/display 重新 measure 第二套 TextColumn，
 * 靠"字号/行距/缩进/字体与渲染端完全一致"凑对齐，任一处差异（简繁/图片/字体回退）
 * 就错位。现在直接读排版阶段 measureBlock 存进 ReaderContentBlock.charBoxes 的那一份
 * 每字真实坐标——渲染端画字与选字端命中用的是同一份数据，从根本上杜绝错位。
 *
 * 行 y = frag.top + (bl.top - sourceTop) + block.baseTop：
 * bl.top 是排版归一化后的行 top（首行=0），sourceTop=首行 top，二者差即相对偏移；
 * baseTop 是归一化前的原始首行 top，补回后与渲染端 place(0,-sourceTopPx) 的绝对坐标同源。
 */
fun buildSelectionIndex(
    readerPage: ReaderTextPage,
    leftPadPx: Int,
): PageSelectionIndex {
    val page = TextPage()
    for (frag in readerPage.fragments) {
        val block: ReaderContentBlock = frag.block
        if (block.lines.isEmpty()) continue
        val sourceTop = frag.sourceTop
        val baseTop = block.baseTop
        // 按 lineIndex 把 charBoxes 分组（排版时已按显示文本 shown 的 UTF-16 下标建好坐标）。
        val boxesByLine = block.charBoxes.groupBy { it.lineIndex }
        for (li in frag.firstLine..frag.lastLine.coerceAtMost(block.lines.lastIndex)) {
            val bl = block.lines.getOrNull(li) ?: continue
            val lineY = frag.top + (bl.top - sourceTop) + baseTop
            val lineBottomY = frag.top + (bl.bottom - sourceTop) + baseTop
            val lineBoxes = boxesByLine[li].orEmpty()
            // text 用本行 boxes 的 charData 拼接，保持与命中列一一对应。
            val lineText = lineBoxes.joinToString("") { it.charData }
            val tl = TextLine(
                text = lineText,
                lineTop = lineY,
                lineBottom = lineBottomY,
                lineBase = lineBottomY,
                chapterPosition = bl.start,
                isTitle = block.isTitle,
                isParagraphEnd = li == block.lines.lastIndex,
            )
            // 行首缩进空白不建列：只跳过 "　　" 这类行首连续空白，行中真实空格照常建列。
            var started = false
            for (box in lineBoxes) {
                if (!started && box.charData.isBlank()) continue
                started = true
                val x0 = leftPadPx.toFloat() + box.left
                val x1 = leftPadPx.toFloat() + box.right
                tl.addColumn(TextColumn(x0, x1, box.charData))
            }
            // 纯缩进/空行也 addLine 一个占位行，保证 page.lines 的 lineIndex 与 fragment 行一一对应。
            if (tl.columns.isEmpty()) {
                val rowWidth = block.lines.maxOfOrNull { it.end - it.start }?.toFloat()?.coerceAtLeast(1f) ?: 1f
                tl.addColumn(TextColumn(leftPadPx.toFloat(), leftPadPx.toFloat() + rowWidth, ""))
            }
            page.addLine(tl)
        }
    }
    return PageSelectionIndex(page)
}
