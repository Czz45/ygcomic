package com.yg.comic.shared

/**
 * 纯 Kotlin 搜索结果聚合：把同名同作者的多源结果合并成一条，照 legado mergeItems 逻辑。
 * 用轻量 DTO，零 Android / 零 app 模型依赖；app 层 VM 负责 DTO <-> 领域模型 的薄映射。
 */

/** 聚合输入：一条书源搜索结果所需的最小字段集。 */
data class AggBookIn(
    val name: String,
    val author: String,
    val kind: String,
    val sourceId: Long,
    val sourceJson: String,
    val sourceName: String,
    val bookUrl: String,
    val origins: List<AggOrigin>,
    val coverUrl: String = "",
)

/** 来源（换源用）。 */
data class AggOrigin(
    val sourceId: Long,
    val sourceJson: String,
    val sourceName: String,
    val bookUrl: String,
    val tocUrl: String,
    val coverUrl: String = "",
)

/** 聚合输出：展示书（作者可能补自同组）、来源列表。 */
data class AggBookOut(
    val name: String,
    val author: String,
    val kind: String,
    val sourceId: Long,
    val sourceJson: String,
    val sourceName: String,
    val bookUrl: String,
    val origins: List<AggOrigin>,
    val coverUrl: String = "",
)

object SearchAggregator {
    private data class Bucket(val rank: Int, val score: Int, val book: AggBookIn)
    private data class Group(val rank: Int, val originCount: Int, val book: AggBookOut)

    /**
     * 聚合：rank 0=书名/作者全等关键词，1=分类含，2=书名/作者含，3=其它（且相关性<=0丢弃）。
     * 先按 (归一书名, 归一作者) 分组；空作者并入同名最优作者组；
     * 每组取 rank 最小的 top 中 score 最高者为展示书，作者空则补组内非空作者；
     * 来源去重合并（已带 tocUrl 的不被空 tocUrl 覆盖）；
     * 最终按 rank 升序、来源数降序排列。
     */
    fun aggregate(merged: List<AggBookIn>, kw: String): List<AggBookOut> {
        val buckets = ArrayList<Bucket>()
        for (sb in merged) {
            val name = sb.name.trim()
            val author = sb.author.trim()
            val kind = sb.kind.trim()
            val score = SearchRules.relevance(name, kw)
            val rank = when {
                name.equals(kw, ignoreCase = true) || author.equals(kw, ignoreCase = true) -> 0
                kind.contains(kw, ignoreCase = true) -> 1
                name.contains(kw, ignoreCase = true) || author.contains(kw, ignoreCase = true) -> 2
                else -> 3
            }
            if (rank == 3 && score <= 0) continue
            buckets.add(Bucket(rank, score, sb))
        }

        // 阶段1：按 (归一书名, 归一作者) 精确分组
        val exact = LinkedHashMap<Pair<String, String>, MutableList<Bucket>>()
        for (b in buckets) {
            val key = SearchRules.normKey(b.book.name) to SearchRules.normKey(b.book.author)
            exact.getOrPut(key) { ArrayList() }.add(b)
        }

        // 阶段2：空作者并入同名最优作者组
        val nameToBestAuthor = HashMap<String, String>()
        for ((key, group) in exact) {
            if (key.second.isEmpty()) continue
            val bestScore = group.maxOf { it.score }
            val prev = nameToBestAuthor[key.first]
            val prevBest = if (prev != null) (exact[key.first to prev]?.maxOf { it.score } ?: Int.MIN_VALUE) else Int.MIN_VALUE
            if (bestScore > prevBest) nameToBestAuthor[key.first] = key.second
        }

        val groups = LinkedHashMap<String, MutableList<Bucket>>()
        for ((key, group) in exact) {
            val targetAuthor = if (key.second.isEmpty()) (nameToBestAuthor[key.first] ?: "") else key.second
            val gkey = key.first + "|" + targetAuthor
            groups.getOrPut(gkey) { ArrayList() }.addAll(group)
        }

        val grouped = groups.values.mapNotNull { group ->
            val bestRank = group.minOf { it.rank }
            val top = group.filter { it.rank == bestRank }
            val best = top.maxByOrNull { it.score } ?: return@mapNotNull null
            // 展示书作者空则补组内第一个非空作者
            val displayAuthor = if (best.book.author.trim().isEmpty()) {
                group.firstNotNullOfOrNull { it.book.author.trim().takeIf { a -> a.isNotEmpty() } } ?: ""
            } else {
                best.book.author
            }
            val displaySb = if (best.book.author.trim().isEmpty()) {
                best.book.copy(author = displayAuthor)
            } else {
                best.book
            }
            // 封面：展示书自己的封面优先，为空则取组内任意一个来源的非空封面
            val displayCover = displaySb.coverUrl.trim().takeIf { it.isNotEmpty() }
                ?: group.firstNotNullOfOrNull { it.book.coverUrl.trim().takeIf { c -> c.isNotEmpty() } }
                ?: ""
            // 来源合并去重
            val all = LinkedHashMap<Pair<Long, String>, AggOrigin>()
            val main = sourceOriginOf(displaySb)
            all[main.sourceId to main.bookUrl] = main
            for (b in group) {
                val origins = b.book.origins.ifEmpty { listOf(sourceOriginOf(b.book)) }
                for (o in origins) {
                    val k = o.sourceId to o.bookUrl
                    val old = all[k]
                    val better = old == null ||
                        (old.tocUrl.isEmpty() && o.tocUrl.isNotEmpty()) ||
                        (old.coverUrl.isEmpty() && o.coverUrl.isNotEmpty())
                    if (better) all[k] = o
                }
            }
            Group(bestRank, all.size, AggBookOut(
                name = displaySb.name,
                author = displaySb.author,
                kind = displaySb.kind,
                sourceId = displaySb.sourceId,
                sourceJson = displaySb.sourceJson,
                sourceName = displaySb.sourceName,
                bookUrl = displaySb.bookUrl,
                origins = all.values.toList(),
                coverUrl = displayCover,
            ))
        }

        return grouped
            .sortedWith(compareBy<Group> { it.rank }.thenByDescending { it.originCount })
            .map { it.book }
    }

    private fun sourceOriginOf(sb: AggBookIn): AggOrigin =
        AggOrigin(
            sourceId = sb.sourceId,
            sourceJson = sb.sourceJson,
            sourceName = sb.sourceName,
            bookUrl = sb.bookUrl,
            tocUrl = "",
            coverUrl = sb.coverUrl,
        )
}
