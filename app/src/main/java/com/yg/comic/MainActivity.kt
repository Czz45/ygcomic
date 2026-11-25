package com.yg.comic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import com.yg.comic.data.SettingsStore
import com.yg.comic.ui.AppRoot
import com.yg.comic.ui.source.SharedSource
import com.yg.comic.ui.theme.YGTheme
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    // 通知权限（Android 13+）：后台预超分靠前台服务常驻通知保活，没权限通知起不来。
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户拒绝也不崩：队列照跑，只是没常驻通知、切后台仍可能被冻结 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 内容边到边绘制：沉浸模式隐藏/唤出状态栏只切换叠加层，不 resize 内容，避免阅读背景跳动。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestHighRefreshRate()
        requestNotificationPermissionIfNeeded()

        // 首次启动若来自「分享/打开」Intent，先接住书源文本。
        handleShareIntent(intent)

        val store = SettingsStore(this)
        setContent {
            YGTheme(store = store) {
                AppRoot(store = store)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * 接收外部共享进来的书源文本：
     *  - ACTION_SEND（开源阅读「分享书源」/ 纯文本分享）→ EXTRA_TEXT 直接是 JSON；
     *  - ACTION_VIEW（文件管理器用本应用打开 .json）→ 读 Uri 内容。
     * 取到文本后放入 SharedSource，由界面导航到书源页导入。
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) SharedSource.post(text)
            }
            Intent.ACTION_VIEW -> {
                val uri: Uri? = intent.data
                if (uri != null) {
                    thread {
                        val text = runCatching {
                            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        }.getOrNull()
                        if (!text.isNullOrBlank()) SharedSource.post(text)
                    }
                }
            }
        }
    }

    /** Android 13+ 通知权限：没它前台服务的常驻通知起不来，后台保活失效。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 向系统申请最高刷新率（120Hz），系统/硬件不支持时自动回落，不强制 */
    private fun requestHighRefreshRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val maxRate = wm.defaultDisplay.supportedModes.maxOfOrNull { it.refreshRate } ?: 60f
        window.attributes = window.attributes.apply {
            preferredRefreshRate = maxRate
        }
    }
}
