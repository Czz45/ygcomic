package com.yg.comic.shared

import kotlin.math.exp
import kotlin.math.max

/**
 * 纯 Kotlin 书源健康度模型与评分器，零 Android 依赖。
 * 从 data.SourceHealthStore 抽出，供 Android/iOS/Windows 多端复用。
 * 存储外壳（SharedPreferences 等）仍留在各端，只复用这里的评分逻辑。
 */

enum class FailKind { SOURCE, RATE, NETWORK }

data class Health(
    var attempts: Int = 0,
    var hits: Int = 0,
    var empties: Int = 0,
    var fails: Int = 0,
    var failStreak: Int = 0,
    var lastSuccessMs: Long = 0L,
    var lastFailMs: Long = 0L,
    var avgLatencyMs: Long = 0L,
    var qualitySum: Double = 0.0,
    var netFails: Int = 0,
    var rateLimits: Int = 0,
    var lastNetFailMs: Long = 0L,
    var lastRateMs: Long = 0L,
    // 排序漏斗的用户偏好信号：点击（打开详情）/采纳（加入书架）。
    // 追加在末尾，保证旧存档 (13 段) 解码兼容。
    var clicks: Int = 0,
    var adopts: Int = 0,
)

object SourceHealthScorer {
    const val PRIOR_STRENGTH = 5.0
    const val PRIOR_RATE = 0.3
    const val HALF_LIFE_MS = 7L * 24 * 3600 * 1000
    const val CIRCUIT_MS = 20L * 60 * 1000
    const val RATE_DECAY_MS = 60L * 60 * 1000
    const val NET_DECAY_MS = 30L * 60 * 1000
    const val GROUP_SPREAD = 0.3
    const val NEW_SOURCE_SCORE = 220.0

    /**
     * 贝叶斯平滑 + 时间衰减 + 命中质量 + 速度 + 分类专精 + 三类失败惩罚。
     * @param now 当前时间戳，由调用方注入（commonMain 无 System.currentTimeMillis）。
     * @param networkDown 全局网络故障开关，开启时网络失败惩罚大幅降低（VPN/断网不误罚书源）。
     * @param groupAvg 该源所属分类的平均分，非 null 时向分组均值收敛。
     */
    fun score(h: Health?, now: Long, networkDown: Boolean, groupAvg: Double? = null): Double {
        if (h == null || h.attempts == 0) return NEW_SOURCE_SCORE
        val p = (h.hits + PRIOR_STRENGTH * PRIOR_RATE) / (h.attempts + PRIOR_STRENGTH)
        val decay = if (h.lastSuccessMs > 0L) {
            exp(-(now - h.lastSuccessMs).coerceAtLeast(0L).toDouble() / HALF_LIFE_MS)
        } else {
            0.15
        }
        val avgQ = if (h.hits > 0) h.qualitySum / h.hits else 0.0
        val qualityFactor = 0.6 + 0.4 * avgQ.coerceIn(0.0, 1.0)
        val speed = 200.0 / (1.0 + h.avgLatencyMs / 1000.0)
        val sourcePenalty = h.fails * 20.0 + h.failStreak * 50.0
        val ratePenalty = h.rateLimits * 8.0 * exp(-(now - h.lastRateMs).coerceAtLeast(0L).toDouble() / RATE_DECAY_MS)
        val netFactor = if (networkDown) 0.1 else 1.0
        val netPenalty = h.netFails * 3.0 * netFactor * exp(-(now - h.lastNetFailMs).coerceAtLeast(0L).toDouble() / NET_DECAY_MS)
        val penalty = sourcePenalty + ratePenalty + netPenalty + h.empties * 5.0
        var s = 1000.0 * p * max(decay, 0.05) * qualityFactor + speed - penalty
        // 排序漏斗：用户点击/采纳是比"搜到"更强的真实偏好信号，显著提权。
        // 用相对曝光的比率并带上限，避免新源偶发一次点击就霸榜；采纳权重高于点击。
        if (h.attempts > 0) {
            val clickRate = h.clicks.toDouble() / h.attempts
            val adoptRate = h.adopts.toDouble() / h.attempts
            val funnelBonus = (clickRate.coerceIn(0.0, 1.0) * 180.0 + adoptRate.coerceIn(0.0, 1.0) * 320.0)
            s += minOf(funnelBonus, 400.0)
        }
        if (!networkDown && h.failStreak >= 3 && (now - h.lastFailMs) < CIRCUIT_MS) s -= 2000.0
        if (groupAvg != null) s += (s - groupAvg) * GROUP_SPREAD
        return s
    }

    fun ema(old: Long, next: Long): Long =
        if (old == 0L) next.coerceAtLeast(0L) else (old * 3L + next.coerceAtLeast(0L)) / 4L

    fun encode(h: Health): String =
        listOf(
            h.attempts, h.hits, h.empties, h.fails, h.failStreak,
            h.lastSuccessMs, h.lastFailMs, h.avgLatencyMs
        ).joinToString("|") + "|${h.qualitySum}|${h.netFails}|${h.rateLimits}|${h.lastNetFailMs}|${h.lastRateMs}" +
            "|${h.clicks}|${h.adopts}"

    fun decode(raw: String): Health {
        val p = raw.split("|")
        if (p.size < 9) return Health()
        return try {
            Health(
                attempts = p[0].toInt(),
                hits = p[1].toInt(),
                empties = p[2].toInt(),
                fails = p[3].toInt(),
                failStreak = p[4].toInt(),
                lastSuccessMs = p[5].toLong(),
                lastFailMs = p[6].toLong(),
                avgLatencyMs = p[7].toLong(),
                qualitySum = p[8].toDouble(),
                netFails = if (p.size > 9) p[9].toInt() else 0,
                rateLimits = if (p.size > 10) p[10].toInt() else 0,
                lastNetFailMs = if (p.size > 11) p[11].toLong() else 0L,
                lastRateMs = if (p.size > 12) p[12].toLong() else 0L,
                clicks = if (p.size > 13) p[13].toInt() else 0,
                adopts = if (p.size > 14) p[14].toInt() else 0,
            )
        } catch (_: Exception) {
            Health()
        }
    }
}
