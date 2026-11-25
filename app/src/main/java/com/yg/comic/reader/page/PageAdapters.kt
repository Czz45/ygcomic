package com.yg.comic.reader.page

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint

/**
 * 适配层（非 legado 源码照搬）：用纯数据持有者替代 legado 整 App 骨架，
 * 为照搬过来的选字内核提供外部依赖。
 */
object ChapterProviderAdapter {
    var contentPaint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    var titlePaint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    var paddingTop: Float = 0f
    var paddingLeft: Float = 0f
    var visibleHeight: Float = 0f
    var visibleBottom: Float = 0f
    var lineSpacingExtra: Float = 0f
}

object ReadBookConfigAdapter {
    var textColor: Int = 0xFF000000.toInt()
    var textAccentColor: Int = 0xFF33B5E5.toInt()
}

object AppConfigAdapter {
    var optimizeRender: Boolean = false
}

data class BookStub(val bookUrl: String)

object ReadBookAdapter {
    var book: BookStub? = null
    val isReadAloud: Boolean = false
}

/** 选中高亮 Paint，替代 legado ThemeStore.accentSelected */
val selectedPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0x6633B5E5
}

/** 绘制桥接：替代 ContentTextView.drawTextWithPaperInk */
@Suppress("UNUSED_PARAMETER")
fun Canvas.drawTextWithPaperInk(
    text: String,
    x: Float,
    y: Float,
    paint: Paint,
    enablePaperInk: Boolean
) {
    drawText(text, x, y, paint)
}

/** 绘制桥接：替代 ContentTextView.drawSelectedRect */
fun Canvas.drawSelectedRect(l: Float, t: Float, r: Float, b: Float) {
    drawRect(l, t, r, b, selectedPaint)
}

/** 替代 legado io.legado.app.utils.dpToPx / spToPx */
fun Float.dpToPx(): Float = this * Resources.getSystem().displayMetrics.density

fun Float.spToPx(): Float = this * Resources.getSystem().displayMetrics.scaledDensity

/** Int 重载：照搬源码中 20.dpToPx() 这类整数字面量调用 */
fun Int.dpToPx(): Float = this.toFloat() * Resources.getSystem().displayMetrics.density

fun Int.spToPx(): Float = this.toFloat() * Resources.getSystem().displayMetrics.scaledDensity
