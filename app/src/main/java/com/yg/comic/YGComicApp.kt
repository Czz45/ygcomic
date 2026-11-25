package com.yg.comic

import android.app.Application
import com.yg.comic.data.BookRepository
import com.yg.comic.data.SettingsStore
import com.yg.comic.sr.SuperResolutionManager
import com.yg.comic.sr.preload.PreloadSrQueue
import java.io.File

class YGComicApp : Application() {
    lateinit var repo: BookRepository
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        repo = BookRepository(this)

        // 后台预超分队列需要一个长生命周期的 SRManager（与 UI 实例共用 companion 全局串行锁）。
        // 赋给 companion.instance 供全局复用；磁盘缓存与进度层由 PreloadSrQueue.init 内部初始化。
        val srManager = SuperResolutionManager(this, SettingsStore(this))
        SuperResolutionManager.instance = srManager
        PreloadSrQueue.init(this, srManager)

        // Mihon/Tachiyomi 扩展宿主：初始化 Injekt 依赖桥（OkHttp/NetworkHelper/Json）。
        com.yg.comic.source.mihon.MihonExtensionManager.init(this)
    }

    /** 崩溃时把堆栈写到 files/last_crash.txt，便于定位真机闪退，不改变系统默认崩溃行为。 */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                File(filesDir, "last_crash.txt").writeText(
                    "time=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n" +
                        "thread=${thread.name}\n" +
                        throwable.stackTraceToString()
                )
            }
            if (previous != null) previous.uncaughtException(thread, throwable)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
