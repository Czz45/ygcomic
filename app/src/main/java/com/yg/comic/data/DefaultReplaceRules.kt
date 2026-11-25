package com.yg.comic.data

import com.yg.comic.model.ReplaceRule

/**
 * 内置默认屏蔽池（legado 风格）。
 *
 * 只放通用、低误伤规则的规则：去广告句、去重复乱码、去空白行。
 * 这些规则默认作用于正文；用户可在管理页禁用/修改/删除。
 * 站-specific 的规则交给用户从订阅导入。
 */
object DefaultReplaceRules {

    val list: List<ReplaceRule> = listOf(
        // 常见广告/推广句（正文）
        ReplaceRule(
            name = "去广告-请收藏", group = "内置·广告",
            pattern = "(?i)(请(记住|收藏)|请把本站|本站地址|最新网址|请收藏本站)[^\\n]{0,40}",
            replacement = "", scopeContent = true, scopeTitle = false, isRegex = true, order = 10,
        ),
        ReplaceRule(
            name = "去广告-手机阅读", group = "内置·广告",
            pattern = "(?i)(手机阅读|请直接用手机|wap\\.[^\\s]{2,30}|请使用(浏览|手机)器)[^\\n]{0,30}",
            replacement = "", scopeContent = true, scopeTitle = false, isRegex = true, order = 11,
        ),
        ReplaceRule(
            name = "去广告-QQ群", group = "内置·广告",
            pattern = "(?i)(QQ群|qq群|微信群|书友群|交流群)[：:]?\\s*[0-9]{4,12}",
            replacement = "", scopeContent = true, scopeTitle = true, isRegex = true, order = 12,
        ),
        ReplaceRule(
            name = "去乱码-替换符", group = "内置·乱码",
            pattern = "\uFFFD+", replacement = "", scopeContent = true, scopeTitle = true,
            isRegex = true, order = 20,
        ),
        ReplaceRule(
            name = "去乱码-零宽字符", group = "内置·乱码",
            pattern = "[\\u200B-\\u200D\\uFEFF]", replacement = "", scopeContent = true,
            scopeTitle = true, isRegex = true, order = 21,
        ),
        ReplaceRule(
            name = "去标题-作者前缀", group = "内置·标题",
            pattern = "^\\s*(作者[：:]|author[：:])\\s*", replacement = "", scopeContent = false,
            scopeTitle = true, isRegex = true, order = 30,
        ),
        ReplaceRule(
            name = "压缩-过多空行", group = "内置·排版",
            pattern = "\\n{3,}", replacement = "\n\n", scopeContent = true, scopeTitle = false,
            isRegex = true, order = 40,
        ),
    )
}
