// 来源：legado app/src/main/java/io/legado/app/ui/book/read/page/entities/TextChapter.kt
// 照搬：构造字段、pages/getPage/getPageByReadPos/lastPage/lastIndex/lastReadLength/pageSize、
//   paragraphsInternal/pageParagraphsInternal、isLastIndex/isLastIndexCurrent、
//   getReadLength、getNextPageLength、getPageIndexByCharIndex 尾部区间判断算法一字不改；
//   仅做适配改写——BookChapter→BookStub、删 ReplaceRule/LayoutProgressListener/TextChapterLayout/
//   Book/BookContent/EpubFile/Jsoup/coroutines；getPageIndexByCharIndex 的 fastBinarySearchBy+abs
//   改为等价线性查找（砍依赖所致，唯一算法层改写）；emptyTextChapter 用 BookStub("")。
package com.yg.comic.reader.page

import androidx.annotation.Keep
import kotlin.math.min

/**
 * 章节信息
 */
@Keep
@Suppress("unused")
data class TextChapter(
    val chapter: BookStub,
    val position: Int,
    val title: String,
    val chaptersSize: Int,
    val sameTitleRemoved: Boolean,
    val isVip: Boolean,
    val isPay: Boolean,
    val effectiveReplaceRules: List<Any>? = null
) {

    private val textPages = arrayListOf<TextPage>()
    val pages: List<TextPage> get() = textPages

    fun getPage(index: Int): TextPage? {
        return pages.getOrNull(index)
    }

    fun getPageByReadPos(readPos: Int): TextPage? {
        return getPage(getPageIndexByCharIndex(readPos))
    }

    val lastPage: TextPage? get() = pages.lastOrNull()

    val lastIndex: Int get() = pages.lastIndex

    val lastReadLength: Int get() = getReadLength(lastIndex)

    val pageSize: Int get() = pages.size

    var isCompleted = false

    val paragraphs by lazy {
        paragraphsInternal
    }

    val pageParagraphs by lazy {
        pageParagraphsInternal
    }

    val paragraphsInternal: ArrayList<TextParagraph>
        get() {
            val paragraphs = arrayListOf<TextParagraph>()
            for (i in pages.indices) {
                val lines = pages[i].lines
                for (a in lines.indices) {
                    val line = lines[a]
                    if (line.paragraphNum <= 0) continue
                    if (paragraphs.lastIndex < line.paragraphNum - 1) {
                        paragraphs.add(TextParagraph(line.paragraphNum))
                    }
                    paragraphs[line.paragraphNum - 1].textLines.add(line)
                }
            }
            return paragraphs
        }

    val pageParagraphsInternal: List<TextParagraph>
        get() {
            val paragraphs = arrayListOf<TextParagraph>()
            for (i in pages.indices) {
                paragraphs.addAll(pages[i].paragraphs)
            }
            for (i in paragraphs.indices) {
                paragraphs[i].num = i + 1
            }
            return paragraphs
        }

    /**
     * @param index 页数
     * @return 是否是最后一页
     */
    fun isLastIndex(index: Int): Boolean {
        return isCompleted && index >= pages.size - 1
    }

    fun isLastIndexCurrent(index: Int): Boolean {
        return index >= pages.size - 1
    }

    /**
     * @param pageIndex 页数
     * @return 已读长度
     */
    fun getReadLength(pageIndex: Int): Int {
        if (pageIndex < 0 || pages.isEmpty()) return 0
        return pages[min(pageIndex, lastIndex)].chapterPosition
    }

    /**
     * @param length 当前页面文字在章节中的位置
     * @return 下一页位置,如果没有下一页返回-1
     */
    fun getNextPageLength(length: Int): Int {
        val pageIndex = getPageIndexByCharIndex(length)
        if (pageIndex + 1 >= pageSize) {
            return -1
        }
        return getReadLength(pageIndex + 1)
    }

    /**
     * @return 根据索引位置获取所在页
     *
     * 适配改写：legado 原实现为
     *   val bIndex = pages.fastBinarySearchBy(charIndex, 0, pageSize) { it.chapterPosition }
     *   val index = abs(bIndex + 1) - 1
     * 因砍掉 fastBinarySearchBy 依赖，改为等价线性查找（取最后一个 chapterPosition<=charIndex 的页），
     * 后续区间判断逻辑（isCompleted / pageEndPos）逐字保留。
     */
    fun getPageIndexByCharIndex(charIndex: Int): Int {
        val pageSize = pages.size
        if (pageSize == 0) {
            return -1
        }
        var index = -1
        for (i in pages.indices) {
            if (pages[i].chapterPosition <= charIndex) {
                index = i
            } else {
                break
            }
        }
        // 判断是否已经排版到 charIndex ，没有则返回 -1
        if (!isCompleted && index == pageSize - 1) {
            val page = pages[index]
            val pageEndPos = page.chapterPosition + page.charSize
            if (charIndex > pageEndPos) {
                return -1
            }
        }
        return index
    }

    companion object {
        val emptyTextChapter = TextChapter(
            BookStub(""), -1, "emptyTextChapter", -1,
            sameTitleRemoved = false,
            isVip = false,
            isPay = false,
            null
        ).apply { isCompleted = true }
    }
}
