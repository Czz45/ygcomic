package eu.kanade.tachiyomi

import com.yg.comic.BuildConfig

/** 提供给 Mihon/Tachiyomi 扩展读取的宿主信息（真实实现，非 stub）。 */
object AppInfo {
    fun getVersionCode(): Int = BuildConfig.VERSION_CODE

    fun getVersionName(): String = BuildConfig.VERSION_NAME
}
