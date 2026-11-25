package com.yg.comic.shared

/**
 * 书源 JSON 里的 bookSourceType==2（漫画）标记并不完全可信：
 * yiove 等订阅里有少量小说/综合/社交站也被标成了 2，导致漫画 tab 搜出小说、
 * 加书架后被当成漫画进漫画阅读器。这里按名字/域名做二次校验，把这类冒牌漫画源剔除。
 *
 * 采用黑名单排除法而非白名单：绝大多数 type==2 确实是漫画站（含大量名字里没有"漫画"
 * 二字的成人漫画站，如 Rawkuma/Hitomi/ExHentai），白名单会误伤，黑名单只剔除已知的非漫画站。
 *
 * 纯 Kotlin，无 Android 依赖。
 */
object ComicSourceRule {
    // 名字或域名命中任一片段即判定为「非漫画」（即便 bookSourceType==2）。
    private val NON_COMIC_MARKERS = listOf(
        "biqug",           // biqug.org 笔趣阁/笔趣漫：小说站
        "笔趣",             // 同上（名字里的中文标记）
        "快乐图书",         // 小说站
        "san.com",         // 快乐图书域名
        "3004ss",          // 快乐图书备用域名
        "hongniuziyuan",   // 红牛资源：小说站
        "红牛资源",
        "weibo",           // 微博博主：社交
        "微博",
        "huaban",          // 花瓣网吧：设计素材站
        "花瓣",
    )

    /**
     * 这个被标记为漫画（type==2）的书源，是不是真正的漫画站。
     * name = 书源名，url = 书源主页 URL（可为 searchUrl 之外的任意 bookSourceUrl）。
     */
    fun isComicSource(name: String, url: String): Boolean {
        val n = name.lowercase()
        val u = url.lowercase()
        if (NON_COMIC_MARKERS.any { n.contains(it) || u.contains(it) }) return false
        // 爱奇艺主站是综合视频，只有带 /manhua 路径的才是漫画源
        if (u.contains("iqiyi.com") && !u.contains("manhua")) return false
        return true
    }
}
