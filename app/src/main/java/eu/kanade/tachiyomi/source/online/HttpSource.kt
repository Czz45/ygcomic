package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest

/**
 * 宿主侧真实 HttpSource 实现，替代 extensions-lib 的 stub。
 * KeiSource 会在 init 里反射改写 [headers] 的 lazy delegate，字段名必须是 `headers$delegate`。
 */
@Suppress("UNUSED", "UnusedReceiverParameter")
abstract class HttpSource : CatalogueSource {

    protected val network: NetworkHelper by injectLazy()

    abstract val baseUrl: String

    open fun getHomeUrl(): String = baseUrl

    open val versionId: Int = 1

    override val id: Long by lazy { generateId(name, lang, versionId) }

    // 新版 keiyoushi 扩展（Generated/Lj0）构造时反射 getDeclaredField("headers$delegate")
    // 再 Field.set 注入自己的 Lazy 实现（实测是 kotlin.Lazy）。原来的 `by lazy` 生成
    // private final Lazy 字段，targetSdk 35 下反射写 final 字段抛异常 → 构造失败 → 扩展认不出。
    // 这里手动声明同名字段且去掉 final，扩展即可成功注入；类型仍是 Lazy<Headers>，
    // 扩展不注入时行为与 by lazy 完全一致，故老扩展/商店导入/内置扩展都不受影响。
    @JvmField
    var `headers$delegate`: Lazy<Headers> = lazy { headersBuilder().build() }

    val headers: Headers get() = `headers$delegate`.value

    open val client: OkHttpClient get() = network.client

    protected open fun headersBuilder(): Headers.Builder =
        Headers.Builder().add("User-Agent", network.defaultUserAgentProvider())

    override fun toString(): String = "$name (${lang.uppercase()})"

    open suspend fun getImageUrl(page: Page): String = page.url

    protected open fun imageRequest(page: Page): Request =
        Request.Builder().url(page.url).headers(headers).build()

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        val uri = orig.toHttpUrlOrNull() ?: return orig
        var path = uri.encodedPath
        if (uri.encodedQuery != null) path += "?" + uri.encodedQuery
        return path
    }

    abstract fun getMangaUrl(manga: SManga): String

    abstract fun getChapterUrl(chapter: SChapter): String

    companion object {
        /** 与 Mihon 相同的 id 生成规则：name/lang/versionId 的 MD5 前 16 位，符号位清零。 */
        fun generateId(name: String, lang: String, versionId: Int): Long {
            val md5 = MessageDigest.getInstance("MD5")
                .digest("$name/$lang/$versionId".toByteArray(Charsets.UTF_8))
            var id = 0L
            for (i in 0 until 8) {
                id = (id shl 8) or (md5[i].toLong() and 0xFF)
            }
            return id and 0x7FFFFFFFFFFFFFFFL
        }
    }
}
