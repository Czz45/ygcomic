package com.yg.comic.ui.source

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yg.comic.data.BookRepository
import com.yg.comic.data.BookSourceEntity
import com.yg.comic.data.ORIGIN_LEGADO
import com.yg.comic.data.ORIGIN_MIHON
import com.yg.comic.data.ORIGIN_YICIYUAN
import com.yg.comic.data.originTypeOf
import com.yg.comic.model.MediaType
import com.yg.comic.source.SearchBook
import com.yg.comic.source.SourceOrigin
import com.yg.comic.source.SourceRepository
import com.yg.comic.shared.ComicSourceRule
import com.yg.comic.source.mihon.MihonExtensionManager
import com.yg.comic.source.mihon.MihonStoreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/** 搜索结果：书 + 来源（用于加入书架时带上书源 JSON 与 sourceId）
 *  origins 为聚合后的全部来源（含主源），空表示只有 source 一个来源。 */
data class SourcedBook(
    val book: SearchBook,
    val source: BookSourceEntity,
    val origins: List<SourceOrigin> = emptyList(),
    /** 加入书架时所在的首页分类索引（首页分类预览进入时填入）。 */
    val categoryIndex: Int = 0,
)

class BookSourceViewModel(
    private val repo: BookRepository,
) : ViewModel() {

    val sources: StateFlow<List<BookSourceEntity>> =
        repo.bookSources.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 按项目标记派生三份列表，供书源页/首页分块展示；sources 本身仍是全源，
    // 搜索聚合（SearchAggregator）继续用 sources，保持全源一起搜不受影响。
    // 空标记兜底按订阅链接派生，兼容旧内存态与尚未回填的行。
    private fun BookSourceEntity.effectiveOrigin(): String = originType.ifBlank { originTypeOf(subscribeUrl) }

    val legadoSources: StateFlow<List<BookSourceEntity>> = sources.map { list ->
        list.filter { it.effectiveOrigin() == ORIGIN_LEGADO }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val yiciyuanSources: StateFlow<List<BookSourceEntity>> = sources.map { list ->
        list.filter { it.effectiveOrigin() == ORIGIN_YICIYUAN }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val mihonSources: StateFlow<List<BookSourceEntity>> = sources.map { list ->
        list.filter { it.effectiveOrigin() == ORIGIN_MIHON }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _results = MutableStateFlow<List<SourcedBook>>(emptyList())
    val results: StateFlow<List<SourcedBook>> = _results.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    fun clearMessage() { _message.value = "" }

    /** 供界面层直接提示一条消息（如安装本地 APK 的结果）。 */
    fun showMessage(text: String) { _message.value = text }

    // ===== 网站连通性检测 =====
    // 状态值：0=检测中，1=通网（TCP 可达或精探确认能搜到书），2=不通，3=精探存疑（TCP 通但搜不到书/规则失效）；map 里不存在=未检测。
    private val netPrefs = repo.appContext.getSharedPreferences("source_net_check", android.content.Context.MODE_PRIVATE)

    // 检测规则 schema 版本：判定方式实质变化时 +1，使升级前的旧检测结果自动作废。
    // 1 = 旧规则（无此字段）；2 = v0.1.104 起 URL 内 <js>/@js:/{{}} 求值修复；
    // 3 = v0.1.118 起改为轻量 TCP 探活（只判域名可达），旧的"搜书判定"结果全部作废重测。
    // 4 = v0.1.119 起新增"精确复检"存疑态（3），旧的纯 TCP 判定结果作废重测。
    private val netCheckSchema = 4
    private var netCheckReset = false

    /**
     * 兼容 legado 的 bookSourceType 两种写法：
     * - 数字：0=文字/1=音频/2=漫画/3=下载/4=视频
     * - 字符串：异次元导出常用“漫画/小说/音频…”中文名
     * 以前直接 optInt 会把“漫画”读成 0，导入后漫画 tab 搜索会把它当小说源过滤掉。
     */
    private fun parseSourceType(jo: org.json.JSONObject): Int {
        return when (val v = jo.opt("bookSourceType")) {
            is Number -> v.toInt()
            is String -> when (v.trim()) {
                "漫画", "comic" -> 2
                "小说", "文字", "novel", "text" -> 0
                "音频", "audio" -> 1
                "下载", "download" -> 3
                "视频", "video" -> 4
                else -> v.trim().toIntOrNull() ?: 0
            }
            else -> 0
        }
    }

    private fun loadNetCheck(): MutableMap<Long, Int> {
        val m = HashMap<Long, Int>()
        if (netPrefs.getInt("schema", 0) != netCheckSchema) {
            // 引擎规则已升级：清掉旧结果，下一次检测按新引擎全量重跑
            netPrefs.edit().remove("ok").remove("bad").remove("suspect").putInt("schema", netCheckSchema).apply()
            netCheckReset = true
            return m
        }
        (netPrefs.getStringSet("ok", emptySet()) ?: emptySet()).forEach { m[it.toLongOrNull() ?: return@forEach] = 1 }
        (netPrefs.getStringSet("bad", emptySet()) ?: emptySet()).forEach { m[it.toLongOrNull() ?: return@forEach] = 2 }
        (netPrefs.getStringSet("suspect", emptySet()) ?: emptySet()).forEach { m[it.toLongOrNull() ?: return@forEach] = 3 }
        return m
    }

    private val netMap: MutableMap<Long, Int> = loadNetCheck()

    /**
     * 轻量连通性探活：先对域名做一次 TCP 连接（≈"ping 通"），连上后再发一个轻量 GET 看状态码。
     * 明确 404/410 的源判为不通（网站还活着但页面已删）；其它状态码或探测异常都按"通"处理，
     * 避免误杀需要特定 header/路径的源。不建 boa JS 引擎、不解析规则，超时短、内存低，可高并发。
     */
    private fun probeHost(url: String, timeoutMs: Int): Boolean {
        return try {
            var s = url.trim()
            if (s.isEmpty()) return false
            if (!s.contains("://")) s = "http://$s"
            val uri = java.net.URI(s)
            val host = uri.host ?: return false
            var port = uri.port
            if (port <= 0) port = if (s.startsWith("https://", true)) 443 else 80
            java.net.Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            }
            // TCP 通了再发一次轻量 GET 判 404/410；只读状态码不读 body，其余情况按通处理。
            try {
                val conn = java.net.URL(s).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                conn.disconnect()
                if (code == 404 || code == 410) return false
            } catch (_: Exception) {
                // HTTP 探测失败不影响 TCP 判定（需要特定 header/证书等的源不算死）
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 书架里已有的归一书名集合，用于搜索结果「加入书架」按钮变蓝、防重复添加。 */
    private val _addedKeys = MutableStateFlow<Set<String>>(emptySet())
    val addedKeys: StateFlow<Set<String>> = _addedKeys.asStateFlow()
    fun shelfKey(name: String): String = com.yg.comic.shared.SearchRules.shelfKey(name)

    init {
        if (netCheckReset) _message.value = "引擎规则已升级，检测结果已重置，请点重新检测"
        viewModelScope.launch(Dispatchers.IO) {
            repo.books.collect { books ->
                _addedKeys.value = books.map { shelfKey(it.title) }.toSet()
            }
        }
    }
    private val _netCheck = MutableStateFlow<Map<Long, Int>>(netMap.toMap())
    /** 书源 id → 连通状态（0 检测中 / 1 通网 / 2 不通 / 3 精探存疑），无条目=未检测 */
    val netCheck: StateFlow<Map<Long, Int>> = _netCheck.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** 精确复检进度：已完成数 → 总数（复用 _progress 展示） */
    private fun persistNetCheck() {
        val ok = HashSet<String>()
        val bad = HashSet<String>()
        val suspect = HashSet<String>()
        netMap.forEach { (id, st) ->
            when (st) {
                1 -> ok.add(id.toString())
                2 -> bad.add(id.toString())
                3 -> suspect.add(id.toString())
            }
        }
        netPrefs.edit().putStringSet("ok", ok).putStringSet("bad", bad).putStringSet("suspect", suspect).apply()
    }

    /**
     * 删除书源或重导（先删旧 id 再写入新 id）时同步清掉这些 id 的检测记录。
     * 否则 netMap 里残留已不存在的 id，UI 计数直接数 netCheck.values 就会只增不减。
     */
    private fun pruneNetCheck(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        var changed = false
        ids.forEach { if (netMap.remove(it) != null) changed = true }
        if (changed) {
            persistNetCheck()
            _netCheck.value = netMap.toMap()
        }
    }

    private var checkJob: kotlinx.coroutines.Job? = null

    fun cancelCheck() {
        checkJob?.cancel()
        _checking.value = false
    }

    /** 剩余未检测的源数量（供 UI 判断“继续检测 / 重新检测”） */
    val remainingCheckCount: Int
        get() = sources.value.filter { it.enabled && (netMap[it.id] == null || netMap[it.id] == 0) }.size

    /** 一键屏蔽所有检测为“不通网”的源（状态 2），批量单事务禁用。返回被屏蔽的数量。 */
    fun blockBadSources(onDone: (Int) -> Unit = {}) {
        val badIds = netMap.filter { it.value == 2 }.keys.toList()
        if (badIds.isEmpty()) {
            _message.value = "没有检测为不通的书源"
            onDone(0)
            return
        }
        viewModelScope.launch {
            try {
                repo.setBookSourcesEnabled(badIds, false)
                _message.value = "已屏蔽 ${badIds.size} 个不通书源"
                onDone(badIds.size)
            } catch (e: Exception) {
                _message.value = "屏蔽失败：${e.message}"
                onDone(0)
            }
        }
    }

    /**
     * 批量检测已启用书源的连通性：对每个源搜一次热门词，能搜到书=通网。
     * 滚动窗口执行：flatMapMerge(concurrency=6) 只限制「同时在途」的 JNI 搜索数（把 native 内存
     * 峰值压到安全值，避免一次性高并发新建大量 boa JS 引擎 + ureq TLS 连接触发 OOM 闪退），
     * 完成一个立刻放行下一个——既没有「等整批最慢源」的批次屏障（慢源只占 1 个槽位不拖死全局），
     * 也不会一次性铺开全部协程。相比 chunked 方案不会被单个卡死的 JNI 源拖住整批。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun checkSources(force: Boolean = false) {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _checking.value = true
            _message.value = ""
            val enabled = sources.value.filter { it.enabled }
            if (enabled.isEmpty()) {
                _message.value = "还没有启用的书源"
                _checking.value = false
                return@launch
            }
            // 重测（force）：先清空历史检测数据并落盘，UI 立刻回到"未检测"再全量重测。
            // 断点续检（非 force）：只检测未测过的源（netMap 无条目或残留的检测中 0），已测出结果(1/2)的跳过。
            // 状态 0 不落盘，进程被杀后重启会重新变成未检测，天然支持从中断处继续。
            if (force) {
                netMap.clear()
                netPrefs.edit().remove("ok").remove("bad").remove("suspect").apply()
                _netCheck.value = emptyMap()
            }
            val pending = if (force) enabled else enabled.filter { netMap[it.id] == null || netMap[it.id] == 0 }
            if (pending.isEmpty()) {
                _message.value = "所有书源都已检测完成，可点“重新检测”全量重测"
                _checking.value = false
                return@launch
            }
            _progress.value = 0 to pending.size
            pending.forEach { netMap[it.id] = 0 }
            _netCheck.value = netMap.toMap()
            var done = 0
            var okCnt = 0
            var badCnt = 0
            var sincePersist = 0
            // 滚动窗口：并发 TCP 探活（不建 boa 引擎、内存极低），完成一个立刻放行下一个，
            // 无批次屏障；单个慢源只占 1 个槽位（3s 超时），不拖死整批。
            val lock = Mutex()
            try {
                pending.asFlow()
                    .flatMapMerge(concurrency = 24) { src ->
                        flow {
                            val ok = probeHost(src.url, 3_000)
                            emit(src.id to ok)
                        }.flowOn(kotlinx.coroutines.Dispatchers.IO)
                    }
                    .collect { (id, ok) ->
                        lock.withLock {
                            netMap[id] = if (ok) 1 else 2
                            if (ok) okCnt++ else badCnt++
                            done++
                            sincePersist++
                            if (done % 5 == 0 || done >= pending.size) {
                                _progress.value = done to pending.size
                                _netCheck.value = netMap.toMap()
                            }
                            if (sincePersist >= 20 || done >= pending.size) {
                                persistNetCheck()
                                sincePersist = 0
                            }
                        }
                    }
            } catch (_: kotlinx.coroutines.CancellationException) {
                persistNetCheck()
                _checking.value = false
                return@launch
            }
            persistNetCheck()
            _message.value = "本次检测 ${pending.size} 个：通网 $okCnt / 不通 $badCnt"
            _checking.value = false
        }
    }

    /**
     * 只重测"不通"的源：网络恢复（如开了 VPN）后，之前判不通的源可能已可用。
     * 不动已确认可用(1)/存疑(3)的源，只针对 netMap==2 的源重新 TCP 探活。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun checkBadSources() {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _checking.value = true
            _message.value = ""
            val bad = sources.value.filter { it.enabled && netMap[it.id] == 2 }
            if (bad.isEmpty()) {
                _message.value = "当前没有“不通”的书源"
                _checking.value = false
                return@launch
            }
            _progress.value = 0 to bad.size
            var done = 0
            var okCnt = 0
            var sincePersist = 0
            val lock = Mutex()
            try {
                bad.asFlow()
                    .flatMapMerge(concurrency = 24) { src ->
                        flow {
                            val ok = probeHost(src.url, 3_000)
                            emit(src.id to ok)
                        }.flowOn(kotlinx.coroutines.Dispatchers.IO)
                    }
                    .collect { (id, ok) ->
                        lock.withLock {
                            netMap[id] = if (ok) 1 else 2
                            if (ok) okCnt++
                            done++
                            sincePersist++
                            if (done % 5 == 0 || done >= bad.size) {
                                _progress.value = done to bad.size
                                _netCheck.value = netMap.toMap()
                            }
                            if (sincePersist >= 20 || done >= bad.size) {
                                persistNetCheck()
                                sincePersist = 0
                            }
                        }
                    }
            } catch (_: kotlinx.coroutines.CancellationException) {
                persistNetCheck()
                _checking.value = false
                return@launch
            }
            persistNetCheck()
            _message.value = "不可用重测 ${bad.size} 个：恢复通网 $okCnt / 仍不通 ${bad.size - okCnt}"
            _checking.value = false
        }
    }

    /**
     * 精确复检（二次精探）：在第一次 TCP 探活判"通"的源上，再跑一次真实搜索验证是否真能搜到书。
     * 揪出"域名活着但规则失效"的源：搜到书保持状态 1（确认可用），搜不到/报错改为状态 3（存疑）。
     * 完整搜索要建 boa 引擎、较耗内存，并发压到 6 并逐个放行，避免高并发触发 OOM 闪退。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun refineCheck() {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _checking.value = true
            _message.value = ""
            // 只对首次判"通"（状态 1）的已启用源做精探
            val targets = sources.value.filter { it.enabled && netMap[it.id] == 1 }
            if (targets.isEmpty()) {
                _message.value = "没有已通网的源可复检，请先做一次连通性检测"
                _checking.value = false
                return@launch
            }
            _progress.value = 0 to targets.size
            targets.forEach { netMap[it.id] = 0 }
            _netCheck.value = netMap.toMap()
            var done = 0
            var confirmed = 0
            var suspect = 0
            val lock = Mutex()
            try {
                targets.asFlow()
                    .flatMapMerge(concurrency = 6) { src ->
                        flow {
                            val ok = try {
                                withTimeout(12_000L) {
                                    val r = SourceRepository.search(src.json, "斗破苍穹", 1)
                                    r.getOrNull()?.isNotEmpty() == true
                                }
                            } catch (_: Exception) {
                                false
                            }
                            emit(src.id to ok)
                        }.flowOn(kotlinx.coroutines.Dispatchers.IO)
                    }
                    .collect { (id, ok) ->
                        lock.withLock {
                            if (ok) { netMap[id] = 1; confirmed++ } else { netMap[id] = 3; suspect++ }
                            done++
                            persistNetCheck()
                            _progress.value = done to targets.size
                            _netCheck.value = netMap.toMap()
                        }
                    }
            } catch (_: kotlinx.coroutines.CancellationException) {
                persistNetCheck()
                _checking.value = false
                return@launch
            }
            persistNetCheck()
            _message.value = "精确复检 ${targets.size} 个：确认可用 $confirmed / 存疑 $suspect（域名通但搜不到书）"
            _checking.value = false
        }
    }

    /**
     * 添加书源：把粘贴的 JSON（单个对象或对象数组）逐个解析并入库。
     * 返回成功数量。
     */
    fun addSourceFromJson(raw: String, subscribeUrl: String = "", nameOverride: String = "", subscribeNameOverride: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val arr = com.yg.comic.source.SourceJsonUtil.splitToObjects(raw)
                if (arr.isEmpty()) { _message.value = "未识别到有效书源 JSON（收到 ${raw.length} 字符）"; return@launch }
                // 照 legado：一次性全量解析到内存，再一次入库（单事务），杜绝逐条 insert+reload 的 O(n²) 卡顿。
                // 每解析 100 个刷新一次进度，让用户看到真实的解析总数（而非误以为只导了几十个）。
                val total = arr.size
                _message.value = "正在解析 $total 个书源…"
                // 订阅名：导入时用户填的自定义名即订阅组名；留空则回退订阅链接域名；都没有则"手动添加"。
                // 同一 subscribeUrl 下的所有源共享此名，首页按它显示而不是那串 URL。
                // 订阅组名与单条源名解耦：subscribeNameOverride 专门守订阅分组名，
                // 让 Mihon 等导入能把 nameOverride 留空以显示真实源名，而订阅分组名不退化成域名。
                val subName = subscribeNameOverride.ifBlank { nameOverride }.ifBlank {
                    runCatching { java.net.URI(subscribeUrl).host }.getOrNull().orEmpty()
                }.ifBlank { "手动添加" }
                val entities = ArrayList<BookSourceEntity>(total)
                var failed = 0
                for (i in arr.indices) {
                    val one = arr[i]
                    val parsed = SourceRepository.parse(one)
                    if (!parsed.ok) { failed++; continue }
                    // 用户自定义名优先；多个源时用「自定义名 (i)」区分，避免全部重名覆盖。
                    val baseName = nameOverride.ifBlank { parsed.name }.ifBlank { "未命名书源" }
                    val name = if (nameOverride.isNotBlank() && arr.size > 1) "$baseName (${i + 1})" else baseName
                    // 照 legado：导入即保留作者的分组标签(bookSourceGroup)与功能类型(bookSourceType)，
                    // 便于书源页按分组查看、按类型决定用哪个阅读器打开。解析失败则留默认值，不影响入库。
                    var group = ""
                    var sType = 0
                    try {
                        val jo = org.json.JSONObject(one)
                        group = jo.optString("bookSourceGroup", "").orEmpty()
                        sType = parseSourceType(jo)
                    } catch (_: Exception) { }
                    entities.add(
                        BookSourceEntity(
                            name = name, url = parsed.url, json = one, enabled = true,
                            createdAt = System.currentTimeMillis(), groupName = group, sourceType = sType,
                            subscribeUrl = subscribeUrl, subscribeName = subName,
                        )
                    )
                    if ((i + 1) % 100 == 0) _message.value = "正在解析 ${i + 1}/$total 个书源…"
                }
                if (entities.isNotEmpty()) {
                    _message.value = "正在写入 ${entities.size} 个书源…"
                    repo.addBookSources(entities)
                }
                val added = entities.size
                // 明确报出「识别总数 / 成功入库 / 失败数」，便于核对是否漏导。
                _message.value = when {
                    added > 0 && failed > 0 -> "已添加 $added 个书源（共识别 $total 个，$failed 个解析失败）"
                    added > 0 -> "已添加 $added 个书源（共识别 $total 个）"
                    else -> "没有可添加的书源（共识别 $total 个，$failed 个解析失败）"
                }
            } catch (e: Exception) {
                _message.value = "添加失败：${e.message}"
            }
        }
    }

    /**
     * 内置精品订阅：读 assets/premium_sources.json（yiove 全量里实测能出书的 164 个源）。
     * 与 addSourceFromJson 分开：这里保留每个源的原始名字，只统一打上精品订阅标记，
     * 避免走 nameOverride 把 164 个源名全改成"精品"而破坏显示。
     */
    fun importPremiumSources() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val raw = repo.appContext.assets.open("premium_sources.json").bufferedReader().use { it.readText() }
                val arr = com.yg.comic.source.SourceJsonUtil.splitToObjects(raw)
                if (arr.isEmpty()) { _message.value = "精品源为空"; return@launch }
                _message.value = "正在导入 ${arr.size} 个精品书源…"
                val entities = ArrayList<BookSourceEntity>(arr.size)
                var failed = 0
                for (one in arr) {
                    val parsed = SourceRepository.parse(one)
                    if (!parsed.ok) { failed++; continue }
                    var group = ""
                    var sType = 0
                    try {
                        val jo = org.json.JSONObject(one)
                        group = jo.optString("bookSourceGroup", "").orEmpty()
                        sType = parseSourceType(jo)
                    } catch (_: Exception) { }
                    entities.add(
                        BookSourceEntity(
                            name = parsed.name.ifBlank { "未命名书源" }, url = parsed.url, json = one,
                            enabled = true, createdAt = System.currentTimeMillis(),
                            groupName = group, sourceType = sType,
                            subscribeUrl = "builtin://premium", subscribeName = "精品",
                        )
                    )
                }
                if (entities.isNotEmpty()) {
                    _message.value = "正在写入 ${entities.size} 个精品书源…"
                    repo.addBookSources(entities)
                }
                _message.value = "已导入 ${entities.size} 个精品书源（识别 ${arr.size} 个，$failed 个失败）"
            } catch (e: Exception) {
                _message.value = "精品源导入失败：${e.message}"
            }
        }
    }

    // ===== 异次元 / Mihon 独立源 =====

    /** 内置异次元源包（110 个明文规则源），与 legado 源完全分开入库。 */
    fun importYiciyuanSources() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val raw = repo.appContext.assets.open("yiciyuan_sources.json")
                    .bufferedReader().use { it.readText() }
                // 重复点“异次元源”时先清掉上一次导入的同订阅源，避免 110 个源变成 220 个；
                // 也顺手清掉旧版本 sourceType=0 的错误记录。
                val oldIds = sources.value.filter { it.subscribeUrl == "builtin://yiciyuan" }.map { it.id }
                if (oldIds.isNotEmpty()) { repo.removeBookSources(oldIds); pruneNetCheck(oldIds) }
                // 显示真实网站名（bookSourceName），订阅分组名仍用 subscribeNameOverride 守成"异次元"。
                // 以前传 nameOverride="异次元" 会把 110 条源名全覆盖成"异次元 (N)"，详情页/阅读器 tab 看不到真名。
                addSourceFromJson(raw, subscribeUrl = "builtin://yiciyuan", nameOverride = "", subscribeNameOverride = "异次元")
            } catch (e: Exception) {
                _message.value = "异次元源导入失败：${e.message}"
            }
        }
    }

    private val _mihonStore = MutableStateFlow<List<MihonStoreItem>>(emptyList())
    val mihonStore: StateFlow<List<MihonStoreItem>> = _mihonStore.asStateFlow()

    private val _mihonBusy = MutableStateFlow(false)
    val mihonBusy: StateFlow<Boolean> = _mihonBusy.asStateFlow()

    private val _mihonLoaded = MutableStateFlow<List<String>>(emptyList())
    /** 已加载的 Mihon 源名，用于扩展管理页展示。 */
    val mihonLoaded: StateFlow<List<String>> = _mihonLoaded.asStateFlow()

    private val _mihonLoadedPackages = MutableStateFlow(
        repo.appContext.getSharedPreferences("mihon_extensions", Context.MODE_PRIVATE)
            .getStringSet("loaded_packages", emptySet())?.toSet() ?: emptySet()
    )
    /** 已加入的扩展包名，用于把商店按钮置为蓝色「已加入」。 */
    val mihonLoadedPackages: StateFlow<Set<String>> = _mihonLoadedPackages.asStateFlow()

    private fun setMihonLoadedPackages(packages: Set<String>) {
        _mihonLoadedPackages.value = packages
        repo.appContext.getSharedPreferences("mihon_extensions", Context.MODE_PRIVATE)
            .edit().putStringSet("loaded_packages", packages).apply()
    }

    fun loadMihonStore() {
        viewModelScope.launch(Dispatchers.IO) {
            syncMihonPackagesFromSources()
            _mihonStore.value = runCatching { MihonExtensionManager.storeItems() }.getOrDefault(emptyList())
        }
    }

    // ===== 自定义 Mihon 扩展仓库（原生 index.json）：拉取预览 → 勾选导入 =====
    private val _customRepoUrl = MutableStateFlow("")
    val customRepoUrl: StateFlow<String> = _customRepoUrl.asStateFlow()

    private val _customRepoItems = MutableStateFlow<List<MihonStoreItem>>(emptyList())
    val customRepoItems: StateFlow<List<MihonStoreItem>> = _customRepoItems.asStateFlow()

    private val _customRepoLoading = MutableStateFlow(false)
    val customRepoLoading: StateFlow<Boolean> = _customRepoLoading.asStateFlow()

    fun setCustomRepoUrl(url: String) {
        _customRepoUrl.value = url
    }

    /** 拉取自定义仓库 index.json，只解析展示、不入库。失败沿用异常兜底提示。 */
    fun loadCustomRepo(url: String) {
        val u = url.trim()
        if (u.isEmpty()) {
            _message.value = "请输入仓库地址"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _customRepoLoading.value = true
            _message.value = "正在拉取仓库…"
            try {
                val items = MihonExtensionManager.fetchRepoItems(u)
                _customRepoItems.value = items
                _message.value = if (items.isEmpty()) "仓库里没有可用扩展" else "仓库已加载：${items.size} 个扩展，勾选后导入"
            } catch (e: Exception) {
                _customRepoItems.value = emptyList()
                _message.value = "拉取仓库失败：${e.message}"
            } finally {
                _customRepoLoading.value = false
            }
        }
    }

    /**
     * 导入勾选的仓库扩展。直接复用 importMihonExtension：
     * - sourceJson() 写死 provider=mihon → SourceProviderRegistry 路由到扩展引擎，不会错。
     * - importMihonExtension 内部按 subscribeUrl="mihon://包名" 先删旧再加 → 重复导同一扩展不累加。
     */
    fun importCustomRepoExtensions(items: List<MihonStoreItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var ok = 0
            var fail = 0
            items.forEach { item ->
                val before = _mihonLoadedPackages.value
                runCatching { MihonExtensionManager.downloadAndLoad(item) }
                    .getOrNull().takeIf { it?.isNotEmpty() == true }
                    ?.let { handles ->
                        setMihonLoadedPackages(before + handles.map { it.packageName })
                        val jsons = handles.map { MihonExtensionManager.sourceJson(it) }
                        val sub = "mihon://${item.packageName}"
                        val oldIds = sources.value.filter { it.subscribeUrl == sub }.map { it.id }
                        if (oldIds.isNotEmpty()) { repo.removeBookSources(oldIds); pruneNetCheck(oldIds) }
                        addSourceFromJson(
                            jsons.joinToString(",", "[", "]"),
                            subscribeUrl = sub,
                            nameOverride = "",
                            subscribeNameOverride = item.name,
                        )
                        ok++
                    } ?: run { fail++ }
            }
            _message.value = "已导入 $ok 个扩展" + if (fail > 0) "（$fail 个失败）" else ""
        }
    }

    /** 旧版本已经导入过的商店扩展，从书源的 subscribeUrl 反推包名，保证重开页面仍显示「已加入」。 */
    private fun syncMihonPackagesFromSources() {
        val packages = sources.value.mapNotNull { it.subscribeUrl }
            .filter { it.startsWith("mihon://") && it != "mihon://builtin" && it != "mihon://installed" }
            .map { it.removePrefix("mihon://") }
            .toSet()
        if (packages.isNotEmpty()) {
            val merged = _mihonLoadedPackages.value + packages
            if (merged != _mihonLoadedPackages.value) setMihonLoadedPackages(merged)
        }
    }

    /** 一键导入随包内置的 4 个 Mihon 扩展，不需要联网下载。 */
    fun importBuiltinMihonExtensions() {
        viewModelScope.launch(Dispatchers.IO) {
            _mihonBusy.value = true
            try {
                _message.value = "正在加载内置 Mihon 扩展…"
                val result = MihonExtensionManager.loadBuiltin()
                if (result.handles.isEmpty()) {
                    _message.value = "内置扩展加载失败：" + result.errors.joinToString("；").ifBlank { "没有可用源" }
                    return@launch
                }
                _mihonLoaded.value = result.handles.map { it.source.name }
                // 追加而不是覆盖：先单独导入的扩展（如 18Manhua）不能被“一键导入内置扩展”清掉蓝色已加入状态。
                setMihonLoadedPackages(_mihonLoadedPackages.value + result.handles.map { it.packageName })
                val jsons = result.handles.map { MihonExtensionManager.sourceJson(it) }
                val sub = "mihon://builtin"
                val oldIds = sources.value.filter { it.subscribeUrl == sub }.map { it.id }
                if (oldIds.isNotEmpty()) { repo.removeBookSources(oldIds); pruneNetCheck(oldIds) }
                addSourceFromJson(
                    jsons.joinToString(",", "[", "]"),
                    subscribeUrl = sub,
                    nameOverride = "",
                    subscribeNameOverride = "Mihon 内置扩展",
                )
                _message.value = "已导入 ${result.handles.size} 个内置源" +
                    if (result.errors.isNotEmpty()) "（${result.errors.size} 个失败）" else ""
            } catch (e: Exception) {
                _message.value = "内置 Mihon 扩展导入失败：${e.message}"
            } finally {
                _mihonBusy.value = false
            }
        }
    }

    /** 扫描手机里已安装的 Mihon/Tachiyomi 扩展，导入其全部源。 */
    fun scanMihonExtensions() {
        viewModelScope.launch(Dispatchers.IO) {
            _mihonBusy.value = true
            try {
                val handles = MihonExtensionManager.scanInstalled()
                if (handles.isEmpty()) {
                    val errs = MihonExtensionManager.lastScanErrors
                    _message.value = if (errs.isNotEmpty()) {
                        "发现扩展但加载失败：${errs.joinToString("；").take(300)}"
                    } else {
                        "没有发现已安装的 Mihon 扩展"
                    }
                    return@launch
                }
                // 部分扩展加载失败时把原因一并提示，避免"装了却少几个"无迹可寻。
                val errs = MihonExtensionManager.lastScanErrors
                if (errs.isNotEmpty()) {
                    _message.value = "已识别 ${handles.size} 个源；${errs.size} 个扩展加载失败：${errs.joinToString("；").take(300)}"
                }
                _mihonLoaded.value = handles.map { it.source.name }
                setMihonLoadedPackages(handles.map { it.packageName }.toSet())
                val jsons = handles.map { MihonExtensionManager.sourceJson(it) }
                val sub = "mihon://installed"
                val oldIds = sources.value.filter { it.subscribeUrl == sub }.map { it.id }
                if (oldIds.isNotEmpty()) { repo.removeBookSources(oldIds); pruneNetCheck(oldIds) }
                addSourceFromJson(jsons.joinToString(",", "[", "]"), subscribeUrl = sub, nameOverride = "", subscribeNameOverride = "Mihon 扩展")
            } catch (e: Exception) {
                _message.value = "扫描 Mihon 扩展失败：${e.message}"
            } finally {
                _mihonBusy.value = false
            }
        }
    }

    /** 从内置扩展商店下载一个 Mihon 扩展 APK 并导入其全部源。 */
    fun importMihonExtension(item: MihonStoreItem) {
        viewModelScope.launch(Dispatchers.IO) {
            _mihonBusy.value = true
            try {
                _message.value = "正在下载 ${item.name}…"
                val handles = MihonExtensionManager.downloadAndLoad(item)
                if (handles.isEmpty()) {
                    _message.value = "${item.name} 没有可用源"
                    return@launch
                }
                _mihonLoaded.value = handles.map { it.source.name }
                setMihonLoadedPackages(_mihonLoadedPackages.value + handles.map { it.packageName })
                val jsons = handles.map { MihonExtensionManager.sourceJson(it) }
                val sub = "mihon://${item.packageName}"
                val oldIds = sources.value.filter { it.subscribeUrl == sub }.map { it.id }
                if (oldIds.isNotEmpty()) { repo.removeBookSources(oldIds); pruneNetCheck(oldIds) }
                addSourceFromJson(
                    jsons.joinToString(",", "[", "]"),
                    subscribeUrl = sub,
                    nameOverride = "",
                    subscribeNameOverride = item.name,
                )
            } catch (e: Exception) {
                _message.value = "Mihon 扩展导入失败：${e.message}"
            } finally {
                _mihonBusy.value = false
            }
        }
    }

    /**
     * 一键导入商店里尚未加入的全部 Mihon 扩展。
     * 逐个下载加载，全部成功后合并成一次书源写入，避免每导入一个就重刷整页。
     */
    fun importAllMihonExtensions() {
        viewModelScope.launch(Dispatchers.IO) {
            _mihonBusy.value = true
            try {
                val pending = _mihonStore.value.filter { it.packageName !in _mihonLoadedPackages.value }
                if (pending.isEmpty()) {
                    _message.value = "商店扩展已全部导入"
                    return@launch
                }
                val jsons = ArrayList<String>()
                val packages = LinkedHashSet<String>()
                var failed = 0
                pending.forEachIndexed { index, item ->
                    _message.value = "正在导入 ${index + 1}/${pending.size}：${item.name}…"
                    val handles = runCatching { MihonExtensionManager.downloadAndLoad(item) }
                        .getOrDefault(emptyList())
                    if (handles.isEmpty()) {
                        failed++
                    } else {
                        packages += handles.map { it.packageName }
                        jsons += handles.map { MihonExtensionManager.sourceJson(it) }
                    }
                }
                if (jsons.isEmpty()) {
                    _message.value = "商店扩展导入失败（$failed 个失败）"
                    return@launch
                }
                setMihonLoadedPackages(_mihonLoadedPackages.value + packages)
                addSourceFromJson(
                    jsons.joinToString(",", "[", "]"),
                    subscribeUrl = "mihon://store-all",
                    nameOverride = "",
                    subscribeNameOverride = "Mihon 商店扩展",
                )
                _message.value = "已导入 ${packages.size} 个扩展" +
                    if (failed > 0) "（$failed 个失败）" else ""
            } catch (e: Exception) {
                _message.value = "商店扩展导入失败：${e.message}"
            } finally {
                _mihonBusy.value = false
            }
        }
    }

    /**
     * 网络导入：从 URL 拉取书源 JSON 文本，再走 addSourceFromJson。
     * 用 java.net.HttpURLConnection，避免新增网络库依赖。支持 http/https。
     */
    fun addSourceFromUrl(url: String, nameOverride: String = "") {
        val u = url.trim()
        if (u.isEmpty()) { _message.value = "请输入书源 URL"; return }
        viewModelScope.launch(Dispatchers.IO) {
            _message.value = "正在下载书源…"
            try {
                val text = fetchText(u)
                if (text.isNullOrBlank()) { _message.value = "下载内容为空"; return@launch }
                addSourceFromJson(text, subscribeUrl = u, nameOverride = nameOverride)
            } catch (e: Exception) {
                _message.value = "下载失败：${e.message}"
            }
        }
    }

    private fun fetchText(urlStr: String): String {
        val conn = (java.net.URL(urlStr).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
            connectTimeout = 20000
            // 大订阅（本例 970 源 / 约 5MB）在真机移动网络下读满需要时间，20s 太紧。
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) YGComic")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            // 服务器可能回 gzip，必须显式解压，否则拿到的是压缩字节→乱码→切分数量锐减。
            val gzip = conn.contentEncoding?.equals("gzip", ignoreCase = true) == true
            val bytes = stream?.use { s ->
                if (gzip) java.util.zip.GZIPInputStream(s).use { it.readBytes() } else s.readBytes()
            } ?: ByteArray(0)
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            // 编码只看响应头 charset，默认 UTF-8。
            // 绝不能用「UTF-8 解出 \uFFFD 就把整份文件退 GBK」这种探测：5MB 文本里只要有一个
            // 非法字节就把整份订阅解成乱码，导致切分出的对象数锐减、逐条 parse 大面积失败
            // （用户看到的「成功80失败60」正是这种症状）。
            val ct = conn.contentType.orEmpty()
            val charsetName = Regex("charset\\s*=\\s*([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(ct)?.groupValues?.get(1) ?: "UTF-8"
            val decoded = runCatching { String(bytes, charset(charsetName)) }
                .getOrElse { String(bytes, Charsets.UTF_8) }
            _message.value = "已下载 ${bytes.size / 1024} KB，正在解析…"
            return decoded
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 删除源后，把「已加入」里已经没有源支撑的 Mihon 扩展包名剔除，
     * 避免商店/仓库弹窗对已删插件仍显示蓝色「✓ 已加入」。
     * 包名取每条源 json 里的 packageName（sourceJson() 写入），不用 subscribeUrl，
     * 这样 builtin/installed/store-all 这类聚合订阅也能按真实包名精确剔除。
     * 必须在 repo 删除前用 sources 快照计算——删除是异步的，删后 flow 刷新有延迟。
     * 只处理「该包名已无剩余源」的情况，一个插件多源时删掉其中一个不会误剔除。
     */
    private fun pruneMihonLoadedPackages(removedIds: List<Long>) {
        if (removedIds.isEmpty()) return
        val snapshot = sources.value
        val removedSet = removedIds.toSet()
        fun pkgOf(json: String): String? = runCatching {
            org.json.JSONObject(json).optString("packageName")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val affected = snapshot.filter { it.id in removedSet }.mapNotNull { pkgOf(it.json) }.toSet()
        if (affected.isEmpty()) return
        val stillPresent = snapshot.filter { it.id !in removedSet }.mapNotNull { pkgOf(it.json) }.toSet()
        val stale = affected - stillPresent
        if (stale.isNotEmpty()) setMihonLoadedPackages(_mihonLoadedPackages.value - stale)
    }

    fun removeSource(id: Long) {
        viewModelScope.launch {
            try {
                pruneMihonLoadedPackages(listOf(id))
                repo.removeBookSource(id); pruneNetCheck(listOf(id))
            } catch (_: Exception) {}
        }
    }

    fun removeSources(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                pruneMihonLoadedPackages(ids)
                repo.removeBookSources(ids); pruneNetCheck(ids)
            } catch (_: Exception) {}
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { try { repo.setBookSourceEnabled(id, enabled) } catch (_: Exception) {} }
    }

    // 搜索进度
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    private var searchJob: kotlinx.coroutines.Job? = null

    fun cancelSearch() {
        searchJob?.cancel()
        _searching.value = false
    }

    /** 切项目时清空搜索结果，避免上一项目的搜索结果串到新项目。 */
    fun clearSearch() {
        searchJob?.cancel()
        _searching.value = false
        _results.value = emptyList()
    }

    /**
     * 在所有启用的书源里搜索关键词——流式：
     * 每个书源一个协程并发抓，谁先返回就先追加到结果列表（边搜边出），不干等全部完成。
     */
    fun search(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searching.value = true
            _results.value = emptyList()
            _message.value = ""
            val enabled = sources.value.filter { it.enabled }
            if (enabled.isEmpty()) {
                _message.value = "还没有启用的书源，请先添加"
                _searching.value = false
                return@launch
            }
            _progress.value = 0 to enabled.size
            // 照 legado：并发上限 12 + 单源 30s 超时，避免多源并发爆炸卡顿。
            val semaphore = Semaphore(12)
            // 用互斥锁保护结果列表的追加，保证每来一批就刷新 UI。
            val lock = Mutex()
            var done = 0
            // 与搜索页 SearchViewModel 一致：记录本次每个源是否命中，搜完批量落盘权重。
            // 否则书源页自带搜索框搜再多，书源权重也一直 0/0。
            val hitIds = HashSet<Long>()
            val jobs = enabled.map { src ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        // runCatching 必须在 withTimeout 外层，超时异常不得逃逸取消父协程，
                        // 否则 jobs.join 中断、recordSearchStats 永不执行（权重全 0）。
                        val books = runCatching {
                            withTimeout(30_000L) { SourceRepository.search(src.json, kw, 1) }
                        }.getOrNull()?.getOrNull().orEmpty()
                        // 逐源实时写权重：本源一搜完立即 useCount+1（命中再 hitCount+1），不等全部跑完。
                        runCatching { repo.recordStatLive(src.id, books.isNotEmpty()) }
                        if (books.isNotEmpty()) {
                            lock.withLock {
                                hitIds.add(src.id)
                                _results.value = _results.value + books.map { SourcedBook(it, src) }
                            }
                        }
                        lock.withLock {
                            done += 1
                            _progress.value = done to enabled.size
                        }
                    }
                }
            }
            jobs.forEach { runCatching { it.join() } }
            // 权重已改为逐源实时写（见上方 recordStatLive），此处不再批量写，避免重复计数。
            if (_results.value.isEmpty()) _message.value = "没有搜到结果"
            _searching.value = false
        }
    }

    /** 加入书架：先取详情拿 tocUrl（若搜索结果里没有），再落库 */
    fun addToShelf(sb: SourcedBook) {
        viewModelScope.launch(Dispatchers.IO) {
            // 已在书架则不再重复请求详情/落库（importNetworkBook 虽已按 shelfKey 去重，
            // 这里提前挡掉可免去无谓的网络 detail 调用）。
            if (shelfKey(sb.book.name) in _addedKeys.value) {
                _message.value = "已在书架：${sb.book.name}"
                return@launch
            }
            try {
                val detail = SourceRepository.detail(sb.source.json, sb.book.bookUrl).getOrNull()
                // detail 失败(源站失效/POST 详情页)时用搜索结果的 bookUrl 回退当目录地址，
                // Rust detail 本就在 toc_url 空时回退 book_url，避免取不到详情就拒绝加入。
                val tocUrl = detail?.tocUrl?.ifBlank { null } ?: sb.book.bookUrl.ifBlank { null }
                if (tocUrl == null) {
                    _message.value = "无法获取目录地址，加入失败"
                    return@launch
                }
                val mediaType = if (sb.source.sourceType == 2 && ComicSourceRule.isComicSource(sb.source.name, sb.source.url)) {
                    MediaType.COMIC
                } else {
                    MediaType.NOVEL
                }
                repo.importNetworkBook(
                    sourceId = sb.source.id,
                    sourceJson = sb.source.json,
                    sourceName = sb.source.name,
                    title = sb.book.name,
                    author = sb.book.author,
                    bookUrl = sb.book.bookUrl,
                    tocUrl = tocUrl,
                    coverUrl = detail?.coverUrl?.ifBlank { null } ?: sb.book.coverUrl,
                    mediaType = mediaType,
                )
                _message.value = "已加入书架：${sb.book.name}"
            } catch (e: Exception) {
                _message.value = "加入失败：${e.message}"
            }
        }
    }
}
