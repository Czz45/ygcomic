package com.yg.comic.sr

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * 漫画自动裁剪白边：从四边向内扫描，找到正文内容的边界并裁掉纯色留白。
 * 近白（各通道 >= 245）与近黑（各通道 <= 12）都视为空白，兼顾黑白漫画的黑色页边。
 * 只做整行/整列的判定，不做逐像素内容识别，避免误裁分镜内的留白。
 */
object BitmapCropper {

    private const val WHITE = 245
    private const val BLACK = 12
    // 判定一行/列是否为空白时，采样的最大像素数；大图按比例跳采样，避免逐像素扫描卡顿。
    private const val MAX_SAMPLES = 96
    // 每边保留的边距像素，避免贴边裁切。
    private const val MARGIN = 4

    /** 裁剪白/黑边；无需裁剪或结果过小时返回原图。 */
    fun crop(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 64 || h < 64) return src

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val blankRow = BooleanArray(h) { rowBlank(pixels, w, h, row = it) }
        val blankCol = BooleanArray(w) { colBlank(pixels, w, h, col = it) }

        var top = 0
        while (top < h && blankRow[top]) top++
        var bottom = h - 1
        while (bottom > top && blankRow[bottom]) bottom--
        var left = 0
        while (left < w && blankCol[left]) left++
        var right = w - 1
        while (right > left && blankCol[right]) right--

        top = max(0, top - MARGIN)
        bottom = min(h - 1, bottom + MARGIN)
        left = max(0, left - MARGIN)
        right = min(w - 1, right + MARGIN)

        val cw = right - left + 1
        val ch = bottom - top + 1
        // 裁剪掉不足 40% 说明几乎整页空白或判定异常，保持原图避免误裁。
        if (cw >= w * 0.4f && ch >= h * 0.4f && (cw < w || ch < h)) {
            return Bitmap.createBitmap(src, left, top, cw, ch)
        }
        return src
    }

    private fun rowBlank(px: IntArray, w: Int, h: Int, row: Int): Boolean {
        val base = row * w
        val step = max(1, w / MAX_SAMPLES)
        var x = 0
        while (x < w) {
            val c = px[base + x]
            if (!isBlank(c)) return false
            x += step
        }
        return true
    }

    private fun colBlank(px: IntArray, w: Int, h: Int, col: Int): Boolean {
        val step = max(1, h / MAX_SAMPLES)
        var y = 0
        while (y < h) {
            val c = px[y * w + col]
            if (!isBlank(c)) return false
            y += step
        }
        return true
    }

    private fun isBlank(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val nearWhite = r >= WHITE && g >= WHITE && b >= WHITE
        val nearBlack = r <= BLACK && g <= BLACK && b <= BLACK
        return nearWhite || nearBlack
    }
}
