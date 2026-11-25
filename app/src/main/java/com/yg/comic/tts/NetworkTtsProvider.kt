package com.yg.comic.tts

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 网络 TTS 通用接口：任何引擎（小米 / 其他云厂商 / 将来的本地服务）实现它即可接入朗读。
 * 朗读代码只依赖这个接口，不认识具体厂商，换引擎不用改 ViewModel。
 */
interface NetworkTtsProvider {
    val id: String
    val displayName: String
    val capabilities: TtsCapabilities

    /**
     * 把一段文本合成为音频。失败直接抛异常，由调用方统一回退系统 TTS。
     * @param voiceId 音色 id，取自 capabilities.voices
     * @param rate 语速，>1 更快、<1 更慢
     * @param styleId 朗读风格/情绪 id，取自 capabilities.styles
     * @param format 音频格式，取自 capabilities.formats
     */
    suspend fun synthesize(
        text: String,
        apiKey: String,
        voiceId: String,
        rate: Float,
        styleId: String = "",
        format: String = "wav",
    ): TtsAudio
}

/**
 * OpenAI /v1/chat/completions 兼容协议的通用实现：负责组请求、带认证头、解析返回的 base64 音频。
 * 小米 MiMo-V2.5-TTS 只是这套协议的一个配置实例；以后换别家或接本地，在
 * [NetworkTtsRegistry] 里加一条配置即可，不用改朗读代码。
 */
class OpenAiCompatTtsProvider(
    override val id: String,
    override val displayName: String,
    private val endpoint: String,
    private val model: String,
    override val capabilities: TtsCapabilities,
    /** 认证头名字。小米是 api-key，标准 OpenAI 兼容服务是 Authorization。 */
    private val authHeader: String = "Authorization",
    /** 认证值前缀。小米直接传 key（空前缀），标准服务是 "Bearer "。 */
    private val authPrefix: String = "Bearer ",
) : NetworkTtsProvider {

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun synthesize(
        text: String,
        apiKey: String,
        voiceId: String,
        rate: Float,
        styleId: String,
        format: String,
    ): TtsAudio = withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "未配置网络 TTS 的 API Key" }
            // 风格指令（情绪 + 语速近似）合并放 user role，不朗读；正文放 assistant role 才会被合成。
            val styleParts = buildList {
                styleLabel(styleId)?.let { add(it) }
                if (!capabilities.supportsNativeRate) rateToStyle(rate).takeIf { it.isNotBlank() }?.let { add(it) }
            }
            val style = styleParts.joinToString(" ")
            val payload = buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    if (style.isNotBlank()) {
                        addJsonObject {
                            put("role", "user")
                            put("content", style)
                        }
                    }
                    addJsonObject {
                        put("role", "assistant")
                        put("content", text)
                    }
                }
                putJsonObject("audio") {
                    put("format", if (format in capabilities.formats) format else capabilities.defaultFormat)
                    put("voice", voiceId)
                }
            }
            val request = Request.Builder()
                .url(endpoint)
                .header(authHeader, authPrefix + apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val bodyText = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("网络 TTS 请求失败 HTTP ${resp.code}")
                resp.body?.string() ?: throw IllegalStateException("网络 TTS 返回为空")
            }
            val root = json.parseToJsonElement(bodyText).jsonObject
            // 兼容两种返回结构：小米 {completion:{choices:[...]}} 与标准 {choices:[...]}
            val choices = (root["completion"]?.jsonObject ?: root)["choices"]?.jsonArray
                ?: throw IllegalStateException("网络 TTS 响应缺少 choices")
            val audioData = choices.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("audio")?.jsonObject
                ?.get("data")?.jsonPrimitive?.content
                ?: throw IllegalStateException("网络 TTS 响应缺少音频数据")
            val bytes = Base64.decode(audioData, Base64.NO_WRAP)
            if (bytes.isEmpty()) throw IllegalStateException("网络 TTS 音频为空")
            TtsAudio(bytes)
        }

    /** 原生不支持语速的引擎：用风格提示词近似，界面需注明是近似值。 */
    private fun rateToStyle(rate: Float): String = when {
        rate < 0.8f -> "请用稍慢、清楚一点的语速朗读。"
        rate < 0.95f -> "请用比平常略慢的语速朗读。"
        rate > 1.6f -> "请用明显较快的语速朗读。"
        rate > 1.15f -> "请用稍快一点的语速朗读。"
        else -> ""
    }

    /** 把风格 id 转成中文风格指令；未选/未知则不加。 */
    private fun styleLabel(styleId: String): String? {
        if (styleId.isBlank()) return null
        return when (styleId) {
            "calm" -> "用平静、沉稳的语气朗读。"
            "excited" -> "用激昂、有感染力的语气朗读。"
            "gentle" -> "用温柔、舒缓的语气朗读。"
            "sad" -> "用低沉、略带悲伤的语气朗读。"
            "happy" -> "用轻快、愉悦的语气朗读。"
            else -> capabilities.styles.firstOrNull { it.id == styleId }?.label
        }
    }
}

/** 网络 TTS 引擎注册表。接新引擎 = 在这里加一条配置，朗读逻辑不用改。 */
object NetworkTtsRegistry {
    val providers: List<NetworkTtsProvider> = listOf(
        OpenAiCompatTtsProvider(
            id = "mimo",
            displayName = "小米 MiMo-V2.5-TTS",
            endpoint = "https://api.xiaomimimo.com/v1/chat/completions",
            model = "mimo-v2.5-tts",
            authHeader = "api-key",
            authPrefix = "",
            capabilities = TtsCapabilities(
                voices = listOf(
                    TtsVoiceOption("mimo_default", "默认"),
                    TtsVoiceOption("冰糖", "冰糖·中文"),
                    TtsVoiceOption("茉莉", "茉莉·中文"),
                    TtsVoiceOption("苏打", "苏打·中文"),
                    TtsVoiceOption("白桦", "白桦·中文"),
                    TtsVoiceOption("Mia", "Mia·英文"),
                    TtsVoiceOption("Chloe", "Chloe·英文"),
                    TtsVoiceOption("Milo", "Milo·英文"),
                    TtsVoiceOption("Dean", "Dean·英文"),
                ),
                defaultVoiceId = "Chloe",
                supportsNativeRate = false,
                supportsWordHighlight = false,
                requiresApiKey = true,
                styles = listOf(
                    TtsVoiceOption("", "默认（不指定）"),
                    TtsVoiceOption("calm", "平静沉稳"),
                    TtsVoiceOption("excited", "激昂有力"),
                    TtsVoiceOption("gentle", "温柔舒缓"),
                    TtsVoiceOption("sad", "低沉悲伤"),
                    TtsVoiceOption("happy", "轻快愉悦"),
                ),
                defaultStyleId = "",
                formats = listOf("wav", "mp3"),
                defaultFormat = "wav",
                supportsVolume = true,
            )
        )
    )

    fun get(id: String): NetworkTtsProvider? = providers.firstOrNull { it.id == id }
    val defaultId: String get() = providers.firstOrNull()?.id.orEmpty()
}
