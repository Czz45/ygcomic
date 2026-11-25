package com.yg.comic.source.mihon

import eu.kanade.tachiyomi.source.Source

/** 一个已加载的 Mihon 扩展里的单个源。 */
data class MihonSourceHandle(
    val packageName: String,
    val apkPath: String,
    val versionName: String,
    val nsfw: Boolean,
    val source: Source,
) {
    val key: String get() = "$packageName:${source.id}"
}

/** 扩展商店条目（内置清单里的一个 APK 包，可能含多个源）。 */
data class MihonStoreItem(
    val name: String,
    val packageName: String,
    val apkUrl: String,
    val iconUrl: String,
    val versionName: String,
    val versionCode: String,
    val extensionLib: String,
    val contentWarning: String,
    val sources: List<MihonStoreSource>,
)

data class MihonStoreSource(
    val id: String,
    val name: String,
    val language: String,
    val homeUrl: String,
)

/** 随 App 打包的 Mihon 扩展（不进商店，点一次直接离线加载）。 */
data class MihonBuiltinItem(
    val assetPath: String,
    val displayName: String,
    val packageName: String,
    val versionName: String,
)

data class MihonBuiltinResult(
    val handles: List<MihonSourceHandle>,
    val errors: List<String>,
)
