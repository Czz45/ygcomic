package com.yg.comic.sr.preload

import android.util.Log

/**
 * 超分性能埋点：统一 tag = SRTrace，抓日志用 adb logcat -s SRTrace:D -v threadtime。
 * 只打时间戳和耗时，不做重活；发布前把 ENABLED 改 false 即整关（空函数调用，零开销）。
 */
object SrTrace {
    const val TAG = "SRTrace"
    const val ENABLED = true

    inline fun mark(msg: String) {
        if (ENABLED) Log.d(TAG, msg)
    }

    fun nowNs(): Long = System.nanoTime()

    inline fun elapsed(startNs: Long, msg: String) {
        if (ENABLED) Log.d(TAG, "$msg ${(System.nanoTime() - startNs) / 1_000_000}ms")
    }
}
