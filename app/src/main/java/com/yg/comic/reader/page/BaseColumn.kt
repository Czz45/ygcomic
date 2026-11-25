// 来源：legado app/src/main/java/io/legado/app/ui/book/read/page/entities/column/BaseColumn.kt
// 照搬：isTouch(x) 算法一字不改；draw 签名去掉 ContentTextView 参数，改为 draw(canvas: Canvas)
package com.yg.comic.reader.page

import android.graphics.Canvas

/**
 * 列基类
 */
interface BaseColumn {
    var start: Float
    var end: Float
    var textLine: TextLine

    fun draw(canvas: Canvas)

    fun isTouch(x: Float): Boolean {
        return x > start && x < end
    }

}
