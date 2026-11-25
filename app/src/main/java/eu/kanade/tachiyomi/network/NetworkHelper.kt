package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Mihon/Tachiyomi 扩展使用的真实网络服务。
 * 保持与 extensions-lib 的 `NetworkHelper(context)` / `client` 签名一致。
 */
class NetworkHelper(context: Context) {

    private val appContext = context.applicationContext

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        // 部分 Mihon 扩展会启用 zstd 压缩；zstd-kmp 的 native 库在部分真机会 abort。
        // 这里声明 identity，扩展的 CompressionInterceptor 就不会再协商 zstd；
        // 同时不要手写 gzip：手写后 OkHttp 会关闭自身的透明 gzip 解压，扩展读到压缩字节会当乱码。
        .addInterceptor { chain ->
            val request = chain.request()
            val newRequest = if (request.header("Accept-Encoding") == null) {
                request.newBuilder().header("Accept-Encoding", "identity").build()
            } else {
                request
            }
            chain.proceed(newRequest)
        }
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor(DEFAULT_USER_AGENT))
        .addInterceptor(CloudflareInterceptor())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun defaultUserAgentProvider(): String = DEFAULT_USER_AGENT

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}

/** 简单内存 CookieJar：扩展登录/Cloudflare 场景够用。 */
private class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val list = store.getOrPut(url.host) { mutableListOf() }
        synchronized(list) {
            for (cookie in cookies) {
                list.removeAll { it.name == cookie.name && it.path == cookie.path }
                list.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        synchronized(list) {
            list.removeAll { it.expiresAt < now }
            return list.filter { it.matches(url) }
        }
    }
}
