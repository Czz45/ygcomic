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
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bmp = BitmapFactory.decodeFile(path, options) ?: return@withContext null
        val sampled = if (reqW > 0 && reqH > 0 && (bmp.width > reqW || bmp.height > reqH)) {
            val ratio = maxOf(bmp.width.toFloat() / reqW, bmp.height.toFloat() / reqH)
            val nw = (bmp.width / ratio).toInt().coerceAtLeast(1)
            val nh = (bmp.height / ratio).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            bmp.recycle()
            scaled
        } else bmp
        memory.put(key, sampled)
        sampled
    }

    fun clear() = memory.evictAll()

    companion object {
        /** 只算缓存目录大小，绝不碰用户原始导入文件 */
        fun cacheDirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }
}
