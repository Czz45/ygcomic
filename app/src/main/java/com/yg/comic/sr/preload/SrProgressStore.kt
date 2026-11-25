package com.yg.comic.sr.preload

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** 单章（或整书）超分进度。chapterIndex = -1 表示整书级。 */
data class SrChapterProgress(
    val bookId: Long,
    val chapterIndex: Int,
    val total: Int,
    val done: Int,
    val state: String, // 见 SrState
)

object SrState {
    const val IDLE = "idle"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val DONE = "done"
    const val CANCELLED = "cancelled"
    const val ERROR = "error"
}

/** 整书聚合进度，供封面方块显示。 */
data class SrBookProgress(val total: Int, val done: Int, val state: String) {
    val percent: Int get() = if (total <= 0) 0 else (done * 100 / total).coerceIn(0, 100)
}

/**
 * 超分进度持久化 + 可观察状态。
 * 单文件 JSON 存 SharedPreferences（apply 异步原子写），不引入 Room 表迁移。
 * Compose 侧（封面/章节菜单）通过 [progress] 观察。
 */
object SrProgressStore {
    private const val PREF = "sr_progress"
    private const val KEY = "map"
    // 心跳只存内存：进程重启/后台被冻结后自然停止，据此识破"表面 running、实际已停"。
    // 正常一页 3-4 秒 + 间隔 1.2 秒，20 秒足够宽松，不会误判排队等待。
    private const val STALE_MS = 20_000L
    private val lock = Any()
    private lateinit var sp: SharedPreferences

    private val _progress = MutableStateFlow<Map<String, SrChapterProgress>>(emptyMap())
    val progress: StateFlow<Map<String, SrChapterProgress>> = _progress
    private val lastBeat = mutableMapOf<String, Long>()

    fun init(context: Context) {
        sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        synchronized(lock) { loadLocked() }
    }

    private fun keyOf(bookId: Long, chapterIndex: Int) = "$bookId|$chapterIndex"

    private fun loadLocked() {
        val raw = try { sp.getString(KEY, "{}") ?: "{}" } catch (e: Throwable) { "{}" }
        val result = linkedMapOf<String, SrChapterProgress>()
        try {
            val obj = JSONObject(raw)
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val o = obj.getJSONObject(k)
                result[k] = SrChapterProgress(
                    bookId = o.optLong("bookId"),
                    chapterIndex = o.optInt("chapterIndex"),
                    total = o.optInt("total"),
                    done = o.optInt("done"),
                    state = o.optString("state", SrState.IDLE),
                )
            }
        } catch (e: Throwable) {
            // 坏数据直接按空处理，不让它阻塞 UI
        }
        _progress.value = result
    }

    private fun persistLocked() {
        val obj = JSONObject()
        for ((k, p) in _progress.value) {
            val o = JSONObject()
            o.put("bookId", p.bookId)
            o.put("chapterIndex", p.chapterIndex)
            o.put("total", p.total)
            o.put("done", p.done)
            o.put("state", p.state)
            obj.put(k, o)
        }
        sp.edit().putString(KEY, obj.toString()).apply()
    }

    fun update(bookId: Long, chapterIndex: Int, total: Int, done: Int, state: String) {
        synchronized(lock) {
            val k = keyOf(bookId, chapterIndex)
            _progress.value = _progress.value + (k to SrChapterProgress(bookId, chapterIndex, total, done, state))
            if (state == SrState.RUNNING) lastBeat[k] = System.currentTimeMillis()
            persistLocked()
        }
    }

    fun get(bookId: Long, chapterIndex: Int): SrChapterProgress? = _progress.value[keyOf(bookId, chapterIndex)]

    /** 某章是否有进行中的任务（用于入队去重）。 */
    fun isActive(bookId: Long, chapterIndex: Int): Boolean {
        val p = get(bookId, chapterIndex) ?: return false
        return p.state == SrState.RUNNING
    }

    /** 该章 RUNNING 但心跳已超时 = 实际已停（进程重启/后台冻结）。 */
    private fun isStale(p: SrChapterProgress): Boolean {
        if (p.state != SrState.RUNNING) return false
        val t = synchronized(lock) { lastBeat[keyOf(p.bookId, p.chapterIndex)] ?: 0L }
        return System.currentTimeMillis() - t > STALE_MS
    }

    /** 后台真的在跑（RUNNING 且心跳未超时）。阅读页据此让路，冻结后不再白让。 */
    fun isAnyRunning(): Boolean = _progress.value.values.any { it.state == SrState.RUNNING && !isStale(it) }

    /** 暂停/继续：保留 total/done，只切状态。 */
    fun markPaused(bookId: Long, chapterIndex: Int) {
        val p = get(bookId, chapterIndex) ?: return
        update(bookId, chapterIndex, p.total, p.done, SrState.PAUSED)
    }

    fun markRunning(bookId: Long, chapterIndex: Int) {
        val p = get(bookId, chapterIndex) ?: return
        update(bookId, chapterIndex, p.total, p.done, SrState.RUNNING)
    }

    /** 整书聚合：封面方块用。取该书所有 chapterIndex>=0 的章节进度求和。 */
    fun bookProgress(bookId: Long): SrBookProgress {
        val chapters = _progress.value.values.filter { it.bookId == bookId && it.chapterIndex >= 0 }
        if (chapters.isEmpty()) return SrBookProgress(0, 0, SrState.IDLE)
        val total = chapters.sumOf { it.total }
        val done = chapters.sumOf { it.done }
        val state = when {
            chapters.any { it.state == SrState.RUNNING && !isStale(it) } -> SrState.RUNNING
            chapters.any { it.state == SrState.PAUSED || isStale(it) } -> SrState.PAUSED
            chapters.all { it.state == SrState.DONE } -> SrState.DONE
            chapters.any { it.state == SrState.ERROR } -> SrState.ERROR
            chapters.any { it.state == SrState.CANCELLED } -> SrState.CANCELLED
            else -> SrState.IDLE
        }
        return SrBookProgress(total, done, state)
    }

    fun clearBook(bookId: Long) {
        synchronized(lock) {
            val prefix = "$bookId|"
            _progress.value = _progress.value.filterKeys { !it.startsWith(prefix) }
            lastBeat.keys.removeAll { it.startsWith(prefix) }
            persistLocked()
        }
    }

    /** 清空全部超分进度（配合 SrDiskCache.clearAll：删全部缓存时让封面标记一起消失）。 */
    fun clearAll() {
        synchronized(lock) {
            _progress.value = emptyMap()
            lastBeat.clear()
            persistLocked()
        }
    }
}
