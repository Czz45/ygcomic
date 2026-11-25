package com.yg.comic.tts

import kotlinx.serialization.Serializable

/**
 * 一套可保存的朗读配置。多套配置存在 DataStore 里，用户可为不同书/不同场景各存一套
 * （各自 API Key、音色、风格、语速等），用的时候在朗读设置页选一套作为当前生效配置。
 *
 * 语速在早期版本只是当场内存值、没落盘；从多配置改造起纳入 profile，随配置一起保存。
 */
@Serializable
data class TtsProfile(
    val id: String = "",
    val name: String = "默认配置",
    /** system=系统TTS / network=网络TTS / local=本地大模型(预留占位) */
    val engine: String = "system",
    /** 网络 TTS 服务商 id，对应 NetworkTtsRegistry */
    val providerId: String = "mimo",
    /** 用户自行填入，存 DataStore，代码里绝不硬编码 */
    val apiKey: String = "",
    val voice: String = "Chloe",
    val style: String = "",
    val format: String = "wav",
    val volume: Float = 1f,
    /** -1=不缓存 / 0=全缓存 / >0=缓存前 N 段 */
    val cacheSegments: Int = -1,
    /** 朗读语速，系统走 setSpeechRate，网络走风格指令近似 */
    val rate: Float = 1.0f,
)
