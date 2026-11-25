package com.yg.comic.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 标准 WebDAV 客户端（基于 HttpURLConnection，与项目其它网络调用一致，不引入 OkHttp）。
 * 支持 Basic Auth，GET 下载、PUT 上传、PROPFIND 列目录，供坚果云等公共/自建 WebDAV 备份同步使用。
 *
 * 来源说明：这是 YGComic 自有的薄网络封装（非移植 legado 类）；legado 用 okhttp + 其自研 WebDavBook，
 * 本项目无 okhttp，改用项目统一的 HttpURLConnection 实现同样的 WebDAV 方法集。
 */
object WebDavClient {

    private const val TIMEOUT_MS = 20_000

    private fun authHeader(user: String, pass: String): String {
        val token = Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $token"
    }

    private fun open(url: String, method: String, user: String, pass: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", authHeader(user, pass))
        return conn
    }

    /** 测试连通性：PROPFIND 根目录，2xx/207 视为通。 */
    suspend fun test(url: String, user: String, pass: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val conn = open(url, "PROPFIND", user, pass)
            conn.setRequestProperty("Depth", "0")
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) Result.success(Unit)
            else Result.failure(Exception("HTTP $code"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** GET 下载为 UTF-8 文本。 */
    suspend fun download(url: String, user: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val conn = open(url, "GET", user, pass)
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                Result.failure(Exception("HTTP $code"))
            } else {
                val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                conn.disconnect()
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** GET 下载为原始字节（ZIP 备份用，避免按 UTF-8 解码损坏二进制）。 */
    suspend fun downloadBytes(url: String, user: String, pass: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val conn = open(url, "GET", user, pass)
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                Result.failure(Exception("HTTP $code"))
            } else {
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                Result.success(bytes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** PUT 上传原始字节（ZIP 备份用）。 */
    suspend fun uploadBytes(url: String, user: String, pass: String, bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val conn = open(url, "PUT", user, pass)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/zip")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) Result.success(Unit)
            else Result.failure(Exception("HTTP $code"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 把服务器根地址与目录拼成可直接 PROPFIND 的 URL。
     * 例：root=https://dav.jianguoyun.com/dav/ dir=legado → https://dav.jianguoyun.com/dav/legado/
     */
    fun dirUrl(root: String, dir: String): String {
        val base = root.ifBlank { "" }.trimEnd('/') + "/"
        val d = dir.trim().trim('/')
        return if (d.isEmpty()) base else base + d + "/"
    }

    /** PUT 上传 UTF-8 文本，返回是否 2xx。 */
    suspend fun upload(url: String, user: String, pass: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val conn = open(url, "PUT", user, pass)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = text.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) Result.success(Unit)
            else Result.failure(Exception("HTTP $code"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * PROPFIND 列目录，返回子元素完整 URL 列表（解析 href，处理 &amp; 与相对路径）。
     * 用于列出 legado 备份目录里的备份文件。
     */
    suspend fun list(url: String, user: String, pass: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val conn = open(url, "PROPFIND", user, pass)
            conn.setRequestProperty("Depth", "1")
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                Result.failure(Exception("HTTP $code"))
            } else {
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                conn.disconnect()
                val base = URL(url)
                val hrefs = Regex("<(?:[a-zA-Z0-9]+:)?href[^>]*>(.*?)</(?:[a-zA-Z0-9]+:)?href>", RegexOption.IGNORE_CASE)
                    .findAll(body).map { it.groupValues[1].trim() }
                    .map { it.replace("&amp;", "&") }
                val out = ArrayList<String>()
                for (href in hrefs) {
                    if (href.isEmpty()) continue
                    val full = try {
                        URL(base, href).toString()
                    } catch (_: Exception) {
                        href
                    }
                    // 过滤掉目录本身
                    if (full.trimEnd('/') == url.trimEnd('/')) continue
                    out.add(full)
                }
                Result.success(out)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
