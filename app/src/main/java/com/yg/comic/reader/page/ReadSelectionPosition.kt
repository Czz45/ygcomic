// 来源：legado app/src/main/java/io/legado/app/ui/book/read/page/entities/ReadSelectionPosition.kt（照搬，仅改包名）
package com.yg.comic.reader.page

/**
 * Immutable read-aloud target captured before the text selection UI is cleared.
 */
data class ReadSelectionPosition(
    val bookUrl: String,
    val chapterIndex: Int,
    val chapterUrl: String,
    val chapterPosition: Int
)
