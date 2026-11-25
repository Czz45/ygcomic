// 来源：legado app/src/main/java/io/legado/app/ui/book/read/page/entities/TextLine.kt
// 照搬：构造字段、派生属性、addColumn/addColumns/mapColumns/getColumn/getColumnReverseAt/
//   getColumnsCount、upTopBottom、isTouch(x,y,relativeOffset)、isTouchY 算法一字不改；
//   仅做适配改写——ChapterProvider.paddingTop→ChapterProviderAdapter.paddingTop；
//   删 CanvasRecorder 缓存/下划线/PaintPool/wordSpacing 及 draw 系列方法；
//   invalidate() 保留为最简空实现（供 TextColumn/TextPage setter 调用）；
//   companion 只留 emptyTextLine。
package com.yg.comic.reader.page

import android.graphics.Paint.FontMetrics
import androidx.annotation.Keep
import com.yg.comic.reader.page.TextPage.Companion.emptyTextPage

/**
 * 行信息
 */
@Keep
@Suppress("unused", "MemberVisibilityCanBePrivate")
data class TextLine(
    var text: String = "",
    private val textColumns: ArrayList<BaseColumn> = arrayListOf(),
    var lineTop: Float = 0f,
    var lineBase: Float = 0f,
    var lineBottom: Float = 0f,
    var indentWidth: Float = 0f,
    var paragraphNum: Int = 0,
    var sourceIndex: Int = -1,
    var chapterPosition: Int = 0,
    var pagePosition: Int = 0,
    val isTitle: Boolean = false,
    var isParagraphEnd: Boolean = false,
    var isImage: Boolean = false,
    var isHtml: Boolean = false,
    var startX: Float = 0f,
    var indentSize: Int = 0,
    var extraLetterSpacing: Float = 0f,
    var extraLetterSpacingOffsetX: Float = 0f,
    var wordSpacing: Float = 0f,
    var exceed: Boolean = false,
    var onlyTextColumn: Boolean = true,
    var isReadAloudOnly: Boolean = false,
) {

    val columns: List<BaseColumn> get() = textColumns
    val charSize: Int get() = text.length
    val lineStart: Float get() = textColumns.firstOrNull()?.start ?: 0f
    val lineEnd: Float get() = textColumns.lastOrNull()?.end ?: 0f
    val chapterIndices: IntRange get() = chapterPosition..chapterPosition + charSize
    val height: Float inline get() = lineBottom - lineTop
    var searchResultColumnCount = 0
    var isReadAloud: Boolean = false
        set(value) {
            if (field != value) {
                invalidate()
            }
            if (value) {
                textPage.hasReadAloudSpan = true
            }
            field = value
        }
    var textPage: TextPage = emptyTextPage
    var isLeftLine = true

    fun addColumn(column: BaseColumn) {
        if (column !is TextColumn) {
            onlyTextColumn = false
        }
        column.textLine = this
        textColumns.add(column)
    }

    fun addColumns(columns: Collection<BaseColumn>) {
        onlyTextColumn = false
        columns.forEach { column ->
            column.textLine = this
        }
        textColumns.addAll(columns)
    }

    fun mapColumns(transform: (BaseColumn) -> BaseColumn) {
        for (index in textColumns.indices) {
            textColumns[index] = transform(textColumns[index]).also { it.textLine = this }
        }
        onlyTextColumn = textColumns.all { it is TextColumn }
    }

    fun getColumn(index: Int): BaseColumn {
        return textColumns.getOrElse(index) {
            textColumns.last()
        }
    }

    fun getColumnReverseAt(index: Int, offset: Int = 0): BaseColumn {
        return textColumns[textColumns.lastIndex - offset - index]
    }

    fun getColumnsCount(): Int {
        return textColumns.size
    }

    fun upTopBottom(durY: Float, textHeight: Float, fontMetrics: FontMetrics) {
        lineTop = ChapterProviderAdapter.paddingTop + durY
        lineBottom = lineTop + textHeight
        lineBase = lineBottom - fontMetrics.descent
    }

    fun isTouch(x: Float, y: Float, relativeOffset: Float): Boolean {
        return y > lineTop + relativeOffset
                && y < lineBottom + relativeOffset
                && x >= lineStart
                && x <= lineEnd + 20.dpToPx()
    }

    fun isTouchY(y: Float, relativeOffset: Float): Boolean {
        return y > lineTop + relativeOffset
                && y < lineBottom + relativeOffset
    }

    /**
     * 适配层：legado 原实现为 canvasRecorder.invalidate() + textPage.invalidate()，
     * 因砍掉 CanvasRecorder 缓存机制，这里保留方法存在并置为空实现，
     * 供 TextColumn 的 selected/isSearchResult setter 与 isReadAloud setter 调用。
     */
    fun invalidate() {
    }

    companion object {
        val emptyTextLine = TextLine()
    }

}
