package com.yg.comic.shared

/**
 * 纯 Kotlin 搜索规则：相关性评分、书名归一、分组键、书架键、失败分类。
 * 零 Android 依赖，从 ui.source.SearchViewModel 抽出，供多端复用。
 */
object SearchRules {

    /**
     * 书名/关键词相关性四档评分：
     * 完全相等 1_000_000 / 前缀 100_000+余量 / 包含 50_000+余量 /
     * 逐字重合命中率 (hit*99/kc.size) 限定 1..99 / 完全不沾 0。
     */
    fun relevance(name: String, key: String): Int {
        val n = name.trim().lowercase()
        val k = key.trim().lowercase()
        if (k.isEmpty()) return 0
        val extra = (n.length - k.length).coerceIn(0, 9999)
        if (n == k) return 1_000_000
        if (n.startsWith(k)) return 100_000 + (10_000 - extra)
        if (n.contains(k)) return 50_000 + (10_000 - extra)
        val kc = k.toCharArray().distinct()
        var hit = 0
        for (ch in kc) if (n.contains(ch)) hit += 1
        if (hit == 0) return 0
        return (hit * 99 / kc.size).coerceIn(1, 99)
    }

    /** 书名归一：去书名号/空白、转小写。用于精确比对与分组。 */
    fun normKey(s: String): String =
        s.trim().trim('《', '》', '〈', '〉', '「', '」', '『', '』')
            .replace(Regex("\\s+"), "")
            .lowercase()

    /** 书架去重键：与 normKey 同一归一规则。 */
    fun shelfKey(name: String): String = normKey(name)

    /** 分组键：取逗号分隔的第一个标签，空则「未分组」。 */
    fun groupKeyOf(groupName: String): String =
        groupName.split(',', '，').firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: "未分组"

    /** 失败类型文本启发式分类：限流 / 网络 / 源站。 */
    fun classifyFail(msg: String?): FailKind {
        val m = msg?.lowercase() ?: return FailKind.SOURCE
        if (listOf("429", "too many", "503", "502", "504", "rate limit", "限流", "频繁")
                .any { m.contains(it) }
        ) return FailKind.RATE
        if (listOf(
                "dns", "refused", "timeout", "timed out", "10060", "10054", "10061",
                "ssl", "tls", "handshake", "connection", "connect", "reset", "unreachable",
                "proxy", "network", "econn", "socket", "连接", "超时", "网络"
            ).any { m.contains(it) }
        ) return FailKind.NETWORK
        return FailKind.SOURCE
    }
}
