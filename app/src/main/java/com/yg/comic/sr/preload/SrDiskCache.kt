package com.yg.comic.sr.preload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 预超分结果的磁盘缓存层。
 *
 * 布局：filesDir/sr_cache/<modelKey>/<bookId>/<sha256(logicalKey)>.webp
 * - logicalKey 由 [SrPageRef.logicalKey] 生成，含 bookId/章序/页序/model.key/autoCrop。
 * - modelKey 单独建目录：换模型后旧结果自然隔离，不需要清缓存。
 * - bookId 单独建目录：支持"单独删某一本书的超分缓存"，删时只清这一层。
 * - 写 temp 再 rename，避免读到半截文件。
 * - 不设容量上限、不自动淘汰：占用上限已按用户要求取消，何时删由用户决定（[clearBook]/[clearAll]）。
 *
 * 这里不持有任何 Bitmap 常驻内存；进程重启后缓存仍在。
 */
object SrDiskCache {
    private const val TAG = "SrDiskCache"
    private const val DIR = "sr_cache"

    @Volatile
    private var rootDir: File? = null

    fun init(context: Context) {
        rootDir = File(context.filesDir, DIR).apply {
            if (!exists()) mkdirs()
        }
        // purgeLegacy 内部走 root() 读 rootDir，必须等上面的赋值完成后再调用；
        // 放在 apply 块里时 rootDir 仍为 null → root() 抛 IllegalStateException → 启动闪退。
        purgeLegacy()
    }

    private fun root(): File = rootDir ?: error("SrDiskCache.init(context) must be called first")

    /**
     * 开发阶段旧格式（sr_cache/<model>/<sha256>.webp，无 bookId 层）直接删除，不做迁移兼容。
     * 只在 init 时扫一次，清掉 model 目录里散落的 .webp/.tmp。
     */
    private fun purgeLegacy() {
        (root().listFiles() ?: return).forEach { modelDir ->
            if (!modelDir.isDirectory) return@forEach
            modelDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".webp") || f.name.endsWith(".tmp"))) f.delete()
            }
        }
    }

    fun sha256(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xff
                append(HEX[v ushr 4])
                append(HEX[v and 0x0f])
            }
        }
    }

    private const val HEX = "0123456789abcdef"

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    /** logicalKey 第一字段就是 bookId，读/写两侧都从它解析，调用方无需改签名。 */
    private fun bookIdOf(logicalKey: String): String = logicalKey.substringBefore('|')

    private fun fileOf(modelKey: String, logicalKey: String): File =
        File(File(File(root(), sanitize(modelKey)), sanitize(bookIdOf(logicalKey))), "${sha256(logicalKey)}.webp")

    /** 是否已落盘。队列据此跳过已完成的页。 */
    fun exists(modelKey: String, logicalKey: String): Boolean {
        val f = try { fileOf(modelKey, logicalKey) } catch (e: Throwable) { return false }
        return f.exists() && f.length() > 0
    }

    /** 命中返回 Bitmap 并刷新 lastModified；失败返回 null，调用方应回退原图。 */
    fun get(modelKey: String, logicalKey: String): Bitmap? {
        val f = try { fileOf(modelKey, logicalKey) } catch (e: Throwable) { return null }
        if (!f.exists() || f.length() == 0L) return null
        return try {
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            if (bmp != null) f.setLastModified(System.currentTimeMillis())
            bmp
        } catch (e: Throwable) {
            Log.w(TAG, "decode failed: ${e.message}")
            null
        }
    }

    /** 原子写入。漫画线稿对压缩敏感，SDK30+ 用无损 WebP，否则 WebP quality=100。 */
    fun put(modelKey: String, logicalKey: String, bmp: Bitmap) {
        val dir = try {
            File(File(root(), sanitize(modelKey)), sanitize(bookIdOf(logicalKey)))
                .apply { if (!exists()) mkdirs() }
        } catch (e: Throwable) { return }
        val final = File(dir, "${sha256(logicalKey)}.webp")
        val tmp = File(dir, "${sha256(logicalKey)}.${System.currentTimeMillis()}.tmp")
        try {
            val format: Bitmap.CompressFormat
            val quality: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format = Bitmap.CompressFormat.WEBP_LOSSLESS
                quality = 100
            } else {
                @Suppress("DEPRECATION")
                format = Bitmap.CompressFormat.WEBP
                quality = 100
            }
            tmp.outputStream().use { out -> bmp.compress(format, quality, out) }
            if (final.exists()) final.delete()
            if (!tmp.renameTo(final)) {
                Log.w(TAG, "rename failed: ${tmp.absolutePath}")
                tmp.delete()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "put failed: ${e.message}")
            tmp.delete()
        }
    }

    /** 已占用的磁盘字节数（设置页显示用）。 */
    fun size(): Long = root().walkTopDown().filter { it.isFile }.map { it.length() }.sum()

    /**
     * 单本删：停任务 → 清进度 → 删磁盘，一步到位。
     * 进度清掉后封面"已超分"三角即时消失，不会出现"缓存删了还显示已超分"。
     */
    fun clearBook(bookId: Long) {
        try { PreloadSrQueue.cancelBook(bookId) } catch (e: Throwable) { Log.w(TAG, "cancelBook: ${e.message}") }
        SrProgressStore.clearBook(bookId)
        val name = sanitize(bookId.toString())
        (root().listFiles() ?: return).forEach { modelDir ->
            if (modelDir.isDirectory) File(modelDir, name).deleteRecursively()
        }
        Log.i(TAG, "cleared sr cache for book=$bookId")
    }

    /** 全删：停所有任务 → 清所有进度 → 删整个缓存内容（保留 root 目录）。 */
    fun clearAll() {
        try { PreloadSrQueue.cancelAll() } catch (e: Throwable) { Log.w(TAG, "cancelAll: ${e.message}") }
        SrProgressStore.clearAll()
        (root().listFiles() ?: return).forEach { it.deleteRecursively() }
        Log.i(TAG, "cleared all sr cache")
    }
}
