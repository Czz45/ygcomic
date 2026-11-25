package com.yg.comic.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 朗读设置页的"试听"播放器：用当前这套配置朗读一段固定示例文本。
 *
 * 复用与小说阅读器完全相同的朗读链路——网络走 [NetworkTtsRegistry] 里的 provider.synthesize，
 * 系统走 [TextToSpeech]，网络失败/没 key/缺引擎都自动回退系统；只是不跟任何书或章节绑定，
 * 播完即停，不涉及进度高亮、翻章、缓存段落等阅读器专属逻辑。
 */
class TtsPreviewPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onState: (state: Int, message: String?) -> Unit,
) {
    companion object {
        const val STATE_IDLE = 0
        const val STATE_PLAYING = 1
        /** 试听文本：带逗号停顿的中文长句，能听出音色、语速、情绪风格的差异。 */
        const val PREVIEW_TEXT = "春日的清晨，阳光透过窗棂，洒在书桌前。他抬起头，深深吸了一口气，微笑着说：今天，真是个适合读书的好天气。"
    }

    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var synthJob: Job? = null
    private var useFallback = false
    /** 本次试听要朗读的文本（用户输入或内置示例），网络与系统两条链路共用。 */
    private var currentText = PREVIEW_TEXT

    /**
     * 开始试听。网络引擎失败会自动回退系统朗读；本地引擎给出与阅读器一致的提示。
     * @param text 要朗读的文本，留空自动用 [PREVIEW_TEXT] 示例兜底。
     */
    fun preview(profile: TtsProfile, text: String = PREVIEW_TEXT) {
        stop()
        useFallback = false
        currentText = text.trim().ifBlank { PREVIEW_TEXT }
        onState(STATE_PLAYING, null)
        when (profile.engine) {
            "network" -> startNetwork(profile)
            "local" -> onState(STATE_IDLE, "本地大模型朗读尚未开放，请改用系统或网络朗读")
            else -> startSystem(profile)
        }
    }

    // ---------- 网络 TTS（通用接口，与阅读器同一套 provider）----------
    private fun startNetwork(profile: TtsProfile) {
        val provider = NetworkTtsRegistry.get(profile.providerId)
        if (provider == null) {
            fallback(profile, "网络朗读引擎未找到，已切回系统朗读")
            return
        }
        if (provider.capabilities.requiresApiKey && profile.apiKey.isBlank()) {
            fallback(profile, "未填写网络朗读 API Key，已切回系统朗读")
            return
        }
        synthJob = scope.launch {
            val voice = profile.voice.ifBlank { provider.capabilities.defaultVoiceId }
            val format = if (profile.format in provider.capabilities.formats) profile.format else provider.capabilities.defaultFormat
            val bytes = try {
                provider.synthesize(currentText, profile.apiKey, voice, profile.rate, profile.style, format).bytes
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    fallback(profile, "网络朗读失败（${e.message ?: "请检查网络或 API Key"}），已切回系统朗读")
                }
                return@launch
            }
            withContext(Dispatchers.Main) { playBytes(bytes, format, profile) }
        }
    }

    private fun playBytes(bytes: ByteArray, format: String, profile: TtsProfile) {
        if (useFallback) return
        try {
            val file = java.io.File(context.cacheDir, "tts_preview.$format")
            file.writeBytes(bytes)
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setVolume(profile.volume, profile.volume)
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener { releasePlayer(); onState(STATE_IDLE, null) }
            mp.setOnErrorListener { _, _, _ ->
                releasePlayer()
                fallback(profile, "网络音频播放失败，已切回系统朗读")
                true
            }
            mp.start()
        } catch (e: Exception) {
            releasePlayer()
            fallback(profile, "网络音频播放失败（${e.message ?: "格式不支持"}），已切回系统朗读")
        }
    }

    /** 网络失败 → 本次试听改用系统 TTS 朗读（不改用户的持久配置）。 */
    private fun fallback(profile: TtsProfile, message: String) {
        useFallback = true
        onState(STATE_PLAYING, message)
        startSystem(profile)
    }

    // ---------- 系统 TTS ----------
    private fun startSystem(profile: TtsProfile) {
        val doSpeak = { engine: TextToSpeech ->
            val lang = runCatching { engine.setLanguage(Locale.CHINESE) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                runCatching { engine.language = Locale.getDefault() }
                onState(STATE_PLAYING, "系统 TTS 缺少中文语音包，已用默认语音朗读")
            }
            engine.setSpeechRate(profile.rate.coerceIn(0.5f, 2.5f))
            engine.speak(currentText, TextToSpeech.QUEUE_FLUSH, Bundle(), "yg_preview")
        }
        tts?.let { doSpeak(it); return }
        var created: TextToSpeech? = null
        created = TextToSpeech(context) { status ->
            val engine = tts ?: created
            if (status == TextToSpeech.SUCCESS && engine != null) {
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { onState(STATE_IDLE, null) }
                    override fun onError(utteranceId: String?) { onState(STATE_IDLE, "朗读失败") }
                })
                tts = engine
                doSpeak(engine)
            } else {
                onState(STATE_IDLE, "系统文字转语音不可用，请在系统设置里检查 TTS 引擎和中文语音包")
            }
        }
        tts = created
    }

    /** 停止试听。 */
    fun stop() {
        synthJob?.cancel()
        synthJob = null
        releasePlayer()
        tts?.setOnUtteranceProgressListener(null)
        tts?.stop()
        onState(STATE_IDLE, null)
    }

    private fun releasePlayer() {
        runCatching { mediaPlayer?.setOnCompletionListener(null) }
        runCatching { mediaPlayer?.setOnErrorListener(null) }
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    /** 离开设置页时释放系统 TTS。 */
    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
