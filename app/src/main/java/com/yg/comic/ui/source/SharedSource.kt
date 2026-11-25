package com.yg.comic.ui.source

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨界面传递「从外部共享进来的书源文本」。
 * 场景：用户从「开源阅读」(legado) 里把书源「分享」到本应用，或从文件管理器用本应用打开 .json，
 * MainActivity 收到 ACTION_SEND / ACTION_VIEW 的 Intent 后把文本放到这里，
 * AppRoot 观察到非空后导航到书源页，BookSourceScreen 消费并导入。
 */
object SharedSource {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun post(text: String) { if (text.isNotBlank()) _pending.value = text }

    fun consume(): String? {
        val v = _pending.value
        _pending.value = null
        return v
    }
}
