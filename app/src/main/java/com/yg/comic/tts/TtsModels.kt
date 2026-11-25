package com.yg.comic.tts

/** 一个可选音色：id 传给接口的 voice 参数，label 给用户看。两者都来自引擎能力描述，UI 不写死。 */
data class TtsVoiceOption(val id: String, val label: String)

/**
 * 引擎能力描述。由每个 NetworkTtsProvider 自己声明，UI 从这里读音色/语速选项，不硬编码。
 */
data class TtsCapabilities(
    val voices: List<TtsVoiceOption>,
    val defaultVoiceId: String,
    val speedMin: Float = 0.7f,
    val speedMax: Float = 1.6f,
    /** 是否原生支持语速参数。网络 TTS 通常 false，改用风格提示词近似。 */
    val supportsNativeRate: Boolean = false,
    /** 是否支持逐字高亮。网络 TTS 整段返回音频，拿不到逐字时间戳 → false，界面退化成整段高亮。 */
    val supportsWordHighlight: Boolean = false,
    /** 是否需要 API Key（本地引擎可 false）。 */
    val requiresApiKey: Boolean = true,
    /** 朗读风格/情绪选项，放 user role 的风格指令里。小米这类模型主打这个。 */
    val styles: List<TtsVoiceOption> = emptyList(),
    val defaultStyleId: String = "",
    /** 支持的音频格式，界面给手动选。 */
    val formats: List<String> = listOf("wav"),
    val defaultFormat: String = "wav",
    /** 是否支持播放侧单独调音量（能播音频就都能调，本地引擎可能自己管）。 */
    val supportsVolume: Boolean = true,
)

/** 合成出来的一段音频。 */
data class TtsAudio(val bytes: ByteArray, val format: String = "wav")
