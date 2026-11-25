// 来源：legado app/src/main/java/io/legado/app/ui/book/read/page/entities/column/TextColumn.kt
// 照搬：selected / isSearchResult setter 与 draw 算法一字不改；仅做适配改写——
//   draw 签名去掉 ContentTextView 参数改为 draw(canvas: Canvas)；
//   ChapterProvider.*→ChapterProviderAdapter.*，ReadBookConfig.*→ReadBookConfigAdapter.*；
//   view.drawTextWithPaperInk/drawSelectedRect→Canvas 扩展函数；删 ThemeStore/ContentTextView import
package com.yg.comic.reader.page

import android.graphics.Canvas
import android.os.Build
import androidx.annotation.Keep
import com.yg.comic.reader.page.TextLine.Companion.emptyTextLine

/**
 * 文字列
 */
@Keep
data class TextColumn(
    override var start: Float,
    override var end: Float,
    override val charData: String,
) : TextBaseColumn {

    override var textLine: TextLine = emptyTextLine

    override var selected: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
            }
            field = value
        }
    override var isSearchResult: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
                if (value) {
                    textLine.searchResultColumnCount++
                } else {
                    textLine.searchResultColumnCount--
                }
            }
            field = value
        }

    override fun draw(canvas: Canvas) {
        val textPaint = if (textLine.isTitle) {
            ChapterProviderAdapter.titlePaint
        } else {
            ChapterProviderAdapter.contentPaint
        }
        val textColor = if (textLine.isReadAloud || isSearchResult) {
            ReadBookConfigAdapter.textAccentColor
        } else {
            ReadBookConfigAdapter.textColor
        }
        val enablePaperInk = !textLine.isReadAloud && !isSearchResult
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }
        val y = textLine.lineBase - textLine.lineTop
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val letterSpacing = textPaint.letterSpacing * textPaint.textSize
            val letterSpacingHalf = letterSpacing * 0.5f
            canvas.drawTextWithPaperInk(charData, start + letterSpacingHalf, y, textPaint, enablePaperInk)
        } else {
            canvas.drawTextWithPaperInk(charData, start, y, textPaint, enablePaperInk)
        }
        if (selected) {
            canvas.drawSelectedRect(start, 0f, end, textLine.height)
        }
    }

}
