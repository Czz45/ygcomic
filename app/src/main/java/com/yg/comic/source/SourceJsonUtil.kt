package com.yg.comic.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 书源 JSON 文本处理：把用户粘贴的「单个对象 / 对象数组」拆成一个个对象字符串。 */
object SourceJsonUtil {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 输入可能是：
     * - 单个书源对象 {...}
     * - 书源数组 [{...},{...}]
     * - 中间带逗号分隔的多个对象 {...},{...}
     * 返回每个书源对象的 JSON 字符串列表。
     */
    fun splitToObjects(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        // 1) 先尝试整段解析为 JsonElement
        val el = runCatching { json.parseToJsonElement(text) }.getOrNull()
        when (el) {
            is JsonArray -> return el.mapNotNull { it as? JsonObject }.map { it.toString() }
            is JsonObject -> return listOf(el.toString())
            else -> Unit
        }
        // 2) 退化为按花括号配平切分（容错：用户可能漏写方括号）
        return splitByBraces(text)
    }

    private fun splitByBraces(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in text.indices) {
            val c = text[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out.add(text.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return out
    }
}
