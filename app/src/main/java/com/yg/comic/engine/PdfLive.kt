package com.yg.comic.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * PDF 实时读取：不复制、不逐页预渲染，打开原始文件（content:// URI 或绝对路径），
 * 只渲染当前需要的一页。导入时只登记引用，阅读时按需渲染，内存只留当前页。
 */
object PdfLive {
    private fun openPdf(context: Context, ref: String): PdfRenderer? {
        val pfd: ParcelFileDescriptor? = try {
            if (ref.startsWith("content://")) {
                context.contentResolver.openFileDescriptor(Uri.parse(ref), "r")
            } else {
                ParcelFileDescriptor.open(File(ref), ParcelFileDescriptor.MODE_READ_ONLY)
            }
        } catch (e: Exception) { null }
        return try {
            pfd?.let { PdfRenderer(it) }
        } catch (e: Exception) {
            try { pfd?.close() } catch (_: Exception) {}
            null
        }
    }

    /** 读取真实页数；打不开返回 0（由调用方兜底为 1，绝不让书显示 0 页） */
    fun pageCount(context: Context, ref: String): Int {
        if (ref.isBlank()) return 0
        val renderer = openPdf(context, ref) ?: return 0
        return try { renderer.pageCount } catch (e: Exception) { 0 } finally {
            try { renderer.close() } catch (_: Exception) {}
        }
    }

    /** 渲染单页为 Bitmap（调用方需在 IO 线程）；ARGB_8888 是 PdfRenderer 原生层唯一支持的格式 */
    fun render(context: Context, ref: String, pageIndex: Int, maxW: Int, maxH: Int): Bitmap? {
        if (ref.isBlank()) return null
        val renderer = openPdf(context, ref) ?: return null
        return try {
            if (pageIndex !in 0 until renderer.pageCount) return null
            renderer.openPage(pageIndex).use { page ->
                val w0 = page.width.coerceAtLeast(1)
                val h0 = page.height.coerceAtLeast(1)
                val ratio = h0.toFloat() / w0
                var w = w0.coerceAtMost(maxW)
                var h = (w * ratio).toInt().coerceAtLeast(1)
                if (h > maxH) { h = maxH; w = (h / ratio).toInt().coerceAtLeast(1) }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        } catch (e: Exception) { null } finally {
            try { renderer.close() } catch (_: Exception) {}
        }
    }
}
