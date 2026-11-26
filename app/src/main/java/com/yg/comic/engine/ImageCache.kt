package com.yg.comic.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 两级缓存：内存 LRU（解码后的采样 Bitmap）+ 磁盘（本地库文件本身即磁盘缓存）。
 * 封面直接取本地库第一张图路径，不额外落库。
 */
class ImageCache(context: Context, maxKb: Int = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
    private val memory = object : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun bitmap(path: String, reqW: Int, reqH: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$path|${reqW}x$reqH"
        memory.get(key)?.let { return@withContext it }
        // 先只读尺寸，按 2 的幂算采样率：大图上"整张解码再缩放"既慢又吃内存，
        // 这是本地漫画首屏慢的主因，改成解码时就直接采到接近目标尺寸。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            var sample = 1
            if (reqW > 0 && reqH > 0 && bounds.outWidth > 0 && bounds.outHeight > 0) {
                while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) {
                    sample *= 2
                }
            }
            inSampleSize = sample
        }
        val bmp = BitmapFactory.decodeFile(path, options) ?: return@withContext null
        // 采样后仍比目标尺寸大的才做一次精确缩放；差不多就跳过，省一次全图拷贝。
        val sampled = if (reqW > 0 && reqH > 0 && (bmp.width > reqW || bmp.height > reqH)) {
            val ratio = maxOf(bmp.width.toFloat() / reqW, bmp.height.toFloat() / reqH)
            if (ratio <= 1.15f) {
                bmp
            } else {
                val nw = (bmp.width / ratio).toInt().coerceAtLeast(1)
                val nh = (bmp.height / ratio).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
                bmp.recycle()
                scaled
            }
        } else bmp
        memory.put(key, sampled)
        sampled
    }

    fun clear() = memory.evictAll()

    companion object {
        /**
         * 进程级单例：每次进阅读器 Composable 重建，若每次 new 一个 ImageCache，内存 LRU 随退出被丢弃，
         * 表现的"每次进去都重新加载"。改成进程级复用，命中常驻内存。
         * 用 applicationContext，避免单例持有 Activity。
         */
        @Volatile
        private var instance: ImageCache? = null
        fun get(context: Context): ImageCache = instance ?: synchronized(this) {
            instance ?: ImageCache(context.applicationContext).also { instance = it }
        }

        /** 只算缓存目录大小，绝不碰用户原始导入文件 */
        fun cacheDirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }
}
