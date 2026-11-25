package com.yg.comic.source.mihon

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.yg.comic.source.SourceProvider
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.File

/**
 * Mihon / Tachiyomi 扩展加载器。
 *
 * 不依赖已安装的 Mihon：直接扫描本机已安装的扩展 APK，也可以从扩展商店下载 APK 后
 * 用 DexClassLoader 加载。扩展里的类只认 `eu.kanade.tachiyomi.*` 接口，宿主提供真实实现。
 */
object MihonExtensionManager {

    private lateinit var appContext: Context
    private val handles = LinkedHashMap<String, MihonSourceHandle>()
    private var injektReady = false
    /** 扩展加载/扫描全局串行锁：搜索可能 12 路并发，多个线程同时 DexClassLoader 会放大内存和类冲突。 */
    private val loadLock = Any()

    private val parentFirstPrefixes = listOf(
        "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.",
        "org.json.", "org.jsoup.", "okhttp3.", "okio.", "rx.",
        "eu.kanade.tachiyomi.source.", "eu.kanade.tachiyomi.network.",
        "eu.kanade.tachiyomi.util.", "uy.kohesive.injekt.",
        "ireader.core.", "io.ktor.", "com.fleeksoft.",
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        ensureInjekt()
    }

    private fun ensureInjekt() {
        if (injektReady) return
        synchronized(this) {
            if (injektReady) return
            val scope = InjektScope(DefaultRegistrar())
            scope.addSingleton<Context>(appContext)
            scope.addSingleton<android.app.Application>(appContext as android.app.Application)
            val networkHelper = NetworkHelper(appContext)
            scope.addSingleton(networkHelper)
            scope.addSingletonFactory<OkHttpClient> { networkHelper.client }
            scope.addSingletonFactory<Json> { Json { ignoreUnknownKeys = true; isLenient = true } }
            // 扩展的 companion 里 injectLazy<ProtoBuf>()，injekt 容器必须注册 ProtoBuf 实例，
            // 否则 No registered instance or factory for type ProtoBuf → ExceptionInInitializerError。
            // 1.9.0 里 ProtoBuf 是抽象类，用 companion 的 ProtoBuf.Default。
            scope.addSingleton<ProtoBuf>(ProtoBuf.Default)
            scope.addSingletonFactory<eu.kanade.tachiyomi.network.JavaScriptEngine> {
                eu.kanade.tachiyomi.network.JavaScriptEngine(appContext)
            }
            Injekt = scope
            injektReady = true
        }
    }

    /** 最近一次 scanInstalled 中"扫到但加载失败"的扩展及原因，供 UI 显示，避免装了却静默识别不到。 */
    @Volatile
    var lastScanErrors: List<String> = emptyList()
        private set

    /** 按包名解析扩展图标的内存缓存（同一扩展多源共用一张，避免每条源都重复读 APK）。 */
    private val iconCache = HashMap<String, android.graphics.drawable.Drawable?>()

    /**
     * 取扩展图标：优先系统已装应用，其次私有目录里下载过的 APK（files/mihon_extensions/{pkg}.apk）。
     * 纯本地解析、不走网络、不改 DB；null 表示没找到，UI 用通用占位。
     */
    fun iconFor(packageName: String): android.graphics.drawable.Drawable? {
        if (packageName.isBlank() || !::appContext.isInitialized) return null
        synchronized(iconCache) {
            if (iconCache.containsKey(packageName)) return iconCache[packageName]
        }
        val pm = appContext.packageManager
        var icon: android.graphics.drawable.Drawable? = null
        // 1) 系统已安装的扩展：直接拿应用图标。
        try {
            @Suppress("DEPRECATION")
            icon = pm.getApplicationIcon(packageName)
        } catch (_: Throwable) {
            // 没装进系统，继续从私有目录 APK 取。
        }
        // 2) 私有目录里下载过（未安装）的扩展 APK。
        if (icon == null) {
            val apk = File(File(appContext.filesDir, "mihon_extensions"), "$packageName.apk")
            if (apk.exists()) {
                try {
                    val info = pm.getPackageArchiveInfo(apk.absolutePath, 0)
                    info?.applicationInfo?.let { ai ->
                        ai.sourceDir = apk.absolutePath
                        ai.publicSourceDir = apk.absolutePath
                        icon = ai.loadIcon(pm)
                    }
                } catch (_: Throwable) {
                    // 读图标失败就留空，UI 用占位。
                }
            }
        }
        synchronized(iconCache) { iconCache[packageName] = icon }
        return icon
    }

    /** 扫描所有已安装的 Mihon/Tachiyomi 扩展。 */
    fun scanInstalled(): List<MihonSourceHandle> = synchronized(loadLock) {
        ensureInjekt()
        val pm = appContext.packageManager
        val result = LinkedHashMap<String, MihonSourceHandle>()
        val errors = ArrayList<String>()
        lastScanErrors = emptyList()
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        }
        for (info in packages) {
            val appInfo = info.applicationInfo ?: continue
            val meta = appInfo.metaData
            // 判定放宽： Mihon/Tachiyomi 扩展包名固定前缀，meta key 有的版本读不到，二者满足其一即尝试加载。
            val isExtensionPkg = info.packageName.startsWith("eu.kanade.tachiyomi.extension.")
            val hasClassKey = meta != null && (meta.containsKey("tachiyomi.extension.class") ||
                meta.containsKey("tachiyomi.extension.factory"))
            if (!isExtensionPkg && !hasClassKey) continue
            val apk = appInfo.sourceDir ?: continue
            // 不再静默吞异常：失败原因记录到 lastScanErrors，UI 能显示"装了但加载失败"的具体原因。
            try {
                val loaded = loadApk(File(apk), info.packageName, info.versionName ?: "")
                loaded.forEach { result[it.key] = it }
                if (loaded.isEmpty()) {
                    errors += "${info.packageName}: 未找到可用入口类或源（meta 缺 class 或接口版本不匹配）"
                }
            } catch (t: Throwable) {
                errors += "${info.packageName}: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        lastScanErrors = errors
        synchronized(handles) { handles.putAll(result) }
        result.values.sortedBy { it.source.name.lowercase() }
    }

    /** 从扩展商店下载 APK 到私有目录，然后加载。 */
    suspend fun downloadAndLoad(item: MihonStoreItem): List<MihonSourceHandle> = withContext(Dispatchers.IO) {
        ensureInjekt()
        val dir = File(appContext.filesDir, "mihon_extensions").apply { mkdirs() }
        val apk = File(dir, "${item.packageName}.apk")
        if (!apk.exists() || apk.length() < 1024) {
            val client = NetworkHelper(appContext).client
            val request = Request.Builder().url(item.apkUrl).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("下载扩展失败：HTTP ${resp.code}")
                resp.body?.byteStream()?.use { input ->
                    apk.outputStream().use { output -> input.copyTo(output) }
                } ?: error("下载扩展失败：空响应")
            }
        }
        loadApk(apk, item.packageName, item.versionName)
    }

    /** 从本地 APK 文件加载，packageName/versionName 可空（会自动读 manifest）。 */
    fun loadApk(file: File, packageNameHint: String? = null, versionNameHint: String? = null): List<MihonSourceHandle> {
        ensureInjekt()
        if (!file.exists()) return emptyList()
        val pm = appContext.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA)
        } ?: return emptyList()
        val appInfo = info.applicationInfo ?: return emptyList()
        appInfo.sourceDir = file.absolutePath
        appInfo.publicSourceDir = file.absolutePath
        val meta = appInfo.metaData ?: return emptyList()
        val className = meta.getString("tachiyomi.extension.class")
            ?: meta.getString("tachiyomi.extension.factory")
            ?: return emptyList()
        // 扩展清单里 nsfw 常是 Integer（1/0），getBoolean 会打 ClassCastException 警告并吞成 false。
        val nsfw = when (val v = meta.get("tachiyomi.extension.nsfw")) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", ignoreCase = true) || v == "1"
            else -> false
        }
        val packageName = packageNameHint ?: info.packageName
        val versionName = versionNameHint ?: info.versionName ?: ""

        // DexClassLoader 只接受应用私有目录里的可写 dex 文件；外部存储的 APK 先复制进来。
        var apkFile = file
        if (!apkFile.absolutePath.startsWith(appContext.filesDir.absolutePath)) {
            val dir = File(appContext.filesDir, "mihon_extensions").apply { mkdirs() }
            val dest = File(dir, "$packageName.apk")
            // 上次加载把 dest 设成了只读，这里 overwrite 会因"目标只读"失败 → 先恢复可写再覆盖。
            if (dest.exists()) dest.setWritable(true)
            if (!dest.exists() || dest.length() != file.length()) file.copyTo(dest, overwrite = true)
            apkFile = dest
        }
        // Android 14+ 要求 DexClassLoader 的 APK 为只读文件，否则报 Writable dex file is not allowed。
        if (apkFile.canWrite()) apkFile.setReadOnly()

        // 扩展是外部 APK，可能因为缺类/ABI/版本不匹配抛 NoClassDefFoundError 等 Error。
        // 这里统一转成 RuntimeException，否则协程里会直接 FATAL 闪退。
        try {
            val dexDir = File(appContext.codeCacheDir, "mihon_dex/${packageName.hashCode()}").apply { mkdirs() }
            val loader = ChildFirstPathClassLoader(apkFile.absolutePath, dexDir, appContext.classLoader, parentFirstPrefixes)
            val clazz = loader.loadClass(className)
            val sources: List<eu.kanade.tachiyomi.source.Source> = when {
                eu.kanade.tachiyomi.source.SourceFactory::class.java.isAssignableFrom(clazz) -> {
                    val factory = clazz.getDeclaredConstructor().newInstance() as eu.kanade.tachiyomi.source.SourceFactory
                    factory.createSources()
                }
                eu.kanade.tachiyomi.source.Source::class.java.isAssignableFrom(clazz) -> {
                    listOf(clazz.getDeclaredConstructor().newInstance() as eu.kanade.tachiyomi.source.Source)
                }
                else -> emptyList()
            }
            val loaded = sources.map {
                MihonSourceHandle(packageName, apkFile.absolutePath, versionName, nsfw, it)
            }
            synchronized(handles) { loaded.forEach { h -> handles[h.key] = h } }
            return loaded
        } catch (t: Throwable) {
            throw RuntimeException("加载扩展失败：${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    fun find(packageName: String, sourceId: Long): MihonSourceHandle? = synchronized(loadLock) {
        synchronized(handles) { handles["$packageName:$sourceId"]?.let { return it } }
        // 先试本 App 私有目录里的扩展 APK（商店下载的源都在这里），避免每次搜索都全机扫描。
        val file = File(appContext.filesDir, "mihon_extensions/$packageName.apk")
        if (file.exists()) {
            loadApk(file, packageName).firstOrNull { it.source.id == sourceId }?.let { return it }
        }
        // 没加载过：再扫手机已安装的扩展
        scanInstalled().firstOrNull { it.packageName == packageName && it.source.id == sourceId }
    }

    fun loadedSources(): List<MihonSourceHandle> = synchronized(handles) { handles.values.toList() }

    /** 随 App 打包的 4 个中文扩展：不联网也能一键导入。 */
    private val builtinItems = listOf(
        MihonBuiltinItem("mihon_builtin/baozimhorg-v1.6.39.apk", "GoDa", "eu.kanade.tachiyomi.extension.zh.baozimhorg", "1.6.39"),
        MihonBuiltinItem("mihon_builtin/dm5-v1.6.4.apk", "动漫屋", "eu.kanade.tachiyomi.extension.zh.dm5", "1.6.4"),
        MihonBuiltinItem("mihon_builtin/kaobeimanhua-v1.6.1.apk", "拷貝漫畫", "eu.kanade.tachiyomi.extension.zh.kaobeimanhua", "1.6.1"),
        MihonBuiltinItem("mihon_builtin/manhuagui.apk", "漫画柜", "eu.kanade.tachiyomi.extension.zh.manhuagui", "1.6.2"),
    )

    fun builtinItems(): List<MihonBuiltinItem> = builtinItems

    /** 加载随包内置扩展；单个失败不影响其它扩展。 */
    fun loadBuiltin(): MihonBuiltinResult = synchronized(loadLock) {
        ensureInjekt()
        val handles = ArrayList<MihonSourceHandle>()
        val errors = ArrayList<String>()
        val dir = File(appContext.filesDir, "mihon_extensions").apply { mkdirs() }
        for (item in builtinItems) {
            try {
                val dest = File(dir, "${item.packageName}.apk")
                if (!dest.exists() || dest.length() < 1024) {
                    appContext.assets.open(item.assetPath).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (dest.canWrite()) dest.setReadOnly()
                handles += loadApk(dest, item.packageName, item.versionName)
            } catch (t: Throwable) {
                errors += "${item.displayName}: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        MihonBuiltinResult(handles, errors)
    }

    /** 读取内置的中文扩展商店清单。 */
    fun storeItems(): List<MihonStoreItem> {
        val raw = runCatching {
            appContext.assets.open("mihon_extensions.json").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val srcs = o.optJSONArray("sources") ?: JSONArray()
                MihonStoreItem(
                    name = o.optString("name"),
                    packageName = o.optString("packageName"),
                    apkUrl = o.optString("apkUrl"),
                    iconUrl = o.optString("iconUrl"),
                    versionName = o.optString("versionName"),
                    versionCode = o.optString("versionCode"),
                    extensionLib = o.optString("extensionLib"),
                    contentWarning = o.optString("contentWarning"),
                    sources = (0 until srcs.length()).mapNotNull { j ->
                        val s = srcs.optJSONObject(j) ?: return@mapNotNull null
                        MihonStoreSource(
                            id = s.optString("id"),
                            name = s.optString("name"),
                            language = s.optString("language"),
                            homeUrl = s.optString("homeUrl"),
                        )
                    },
                )
            }.getOrNull()
        }
    }

    /**
     * 拉取用户自定义 Mihon 扩展仓库的 index.json，解析成商店条目列表。
     * 失败直接抛异常，由 ViewModel 统一兜底提示。走 okhttp（仓库多为 https + 重定向）。
     */
    suspend fun fetchRepoItems(url: String): List<MihonStoreItem> = withContext(Dispatchers.IO) {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) error("仓库地址需以 http(s):// 开头")
        // Mihon 官方仓库主推 index.pb（gzip 压缩的 protobuf），本 App 用 JSON 解析器读不了。
        // 检测 .pb 结尾就换同目录的 index.json（keiyoushi 等主流仓库两个格式都提供），保证能导入。
        val fetchUrl = u.replace(Regex("""\.pb(?=\?|#|$)""", RegexOption.IGNORE_CASE), ".json")
        val client = NetworkHelper(appContext).client
        val request = Request.Builder().url(fetchUrl).build()
        val raw = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("拉取仓库失败：HTTP ${resp.code}")
            resp.body.byteStream().use { it.readBytes().toString(Charsets.UTF_8) }
        }
        parseRepoIndex(raw)
    }

    /**
     * 解析仓库清单，兼容两种格式：
     * 1) Mihon 原生 index.json：{ "extensions": [ { name, pkg, apk, icon, lang, nsfw, sources:[{id,name,lang}] } ] }
     * 2) 本 App 内置清单：顶层数组，字段 packageName/apkUrl/iconUrl/contentWarning，语言在 source.language。
     */
    fun parseRepoIndex(raw: String): List<MihonStoreItem> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        // 兼容三种入口：
        // ① Mihon 真实 index.json：extensions 嵌在 extensionList 里
        // ② 老 Mihon index.json：顶层 extensions 数组
        // ③ 本 App 内置清单：顶层数组
        val arr: JSONArray = runCatching {
            val root = JSONObject(text)
            root.optJSONObject("extensionList")?.optJSONArray("extensions")
                ?: root.optJSONArray("extensions")
        }.getOrNull() ?: runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()

        // 字段值可能是字符串或数字（versionCode/versionName 在新格式里是字符串），统一取字符串。
        fun sv(obj: JSONObject?, vararg keys: String): String {
            if (obj == null) return ""
            for (k in keys) {
                val v = obj.opt(k) ?: continue
                if (v === JSONObject.NULL) continue
                val out = v.toString().trim()
                if (out.isNotEmpty() && out != "null") return out
            }
            return ""
        }

        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.optJSONObject(i) ?: return@runCatching null
                val res = o.optJSONObject("resources") // 新格式 apk/icon 嵌在 resources 里
                val packageName = sv(o, "packageName", "pkg")
                val apkUrl = sv(o, "apkUrl", "apk").ifBlank { sv(res, "apkUrl", "apk") }
                if (packageName.isBlank() || apkUrl.isBlank()) return@runCatching null
                val extLang = sv(o, "lang", "language")
                // 新格式 contentWarning 是字符串枚举 CONTENT_WARNING_NSFW / MIXED / SAFE；老格式是 nsfw 数字。
                val cw = sv(o, "contentWarning")
                val nsfw = (o.has("nsfw") && (o.optInt("nsfw") == 1 || o.optBoolean("nsfw"))) ||
                    cw.contains("NSFW", true) || cw.contains("MIXED", true)
                val contentWarning = when {
                    cw.contains("NSFW", true) -> "NSFW"
                    cw.contains("MIXED", true) -> "MIXED"
                    cw.isNotBlank() -> cw
                    nsfw -> "NSFW"
                    else -> ""
                }
                val srcs = o.optJSONArray("sources") ?: JSONArray()
                MihonStoreItem(
                    name = sv(o, "name").ifBlank { packageName },
                    packageName = packageName,
                    apkUrl = apkUrl,
                    iconUrl = sv(o, "iconUrl", "icon").ifBlank { sv(res, "iconUrl", "icon") },
                    versionName = sv(o, "versionName"),
                    versionCode = sv(o, "versionCode"),
                    extensionLib = sv(o, "extensionLib"),
                    contentWarning = contentWarning,
                    sources = (0 until srcs.length()).mapNotNull { j ->
                        runCatching {
                            val so = srcs.optJSONObject(j) ?: return@runCatching null
                            MihonStoreSource(
                                id = sv(so, "id"),
                                name = sv(so, "name"),
                                language = sv(so, "language", "lang").ifBlank { extLang },
                                homeUrl = sv(so, "homeUrl", "baseUrl"),
                            )
                        }.getOrNull()
                    },
                )
            }.getOrNull()
        }
    }

    /** 生成一个可直接入库的 Mihon 书源 JSON。 */
    fun sourceJson(handle: MihonSourceHandle): String = JSONObject().apply {
        put("provider", "mihon")
        put("bookSourceName", handle.source.name)
        put("bookSourceUrl", (handle.source as? eu.kanade.tachiyomi.source.online.HttpSource)?.baseUrl ?: "")
        put("bookSourceGroup", "Mihon")
        put("bookSourceType", 2) // 漫画源；0=文字/1=音频/2=漫画/3=下载/4=视频
        put("packageName", handle.packageName)
        put("sourceId", handle.source.id)
        put("className", handle.source.javaClass.name)
        put("nsfw", handle.nsfw)
        // 每个语言源各自的语言显示名（Deutsch / English / Español …），键名统一为 lang，
        // 首页浏览页下拉与图源页语言行都读它，避免运行时反复加载扩展。老源没有此字段需重扫。
        val langCode = (handle.source as? eu.kanade.tachiyomi.source.CatalogueSource)?.lang.orEmpty()
        val langName = if (langCode.isBlank()) "" else try {
            val loc = java.util.Locale(langCode)
            loc.getDisplayLanguage(loc).replaceFirstChar { it.uppercaseChar() }
        } catch (_: Exception) {
            langCode.uppercase()
        }
        put("lang", langName)
    }.toString()

    /**
     * 运行时查某个源的 lang 显示名（Deutsch / English / Español …）。
     * 老数据入库时没写 lang 字段，首页下拉读不到时调这个从本地已加载扩展兜底取。
     * 从本地 APK/已装扩展读，不联网；取不到返回 null，由调用方回退源名。
     */
    fun sourceLangName(packageName: String, sourceId: String): String? {
        val sid = sourceId.toLongOrNull() ?: return null
        val handle = find(packageName, sid) ?: return null
        val code = (handle.source as? eu.kanade.tachiyomi.source.CatalogueSource)?.lang.orEmpty()
        if (code.isBlank()) return null
        return try {
            val loc = java.util.Locale(code)
            loc.getDisplayLanguage(loc).replaceFirstChar { it.uppercaseChar() }
        } catch (_: Exception) {
            code.uppercase()
        }
    }
}
