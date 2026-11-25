// 来源：legado app/src/main/java/io/legado/app/ui/book/read/page/entities/TextPage.kt
// 照搬：data class 构造字段、lines/lineSize/charSize/chapterPosition 派生属性、
//   paragraphsInternal/addLine/getLine 以及 getPosByLineColumn 算法一字不改；
//   仅做适配改写——构造默认值 appCtx.getString(R.string...) 改空串；
//   删 CanvasRecorder/epubDrawOffset/全部 Epub 相关字段与绘制方法；import 只保留内核所需。
package com.yg.comic.reader.page

import android.graphics.RectF
import androidx.annotation.Keep
import com.yg.comic.reader.page.TextChapter.Companion.emptyTextChapter
import java.text.DecimalFormat
import kotlin.math.min

/**
 * 页面信息
 */
@Keep
@Suppress("unused", "MemberVisibilityCanBePrivate")
data class TextPage(
    var index: Int = 0,
    var text: String = "",
    var title: String = "",
    private val textLines: ArrayList<TextLine> = arrayListOf(),
    var chapterSize: Int = 0,
    var chapterIndex: Int = 0,
    var height: Float = 0f,
    var leftLineSize: Int = 0,
    var renderHeight: Int = 0
) {

    data class NativeTextSelection(
        val text: String,
        val rect: RectF,
        val expandedText: String? = null
    )

    companion object {
        val readProgressFormatter = DecimalFormat("0.0%")
        val emptyTextPage = TextPage()
    }

    val lines: List<TextLine> get() = textLines
    val lineSize: Int get() = textLines.size
    val charSize: Int get() = text.length.coerceAtLeast(1)
    val chapterPosition: Int get() = textLines.firstOrNull()?.chapterPosition ?: fallbackChapterPosition
    val searchResult = hashSetOf<TextBaseColumn>()
    var isMsgPage: Boolean = false
    var isCompleted = false
    var hasReadAloudSpan = false
    var fallbackChapterPosition: Int = 0

    @JvmField
    var textChapter = emptyTextChapter
    val pageSize get() = textChapter.pageSize

    val paragraphs by lazy {
        paragraphsInternal
    }

    val paragraphsInternal: ArrayList<TextParagraph>
        get() {
            val paragraphs = arrayListOf<TextParagraph>()
            val lines = textLines.filter { it.paragraphNum > 0 }
            if (lines.isEmpty()) return paragraphs
            val offset = lines.first().paragraphNum - 1
            lines.forEach { line ->
                if (paragraphs.lastIndex < line.paragraphNum - offset - 1) {
                    paragraphs.add(TextParagraph(0))
                }
                paragraphs[line.paragraphNum - offset - 1].textLines.add(line)
            }
            return paragraphs
        }

    fun addLine(line: TextLine) {
        line.textPage = this
        textLines.add(line)
    }

    fun getLine(index: Int): TextLine {
        return textLines.getOrElse(index) {
            textLines.lastOrNull() ?: TextLine(chapterPosition = fallbackChapterPosition)
        }
    }

    /**
     * @return 页内坐标对应的章节字符偏移；选字闭环关键方法，算法一字不改。
     */
    fun getPosByLineColumn(lineIndex: Int, columnIndex: Int): Int {
        var length = 0
        val maxIndex = min(lineIndex, lineSize - 1)
        for (index in 0 until maxIndex) {
            length += textLines[index].charSize
            if (textLines[index].isParagraphEnd) {
                length++
            }
        }
        val columns = textLines[maxIndex].columns
        for (index in 0 until columnIndex) {
            val column = columns[index]
            if (column is TextBaseColumn) {
                length += column.charData.length
            }
        }
        return length
    }

    fun getTextChapter() = textChapter
}
