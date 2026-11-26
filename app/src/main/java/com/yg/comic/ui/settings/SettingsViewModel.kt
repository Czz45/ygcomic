package com.yg.comic.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yg.comic.BuildConfig
import com.yg.comic.data.BookRepository
import com.yg.comic.data.SettingsStore
import com.yg.comic.engine.ImageCache
import com.yg.comic.model.*
import com.yg.comic.update.UpdateManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: BookRepository,
    private val store: SettingsStore,
) : ViewModel() {
    val theme = store.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val direction = store.globalReader.map { it.direction }.stateIn(viewModelScope, SharingStarted.Eagerly, PageDirection.LTR)
    val dual = store.globalReader.map { it.dualPage }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val scale = store.globalReader.map { it.scale }.stateIn(viewModelScope, SharingStarted.Eagerly, 1f)
    val sensitivity = store.globalReader.map { it.sensitivity }.stateIn(viewModelScope, SharingStarted.Eagerly, 1f)
    val cacheLimit = store.cacheLimitMb.stateIn(viewModelScope, SharingStarted.Eagerly, 512)
    val autoSortEnabled = store.autoSortEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    // 测试版更新通道（默认关：只检测正式版）
    val betaChannel = store.betaChannel.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setBetaChannel(v: Boolean) = viewModelScope.launch { store.setBetaChannel(v) }
    val sources = repo.sourceFolders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize: StateFlow<Long> = _cacheSize.asStateFlow()

    // 当前内置版本（随构建自动注入，发版无需改代码）
    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE

    // ---- 更新检测/下载 ----
    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class Found(val release: UpdateManager.Release) : UpdateState
        data object UpToDate : UpdateState
        /** 通道里没有任何可用版本（目前仓库就没有正式版） */
        data class NoneAvailable(val beta: Boolean) : UpdateState
        data class Failed(val message: String) : UpdateState
        data class Downloading(val progress: Int) : UpdateState
        data class ReadyToInstall(val path: String) : UpdateState
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    init { refreshCacheSize() }

    fun checkUpdate() = viewModelScope.launch {
        _updateState.value = UpdateState.Checking
        val beta = betaChannel.value
        val release = try {
            UpdateManager.checkLatest(beta = beta)
        } catch (e: UpdateManager.CheckException) {
            _updateState.value = UpdateState.Failed("检测失败：${e.message}")
            return@launch
        }
        if (release == null) {
            // 该通道里一个版本都没有（当前仓库确实没有正式版）
            _updateState.value = UpdateState.NoneAvailable(beta)
            return@launch
        }
        _updateState.value = if (UpdateManager.isNewer(release.version, versionName))
            UpdateState.Found(release) else UpdateState.UpToDate
    }

    fun downloadUpdate(release: UpdateManager.Release, context: Context) = viewModelScope.launch {
        _updateState.value = UpdateState.Downloading(0)
        val apk = UpdateManager.download(context, release.apkUrl) { p ->
            _updateState.value = UpdateState.Downloading(p)
        }
        if (apk != null) {
            _updateState.value = UpdateState.ReadyToInstall(apk.absolutePath)
            UpdateManager.install(context, apk)
        } else {
            _updateState.value = UpdateState.Failed("下载失败，请检查网络后重试")
        }
    }

    fun resetUpdate() { _updateState.value = UpdateState.Idle }

    fun setTheme(v: ThemeMode) = viewModelScope.launch { store.setTheme(v) }
    fun setDirection(v: PageDirection) = viewModelScope.launch { store.setDirection(v) }
    fun setDual(v: Boolean) = viewModelScope.launch { store.setDual(v) }
    fun setScale(v: Float) = viewModelScope.launch { store.setScale(v) }
    fun setSensitivity(v: Float) = viewModelScope.launch { store.setSensitivity(v) }
    fun setCacheLimit(v: Int) = viewModelScope.launch { store.setCacheLimitMb(v) }
    fun setAutoSortEnabled(v: Boolean) = viewModelScope.launch { store.setAutoSortEnabled(v) }

    fun addSource(uri: String, name: String) = viewModelScope.launch { repo.addSourceFolder(uri, name) }
    fun removeSource(id: Long) = viewModelScope.launch { repo.removeSourceFolder(id) }

    fun refreshCacheSize() = viewModelScope.launch {
        _cacheSize.value = with(kotlinx.coroutines.Dispatchers.IO) {
            ImageCache.cacheDirSize(repo.libraryDir())
        }
    }

    fun clearCache() = viewModelScope.launch {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repo.engine.releaseIdle() // 清 decode/temp_import 缓存，绝不碰 library 与原始导入文件
        }
        refreshCacheSize()
    }
}
