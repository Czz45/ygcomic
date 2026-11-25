package com.yg.comic.ui.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yg.comic.data.BookRepository
import com.yg.comic.engine.CancelledException
import com.yg.comic.engine.Candidate
import com.yg.comic.engine.EngineSource
import com.yg.comic.engine.SourceKind
import com.yg.comic.engine.TxtLive
import com.yg.comic.engine.PasswordVault
import com.yg.comic.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

enum class ImportStep { PICK, CONFIRM, IMPORTING, DONE }

/** 导入时的归类方式：根目录 / 自动以来源名新建 / 自定义名新建 / 归入已有合集 */
enum class CategoryMode { ROOT, AUTO_NEW, CUSTOM_NEW, EXISTING }

class ImportViewModel(private val repo: BookRepository) : ViewModel() {

    private val _step = MutableStateFlow(ImportStep.PICK)
    val step: StateFlow<ImportStep> = _step.asStateFlow()

    private val _candidates = MutableStateFlow<List<Candidate>>(emptyList())
    val candidates: StateFlow<List<Candidate>> = _candidates.asStateFlow()

    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    // ---- 分类（归入合集）----
    private val _collections = MutableStateFlow<List<ComicCollection>>(emptyList())
    val collections: StateFlow<List<ComicCollection>> = _collections.asStateFlow()
    private val _categoryMode = MutableStateFlow(CategoryMode.ROOT)
    val categoryMode: StateFlow<CategoryMode> = _categoryMode.asStateFlow()
    private val _existingCollectionId = MutableStateFlow<Long?>(null)
    val existingCollectionId: StateFlow<Long?> = _existingCollectionId.asStateFlow()
    /** 自定义新分类名（CUSTOM_NEW 时用） */
    private val _customName = MutableStateFlow("")
    val customName: StateFlow<String> = _customName.asStateFlow()

    private var sources: List<EngineSource> = emptyList()
    private var names: List<String> = emptyList()
    /** 与 _candidates 一一对应：第 i 个候选来自哪个 source */
    private var candidateSources: List<EngineSource> = emptyList()
    private var importJob: Job? = null
    @Volatile private var cancelled = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _collections.value = repo.allCollections()
        }
    }

    fun setCategory(mode: CategoryMode, existingId: Long?) {
        _categoryMode.value = mode
        _existingCollectionId.value = if (mode == CategoryMode.EXISTING) existingId else null
    }

    fun setCustomName(name: String) { _customName.value = name }

    fun onSourcesPicked(uris: List<Uri>, context: Context) {
        // SAF 的 content:// Uri 尾段是 document id，不是文件名；必须查 DISPLAY_NAME。
        names = uris.map { resolveDisplayName(context, it) }
        sources = uris.mapIndexed { i, u -> EngineSource.AndroidUri(u, names[i]) }
        _displayName.value = names.joinToString("、")
        viewModelScope.launch(Dispatchers.IO) {
            val cands = mutableListOf<Candidate>()
            val owners = mutableListOf<EngineSource>()
            for (s in sources) {
                runCatching { repo.engine.listCandidates(s, null) }
                    .onSuccess { got -> got.forEach { cands += it; owners += s } }
                    .onFailure { _error.value = "部分文件解析失败：${it.message}" }
            }
            candidateSources = owners
            _candidates.value = cands
            _step.value = ImportStep.CONFIRM
        }
    }

    /**
     * 并行导入：用 Semaphore 限制同时进行的候选数，避免多个大图/PDF 同时解码导致内存积压。
     * 每个候选在独立的 async 中完成，进度按"已完成候选数"聚合，失败汇总与取消均线程安全。
     */
    fun startImport(indices: List<Int>, password: String, context: Context) {
        if (indices.isEmpty()) return
        cancelled = false
        _step.value = ImportStep.IMPORTING
        _error.value = null
        val vault = PasswordVault(context)
        val usePassword = password.ifBlank { null }
        val total = indices.size
        _progress.value = 0 to total

        importJob = viewModelScope.launch(Dispatchers.IO) {
            val failed = Collections.synchronizedList(mutableListOf<String>())
            val completed = AtomicInteger(0)
            // 并发数：核数一半，限制在 2~3；单个 PDF 页最大约 30MB，2~3 路峰值受控
            val permits = Semaphore((Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 3))

            // 先解析目标分类 id（整个批次归入同一处）
            val collectionId: Long? = when (_categoryMode.value) {
                CategoryMode.ROOT -> null
                CategoryMode.AUTO_NEW ->
                    repo.ensureCollection(_displayName.value.ifBlank { "新建分类" }, null, "import")
                CategoryMode.CUSTOM_NEW ->
                    repo.ensureCollection(_customName.value.ifBlank { "新建分类" }, null, "import")
                CategoryMode.EXISTING -> _existingCollectionId.value
            }

            coroutineScope {
                indices.map { idx ->
                    async(Dispatchers.IO) {
                        permits.acquire()
                        var createdBookId: Long? = null
                        var destDir: File? = null
                        try {
                            if (cancelled) return@async
                            val candidate = _candidates.value.getOrNull(idx) ?: return@async
                            val source = candidateSources.getOrNull(idx)
                                ?: sources.firstOrNull() ?: return@async
                            val key = "src_${candidate.id.hashCode()}"
                            val pwd = usePassword ?: vault.load(key)
                            if (!usePassword.isNullOrBlank()) vault.save(key, usePassword)

                            val bookId = repo.saveBook(
                                Book(
                                    title = candidate.label, pageCount = candidate.pageCountHint,
                                    sortField = System.currentTimeMillis(),
                                ),
                                emptyList(), collectionId,
                            )
                            createdBookId = bookId
                            val dir = repo.libraryDir().resolve("book_${bookId}_f0")
                            destDir = dir

                            // PDF 走实时导入：只复制源 PDF + 渲染首页封面，秒级完成；不逐页预渲染
                            if (candidate.kind == SourceKind.PDF) {
                                val live = runCatching {
                                    repo.engine.importPdfLive(source, candidate.entryPrefix, dir)
                                }.onFailure {
                                    if (it !is CancelledException) failed += candidate.label
                                }.getOrNull()
                                if (cancelled) {
                                    dir.deleteRecursively()
                                    repo.deleteBook(bookId)
                                    createdBookId = null
                                } else if (live == null || live.pdfPath.isBlank()) {
                                    // 关键：实时导入异常也绝不静默删书，否则书架看不到。
                                    // 用源条目兜底建一个 PDF 片段，阅读页按需渲染失败会给占位。
                                    val fallbackPath = when (source) {
                                        is EngineSource.FilePath -> source.path
                                        is EngineSource.AndroidUri -> source.uri.toString()
                                    }
                                    val frag = Fragment(
                                        bookId = bookId, kind = FragmentKind.PDF,
                                        sourcePath = fallbackPath, entryPrefix = "",
                                        orderIndex = 0, pageCount = candidate.pageCountHint.coerceAtLeast(1),
                                    )
                                    repo.saveBook(
                                        Book(
                                            id = bookId, title = candidate.label, coverPath = null,
                                            pageCount = candidate.pageCountHint.coerceAtLeast(1), sortField = bookId.toLong(),
                                        ),
                                        listOf(frag), collectionId,
                                    )
                                } else {
                                    val frag = Fragment(
                                        bookId = bookId, kind = FragmentKind.PDF,
                                        sourcePath = live.pdfPath, entryPrefix = "",
                                        orderIndex = 0, pageCount = live.pageCount.coerceAtLeast(1),
                                    )
                                    repo.saveBook(
                                        Book(
                                            id = bookId, title = candidate.label, coverPath = live.coverPath,
                                            pageCount = live.pageCount.coerceAtLeast(1), sortField = bookId.toLong(),
                                        ),
                                        listOf(frag), collectionId,
                                    )
                                }
                            } else if (candidate.kind == SourceKind.TXT) {
                                // TXT 小说实时读取：不复制原文件，只登记引用（content:// 或绝对路径）
                                val ref = candidate.entryPrefix.ifBlank {
                                    when (source) {
                                        is EngineSource.FilePath -> source.path
                                        is EngineSource.AndroidUri -> source.uri.toString()
                                    }
                                }
                                val count = runCatching { TxtLive.pageCount(repo.appContext, ref) }
                                    .getOrDefault(candidate.pageCountHint).coerceAtLeast(1)
                                val frag = Fragment(
                                    bookId = bookId, kind = FragmentKind.TXT,
                                    sourcePath = ref, entryPrefix = "",
                                    orderIndex = 0, pageCount = count,
                                )
                                repo.saveBook(
                                    Book(
                                        id = bookId, title = candidate.label, coverPath = null,
                                        mediaType = MediaType.NOVEL, pageCount = count, sortField = bookId.toLong(),
                                    ),
                                    listOf(frag), collectionId,
                                )
                            } else {
                                val paths = runCatching {
                                    repo.engine.importCandidate(
                                        source, candidate, pwd, dir,
                                        onProgress = { _, _ -> Unit }, // 并行下用聚合进度
                                        isCancelled = { cancelled },
                                    )
                                }.onFailure {
                                    if (it !is CancelledException) failed += candidate.label
                                }.getOrDefault(emptyList())

                                if (cancelled || paths.isEmpty()) {
                                    dir.deleteRecursively()
                                    repo.deleteBook(bookId)
                                    createdBookId = null
                                } else {
                                    val frag = Fragment(
                                        bookId = bookId, kind = when (candidate.kind) {
                                            SourceKind.ZIP -> FragmentKind.ZIP
                                            SourceKind.SEVENZ -> FragmentKind.SEVENZ
                                            SourceKind.RAR -> FragmentKind.RAR
                                            else -> FragmentKind.FOLDER
                                        }, sourcePath = dir.absolutePath, entryPrefix = candidate.entryPrefix,
                                        orderIndex = 0, pageCount = paths.size,
                                        passwordAlias = if (usePassword != null) key else null,
                                    )
                                    repo.saveBook(
                                        Book(
                                            id = bookId, title = candidate.label, coverPath = paths.firstOrNull(),
                                            pageCount = paths.size, sortField = bookId.toLong(),
                                        ),
                                        listOf(frag), collectionId,
                                    )
                                }
                            }
                        } catch (e: CancelledException) {
                            destDir?.deleteRecursively()
                            createdBookId?.let { repo.deleteBook(it) }
                        } catch (e: Exception) {
                            failed += (_candidates.value.getOrNull(idx)?.label ?: "未知文件")
                            destDir?.deleteRecursively()
                            createdBookId?.let { repo.deleteBook(it) }
                        } finally {
                            permits.release()
                            _progress.value = completed.incrementAndGet() to total
                        }
                    }
                }.awaitAll()
            }
            if (failed.isNotEmpty()) _error.value = failed.joinToString("、")
            _progress.value = total to total
            _step.value = ImportStep.DONE
        }
    }

    fun cancel() {
        cancelled = true
        importJob?.cancel()
    }

    fun reset() {
        _step.value = ImportStep.PICK
        _candidates.value = emptyList()
        _error.value = null
        sources = emptyList()
        names = emptyList()
        candidateSources = emptyList()
        setCategory(CategoryMode.ROOT, null)
        _customName.value = ""
    }

    companion object {
        /** 从 SAF Uri 读取真实文件名；目录 Uri 与旧设备兜底用最后一段 */
        fun resolveDisplayName(context: Context, uri: Uri): String {
            var result: String? = null
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) result = c.getString(idx)
                    }
                }
            } catch (_: Exception) {}
            if (!result.isNullOrBlank()) return result!!
            return Uri.decode(uri.toString().substringAfterLast('/')).ifBlank { "导入内容" }
        }
    }
}
