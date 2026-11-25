package com.yg.comic.sr.preload

import com.yg.comic.sr.SRModel

/**
 * 统一的"一页"引用。三类书（网络 / PDF / 本地目录）取图方式不同，
 * 但预超分队列只关心"这是哪本书哪一章哪一页、从哪拿原图"，故收敛到这一个 sealed 类型。
 * 逻辑 key 与取图方式解耦：key 只由稳定字段构成，绝不用会过期的图片 URL 原串。
 */
sealed interface SrPageRef {
    val bookId: Long
    val chapterIndex: Int
    val pageIndex: Int

    data class Network(
        override val bookId: Long,
        override val chapterIndex: Int,
        override val pageIndex: Int,
        val url: String,
        val referer: String,
    ) : SrPageRef

    data class Pdf(
        override val bookId: Long,
        override val chapterIndex: Int,
        override val pageIndex: Int,
        val pdfPath: String,
    ) : SrPageRef

    data class Local(
        override val bookId: Long,
        override val chapterIndex: Int,
        override val pageIndex: Int,
        val path: String,
    ) : SrPageRef
}

/**
 * 磁盘缓存的逻辑 key。唯一真源——SrDiskCache 落盘名和 ReaderScreen 读取名都走这里，
 * 避免两处各拼一套导致命中不上。
 */
fun SrPageRef.logicalKey(model: SRModel, autoCrop: Boolean): String =
    "$bookId|$chapterIndex|$pageIndex|${model.key}|$autoCrop"

/**
 * 网络漫画的“扁平页 -> 页引用”构造：章序取外层下标，章内页序取该章图片下标。
 * 后台预超分写入侧与阅读器读取侧都调这一个函数，(章序,页序) 才不会出现一位错位。
 */
fun buildNetworkRefs(
    bookId: Long,
    referer: String,
    imagesByChapter: List<List<String>>,
    startChapterIndex: Int = 0,
    chapterIndices: List<Int>? = null,
): List<SrPageRef> = buildList {
    imagesByChapter.forEachIndexed { ci, images ->
        val chapterIndex = chapterIndices?.getOrNull(ci) ?: (startChapterIndex + ci)
        images.forEachIndexed { pi, url ->
            add(SrPageRef.Network(bookId, chapterIndex, pi, url, referer))
        }
    }
}
