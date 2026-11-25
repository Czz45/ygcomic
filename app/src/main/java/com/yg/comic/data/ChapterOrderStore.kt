package com.yg.comic.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * 目录顺序的本地持久化：按「书源 JSON + 这本书的 bookUrl」记住正序/反序。
 * 阅读页和详情页共用同一份 key，所以两边永远同步。
 */
object ChapterOrderStore {
    private const val PREFS_NAME = "chapter_order"
    private const val KEY_SET = "reversed_keys"

    private var prefs: SharedPreferences? = null
    private val _reversedKeys = MutableStateFlow<Set<String>>(emptySet())
    val reversedKeys: StateFlow<Set<String>> = _reversedKeys.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _reversedKeys.value = p.getStringSet(KEY_SET, emptySet())?.toSet().orEmpty()
    }

    /** 稳定 key：bookUrl 可带签名参数，不能直接当文件名；SHA-256 后固定长度且安全。 */
    fun keyOf(sourceJson: String, bookUrl: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((sourceJson + "\u0000" + bookUrl).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isReversed(sourceJson: String, bookUrl: String): Boolean =
        keyOf(sourceJson, bookUrl) in _reversedKeys.value

    fun setReversed(sourceJson: String, bookUrl: String, reversed: Boolean) {
        val k = keyOf(sourceJson, bookUrl)
        val next = if (reversed) _reversedKeys.value + k else _reversedKeys.value - k
        prefs?.edit()?.putStringSet(KEY_SET, next)?.apply()
        _reversedKeys.value = next
    }
}
