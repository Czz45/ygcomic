package com.yg.comic.shared

/**
 * 屏蔽池 / 净化替换系统 —— 纯 Kotlin 层，零 Android 依赖。
 *
 * 参照 legado 的 ReplaceRule + ContentProcessor：
 *  - 每条规则可作用于标题(scopeTitle)或正文(scopeContent)
 *  - pattern 为被替换内容，replacement 为替换为；isRegex 决定按正则还是字面
 *  - scope / excludeScope 为按书源名过滤的作用域正则（可空）
 *  - 正文净化时先做「特殊内容占位保护」（URL、HTML 标签、章节号），替换后还原
 *  - 规则按 order 升序应用
 */

/** 替换规则轻量 DTO（纯 Kotlin，不依赖 Room/Android/app 模型）。 */
data class PurifyRule(
    val id: Long = 0,
    val name: String = "",
    val group: String = "",
    val pattern: String = "",
    val replacement: String = "",
    val scope: String = "",
    val scopeTitle: Boolean = false,
    val scopeContent: Boolean = true,
    val excludeScope: String = "",
    val isEnabled: Boolean = true,
    val isRegex: Boolean = true,
    val timeoutMillisecond: Long = 3000,
    val order: Int = 0,
)

/** 作用目标：标题（书名/作者/简介）或正文。 */
enum class PurifyTarget { TITLE, CONTENT }

object TextPurifier {

    // 特殊内容占位符，用户正文几乎不可能出现
    private const val PLACEHOLDER_PREFIX = "\u0001ygp"
    private val specialContentPattern = Regex(
        "(https?://[^\\s<>\"'）)]+|www\\.[^\\s<>\"'）)]+|<[^>]{1,200}>|第[0-9零一二三四五六七八九十百千万]+[章节回卷篇集])",
        RegexOption.IGNORE_CASE,
    )

    /** 对文本应用替换规则（统一入口）。 */
    fun purify(
        text: String,
        rules: List<PurifyRule>,
        target: PurifyTarget,
        scopeKey: String? = null,
    ): String = when (target) {
        PurifyTarget.TITLE -> purifyTitle(text, rules, scopeKey)
        PurifyTarget.CONTENT -> purifyContentWithProtection(text, rules, scopeKey)
    }

    /** 标题净化：不做特殊内容保护（标题里的"第X章"等也应能被替换）。 */
    fun purifyTitle(text: String, rules: List<PurifyRule>, scopeKey: String? = null): String {
        if (text.isEmpty()) return text
        val applicable = applicable(rules, PurifyTarget.TITLE, scopeKey)
        if (applicable.isEmpty()) return text
        var result = text
        for (rule in applicable) result = applyRule(result, rule)
        return result
    }

    /** 正文净化：先保护 URL/HTML 标签/章节号，替换后按槽位还原。 */
    fun purifyContentWithProtection(
        text: String,
        rules: List<PurifyRule>,
        scopeKey: String? = null,
    ): String {
        if (text.isEmpty()) return text
        val applicable = applicable(rules, PurifyTarget.CONTENT, scopeKey)
        if (applicable.isEmpty()) return text

        val slots = ArrayList<String>()
        // 字面规则（用户长按选中即要删的文字，可能是含 www.URL 的广告）：
        // 必须在占位保护之前、于原始文本上执行，否则 URL 被挖成占位符后永远匹配不到。
        var result = text
        for (rule in applicable.filter { isLiteralRule(it) }) {
            result = applyRule(result, rule)
        }
        // 正则规则仍在占位文本上执行，避免误伤 URL / HTML 标签 / 章节号。
        result = specialContentPattern.replace(result) { m ->
            val token = "$PLACEHOLDER_PREFIX${slots.size}\u0002"
            slots.add(m.value)
            token
        }
        for (rule in applicable.filter { !isLiteralRule(it) }) {
            result = applyRule(result, rule)
        }
        slots.forEachIndexed { i, original ->
            result = result.replace("$PLACEHOLDER_PREFIX$i\u0002", original)
        }
        return result
    }

    private fun applicable(
        rules: List<PurifyRule>,
        target: PurifyTarget,
        scopeKey: String?,
    ): List<PurifyRule> = rules
        .asSequence()
        .filter { it.isEnabled && it.pattern.isNotEmpty() }
        .filter {
            when (target) {
                PurifyTarget.TITLE -> it.scopeTitle
                PurifyTarget.CONTENT -> it.scopeContent
            }
        }
        .filter { scopeMatches(it, scopeKey) }
        .sortedBy { it.order }
        .toList()

    /** Kotlin Regex.escape 对含特殊字符的串生成 \Q...\E 字面量引用。
     *  返回解包后的字面文本（供显示与字面替换共用）；非 \Q...\E 形式原样返回。 */
    fun unwrapQuoted(pattern: String): String {
        if (pattern.startsWith("\\Q")) {
            val end = pattern.lastIndexOf("\\E")
            if (end > 1) return pattern.substring(2, end)
            return pattern.substring(2)
        }
        return pattern
    }

    /** 字面规则：isRegex=false，或旧的 \Q...\E 规则（它们本就按字面匹配）。 */
    private fun isLiteralRule(rule: PurifyRule): Boolean =
        !rule.isRegex || rule.pattern.startsWith("\\Q")

    /**
     * 字面规则的宽松匹配正则：连续空白 → \s+，全角括号与半角括号互通，
     * 其余字符逐字转义保持字面语义。
     * 用户手输规则时常把全角（）写成半角()、空格数也和正文不同，
     * 精确 replace 会漏；宽松正则在不误伤其它文本的前提下覆盖这些差异。
     */
    private fun literalToLooseRegex(literal: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < literal.length) {
            val c = literal[i]
            if (c.isWhitespace()) {
                while (i < literal.length && literal[i].isWhitespace()) i++
                sb.append("\\s+")
                continue
            }
            when (c) {
                '（', '(' -> sb.append("[（(]")
                '）', ')' -> sb.append("[）)]")
                '【' -> sb.append("[【\\[]")
                '】' -> sb.append("[】\\]]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(sb.toString())
    }

    private fun applyRule(text: String, rule: PurifyRule): String = try {
        if (rule.isRegex && !rule.pattern.startsWith("\\Q")) {
            Regex(rule.pattern).replace(text, rule.replacement)
        } else {
            val literal = unwrapQuoted(rule.pattern)
            if (literal.isEmpty()) text
            else if (text.contains(literal)) {
                // 精确命中优先，保持与旧版一致的行为
                text.replace(literal, rule.replacement)
            } else {
                // 未精确命中：空白/全半角括号宽松匹配，覆盖手输规则的字符差异
                literalToLooseRegex(literal).replace(text, rule.replacement)
            }
        }
    } catch (t: Throwable) {
        // 非法正则或异常：跳过该条，不影响其它规则
        text
    }

    /** scope：非空时必须匹配 scopeKey；excludeScope：匹配则排除。
     *  scope 支持多个候选用逗号/分号分隔（中英文），任一命中即生效——对齐 legado「选填书名或书源 URL」语义。 */
    private fun matchAny(pattern: String, key: String): Boolean =
        pattern.split(',', ';', '，', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { part ->
                try {
                    Regex(part, RegexOption.IGNORE_CASE).containsMatchIn(key)
                } catch (t: Throwable) {
                    part in key
                }
            }

    private fun scopeMatches(rule: PurifyRule, scopeKey: String?): Boolean {
        val key = scopeKey ?: ""
        if (rule.scope.isNotBlank()) {
            if (key.isEmpty()) return false
            if (!matchAny(rule.scope, key)) return false
        }
        if (rule.excludeScope.isNotBlank() && key.isNotEmpty()) {
            if (matchAny(rule.excludeScope, key)) return false
        }
        return true
    }
}
