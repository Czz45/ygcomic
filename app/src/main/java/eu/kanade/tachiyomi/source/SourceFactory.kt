package eu.kanade.tachiyomi.source

/** 扩展 APK 里 SourceFactory 的实现入口。 */
@Suppress("Unused")
interface SourceFactory {
    fun createSources(): List<Source>
}
