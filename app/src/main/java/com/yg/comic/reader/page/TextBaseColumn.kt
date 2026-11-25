// 来源：legado app/src/main/java/io/legado/app/ui/book/read/page/entities/column/TextBaseColumn.kt（照搬，仅改包名）
package com.yg.comic.reader.page

/**
 * 文字基列
 */
interface TextBaseColumn : BaseColumn {
    override var start: Float
    override var end: Float
    val charData: String
    var selected: Boolean
    var isSearchResult: Boolean
}
