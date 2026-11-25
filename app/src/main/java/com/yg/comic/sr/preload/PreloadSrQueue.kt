package com.yg.comic.sr.preload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import coil3.BitmapImage
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.yg.comic.engine.PdfLive
import com.yg.comic.sr.BitmapCropper
import com.yg.comic.sr.SRModel
import com.yg.comic.sr.SuperResolutionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 后台预超分队列。
 *
 * 硬约束全部在这里落地：
 *  - 全局串行：[semaphore] = Semaphore(1)，任何时刻只有一页在推理，杜绝 Vulkan 并发崩（保留：防崩串行锁）；
 *  - 限线程：native 层 OMP_NUM_THREADS=1（见 RealESRGANProcessor），配合此处串行 = 单核算力；
 *  - 全尺寸：按原尺寸超分，不再缩小图片、不再页间节流、不再磁盘配额（用户 11-24 拍板取消占用上限）；
 *  - 跑完释放：一批结束调 [SuperResolutionManager.releaseNativeOnly] 归还 GPU 显存；
 *  - 可取消：[cancel] / [cancelBook] / [cancelAll]，供总开关关闭或用户主动停止。
 */
object PreloadSrQueue {
    private const val TAG = "PreloadSrQueue"
    private lateinit var appContext: Context
    private lateinit var srManager: SuperResolutionManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(1)
    private val runningJobs = mutableMapOf<String, Job>()
    // 暂停中的任务键：暂停只挂起页循环、不取消任务，恢复后接着跑；停止才真正 cancel。
    private val pausedKeys = mutableSetOf<String>()

    fun init(context: Context, manager: SuperResolutionManager) {
        appContext = context.applicationContext
        srManager = manager
        SrDiskCache.init(appContext)
        SrProgressStore.init(appContext)
    }

    private fun jobKey(bookId: Long, chapterIndex: Int) = "$bookId|$chapterIndex"

    fun isRunning(bookId: Long, chapterIndex: Int): Boolean =
        synchronized(runningJobs) { runningJobs.containsKey(jobKey(bookId, chapterIndex)) }

    fun cancel(bookId: Long, chapterIndex: Int) {
        val key = jobKey(bookId, chapterIndex)
        synchronized(runningJobs) { runningJobs.remove(key)?.cancel() }
        SrProgressStore.update(bookId, chapterIndex, 0, 0, SrState.CANCELLED)
    }

    fun cancelBook(bookId: Long) {
        val prefix = "$bookId|"
        val keys = synchronized(runningJobs) {
            val matched = runningJobs.keys.filter { it.startsWith(prefix) }
            matched.forEach { runningJobs.remove(it)?.cancel() }
            matched
        }
        keys.forEach { key ->
            val ci = key.substringAfter('|').toIntOrNull() ?: 0
            SrProgressStore.update(bookId, ci, 0, 0, SrState.CANCELLED)
        }
        synchronized(pausedKeys) { pausedKeys.removeAll { it.startsWith(prefix) } }
    }

    /** 取消所有在跑的任务（配合 SrDiskCache.clearAll：删全部缓存前先停，防止边删边写复活）。 */
    fun cancelAll() {
        val keys = synchronized(runningJobs) {
            val all = runningJobs.keys.toList()
            all.forEach { runningJobs.remove(it)?.cancel() }
            all
        }
        keys.forEach { key ->
            val parts = key.split('|')
            val bookId = parts.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val ci = parts.getOrNull(1)?.toIntOrNull() ?: 0
            SrProgressStore.update(bookId, ci, 0, 0, SrState.CANCELLED)
        }
        synchronized(pausedKeys) { pausedKeys.clear() }
    }

    /** 暂停整本书：只给任务键打暂停标记，页循环会在下一页前挂起，不取消任务。 */
    fun pauseBook(bookId: Long) {
        val keys = synchronized(runningJobs) {
            runningJobs.keys.filter { it.startsWith("$bookId|") }.toList()
        }
        keys.forEach { key ->
            synchronized(pausedKeys) { pausedKeys.add(key) }
            val ci = key.substringAfter('|').toIntOrNull() ?: 0
            SrProgressStore.markPaused(bookId, ci)
        }
    }

    /** 继续整本书：移除暂停标记，页循环从当前页接着跑。 */
    fun resumeBook(bookId: Long) {
        val keys = synchronized(runningJobs) {
            runningJobs.keys.filter { it.startsWith("$bookId|") }.toList()
        }
        keys.forEach { key ->
            synchronized(pausedKeys) { pausedKeys.remove(key) }
            val ci = key.substringAfter('|').toIntOrNull() ?: 0
            SrProgressStore.markRunning(bookId, ci)
        }
    }

    /** 停止整本书：真正取消任务并落 CANCELLED 状态。 */
    fun stopBook(bookId: Long) = cancelBook(bookId)

    /** 这本书是否处于暂停态（供菜单在暂停/继续间切换）。 */
    fun isPausedBook(bookId: Long): Boolean =
        synchronized(pausedKeys) { pausedKeys.any { it.startsWith("$bookId|") } }

    /**
     * 把一章（或任意一批同 bookId/chapterIndex 的页）投递到后台队列。
     * 已在跑的同键任务会被忽略，避免重复入队。
     */
    fun enqueue(refs: List<SrPageRef>, model: SRModel, autoCrop: Boolean) {
        if (refs.isEmpty()) return
        val bookId = refs.first().bookId
        val chapterIndex = refs.first().chapterIndex
        val key = jobKey(bookId, chapterIndex)
        SrTrace.mark("BATCH enqueue book=$bookId ch=$chapterIndex pages=${refs.size} key=$key")
        synchronized(runningJobs) {
            if (runningJobs.containsKey(key)) {
                SrTrace.mark("BATCH skip(dup) $key")
                return
            }
        }
        // 点击后立即写 RUNNING：封面进度块/菜单马上出现反馈，不必等 launch 拿到锁。
        SrProgressStore.update(bookId, chapterIndex, refs.size, 0, SrState.RUNNING)
        // 起前台服务保活：切后台后系统会把普通进程冻结，单页 3 秒能变 7 分钟。
        // 队列里还有任务时持续占用通知栏，最后一页结束后由 finally 撤掉。
        SrForegroundService.start(appContext)
        val job = scope.launch {
            semaphore.withPermit {
                val total = refs.size
                var done = 0
                SrProgressStore.update(bookId, chapterIndex, total, 0, SrState.RUNNING)
                val batchStart = SrTrace.nowNs()
                SrTrace.mark("BATCH START $key pages=$total model=${model.key} autoCrop=$autoCrop")
                try {
                    for (ref in refs) {
                        if (!isActive) break
                        // 暂停：挂起页循环但不取消任务，恢复后从当前页接着跑；停止会 cancel 使 isActive=false。
                        while (synchronized(pausedKeys) { key in pausedKeys } && isActive) {
                            delay(200)
                        }
                        if (!isActive) break
                        val logical = ref.logicalKey(model, autoCrop)
                        val pageStart = SrTrace.nowNs()
                        // 已落盘跳过（换模型/改 autoCrop 后 key 变化，不会误命中旧图）
                        if (SrDiskCache.exists(model.key, logical)) {
                            done++
                            SrTrace.mark("PAGE $key ${done}/$total p=${ref.pageIndex} SKIP_CACHE")
                            SrProgressStore.update(bookId, chapterIndex, total, done, SrState.RUNNING)
                            continue
                        }
                        val raw = loadSource(ref)
                        if (raw == null) {
                            Log.w(TAG, "load source failed: $logical")
                            SrTrace.mark("PAGE $key ${done + 1}/$total p=${ref.pageIndex} LOAD_FAIL")
                            done++
                            SrProgressStore.update(bookId, chapterIndex, total, done, SrState.RUNNING)
                            continue
                        }
                        val src = if (autoCrop) BitmapCropper.crop(raw) else raw
                        val out = srManager.processForPreload(src, model)
                        if (out !== src) SrDiskCache.put(model.key, logical, out)
                        if (raw !== src) raw.recycle()
                        done++
                        SrTrace.elapsed(pageStart, "PAGE $key ${done}/$total p=${ref.pageIndex} ${ref.javaClass.simpleName} done")
                        SrProgressStore.update(bookId, chapterIndex, total, done, SrState.RUNNING)
                        // 通知栏同步current进度（完成一张刷一张）。
                        SrForegroundService.progress(appContext, done, total)
                    }
                    val finalState = if (isActive) SrState.DONE else SrState.CANCELLED
                    SrTrace.elapsed(batchStart, "BATCH END $key done=$done/$total state=$finalState")
                    SrProgressStore.update(bookId, chapterIndex, total, done, finalState)
                    srManager.releaseNativeOnly()
                } catch (e: Throwable) {
                    // 停止/取消走 CANCELLED，不误标 ERROR。
                    val finalState = if (e is CancellationException) SrState.CANCELLED else SrState.ERROR
                    SrTrace.mark("BATCH ERROR $key done=$done/$total err=${e.message} state=$finalState")
                    Log.w(TAG, "enqueue failed: ${e.message}")
                    SrProgressStore.update(bookId, chapterIndex, total, done, finalState)
                } finally {
                    synchronized(runningJobs) { runningJobs.remove(key) }
                    // 队列空了才撤前台服务；还有其它书在排队就继续保活。
                    if (synchronized(runningJobs) { runningJobs.isEmpty() }) {
                        SrForegroundService.stop(appContext)
                    }
                }
            }
        }
        synchronized(runningJobs) { runningJobs[key] = job }
    }

    /**
     * 按 SrPageRef 类型取原图，一律返回原尺寸，不再为提速缩小（用户 11-24 拍板全尺寸超分）。
     * 网络图走 Coil（复用内存缓存，不重复下载，属 Coil 缓存绝不回收）；
     * 本地图直接解码；PDF 按页面原始 point 尺寸渲染，不再压到 960x1280。
     */
    private suspend fun loadSource(ref: SrPageRef): Bitmap? = withContext(Dispatchers.IO) {
        try {
            when (ref) {
                is SrPageRef.Network -> {
                    val headers = NetworkHeaders.Builder().apply {
                        if (ref.referer.isNotBlank()) set("Referer", ref.referer)
                    }.build()
                    val req = ImageRequest.Builder(appContext).data(ref.url).httpHeaders(headers).build()
                    val result = appContext.imageLoader.execute(req)
                    (result.image as? BitmapImage)?.bitmap
                }
                is SrPageRef.Pdf -> {
                    val t = SrTrace.nowNs()
                    SrTrace.mark("PDF_RENDER start p=${ref.pageIndex}")
                    // maxW/maxH 给足够大上限，让 PdfLive 按页面自身尺寸渲染（不再人为压到 960x1280）
                    val bmp = PdfLive.render(appContext, ref.pdfPath, ref.pageIndex, Int.MAX_VALUE, Int.MAX_VALUE)
                        ?: return@withContext null
                    SrTrace.elapsed(t, "PDF_RENDER end p=${ref.pageIndex} size=${bmp.width}x${bmp.height}")
                    bmp
                }
                is SrPageRef.Local -> BitmapFactory.decodeFile(ref.path)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "loadSource failed: ${e.message}")
            null
        }
    }
}
