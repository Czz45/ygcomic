package com.yg.comic.engine

import android.net.Uri
import java.io.File

/**
 * 平台无关底层契约。
 * 当前由 KotlinComicEngine 实现；未来 Windows/Rust 端实现同一接口即可复用全部上层逻辑。
 * 所有耗时方法均为 suspend，不阻塞 UI。
 */
interface ComicEngine {
    /** 探测来源类型 */
    suspend fun probe(source: EngineSource): SourceKind

    /** 列出可拆分的候选片段（zip 顶层目录、文件夹子目录、PDF 单本） */
    suspend fun listCandidates(source: EngineSource, password: String?): List<Candidate>

    /** 将某个候选片段的图片按原样导入到本地库目录，返回落库后的图片绝对路径列表（已按自然序） */
    suspend fun importCandidate(
        source: EngineSource,
        candidate: Candidate,
        password: String?,
        destDir: File,
        onProgress: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean,
    ): List<String>

    /**
     * PDF 实时导入：不复制原文件，只登记原文件引用（content:// Uri 或绝对路径），
     * 阅读器直接打开原文件按需渲染。封面尝试渲染首页（失败不影响登记）。
     * 返回 PdfLiveBook(pdfPath=原文件引用, coverPath=封面 jpg 或 null, pageCount=总页数)。
     */
    suspend fun importPdfLive(
        source: EngineSource,
        entryPrefix: String,
        destDir: File,
    ): PdfLiveBook

    /** 从本地库路径加载一张采样后的图片文件（防止 OOM），返回临时文件路径 */
    suspend fun decodeSampled(path: String, reqWidth: Int, reqHeight: Int): Result<String>

    /** 释放底层闲置资源 */
    fun releaseIdle()
}

/** 跨语言/跨平台来源抽象：Android 用 Uri，Windows 用普通文件路径 */
sealed class EngineSource {
    data class AndroidUri(val uri: Uri, val displayName: String) : EngineSource()
    data class FilePath(val path: String) : EngineSource()
}

enum class SourceKind { FOLDER, ZIP, SEVENZ, RAR, PDF, IMAGE_DIR, TXT, UNKNOWN }

/** 导入阶段的一个候选“书片段” */
data class Candidate(
    val id: String,
    val label: String,
    val kind: SourceKind,
    val entryPrefix: String = "",
    val pageCountHint: Int = 0,
    /** true 表示 entryPrefix 指向文件夹内某个具体文件（压缩包/PDF），需按该文件打开而非父文件夹 */
    val isFileCandidate: Boolean = false,
)

class CancelledException : Exception("cancelled")

/** PDF 实时导入结果：pdfPath 指向原文件引用（content:// 或绝对路径），不复制；阅读时按页实时渲染 */
data class PdfLiveBook(val pdfPath: String, val coverPath: String?, val pageCount: Int)
