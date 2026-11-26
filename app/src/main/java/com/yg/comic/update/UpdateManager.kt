package com.yg.comic.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内置更新：从 GitHub Releases 检测最新版本、下载 APK、调起系统安装。
 * 仅依赖 JDK 的 HttpURLConnection + kotlinx-serialization，不引第三方网络库。
 */
object UpdateManager {
    private const val REPO = "Czz45/ygcomic"
    // 官方渠道优先：GitHub Releases API 是唯一真源（版本号来自 release tag，不可能陈旧）；
    // 静态 version.json 清单只是加速镜像，且 @main CDN 有缓存陈旧风险，仅作 API 不可达时的兜底。
    // checkLatest 会并发请求所有渠道、按此优先级取第一个成功结果，不再串行等待 → 快。
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    // 测试通道：拉最近若干个 release（含 prerelease），取最新的那个
    private const val API_LIST_URL = "https://api.github.com/repos/$REPO/releases?per_page=10"
    private fun manifestUrls(beta: Boolean): List<String> {
        val ts = System.currentTimeMillis()
        val file = if (beta) "version-beta.json" else "version.json"
        val raw = "https://raw.githubusercontent.com/Czz45/ygcomic/main/$file"
        return listOf(
            "$raw?t=$ts",
            "https://ghfast.top/$raw?t=$ts",
            "https://ghproxy.net/$raw?t=$ts",
            "https://gh-proxy.com/$raw?t=$ts",
            "https://cdn.jsdelivr.net/gh/Czz45/ygcomic@main/$file?t=$ts",
        )
    }
    // 单渠道超时收紧到 6s：并发下最慢也就 6s，不再叠加到几十秒。
    private const val CHECK_TIMEOUT = 6000
    private val json = Json { ignoreUnknownKeys = true }

    data class Release(
        val version: String,      // 例 0.1.3
        val notes: String,        // release body
        val apkUrl: String,
        val sizeMb: Float,
    )

    /** 检测失败的详细信息，传给 UI 展示真实原因。 */
    class CheckException(message: String) : Exception(message)

    private fun get(url: String, timeout: Int = 12000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = timeout
        conn.readTimeout = timeout
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "YGComic")
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw CheckException("服务器返回 $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 官方 GitHub Releases API（releases/latest 返回单个对象）解析成 Release。 */
    private fun parseRelease(text: String): Release? {
        val obj = json.parseToJsonElement(text).jsonObject
        val version = Regex("v(\\d+\\.\\d+\\.\\d+)")
            .find(obj["tag_name"]?.jsonPrimitive?.content.orEmpty())?.groupValues?.get(1)
            ?: return null
        val apkAsset = obj["assets"]?.jsonArray?.firstOrNull { a ->
            a.jsonObject["name"]?.jsonPrimitive?.content?.endsWith(".apk", ignoreCase = true) == true
        }?.jsonObject ?: return null
        val apkUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.content ?: return null
        if (!apkUrl.startsWith("https://")) return null
        val notes = obj["body"]?.jsonPrimitive?.content.orEmpty()
        val size = (apkAsset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) / 1024f / 1024f
        return Release(version, notes, apkUrl, size)
    }

    /** 测试通道：API 返回 release 数组，按发布时间取第一个带 APK 的非草稿版本（含 prerelease）。 */
    private fun parseReleaseList(text: String): Release? {
        val arr = json.parseToJsonElement(text).jsonArray
        for (el in arr) {
            val obj = el.jsonObject
            if (obj["draft"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) continue
            val version = Regex("v(\\d+\\.\\d+\\.\\d+)")
                .find(obj["tag_name"]?.jsonPrimitive?.content.orEmpty())?.groupValues?.get(1) ?: continue
            val apkAsset = obj["assets"]?.jsonArray?.firstOrNull { a ->
                a.jsonObject["name"]?.jsonPrimitive?.content?.endsWith(".apk", ignoreCase = true) == true
            }?.jsonObject ?: continue
            val apkUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.content ?: continue
            if (!apkUrl.startsWith("https://")) continue
            val notes = obj["body"]?.jsonPrimitive?.content.orEmpty()
            val size = (apkAsset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) / 1024f / 1024f
            return Release(version, notes, apkUrl, size)
        }
        return null
    }

    /** 静态 version.json 清单解析成 Release。 */
    private fun parseManifest(text: String): Release? {
        val o = json.parseToJsonElement(text).jsonObject
        val version = o["version"]?.jsonPrimitive?.content.orEmpty()
        if (Regex("\\d+\\.\\d+\\.\\d+").matchEntire(version) == null) return null
        val apkUrl = o["apk"]?.jsonPrimitive?.content.orEmpty()
        if (!apkUrl.startsWith("https://")) return null
        val notes = o["notes"]?.jsonPrimitive?.content.orEmpty()
        val sizeMb = o["sizeMb"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
        return Release(version, notes, apkUrl, sizeMb)
    }

    /**
     * 检测最新版本。
     * beta=false（默认）：只认正式版——用 releases/latest（GitHub 天然排除 prerelease）+ version.json 清单。
     * beta=true：连测试版一起认——用 releases 列表取最新一条（含 prerelease）+ version-beta.json 清单。
     * 所有渠道并发请求，按优先级取第一个成功结果。
     */
    suspend fun checkLatest(beta: Boolean = false): Release? = withContext(Dispatchers.IO) {
        data class Source(val label: String, val fetch: suspend () -> Release?)
        val sources = mutableListOf<Source>()
        sources += if (beta) {
            Source("GitHub 官方(含测试版)") { parseReleaseList(get(API_LIST_URL, CHECK_TIMEOUT)) }
        } else {
            Source("GitHub 官方") { parseRelease(get(API_URL, CHECK_TIMEOUT)) }
        }
        manifestUrls(beta).forEachIndexed { i, url ->
            sources += Source("清单${i + 1}") { parseManifest(get(url, CHECK_TIMEOUT)) }
        }
        // 全部同时发出：结果带上"渠道本身是否可达"，用来区分「网络全挂」和「确实没有这个版本」
        val deferred = sources.map { s ->
            async {
                runCatching { s.fetch() }.fold(
                    onSuccess = { rel -> Pair(rel, true) },
                    onFailure = { Pair(null, false) },
                )
            }
        }
        var lastErr = "未知错误"
        var anyReachable = false
        for ((i, d) in deferred.withIndex()) {
            val (rel, reachable) = d.await()
            if (rel != null) {
                android.util.Log.i("YGUpdate", "命中渠道=${sources[i].label} 版本=${rel.version}")
                return@withContext rel
            }
            // 渠道通了但没有任何版本（例如仓库里压根没有正式版，releases/latest 返回 404）
            if (reachable) anyReachable = true
            lastErr = "${sources[i].label} 不可用"
        }
        // 有渠道可达却都解析不出 Release → 确实没有可用版本（例如当前仓库没有正式版）
        if (anyReachable) return@withContext null
        throw CheckException(lastErr)
    }

    private fun describe(e: Exception): String = when (e) {
        is CheckException -> e.message ?: "服务器返回异常"
        is java.net.SocketTimeoutException -> "连接超时"
        is java.net.UnknownHostException -> "无法解析域名"
        is java.io.IOException -> "网络不可达"
        else -> e.message ?: "请求失败"
    }

    /** remote 是否比 local 新。版本号按 主.次.修 三段比较。 */
    fun isNewer(remote: String, local: String): Boolean = code(remote) > code(local)

    private fun code(v: String): Int {
        val p = v.split(".").map { it.toIntOrNull() ?: 0 }
        return (p.getOrNull(0) ?: 0) * 10000 + (p.getOrNull(1) ?: 0) * 100 + (p.getOrNull(2) ?: 0)
    }

    /** 把 github.com 下载直链转成国内可达代理镜像。 */
    private fun downloadUrls(origin: String): List<String> {
        val mirrors = mutableListOf(origin)
        if (origin.startsWith("https://github.com/")) {
            mirrors += "https://gh-proxy.com/$origin"
            mirrors += "https://ghfast.top/$origin"
        }
        return mirrors
    }

    /** 下载 APK 到应用缓存，进度 0..100；直连失败自动切代理镜像，全部失败返回 null。 */
    suspend fun download(context: Context, url: String, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val apk = File(dir, "ygcomic-update.apk")
            for (candidate in downloadUrls(url)) {
                try {
                    apk.delete()
                    val conn = URL(candidate).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 25000
                    conn.setRequestProperty("User-Agent", "YGComic")
                    conn.connect()
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) { conn.disconnect(); continue }
                    val total = conn.contentLength
                    val input = conn.inputStream
                    val out = FileOutputStream(apk)
                    val buf = ByteArray(64 * 1024)
                    var read = 0
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read * 100 / total).coerceIn(0, 100))
                    }
                    out.flush(); out.close(); input.close(); conn.disconnect()
                    if (apk.length() > 0) return@withContext apk
                } catch (e: Exception) {
                    apk.delete()
                }
            }
            null
        }

    /** 通过 FileProvider 调起系统安装器。 */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
