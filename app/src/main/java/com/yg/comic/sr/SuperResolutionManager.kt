package com.yg.comic.sr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.yg.comic.data.SettingsStore
import com.yg.comic.sr.preload.SrTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.core.superresolution.RealESRGANProcessor
import mihon.core.superresolution.VulkanHelper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap

/**
 * 超分管理层（简化自 mihon-super-resolution 的 SuperResolutionManager+ModelManager，MIT）。
 * 去掉 Injekt/logcat/MR/Coil Interceptor 依赖，改由 ReaderScreen 直接调用。
 * 职责：模型按挡位选择+首次下载(param/bin 多镜像 fallback)+LRU 内存缓存+native 推理。
 */
class SuperResolutionManager(
    private val context: Context,
    private val settings: SettingsStore,
) {
    companion object {
        private const val TAG = "SRManager"
        const val MIN_INPUT_WIDTH = 300
        const val MIN_INPUT_HEIGHT = 400
        @Volatile
        var instance: SuperResolutionManager? = null

        /**
         * 全局串行锁：所有 SRManager 实例共用同一把锁。
         * 后台预超分队列与阅读页实时回退各持一个实例，若 mutex 是实例级的，
         * 两者会并发喂 Vulkan → Adreno 空 VkPipeline SIGSEGV。提升为 companion 级
         * 后无论多少实例，同一时刻只有一个在跑 native 推理。
         */
        private val GLOBAL_MUTEX = Mutex()
    }

    private val mutex: Mutex get() = GLOBAL_MUTEX
    private val cacheLock = Any()
    // 内存缓存不再设上限（用户 11-24 拍板取消内存/硬盘占用上限）：访问序 LRU 结构保留，但不淘汰。
    private val srCache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var processor: RealESRGANProcessor? = null
    private var loadedModel: SRModel? = null
    @Volatile
    private var modelVersion = 0
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()

    val vulkanAvailable: Boolean by lazy {
        VulkanHelper.isVulkanSupported(context) && VulkanHelper.gpuCount > 0
    }

    private fun modelsRoot(): File = File(context.filesDir, "superresolution/models")

    private fun modelDir(model: SRModel): File = File(modelsRoot(), model.dir)

    /** 模型是否已完整下载(param+bin)。 */
    fun isModelReady(model: SRModel): Boolean {
        val d = modelDir(model)
        return File(d, "${model.dir}.param").exists() && File(d, "${model.dir}.bin").length() > 0
    }

    fun isReady(): Boolean = processor?.isReady == true

    fun modelDescription(): String {
        val m = loadedModel ?: return "未加载"
        val gpu = if (vulkanAvailable) "Vulkan GPU" else "CPU"
        return "${m.label} · $gpu"
    }

    /** 下载模型：每条镜像尝试 param+bin，任一镜像两者都成功即返回 true。带进度回调(0..100)。 */
    suspend fun downloadModel(model: SRModel, onProgress: ((Int, String) -> Unit)? = null): Boolean =
        withContext(Dispatchers.IO) {
            val dir = modelDir(model)
            val paramFile = File(dir, "${model.dir}.param")
            val binFile = File(dir, "${model.dir}.bin")
            if (isModelReady(model)) {
                onProgress?.invoke(100, "已下载")
                return@withContext true
            }
            if (!dir.exists()) dir.mkdirs()
            for (mirrorIdx in model.mirrorUrls("${model.dir}.param").indices) {
                val paramUrl = model.mirrorUrls("${model.dir}.param")[mirrorIdx]
                val binUrl = model.mirrorUrls("${model.dir}.bin")[mirrorIdx]
                try {
                    onProgress?.invoke(5, "下载配置 ${mirrorIdx + 1}")
                    if (!downloadUrl(paramUrl, paramFile)) continue
                    val binSize = contentLength(binUrl)
                    onProgress?.invoke(15, "下载权重 ${mirrorIdx + 1}")
                    if (!downloadUrl(binUrl, binFile, binSize, onProgress)) continue
                    if (isModelReady(model)) {
                        onProgress?.invoke(100, "完成")
                        Log.i(TAG, "model downloaded: ${model.dir}")
                        return@withContext true
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "mirror $mirrorIdx failed: ${e.message}")
                    paramFile.delete(); binFile.delete()
                }
            }
            onProgress?.invoke(0, "下载失败：请检查网络后重试")
            false
        }

    private fun contentLength(urlStr: String): Long = try {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val n = conn.contentLength.toLong().let { if (it < 0) 0L else it }
        conn.disconnect()
        n
    } catch (_: Throwable) { 0L }

    private fun downloadUrl(urlStr: String, dest: File, expected: Long = 0L, onProgress: ((Int, String) -> Unit)? = null): Boolean {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "YGComic/1.0")
        conn.connect()
        if (conn.responseCode !in 200..299) {
            Log.w(TAG, "HTTP ${conn.responseCode} for $urlStr")
            conn.disconnect()
            return false
        }
        val total = if (expected > 0) expected else conn.contentLength.toLong()
        FileOutputStream(dest).use { out ->
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var sum = 0L
                var lastPct = -1
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    sum += read
                    if (total > 0) {
                        val pct = (10 + 85 * sum / total).toInt().coerceIn(10, 95)
                        if (pct != lastPct) { onProgress?.invoke(pct, "下载中 ${sum / 1024}/${total / 1024}KB"); lastPct = pct }
                    }
                }
            }
        }
        conn.disconnect()
        return dest.exists() && dest.length() > 0
    }

    /** 切换模型：同模型仅更新降噪，否则释放旧 processor、清缓存、版本号++。 */
    suspend fun switchModel(model: SRModel) = mutex.withLock {
        if (loadedModel == model && processor?.isReady == true) return@withLock
        processor?.release()
        processor = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        loadedModel = model
        synchronized(cacheLock) { srCache.clear() }
        modelVersion++
        Log.i(TAG, "switch model -> ${model.dir}")
    }

    private suspend fun ensureLoaded(model: SRModel) {
        if (processor?.isReady == true && loadedModel == model) return
        val p = RealESRGANProcessor()
        val gpuid = if (vulkanAvailable) 0 else -1
        val modelPath = File(modelDir(model), model.dir).absolutePath
        p.initialize(modelPath, model.modelType, model.scale, gpuid)
        processor = p
        loadedModel = model
    }

    /** 处理单张图：过小缩略图跳过；命中缓存直接返回；推理失败回原图。不再设尺寸上限（全尺寸超分）。 */
    suspend fun process(input: Bitmap, key: String, model: SRModel): Bitmap {
        if (input.width < MIN_INPUT_WIDTH || input.height < MIN_INPUT_HEIGHT) return input
        synchronized(cacheLock) { srCache[key]?.let { return it } }
        val versionAtStart = modelVersion
        return mutex.withLock {
            try {
                if (versionAtStart != modelVersion) return@withLock input
                if (!isModelReady(model)) {
                    Log.w(TAG, "model not downloaded: ${model.dir}")
                    return@withLock input
                }
                ensureLoaded(model)
                val p = processor
                if (p?.isReady != true) return@withLock input
                SrTrace.mark("INFER realtime START model=${model.key} ${input.width}x${input.height}")
                val t = SrTrace.nowNs()
                withContext(Dispatchers.IO) {
                    withTimeout(120_000L) {
                        p.process(input, model.scale, 0.5f, 0, false)
                    }
                }.also { out ->
                    SrTrace.elapsed(t, "INFER realtime END model=${model.key}")
                    if (out !== input) synchronized(cacheLock) { srCache[key] = out }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "process failed: ${e.message}")
                input
            }
        }
    }

    /**
     * 预超分后台批量专用入口：与 [process] 共用同一把 [mutex]（全局串行，绝不并发喂 Vulkan），
     * 但结果【不写内存 LRU srCache】——磁盘层 SrDiskCache 才是预超分的持久化目的地。
     * 这样后台跑几百页也不会把内存缓存撑爆，翻页实时回退路径仍走原 [process]。
     */
    suspend fun processForPreload(input: Bitmap, model: SRModel): Bitmap {
        if (input.width < MIN_INPUT_WIDTH || input.height < MIN_INPUT_HEIGHT) return input
        val versionAtStart = modelVersion
        return mutex.withLock {
            try {
                if (versionAtStart != modelVersion) return@withLock input
                if (!isModelReady(model)) return@withLock input
                ensureLoaded(model)
                val p = processor
                if (p?.isReady != true) return@withLock input
                SrTrace.mark("INFER preload START model=${model.key} ${input.width}x${input.height}")
                val t = SrTrace.nowNs()
                withContext(Dispatchers.IO) {
                    withTimeout(120_000L) { p.process(input, model.scale, 0.5f, 0, false) }
                }.also { SrTrace.elapsed(t, "INFER preload END model=${model.key}") }
            } catch (e: Throwable) {
                SrTrace.mark("INFER preload ERROR ${e.message}")
                Log.w(TAG, "processForPreload failed: ${e.message}")
                input
            }
        }
    }

    fun cached(key: String): Bitmap? = synchronized(cacheLock) { srCache[key] }

    fun cacheSize(): Int = synchronized(cacheLock) { srCache.size }

    /**
     * 只释放 native 推理器显存，不取消 [scope]、不动内存 LRU。
     * 供后台批量跑完后主动归还 GPU 显存；下次 [process]/[processForPreload]
     * 会因 processor==null 走 ensureLoaded 自动重建。与整对象销毁用的 [release] 区分。
     */
    fun releaseNativeOnly() {
        processor?.release(); processor = null
        loadedModel = null
        modelVersion++
        Log.i(TAG, "native released (gpu memory returned)")
    }

    fun release() {
        jobs.forEach { it.cancel() }; jobs.clear()
        processor?.release(); processor = null
        scope.cancel()
    }
}
