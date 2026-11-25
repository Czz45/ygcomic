package com.yg.comic.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yg.comic.model.*
import com.yg.comic.model.NovelSettings
import com.yg.comic.model.NovelFont
import com.yg.comic.tts.TtsProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore by preferencesDataStore("ygcomic_settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val DIRECTION = stringPreferencesKey("direction")
        val SCALE = floatPreferencesKey("scale")
        val DUAL = booleanPreferencesKey("dual")
        val BACKGROUND = longPreferencesKey("background")
        val SENSITIVITY = floatPreferencesKey("sensitivity")
        val IMMERSIVE = booleanPreferencesKey("immersive")
        val CACHE_LIMIT_MB = intPreferencesKey("cache_limit_mb")
        val LOG = booleanPreferencesKey("log_enabled")
        val FILTER = stringPreferencesKey("filter")
        val SORT = stringPreferencesKey("sort")
        val AUTO_SORT_ENABLED = booleanPreferencesKey("auto_sort_enabled")
        // 漫画超分
        val SR_ENABLED = booleanPreferencesKey("sr_enabled")
        val SR_MODEL = stringPreferencesKey("sr_model")
        val AUTO_CROP_ENABLED = booleanPreferencesKey("auto_crop_enabled")
        // 后台预超分总开关（默认关：关时阅读页走原实时超分回退路径）
        val PRELOAD_SR_ENABLED = booleanPreferencesKey("preload_sr_enabled")
        val SR_WIFI_ONLY = booleanPreferencesKey("sr_wifi_only")
        val SR_CACHE_QUOTA_MB = intPreferencesKey("sr_cache_quota_mb")
        // 小说阅读器独立设置
        val NOVEL_FONT_SIZE = floatPreferencesKey("novel_font_size")
        val NOVEL_LINE_HEIGHT = floatPreferencesKey("novel_line_height")
        val NOVEL_LETTER_SPACING = floatPreferencesKey("novel_letter_spacing")
        val NOVEL_FONT = stringPreferencesKey("novel_font")
        val NOVEL_PARA_SPACING = floatPreferencesKey("novel_para_spacing")
        val NOVEL_SIDE_MARGIN = floatPreferencesKey("novel_side_margin")
        val NOVEL_VERTICAL_MARGIN = floatPreferencesKey("novel_vertical_margin")
        val NOVEL_INDENT = booleanPreferencesKey("novel_indent")
        val NOVEL_AUTO_PARA = booleanPreferencesKey("novel_auto_para")
        val NOVEL_BG = longPreferencesKey("novel_bg")
        val NOVEL_WEIGHT = stringPreferencesKey("novel_weight")
        val NOVEL_VARIANT = stringPreferencesKey("novel_variant")
        val NOVEL_TEXT_COLOR = longPreferencesKey("novel_text_color")
        val NOVEL_ANIMATION = stringPreferencesKey("novel_animation")
        // 阅读时间统计
        val READ_DATE = stringPreferencesKey("read_date")
        val TODAY_READ_MS = longPreferencesKey("today_read_ms")
        val TOTAL_READ_MS = longPreferencesKey("total_read_ms")
        // WebDAV 备份与恢复
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val WEBDAV_ACCOUNT = stringPreferencesKey("webdav_account")
        val WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
        val WEBDAV_DIR = stringPreferencesKey("webdav_dir")
        // 小说朗读引擎（system=系统TTS / network=网络TTS / local=本地大模型预留）
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val TTS_PROVIDER = stringPreferencesKey("tts_provider")
        val TTS_API_KEY = stringPreferencesKey("tts_api_key")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_STYLE = stringPreferencesKey("tts_style")
        val TTS_FORMAT = stringPreferencesKey("tts_format")
        val TTS_VOLUME = floatPreferencesKey("tts_volume")
        val TTS_CACHE_SEGMENTS = intPreferencesKey("tts_cache_segments")

        // 朗读配置：多套配置存 JSON 列表 + 当前选中 id；旧的 8 个扁平 tts_* key 保留只为首次迁移
        val TTS_PROFILES = stringPreferencesKey("tts_profiles")
        val TTS_ACTIVE_PROFILE_ID = stringPreferencesKey("tts_active_profile_id")
        // 目录智能排序（小说/漫画阅读器目录弹窗共用开关，默认关=按源顺序）
        val SMART_TOC_SORT = booleanPreferencesKey("smart_toc_sort")
    }

    /** WebDAV 备份配置（服务器地址、账号、密码、目录）。目录默认 legado，与阅读App保持一致。 */
    data class WebDavConfig(val url: String, val account: String, val password: String, val dir: String)
    val webDavConfig: Flow<WebDavConfig> = context.dataStore.data.map { p ->
        WebDavConfig(
            url = p[Keys.WEBDAV_URL] ?: "",
            account = p[Keys.WEBDAV_ACCOUNT] ?: "",
            password = p[Keys.WEBDAV_PASSWORD] ?: "",
            dir = p[Keys.WEBDAV_DIR] ?: "legado"
        )
    }
    suspend fun setWebDav(url: String, account: String, password: String, dir: String) = context.dataStore.edit { p ->
        p[Keys.WEBDAV_URL] = url
        p[Keys.WEBDAV_ACCOUNT] = account
        p[Keys.WEBDAV_PASSWORD] = password
        p[Keys.WEBDAV_DIR] = dir.ifBlank { "legado" }
    }

    /** 阅读时间统计：(今日毫秒, 累计毫秒)。跨天自动把今日清零。 */
    data class ReadStats(val todayMs: Long, val totalMs: Long)
    val readStats: Flow<ReadStats> = context.dataStore.data.map { p ->
        val today = java.time.LocalDate.now().toString()
        val storedDate = p[Keys.READ_DATE] ?: ""
        val todayMs = if (storedDate == today) (p[Keys.TODAY_READ_MS] ?: 0L) else 0L
        ReadStats(todayMs, p[Keys.TOTAL_READ_MS] ?: 0L)
    }
    /** 累加阅读时长（毫秒）。跨天时重置今日计数。 */
    suspend fun addReadMs(delta: Long) {
        if (delta <= 0) return
        val today = java.time.LocalDate.now().toString()
        context.dataStore.edit { p ->
            val storedDate = p[Keys.READ_DATE] ?: ""
            val baseToday = if (storedDate == today) (p[Keys.TODAY_READ_MS] ?: 0L) else 0L
            p[Keys.READ_DATE] = today
            p[Keys.TODAY_READ_MS] = baseToday + delta
            p[Keys.TOTAL_READ_MS] = (p[Keys.TOTAL_READ_MS] ?: 0L) + delta
        }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { p ->
        runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }
    suspend fun setTheme(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME] = mode.name }

    val globalReader: Flow<ReaderSettings> = context.dataStore.data.map { p ->
        ReaderSettings(
            bookId = null,
            direction = runCatching { PageDirection.valueOf(p[Keys.DIRECTION] ?: "LTR") }.getOrDefault(PageDirection.LTR),
            scale = p[Keys.SCALE] ?: 1f,
            dualPage = p[Keys.DUAL] ?: false,
            background = p[Keys.BACKGROUND] ?: 0xFF000000L,
            sensitivity = p[Keys.SENSITIVITY] ?: 1f,
            immersive = p[Keys.IMMERSIVE] ?: true,
        )
    }
    suspend fun setDirection(v: PageDirection) = context.dataStore.edit { it[Keys.DIRECTION] = v.name }
    suspend fun setScale(v: Float) = context.dataStore.edit { it[Keys.SCALE] = v }
    suspend fun setDual(v: Boolean) = context.dataStore.edit { it[Keys.DUAL] = v }
    suspend fun setBackground(v: Long) = context.dataStore.edit { it[Keys.BACKGROUND] = v }
    suspend fun setSensitivity(v: Float) = context.dataStore.edit { it[Keys.SENSITIVITY] = v }
    suspend fun setImmersive(v: Boolean) = context.dataStore.edit { it[Keys.IMMERSIVE] = v }

    val cacheLimitMb: Flow<Int> = context.dataStore.data.map { it[Keys.CACHE_LIMIT_MB] ?: 512 }
    suspend fun setCacheLimitMb(v: Int) = context.dataStore.edit { it[Keys.CACHE_LIMIT_MB] = v }

    val logEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.LOG] ?: false }
    suspend fun setLog(v: Boolean) = context.dataStore.edit { it[Keys.LOG] = v }

    val novelReader: Flow<NovelSettings> = context.dataStore.data.map { p ->
        NovelSettings(
            fontSize = p[Keys.NOVEL_FONT_SIZE] ?: 24f,
            lineHeightMul = p[Keys.NOVEL_LINE_HEIGHT] ?: 1.9f,
            letterSpacing = p[Keys.NOVEL_LETTER_SPACING] ?: 0f,
            font = runCatching { NovelFont.valueOf(p[Keys.NOVEL_FONT] ?: "SERIF") }.getOrDefault(NovelFont.SERIF),
            fontWeight = runCatching { NovelWeight.valueOf(p[Keys.NOVEL_WEIGHT] ?: "MEDIUM") }.getOrDefault(NovelWeight.MEDIUM),
            variant = runCatching { ChineseVariant.valueOf(p[Keys.NOVEL_VARIANT] ?: "SIMPLIFIED") }.getOrDefault(ChineseVariant.SIMPLIFIED),
            paragraphSpacing = p[Keys.NOVEL_PARA_SPACING] ?: 16f,
            sideMargin = p[Keys.NOVEL_SIDE_MARGIN] ?: 20f,
            verticalMargin = p[Keys.NOVEL_VERTICAL_MARGIN] ?: 16f,
            indentFirstLine = p[Keys.NOVEL_INDENT] ?: true,
            autoParagraph = p[Keys.NOVEL_AUTO_PARA] ?: true,
            background = p[Keys.NOVEL_BG] ?: 0xFF2B2B2BL,
            textColor = p[Keys.NOVEL_TEXT_COLOR] ?: 0xFFCFC8BCL,
            animation = runCatching { PageAnimation.valueOf(p[Keys.NOVEL_ANIMATION] ?: "SLIDE") }.getOrDefault(PageAnimation.SLIDE),
        )
    }
    suspend fun setNovelFontSize(v: Float) = context.dataStore.edit { it[Keys.NOVEL_FONT_SIZE] = v }
    suspend fun setNovelLineHeight(v: Float) = context.dataStore.edit { it[Keys.NOVEL_LINE_HEIGHT] = v }
    suspend fun setNovelLetterSpacing(v: Float) = context.dataStore.edit { it[Keys.NOVEL_LETTER_SPACING] = v }
    suspend fun setNovelFont(v: NovelFont) = context.dataStore.edit { it[Keys.NOVEL_FONT] = v.name }
    suspend fun setNovelWeight(v: NovelWeight) = context.dataStore.edit { it[Keys.NOVEL_WEIGHT] = v.name }
    suspend fun setNovelVariant(v: ChineseVariant) = context.dataStore.edit { it[Keys.NOVEL_VARIANT] = v.name }
    suspend fun setNovelParaSpacing(v: Float) = context.dataStore.edit { it[Keys.NOVEL_PARA_SPACING] = v }
    suspend fun setNovelSideMargin(v: Float) = context.dataStore.edit { it[Keys.NOVEL_SIDE_MARGIN] = v }
    suspend fun setNovelVerticalMargin(v: Float) = context.dataStore.edit { it[Keys.NOVEL_VERTICAL_MARGIN] = v }
    suspend fun setNovelIndent(v: Boolean) = context.dataStore.edit { it[Keys.NOVEL_INDENT] = v }
    suspend fun setNovelAutoPara(v: Boolean) = context.dataStore.edit { it[Keys.NOVEL_AUTO_PARA] = v }
    suspend fun setNovelBg(v: Long) = context.dataStore.edit { it[Keys.NOVEL_BG] = v }
    suspend fun setNovelTextColor(v: Long) = context.dataStore.edit { it[Keys.NOVEL_TEXT_COLOR] = v }
    suspend fun setNovelTheme(bg: Long, text: Long) = context.dataStore.edit {
        it[Keys.NOVEL_BG] = bg
        it[Keys.NOVEL_TEXT_COLOR] = text
    }
    suspend fun setNovelAnimation(v: PageAnimation) = context.dataStore.edit { it[Keys.NOVEL_ANIMATION] = v.name }

    val filter: Flow<String> = context.dataStore.data.map { it[Keys.FILTER] ?: "全部" }
    suspend fun setFilter(v: String) = context.dataStore.edit { it[Keys.FILTER] = v }
    val sort: Flow<SortMode> = context.dataStore.data.map {
        runCatching { SortMode.valueOf(it[Keys.SORT] ?: "NATURAL") }.getOrDefault(SortMode.NATURAL)
    }
    suspend fun setSort(v: SortMode) = context.dataStore.edit { it[Keys.SORT] = v.name }

    /** 智能排序总开关：按书源质量（网络状态/健康度/内容质量）自动排序，默认开启。 */
    val autoSortEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_SORT_ENABLED] ?: true }
    suspend fun setAutoSortEnabled(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_SORT_ENABLED] = v }

    /** 漫画超分总开关，默认关闭（需用户主动开启并下载模型）。作用于所有漫画类型：网络 / 本地图片 / PDF 漫画。 */
    val srEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.SR_ENABLED] ?: false }
    suspend fun setSrEnabled(v: Boolean) = context.dataStore.edit { it[Keys.SR_ENABLED] = v }

    /** 超分模型 key（对应 SRModel.key），默认线条 2 倍档（省内存、漫画线条更清晰）。 */
    val srModelKey: Flow<String> =
        context.dataStore.data.map { it[Keys.SR_MODEL] ?: "realcugan_2x" }
    suspend fun setSrModelKey(v: String) = context.dataStore.edit { it[Keys.SR_MODEL] = v }

    /** 漫画自动裁剪白边：默认关闭，避免误裁正常留白。 */
    val autoCropEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_CROP_ENABLED] ?: false }
    suspend fun setAutoCropEnabled(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_CROP_ENABLED] = v }

    /** 后台预超分总开关，默认关闭。开启后长按章节批量超分落盘，关闭则阅读页走原实时超分回退。 */
    val preloadSrEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.PRELOAD_SR_ENABLED] ?: false }
    suspend fun setPreloadSrEnabled(v: Boolean) = context.dataStore.edit { it[Keys.PRELOAD_SR_ENABLED] = v }

    /** 预超分仅在 WiFi 下进行，默认开启，避免耗移动流量。 */
    val srWifiOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.SR_WIFI_ONLY] ?: true }
    suspend fun setSrWifiOnly(v: Boolean) = context.dataStore.edit { it[Keys.SR_WIFI_ONLY] = v }

    /** 预超分磁盘缓存配额（MB），默认 2048。 */
    val srCacheQuotaMb: Flow<Int> = context.dataStore.data.map { it[Keys.SR_CACHE_QUOTA_MB] ?: 2048 }
    suspend fun setSrCacheQuotaMb(v: Int) = context.dataStore.edit { it[Keys.SR_CACHE_QUOTA_MB] = v }

    // ===== 朗读配置（多套，当前一套生效）=====
    private val profileJson = Json { ignoreUnknownKeys = true }

    /** 从旧扁平 tts_* key 迁移出一条“默认配置”，保留用户已填的 API Key / 音色等，不丢数据。 */
    private fun legacyProfile(p: androidx.datastore.preferences.core.Preferences): TtsProfile = TtsProfile(
        id = "default",
        name = "默认配置",
        engine = p[Keys.TTS_ENGINE] ?: "system",
        providerId = p[Keys.TTS_PROVIDER] ?: "mimo",
        apiKey = p[Keys.TTS_API_KEY] ?: "",
        voice = p[Keys.TTS_VOICE] ?: "Chloe",
        style = p[Keys.TTS_STYLE] ?: "",
        format = p[Keys.TTS_FORMAT] ?: "wav",
        volume = p[Keys.TTS_VOLUME] ?: 1f,
        cacheSegments = p[Keys.TTS_CACHE_SEGMENTS] ?: -1,
    )

    private fun parseProfiles(raw: String?): List<TtsProfile>? = raw?.takeIf { it.isNotBlank() }?.let {
        runCatching { profileJson.decodeFromString<List<TtsProfile>>(it) }.getOrNull()
    }

    /** 所有朗读配置。首次没有列表时，用旧扁平 key 虚拟出一条默认配置（不落盘，直到用户改动才写回）。 */
    val ttsProfiles: Flow<List<TtsProfile>> = context.dataStore.data.map { p ->
        parseProfiles(p[Keys.TTS_PROFILES]) ?: listOf(legacyProfile(p))
    }
    val activeTtsProfileId: Flow<String> = context.dataStore.data.map { it[Keys.TTS_ACTIVE_PROFILE_ID] ?: "" }
    /** 当前生效的一套配置；选中 id 失效（被删/老数据）时回退到第一条。 */
    val activeTtsProfile: Flow<TtsProfile> = combine(ttsProfiles, activeTtsProfileId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull() ?: TtsProfile(id = "default")
    }

    /** 改当前生效配置的某个字段；旧扁平 key 尚未迁移时先把迁移出的默认配置写回列表。 */
    private suspend fun updateActive(transform: (TtsProfile) -> TtsProfile) = context.dataStore.edit { p ->
        val list = (parseProfiles(p[Keys.TTS_PROFILES]) ?: listOf(legacyProfile(p))).toMutableList()
        val activeId = p[Keys.TTS_ACTIVE_PROFILE_ID] ?: ""
        val idx = list.indexOfFirst { it.id == activeId }.let { if (it < 0) 0 else it }
        if (list.isNotEmpty()) {
            list[idx] = transform(list[idx])
            p[Keys.TTS_PROFILES] = profileJson.encodeToString(list)
        }
    }

    suspend fun addTtsProfile(name: String) {
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { p ->
            val list = (parseProfiles(p[Keys.TTS_PROFILES]) ?: listOf(legacyProfile(p))).toMutableList()
            list.add(TtsProfile(id = id, name = name))
            p[Keys.TTS_PROFILES] = profileJson.encodeToString(list)
            p[Keys.TTS_ACTIVE_PROFILE_ID] = id
        }
    }

    suspend fun duplicateTtsProfile(id: String) {
        context.dataStore.edit { p ->
            val list = (parseProfiles(p[Keys.TTS_PROFILES]) ?: listOf(legacyProfile(p))).toMutableList()
            val src = list.firstOrNull { it.id == id } ?: return@edit
            val copy = src.copy(id = UUID.randomUUID().toString(), name = src.name + " 副本")
            list.add(copy)
            p[Keys.TTS_PROFILES] = profileJson.encodeToString(list)
            p[Keys.TTS_ACTIVE_PROFILE_ID] = copy.id
        }
    }

    suspend fun renameTtsProfile(id: String, name: String) = context.dataStore.edit { p ->
        val list = (parseProfiles(p[Keys.TTS_PROFILES]) ?: listOf(legacyProfile(p))).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(name = name)
            p[Keys.TTS_PROFILES] = profileJson.encodeToString(list)
        }
    }

    suspend fun deleteTtsProfile(id: String) = context.dataStore.edit { p ->
        val list = (parseProfiles(p[Keys.TTS_PROFILES]) ?: listOf(legacyProfile(p))).toMutableList()
        if (list.size <= 1) return@edit // 至少留一套，否则没有生效配置
        list.removeAll { it.id == id }
        p[Keys.TTS_PROFILES] = profileJson.encodeToString(list)
        if (p[Keys.TTS_ACTIVE_PROFILE_ID] == id) p[Keys.TTS_ACTIVE_PROFILE_ID] = list.first().id
    }

    suspend fun setActiveTtsProfile(id: String) = context.dataStore.edit { p ->
        p[Keys.TTS_ACTIVE_PROFILE_ID] = id
    }

    // —— 以下单项 getter/setter 改为读写「当前生效配置」，保留旧 API 名，调用方无需改 ——
    /** 朗读引擎，默认系统 TTS。system / network / local */
    val ttsEngine: Flow<String> = activeTtsProfile.map { it.engine }
    suspend fun setTtsEngine(v: String) = updateActive { it.copy(engine = v) }
    val ttsApiKey: Flow<String> = activeTtsProfile.map { it.apiKey }
    suspend fun setTtsApiKey(v: String) = updateActive { it.copy(apiKey = v) }
    val ttsProvider: Flow<String> = activeTtsProfile.map { it.providerId }
    suspend fun setTtsProvider(v: String) = updateActive { it.copy(providerId = v) }
    val ttsVoice: Flow<String> = activeTtsProfile.map { it.voice }
    suspend fun setTtsVoice(v: String) = updateActive { it.copy(voice = v) }
    val ttsStyle: Flow<String> = activeTtsProfile.map { it.style }
    suspend fun setTtsStyle(v: String) = updateActive { it.copy(style = v) }
    val ttsFormat: Flow<String> = activeTtsProfile.map { it.format }
    suspend fun setTtsFormat(v: String) = updateActive { it.copy(format = v) }
    val ttsVolume: Flow<Float> = activeTtsProfile.map { it.volume }
    suspend fun setTtsVolume(v: Float) = updateActive { it.copy(volume = v) }
    val ttsCacheSegments: Flow<Int> = activeTtsProfile.map { it.cacheSegments }
    suspend fun setTtsCacheSegments(v: Int) = updateActive { it.copy(cacheSegments = v) }
    /** 朗读语速纳入配置，随配置一起保存。 */
    val ttsRate: Flow<Float> = activeTtsProfile.map { it.rate }
    suspend fun setTtsRate(v: Float) = updateActive { it.copy(rate = v) }

    // ===== 目录智能排序 =====
    /** 默认关=false=按书源原始顺序；开=true=按章号理顺（第一章在前），仅影响目录展示/点选映射。 */
    val smartTocSort: Flow<Boolean> = context.dataStore.data.map { it[Keys.SMART_TOC_SORT] ?: false }
    suspend fun setSmartTocSort(v: Boolean) = context.dataStore.edit { it[Keys.SMART_TOC_SORT] = v }
}
