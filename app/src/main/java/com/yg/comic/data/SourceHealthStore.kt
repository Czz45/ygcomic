package com.yg.comic.data

import android.content.Context
import android.content.SharedPreferences
import com.yg.comic.shared.FailKind
import com.yg.comic.shared.Health
import com.yg.comic.shared.SourceHealthScorer
import java.util.concurrent.ConcurrentHashMap

/**
 * 书源健康度：持久化每个源的搜索表现，供"智能排序"使用。
 *
 * 不动 SQLite 表结构（旧 hitCount/useCount 保留兼容），单独用 SharedPreferences 存。
 * 评分/编解码等纯逻辑已抽到 [SourceHealthScorer]（com.yg.comic.shared，跨端复用）；
 * 这里只保留 Android 的存储外壳（SharedPreferences + 内存缓存 + 网络故障标志）。
 */
class SourceHealthStore(context: Context) {

    private val sp: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cache = ConcurrentHashMap<Long, Health>()

    /** 全局网络故障标志：没开 VPN / 断网期间为 true，有源成功即清除。仅内存态。 */
    @Volatile
    var networkDown: Boolean = false
        private set

    init {
        val ids = sp.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        for (idStr in ids) {
            val id = idStr.toLongOrNull() ?: continue
            val raw = sp.getString("h_$id", null) ?: continue
            cache[id] = SourceHealthScorer.decode(raw)
        }
    }

    fun health(id: Long): Health = cache.getOrPut(id) { Health() }

    /** 一轮搜索结束时由外部判定：大量源同时网络失败且零命中 → 整体网络故障。 */
    fun setNetworkDown(down: Boolean) {
        networkDown = down
    }

    /** Ok(非空)：命中。quality=本源最高相关度/1_000_000，落在 0~1。 */
    @Synchronized
    fun recordSuccess(id: Long, latencyMs: Long, quality: Double) {
        val h = health(id)
        h.attempts++
        h.hits++
        h.failStreak = 0
        // 网络恢复自愈：成功一次就把网络/限流的"近期失败"减半，多次成功后自然归零
        h.netFails = h.netFails / 2
        h.rateLimits = h.rateLimits / 2
        h.lastSuccessMs = System.currentTimeMillis()
        h.avgLatencyMs = SourceHealthScorer.ema(h.avgLatencyMs, latencyMs)
        h.qualitySum += quality.coerceIn(0.0, 1.0)
        persist(id, h)
    }

    /** Ok(空)：连得上但这个关键词搜不出书，轻惩罚（不算失败）。 */
    @Synchronized
    fun recordEmpty(id: Long, latencyMs: Long) {
        val h = health(id)
        h.attempts++
        h.empties++
        h.failStreak = 0
        h.lastSuccessMs = System.currentTimeMillis()
        h.avgLatencyMs = SourceHealthScorer.ema(h.avgLatencyMs, latencyMs)
        persist(id, h)
    }

    /** 失败：按类型分档记账。网络/限流不累加 failStreak，避免环境或临时问题把源判死刑。 */
    @Synchronized
    fun recordFail(id: Long, kind: FailKind, latencyMs: Long = 0L) {
        val h = health(id)
        h.attempts++
        val now = System.currentTimeMillis()
        when (kind) {
            FailKind.SOURCE -> {
                h.fails++
                h.failStreak++
                h.lastFailMs = now
            }
            FailKind.RATE -> {
                h.rateLimits++
                // 限流说明源是通的，只是被临时挡了：清空连续失败，不熔断
                h.failStreak = 0
                h.lastRateMs = now
                if (latencyMs > 0) h.avgLatencyMs = SourceHealthScorer.ema(h.avgLatencyMs, latencyMs)
            }
            FailKind.NETWORK -> {
                h.netFails++
                // 网络类连不通是本机环境问题（如没开 VPN），绝不清零/累加源站熔断计数
                h.lastNetFailMs = now
            }
        }
        persist(id, h)
    }

    /** 旧调用兼容：默认按源站失败处理。 */
    @Synchronized
    fun recordFail(id: Long) = recordFail(id, FailKind.SOURCE)

    /** 排序漏斗：用户点开某源的结果进详情——比"搜到"更强的偏好信号。 */
    @Synchronized
    fun recordClick(id: Long) {
        health(id).clicks++
        persist(id, health(id))
    }

    /** 排序漏斗：用户把某源的结果加入书架——最强的采纳信号。 */
    @Synchronized
    fun recordAdopt(id: Long) {
        health(id).adopts++
        persist(id, health(id))
    }

    /**
     * 排序分数，越大越靠前。[groupAvg] 为该源所属分组的平均分，用于在分组内做相对归一化。
     * 纯评分逻辑在 [SourceHealthScorer]，这里注入当前时间与全局网络故障标志。
     */
    fun score(id: Long, groupAvg: Double? = null): Double {
        val h = cache[id] ?: return NEW_SOURCE_SCORE
        if (h.attempts == 0) return NEW_SOURCE_SCORE
        return SourceHealthScorer.score(h, System.currentTimeMillis(), networkDown, groupAvg)
    }

    private fun persist(id: Long, h: Health) {
        val ids = (sp.getStringSet(KEY_IDS, emptySet()) ?: emptySet()).toMutableSet()
        ids.add(id.toString())
        sp.edit()
            .putStringSet(KEY_IDS, ids)
            .putString("h_$id", SourceHealthScorer.encode(h))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "source_health"
        private const val KEY_IDS = "ids"
        /** 新源/没数据的源给中性分：保留探索机会，但不霸榜。 */
        const val NEW_SOURCE_SCORE = 220.0
    }
}
