package com.yg.comic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yg.comic.source.SourceRepository
import com.yg.comic.source.mihon.MihonExtensionManager
import com.yg.comic.source.yiciyuan.YiciyuanProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 仅 debug 包存在的自检入口：adb broadcast 触发，把结果写到 files/selftest.txt。 */
class SourceSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.yg.comic.FULLTEST") {
            val pending = goAsync()
            Thread {
                val sb = StringBuilder()
                fun log(s: String) {
                    android.util.Log.i("YGSELFTEST", s)
                    sb.appendLine(s)
                }
                fun test(tag: String, srcJson: String, key: String) {
                    try {
                        log("$tag provider=${SourceRepository.providerFor(srcJson).providerId}")
                        val books = runBlocking { withTimeoutOrNull(30000) { SourceRepository.search(srcJson, key) } }?.getOrNull()
                        log("$tag search=${books?.size}")
                        val book = books?.firstOrNull() ?: return
                        log("$tag book=${book.name} url=${book.bookUrl.take(100)}")
                        val dRes = runBlocking { withTimeoutOrNull(30000) { SourceRepository.detail(srcJson, book.bookUrl) } }
                        val d = dRes?.getOrNull()
                        log("$tag detail=${d?.name} tocUrl=${d?.tocUrl} err=${dRes?.exceptionOrNull()}")
                        if (tag == "yiciyuan") {
                            log("$tag ruleToc=${YiciyuanProvider.mapSource(srcJson).optJSONObject("ruleToc")}")
                            log("$tag ruleContent=${YiciyuanProvider.mapSource(srcJson).optJSONObject("ruleContent")}")
                        }
                        val tocUrl = d?.tocUrl?.takeIf { it.isNotBlank() } ?: book.bookUrl
                        val tRes = runBlocking { withTimeoutOrNull(30000) { SourceRepository.toc(srcJson, tocUrl) } }
                        val toc = tRes?.getOrNull()
                        log("$tag toc=${toc?.size} err=${tRes?.exceptionOrNull()}")
                        val ch = toc?.firstOrNull() ?: return
                        log("$tag chapter=${ch.title.take(40)} url=${ch.url.take(100)}")
                        if (tag == "yiciyuan" && !intent.getBooleanExtra("skipYcy", false)) {
                            val rawC = com.yg.comic.source.SourceEngine.nativeContent(
                                YiciyuanProvider.mapSource(srcJson).toString(), ch.url
                            )
                            log("$tag rawContent=${rawC.take(400)}")
                        }
                        if (tag == "yiciyuan" && intent.getBooleanExtra("skipYcy", false)) {
                            log("$tag skip content")
                            return
                        }
                        val cRes = runBlocking { withTimeoutOrNull(45000) { SourceRepository.content(srcJson, ch.url) } }
                        val c = cRes?.getOrNull()
                        val direct = runBlocking { withTimeoutOrNull(20000) { YiciyuanProvider.content(srcJson, ch.url) } }
                        log("$tag content images=${c?.images?.size} err=${cRes?.exceptionOrNull()} direct=${direct?.getOrNull()?.images?.size} derr=${direct?.exceptionOrNull()} first=${c?.images?.firstOrNull()?.take(120)}")
                    } catch (t: Throwable) {
                        log("$tag error=$t")
                    }
                }
                try {
                    log("engine=${com.yg.comic.source.SourceEngine.nativeVersion()}")
                    val raw = context.assets.open("yiciyuan_sources.json").use { it.readBytes().toString(Charsets.UTF_8) }
                    val arr = JSONArray(raw)
                    var ycSrc: String? = null
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optString("bookSourceName").contains("包子漫画")) { ycSrc = o.toString(); break }
                    }
                    if (ycSrc != null) test("yiciyuan", ycSrc, "斗破苍穹") else log("yiciyuan source not found")
                    val apk = File(intent.getStringExtra("path") ?: File(context.getExternalFilesDir(null), "baozimh.apk").absolutePath)
                    val handles = runCatching { MihonExtensionManager.loadApk(apk) }.getOrDefault(emptyList())
                    log("mihon handles=${handles.size}")
                    handles.firstOrNull()?.let { test("mihon", MihonExtensionManager.sourceJson(it), "斗破苍穹") }
                } catch (t: Throwable) {
                    log("fulltest fatal=$t")
                }
                runCatching { File(context.filesDir, "fulltest.txt").writeText(sb.toString()) }
                pending.finish()
            }.start()
            return
        }
        if (intent.action == "com.yg.comic.MIHONTEST") {
            val pending = goAsync()
            Thread {
                val sb = StringBuilder()
                fun log(s: String) {
                    android.util.Log.i("YGSELFTEST", s)
                    sb.appendLine(s)
                }
                try {
                    log("engine=${com.yg.comic.source.SourceEngine.nativeVersion()}")
                    val apk = File(intent.getStringExtra("path") ?: File(context.getExternalFilesDir(null), "dm5.apk").absolutePath)
                    log("apk=${apk.absolutePath} exists=${apk.exists()} len=${apk.length()}")
                    val handles = runCatching { MihonExtensionManager.loadApk(apk) }.getOrDefault(emptyList())
                    log("handles=${handles.size}")
                    val h = handles.firstOrNull()
                    if (h != null) {
                        val srcJson = MihonExtensionManager.sourceJson(h)
                        log("provider=${SourceRepository.providerFor(srcJson).providerId}")
                        val books = runBlocking { withTimeoutOrNull(60000) { SourceRepository.search(srcJson, "斗破苍穹") } }?.getOrNull()
                        log("search=${books?.size}")
                        val book = books?.firstOrNull()
                        if (book != null) {
                            log("book=${book.name} url=${book.bookUrl}")
                            val dRes = runBlocking { withTimeoutOrNull(60000) { SourceRepository.detail(srcJson, book.bookUrl) } }
                            val d = dRes?.getOrNull()
                            log("detail=${d?.name} tocUrl=${d?.tocUrl} err=${dRes?.exceptionOrNull()}")
                            val tocUrl = d?.tocUrl?.takeIf { it.isNotBlank() } ?: book.bookUrl
                            val tRes = runBlocking { withTimeoutOrNull(90000) { SourceRepository.toc(srcJson, tocUrl) } }
                            val toc = tRes?.getOrNull()
                            log("toc=${toc?.size} err=${tRes?.exceptionOrNull()}")
                            val ch = toc?.firstOrNull()
                            if (ch != null) {
                                log("chapter=${ch.title} url=${ch.url}")
                                val cRes = runBlocking { withTimeoutOrNull(90000) { SourceRepository.content(srcJson, ch.url) } }
                                val c = cRes?.getOrNull()
                                log("content images=${c?.images?.size} err=${cRes?.exceptionOrNull()} first=${c?.images?.firstOrNull()?.take(120)}")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    log("fatal=$t")
                }
                runCatching { File(context.filesDir, "mihontest.txt").writeText(sb.toString()) }
                pending.finish()
            }.start()
            return
        }
        if (intent.action == "com.yg.comic.LOADAPK") {
            val pending = goAsync()
            Thread {
                val sb = StringBuilder()
                fun log(s: String) {
                    android.util.Log.i("YGSELFTEST", s)
                    sb.appendLine(s)
                }
                try {
                    val path = intent.getStringExtra("path")
                        ?: File(context.getExternalFilesDir(null), "baozimh.apk").absolutePath
                    log("loadapk_path=$path exists=${File(path).exists()} len=${File(path).length()}")
                    val handles = runCatching { MihonExtensionManager.loadApk(File(path)) }
                    log("loadapk_handles=${handles.getOrNull()?.size ?: -1} err=${handles.exceptionOrNull()}")
                    handles.getOrNull()?.forEach { h ->
                        log("loadapk_source=${h.source.name} id=${h.source.id} class=${h.source.javaClass.name}")
                        val sj = MihonExtensionManager.sourceJson(h)
                        val r = runCatching {
                            runBlocking { withTimeoutOrNull(30000) { SourceRepository.search(sj, "斗破苍穹") } }
                        }
                        val books = r.getOrNull()?.getOrNull()
                        log("mihon_search[${h.source.name}]=" + (books?.map { it.name.take(24) }?.take(3) ?: "null/err=${r.exceptionOrNull()}"))
                    }
                } catch (t: Throwable) {
                    log("loadapk_fatal=$t")
                }
                runCatching { File(context.filesDir, "loadapk.txt").writeText(sb.toString()) }
                pending.finish()
            }.start()
            return
        }
        val pending = goAsync()
        Thread {
            val sb = StringBuilder()
            fun log(s: String) {
                android.util.Log.i("YGSELFTEST", s)
                sb.appendLine(s)
            }
            try {
                val raw = context.assets.open("yiciyuan_sources.json").use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                val arr = JSONArray(raw)
                log("yiciyuan_count=${arr.length()}")
                var ok = 0
                var hasSearch = 0
                for (i in 0 until arr.length()) {
                    val one = arr.getJSONObject(i).toString()
                    val p = runCatching { YiciyuanProvider.parse(one) }.getOrNull()
                    if (p?.ok == true) ok++
                    if (p?.hasSearch == true) hasSearch++
                }
                log("yiciyuan_parse_ok=$ok has_search=$hasSearch")

                val candidates = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val n = o.optString("bookSourceName")
                    if (n.contains("动漫屋") || n.contains("拷贝") || n.contains("包子") || n.contains("漫客栈")) {
                        candidates.add(o.toString())
                    }
                    if (candidates.size >= 3) break
                }
                for (src in candidates) {
                    val name = JSONObject(src).optString("bookSourceName")
                    val r = runCatching {
                        runBlocking { withTimeoutOrNull(20000) { SourceRepository.search(src, "斗破苍穹") } }
                    }
                    val books = r.getOrNull()?.getOrNull()
                    log("yiciyuan_search[$name]=" + (books?.map { it.name.take(24) }?.take(3) ?: "null/error=${r.exceptionOrNull()}"))
                }

                val items = MihonExtensionManager.storeItems()
                log("mihon_store=${items.size}")
                val item = items.firstOrNull { it.packageName.contains("baozimh") }
                    ?: items.firstOrNull { it.packageName.contains("copymanga") }
                    ?: items.firstOrNull()
                if (item != null) {
                    log("mihon_pick=${item.packageName} name=${item.name} apk=${item.apkUrl.take(80)}")
                    val handles = runCatching {
                        runBlocking { withTimeoutOrNull(120000) { MihonExtensionManager.downloadAndLoad(item) } ?: emptyList() }
                    }.getOrElse {
                        log("mihon_load_error=$it")
                        emptyList()
                    }
                    log("mihon_loaded=${handles.size} names=${handles.map { it.source.name }.take(6)}")
                }
            } catch (t: Throwable) {
                log("fatal=$t")
            }
            runCatching { File(context.filesDir, "selftest.txt").writeText(sb.toString()) }
            pending.finish()
        }.start()
    }
}
