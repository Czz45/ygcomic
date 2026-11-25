package com.yg.comic.shared

/**
 * 纯 Kotlin 联网换源判定：书名归一、同名过滤、作者众数精修。
 * 零 Android 依赖，从 ui.reader.NovelReaderViewModel.searchSwitchSources 抽出。
 * 网络并发/缓存留各端编排层，这里只做纯判定。
 */

/** 换源候选项（纯数据，字段与各端展示模型薄映射）。 */
data class SwitchBookIn(
    val name: String,
    val author: String,
    val sourceId: Long,
    val sourceJson: String,
    val sourceName: String,
    val bookUrl: String,
    val coverUrl: String,
    val kind: String,
    val wordCount: String,
    val lastChapter: String,
    val intro: String,
)

object SwitchMatcher {
    /** 书名归一：去首尾空白与书名号/全角空格，用于精确比对。 */
    fun norm(s: String): String = s.trim().replace(Regex("[\\s《》〈〉\\u3000]"), "")

    /**
     * 作者众数：取候选中出现次数最多的非空作者，作为「同一本小说」的参考作者。
     * 空则返回空串（表示不过滤作者）。
     */
    fun pickRefAuthor(authors: List<String>): String =
        authors.map { it.trim() }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key.orEmpty()

    /**
     * 过滤最终候选：先按归一书名精确相等过滤（调用方在收集时已做），
     * 再按参考作者精修——参考作者为空 / 候选作者为空 / 作者相等 才保留。
     */
    fun refineByAuthor(candidates: List<SwitchBookIn>, refAuthor: String): List<SwitchBookIn> =
        if (refAuthor.isEmpty()) {
            candidates
        } else {
            candidates.filter { it.author.isEmpty() || it.author == refAuthor }
        }

    /** 书名是否精确匹配目标（归一后比较）。 */
    fun matchesName(name: String, targetNorm: String): Boolean = norm(name) == targetNorm
}
