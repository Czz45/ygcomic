package com.yg.comic.ui.detail

/**
 * 「开始阅读」（未加入书架）时把整条来源数据带到阅读器。
 * 仿 SharedBookDetail：Activity 作用域单例，sourceJson/originsJson 很大不能走路由参数。
 * 阅读器用 bookId = -1L 识别临时态：只读不落库、进度不保存、不进书架。
 */
object SharedTempRead {
    data class TempReadBook(
        val sourceId: Long,
        val sourceJson: String,
        val sourceName: String,
        val bookUrl: String,
        val tocUrl: String,
        val originsJson: String,
        val title: String,
        val author: String,
        val coverUrl: String,
        val categoryIndex: Int,
        val mediaType: com.yg.comic.model.MediaType = com.yg.comic.model.MediaType.NOVEL,
        val initialChapterIndex: Int = 0,
        /**
         * 点的那一章的 URL / 标题。阅读器的目录是它自己那份（缓存或联网拉的），
         * 章号下标在两边目录条数/顺序不一致时会错位；URL 是章的唯一标识，
         * 阅读器优先按 URL 定位，找不到再退回章号，避免「点第一章进去是别的章」。
         */
        val initialChapterUrl: String = "",
        val initialChapterTitle: String = "",
    )

    private var pending: TempReadBook? = null
    fun post(b: TempReadBook) { pending = b }
    val current: TempReadBook? get() = pending
    fun consume(): TempReadBook? { val v = pending; pending = null; return v }
}
