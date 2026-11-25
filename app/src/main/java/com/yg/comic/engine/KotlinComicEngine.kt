package com.yg.comic.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import kotlin.math.max

/**
 * ComicEngine 的 Kotlin/Android 实现。
 * 所有耗时操作在 Dispatchers.IO；不递归解压嵌套压缩包；只读图片，遇到非图片跳过。
 */
class KotlinComicEngine(private val context: Context) : ComicEngine {

    companion object {
        private const val MAX_IMAGE_BYTES = 60L * 1024 * 1024   // 单图上限，防炸弹
        private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024 // 单本总量上限
        private val GBK: Charset = Charset.forName("GBK")
    }

    private val cacheDir = File(context.cacheDir, "decode").apply { mkdirs() }
    private val tempDir = File(context.cacheDir, "temp_import").apply { mkdirs() }

    override suspend fun probe(source: EngineSource): SourceKind = withContext(Dispatchers.IO) {
        when (source) {
            is EngineSource.FilePath -> probeName(source.path.substringAfterLast('/'))
            is EngineSource.AndroidUri -> probeName(source.displayName)
        }
    }

    private fun probeName(name: String): SourceKind = when {
        name.endsWith(".zip", true) -> SourceKind.ZIP
        name.endsWith(".7z", true) -> SourceKind.SEVENZ
        name.endsWith(".rar", true) -> SourceKind.RAR
        name.endsWith(".pdf", true) -> SourceKind.PDF
        name.endsWith(".txt", true) -> SourceKind.TXT
        else -> SourceKind.FOLDER
    }

    override suspend fun listCandidates(source: EngineSource, password: String?): List<Candidate> =
        withContext(Dispatchers.IO) {
            when (val kind = probe(source)) {
                SourceKind.ZIP -> listZipCandidates(source, password)
                SourceKind.PDF -> {
                    // 探测阶段读真实页数；读不到兜底 1，绝不让候选显示"约 0 页"
                    val ref = when (source) {
                        is EngineSource.FilePath -> source.path
                        is EngineSource.AndroidUri -> source.uri.toString()
                    }
                    val pageCount = PdfLive.pageCount(context, ref).coerceAtLeast(1)
                    listOf(Candidate("pdf_root", "整本 PDF", SourceKind.PDF, "", pageCount))
                }
                SourceKind.RAR, SourceKind.SEVENZ ->
                    listOf(Candidate("archive_root", "整本压缩包", kind))
                SourceKind.TXT -> {
                    val ref = when (source) {
                        is EngineSource.FilePath -> source.path
                        is EngineSource.AndroidUri -> source.uri.toString()
                    }
                    val count = TxtLive.pageCount(context, ref).coerceAtLeast(1)
                    listOf(Candidate("txt_root", "整本 TXT（约 $count 页）", SourceKind.TXT, ref, count))
                }
                else -> listFolderCandidates(source)
            }
        }

    // ---------- ZIP：兼容 GBK 文件名，按顶层目录拆分 ----------
    private fun listZipCandidates(source: EngineSource, password: String?): List<Candidate> {
        val file = resolveToTempFile(source) ?: return emptyList()
        val headers = readZipHeaders(file, password)
        val imageHeaders = headers.filter { !it.isDirectory && isImage(decodeName(it.fileName)) }
        val topDirs = imageHeaders.map { h ->
            decodeName(h.fileName).substringBefore('/').let { if (it.contains('.')) "" else it }
        }.filter { it.isNotEmpty() }.distinct().sortedWith(NaturalOrder)
        return if (topDirs.size >= 2) {
            topDirs.map { dir ->
                val count = imageHeaders.count { decodeName(it.fileName).startsWith("$dir/") }
                Candidate("zip:$dir", dir, SourceKind.ZIP, "$dir/", count)
            }
        } else {
            listOf(Candidate("zip:root", "整本 ZIP", SourceKind.ZIP, "", imageHeaders.size))
        }
    }

    private data class ZHeader(val fileName: String, val isDirectory: Boolean, val size: Long, val raw: FileHeader?)

    /** zip4j 优先（支持加密/GBK），失败退回 JDK ZipInputStream */
    private fun readZipHeaders(file: File, password: String?): List<ZHeader> = try {
        val zip = if (password.isNullOrEmpty()) ZipFile(file) else ZipFile(file, password.toCharArray())
        zip.fileHeaders.map { h ->
            ZHeader(h.fileName, h.isDirectory, h.uncompressedSize, h)
        }
    } catch (e: Exception) {
        val out = mutableListOf<ZHeader>()
        ZipInputStream(file.inputStream(), GBK).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                out += ZHeader(e.name, e.isDirectory, e.size, null)
                zis.closeEntry(); e = zis.nextEntry
            }
        }
        out
    }

    private fun decodeName(name: String): String {
        // JDK 在 UTF-8 flag 缺失时会按 GBK 误读，这里检测乱字符后尝试修正
        val hasCjkGarbage = name.any { it.code in 0x00C0..0x00FF }
        if (!hasCjkGarbage) return name
        return try {
            String(name.toByteArray(Charsets.ISO_8859_1), GBK)
        } catch (e: Exception) {
            name
        }
    }

    // ---------- 文件夹 / 图片目录：图片按子目录拆分，PDF/压缩包每个文件一本 ----------
    private fun fileKind(name: String): SourceKind = when {
        name.endsWith(".zip", true) -> SourceKind.ZIP
        name.endsWith(".7z", true) -> SourceKind.SEVENZ
        name.endsWith(".rar", true) -> SourceKind.RAR
        name.endsWith(".pdf", true) -> SourceKind.PDF
        name.endsWith(".txt", true) -> SourceKind.TXT
        else -> SourceKind.UNKNOWN
    }

    private fun listFolderCandidates(source: EngineSource): List<Candidate> = when (source) {
        is EngineSource.FilePath -> {
            val root = File(source.path)
            val all = root.listFiles() ?: emptyArray()
            val out = mutableListOf<Candidate>()
            val subDirs = all.filter { it.isDirectory }
                .filter { d -> d.listFiles { f -> f.isFile && isImage(f.name) }?.isNotEmpty() == true }
                .sortedWith(compareBy(NaturalOrder) { it.name })
            subDirs.forEach { d ->
                val n = d.listFiles { f -> f.isFile && isImage(f.name) }?.size ?: 0
                out += Candidate("dir:${d.name}", d.name, SourceKind.IMAGE_DIR, d.name, n)
            }
            if (subDirs.isEmpty() && all.any { it.isFile && isImage(it.name) }) {
                val n = all.count { it.isFile && isImage(it.name) }
                out += Candidate("dir:root", root.name, SourceKind.IMAGE_DIR, "", n)
            }
            // 根目录直属 PDF / 压缩包：每个文件一个候选（支持多格式混排）
            all.filter { it.isFile && fileKind(it.name) != SourceKind.UNKNOWN }
                .sortedWith(compareBy(NaturalOrder) { it.name })
                .forEach { p ->
                    val k = fileKind(p.name)
                    out += Candidate("${k.name.lowercase()}:${p.absolutePath}", p.name, k,
                        p.absolutePath, isFileCandidate = true)
                }
            if (out.isEmpty()) out += Candidate("dir:root", root.name, SourceKind.IMAGE_DIR, "")
            out
        }
        is EngineSource.AndroidUri -> {
            // SAF 目录：用 DocumentFile 列出子目录 + 直属 PDF/压缩包
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, source.uri)
            val all = root?.listFiles() ?: emptyArray()
            val out = mutableListOf<Candidate>()
            val subs = all.filter { it.isDirectory && it.listFiles().any { c -> c.isFile && isImage(c.name ?: "") } }
                .sortedWith(compareBy(NaturalOrder) { it.name ?: "" })
            subs.forEach { d ->
                val n = d.listFiles().count { c -> c.isFile && isImage(c.name ?: "") }
                out += Candidate("saf:${d.uri}", d.name ?: "子目录", SourceKind.IMAGE_DIR, d.uri.toString(), n)
            }
            if (subs.isEmpty() && all.any { it.isFile && isImage(it.name ?: "") }) {
                val n = all.count { it.isFile && isImage(it.name ?: "") }
                out += Candidate("saf:root", source.displayName, SourceKind.IMAGE_DIR, "", n)
            }
            all.filter { it.isFile && fileKind(it.name ?: "") != SourceKind.UNKNOWN }
                .sortedWith(compareBy(NaturalOrder) { it.name ?: "" })
                .forEach { p ->
                    val k = fileKind(p.name ?: "")
                    out += Candidate("${k.name.lowercase()}:${p.uri}", p.name ?: k.name, k,
                        p.uri.toString(), isFileCandidate = true)
                }
            if (out.isEmpty()) out += Candidate("saf:root", source.displayName, SourceKind.IMAGE_DIR, "")
            out
        }
    }

    // ---------- 导入候选到本地库 ----------
    override suspend fun importCandidate(
        source: EngineSource,
        candidate: Candidate,
        password: String?,
        destDir: File,
        onProgress: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean,
    ): List<String> = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val written = mutableListOf<String>()
        var totalBytes = 0L
        // 文件夹里扫描到的具体文件候选：将其 entryPrefix（路径或 content:// uri）包装成独立 source
        val fileSource: EngineSource? = if (candidate.isFileCandidate && candidate.entryPrefix.isNotBlank()) {
            when {
                candidate.entryPrefix.startsWith("content://") ->
                    EngineSource.AndroidUri(Uri.parse(candidate.entryPrefix), candidate.label)
                candidate.entryPrefix.startsWith("/") && File(candidate.entryPrefix).exists() ->
                    EngineSource.FilePath(candidate.entryPrefix)
                else -> null
            }
        } else null
        when (candidate.kind) {
            SourceKind.PDF -> importPdf(source, candidate.entryPrefix, destDir, written, onProgress, isCancelled)
            SourceKind.RAR -> importRar(fileSource ?: source, password, destDir, written, onProgress, isCancelled) { totalBytes += it }
            SourceKind.SEVENZ -> importSevenZ(fileSource ?: source, password, destDir, written, onProgress, isCancelled) { totalBytes += it }
            SourceKind.ZIP -> {
                val zs = fileSource ?: source
                val zf = resolveToTempFile(zs)
                if (zf != null && zf.extension.equals("zip", true)) {
                    // fileSource 来自文件夹扫描 → 整包无前缀；直接选中的 zip → 用拆分前缀
                    val prefix = if (fileSource != null) "" else candidate.entryPrefix
                    importZipFile(zf, prefix, password, destDir, written, onProgress, isCancelled) { totalBytes += it }
                }
            }
            else -> importZipOrFolder(source, candidate, password, destDir, written, onProgress, isCancelled) { totalBytes += it }
        }
        written.sortedWith(NaturalOrder)
    }

    private fun copySafe(input: InputStream, out: File, remaining: () -> Long): Boolean {
        if (out.exists() && out.length() > 0) return true
        val tmp = File(out.parentFile, "${out.name}.part")
        var written = 0L
        try {
            input.use { ins -> FileOutputStream(tmp).use { fos ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (ins.read(buf).also { n = it } > 0) {
                    written += n
                    if (written > MAX_IMAGE_BYTES || written > remaining()) {
                        tmp.delete(); return false
                    }
                    fos.write(buf, 0, n)
                }
            } }
            if (!tmp.renameTo(out)) { tmp.delete(); return false }
            return true
        } catch (e: Exception) {
            tmp.delete(); return false
        }
    }

    private fun importZipOrFolder(
        source: EngineSource, candidate: Candidate, password: String?, destDir: File,
        written: MutableList<String>, onProgress: (Int, Int) -> Unit, isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
    ) {
        val prefix = candidate.entryPrefix
        when (source) {
            is EngineSource.AndroidUri -> {
                val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, source.uri)
                val files = if (prefix.startsWith("content://")) {
                    val d = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, Uri.parse(prefix))
                    d?.listFiles() ?: emptyArray()
                } else {
                    (root?.listFiles() ?: emptyArray()).filter {
                        prefix.isEmpty() || (it.name ?: "").startsWith(prefix)
                    }.toTypedArray()
                }
                val images = files.filter { it.isFile && isImage(it.name ?: "") }
                images.forEachIndexed { idx, f ->
                    if (isCancelled()) throw CancelledException()
                    val out = File(destDir, String.format("%05d_%s", idx, sanitize(f.name ?: "img.jpg")))
                    val ins = context.contentResolver.openInputStream(f.uri)
                    if (ins != null && copySafe(ins, out) { MAX_TOTAL_BYTES - onBytesTotal(written, destDir) }) {
                        onBytes(out.length()); written += out.absolutePath
                    }
                    onProgress(idx + 1, images.size)
                }
            }
            is EngineSource.FilePath -> {
                val f = File(source.path)
                if (f.isDirectory) {
                    val dir = if (prefix.isNotEmpty()) File(f, prefix) else f
                    val images = (dir.listFiles { x -> x.isFile && isImage(x.name) } ?: emptyArray())
                        .sortedWith(compareBy(NaturalOrder) { it.name })
                    images.forEachIndexed { idx, img ->
                        if (isCancelled()) throw CancelledException()
                        val out = File(destDir, String.format("%05d_%s", idx, sanitize(img.name)))
                        if (copySafe(img.inputStream(), out) { MAX_TOTAL_BYTES - onBytesTotal(written, destDir) }) {
                            onBytes(out.length()); written += out.absolutePath
                        }
                        onProgress(idx + 1, images.size)
                    }
                } else if (f.extension.equals("zip", true)) {
                    importZipFile(f, prefix, password, destDir, written, onProgress, isCancelled, onBytes)
                }
            }
        }
    }

    private fun onBytesTotal(written: List<String>, dir: File): Long =
        written.map { File(it).length() }.sum() + dir.walkTopDown().filter { it.extension == "part" }.map { it.length() }.sum()

    private fun importZipFile(
        file: File, prefix: String, password: String?, destDir: File,
        written: MutableList<String>, onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean, onBytes: (Long) -> Unit,
    ) {
        val headers = readZipHeaders(file, password).filter {
            !it.isDirectory && isImage(decodeName(it.fileName)) && decodeName(it.fileName).startsWith(prefix)
        }
        headers.forEachIndexed { idx, h ->
            if (isCancelled()) throw CancelledException()
            val out = File(destDir, String.format("%05d_%s", idx, sanitize(decodeName(h.fileName).substringAfterLast('/'))))
            val ok = if (h.raw != null && !password.isNullOrEmpty()) {
                val zip = ZipFile(file, password.toCharArray())
                val ins = zip.getInputStream(h.raw)
                copySafe(ins, out) { MAX_TOTAL_BYTES - onBytesTotal(written, destDir) }
            } else {
                val ins = openZipEntryByName(file, h.fileName, password)
                if (ins != null) copySafe(ins, out) { MAX_TOTAL_BYTES - onBytesTotal(written, destDir) } else false
            }
            if (ok) { onBytes(out.length()); written += out.absolutePath }
            onProgress(idx + 1, headers.size)
        }
    }

    private fun openZipEntryByName(file: File, name: String, password: String?): InputStream? {
        val zip = if (password.isNullOrEmpty()) ZipFile(file) else ZipFile(file, password.toCharArray())
        return zip.getFileHeader(name)?.let { zip.getInputStream(it) }
    }

    private fun importRar(
        source: EngineSource, password: String?, destDir: File,
        written: MutableList<String>, onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean, onBytes: (Long) -> Unit,
    ) {
        val file = resolveToTempFile(source) ?: return
        try {
            val rar = com.github.junrar.Archive(file)
            val headers = rar.fileHeaders.filter { !it.isDirectory && isImage(it.fileName) }
            headers.forEachIndexed { idx, h ->
                if (isCancelled()) throw CancelledException()
                val out = File(destDir, String.format("%05d_%s", idx, sanitize(h.fileName.substringAfterLast('\\').substringAfterLast('/'))))
                val ins = rar.getInputStream(h)
                if (copySafe(ins, out) { MAX_TOTAL_BYTES - onBytesTotal(written, destDir) }) {
                    onBytes(out.length()); written += out.absolutePath
                }
                onProgress(idx + 1, headers.size)
            }
            rar.close()
        } catch (e: Exception) { /* 损坏 rar 跳过，由上层汇总 */ }
    }

    private fun importSevenZ(
        source: EngineSource, password: String?, destDir: File,
        written: MutableList<String>, onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean, onBytes: (Long) -> Unit,
    ) {
        val file = resolveToTempFile(source) ?: return
        try {
            val sz = org.apache.commons.compress.archivers.sevenz.SevenZFile.Builder()
                .setFile(file)
                .setPassword(password?.toCharArray())
                .get()
            val entries = mutableListOf<org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry>()
            var e = sz.nextEntry
            while (e != null) { if (!e.isDirectory && isImage(e.name)) entries += e; e = sz.nextEntry }
            // SevenZFile 需按序号重新打开读取
            entries.forEachIndexed { idx, entry ->
                if (isCancelled()) throw CancelledException()
                val reader = org.apache.commons.compress.archivers.sevenz.SevenZFile.Builder()
                    .setFile(file).setPassword(password?.toCharArray()).get()
                var cur = reader.nextEntry
                var found = false
                while (cur != null) {
                    if (cur.name == entry.name && !cur.isDirectory) { found = true; break }
                    cur = reader.nextEntry
                }
                if (found) {
                    val out = File(destDir, String.format("%05d_%s", idx, sanitize(entry.name.substringAfterLast('/'))))
                    if (copySafe(reader.getInputStream(cur), out) { MAX_TOTAL_BYTES - onBytesTotal(written, destDir) }) {
                        onBytes(out.length()); written += out.absolutePath
                    }
                }
                reader.close()
                onProgress(idx + 1, entries.size)
            }
            sz.close()
        } catch (e: Exception) { /* 跳过 */ }
    }

    private fun importPdf(
        source: EngineSource, entryPrefix: String, destDir: File, written: MutableList<String>,
        onProgress: (Int, Int) -> Unit, isCancelled: () -> Boolean,
    ) {
        // entryPrefix 非空 = 文件夹里扫描到的某个具体 PDF（content:// uri 或绝对文件路径）；
        // 为空 = 直接选中的单个 PDF 文件，用 source 本身打开。
        val pfd: ParcelFileDescriptor? = when {
            entryPrefix.startsWith("content://") -> context.contentResolver.openFileDescriptor(Uri.parse(entryPrefix), "r")
            entryPrefix.startsWith("/") && File(entryPrefix).exists() ->
                ParcelFileDescriptor.open(File(entryPrefix), ParcelFileDescriptor.MODE_READ_ONLY)
            else -> when (source) {
                is EngineSource.AndroidUri -> context.contentResolver.openFileDescriptor(source.uri, "r")
                is EngineSource.FilePath -> ParcelFileDescriptor.open(File(source.path), ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        if (pfd == null) return
        val renderer = try { PdfRenderer(pfd) } catch (e: Exception) {
            android.util.Log.e("YGComic", "PdfRenderer init failed: ${e.message}")
            pfd.close(); return
        }
        try {
            val count = renderer.pageCount
            for (i in 0 until count) {
                if (isCancelled()) throw CancelledException()
                val page = renderer.openPage(i)
                val scale = 2f
                val w = (page.width * scale).toInt().coerceIn(1, 2400)
                val h = (page.height * scale).toInt().coerceIn(1, 3200)
                // PdfRenderer 原生层要求 ARGB_8888；RGB_565 会抛 "Unsupported bitmap config"
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = File(destDir, String.format("%05d_p%03d.jpg", i, i))
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bmp.recycle(); page.close()
                written += out.absolutePath
                onProgress(i + 1, count)
            }
        } catch (e: Exception) {
            android.util.Log.e("YGComic", "PDF render failed at page: ${e.message}", e)
            throw e
        } finally {
            try { renderer.close() } catch (e: Exception) {}
            try { pfd.close() } catch (e: Exception) {}
        }
    }

    // ---------- PDF 实时导入：不复制原文件，只登记引用；观看器直接打开原文件按页渲染 ----------
    override suspend fun importPdfLive(
        source: EngineSource, entryPrefix: String, destDir: File,
    ): PdfLiveBook = withContext(Dispatchers.IO) {
        val context = this@KotlinComicEngine.context
        // 原文件引用：content:// Uri 直接读，普通路径直接读。绝不复制整本 PDF。
        val ref: String = when {
            entryPrefix.startsWith("content://") -> entryPrefix
            entryPrefix.startsWith("/") && File(entryPrefix).exists() -> entryPrefix
            else -> when (source) {
                is EngineSource.FilePath -> source.path
                is EngineSource.AndroidUri -> source.uri.toString()
            }
        }
        if (ref.isBlank()) return@withContext PdfLiveBook("", null, 1)

        // 页数实时读取：拿不到兜底 1，绝不让书显示 0 页或从书架消失。
        var count = PdfLive.pageCount(context, ref)
        if (count <= 0) count = 1

        // 封面可选：渲染首页存到库缓存目录；失败就用占位（书依然在书架）。
        var coverPath: String? = null
        destDir.mkdirs()
        val coverBmp = PdfLive.render(context, ref, 0, 480, 720)
        if (coverBmp != null) {
            val cf = File(destDir, "00000_cover.jpg")
            try {
                FileOutputStream(cf).use { coverBmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                coverPath = cf.absolutePath
            } catch (_: Exception) {}
            coverBmp.recycle()
        }
        PdfLiveBook(ref, coverPath, count)
    }

    // ---------- 工具 ----------
    private fun EngineSource.displayNameOr(fallback: String): String = when (this) {
        is EngineSource.AndroidUri -> displayName.ifBlank { fallback }
        is EngineSource.FilePath -> File(path).name.ifBlank { fallback }
    }

    @Synchronized
    private fun resolveToTempFile(source: EngineSource): File? = when (source) {
        is EngineSource.FilePath -> File(source.path)
        is EngineSource.AndroidUri -> {
            // 文件名带 uri 哈希：并行导入时不同来源绝不复用同一临时文件；@Synchronized 消除同来源竞态
            val name = sanitize(source.displayName) + "_" + source.uri.toString().hashCode()
            val out = File(tempDir, name)
            if (out.exists() && out.length() > 0) return out
            val ins = context.contentResolver.openInputStream(source.uri) ?: return null
            if (copySafe(ins, out) { Long.MAX_VALUE }) out else null
        }
    }

    private fun sanitize(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)

    override suspend fun decodeSampled(path: String, reqWidth: Int, reqHeight: Int): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                var sample = 1
                if (reqWidth > 0 && reqHeight > 0) {
                    val h = max(opts.outHeight / reqHeight, opts.outWidth / reqWidth)
                    sample = max(1, h)
                }
                val out = File(cacheDir, "${path.hashCode()}_${sample}.jpg")
                if (!out.exists()) {
                    val dec = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
                    val bmp = BitmapFactory.decodeFile(path, dec) ?: throw IllegalArgumentException("decode failed")
                    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle()
                }
                out.absolutePath
            }
        }

    override fun releaseIdle() {
        cacheDir.listFiles()?.forEach { it.delete() }
        tempDir.listFiles()?.forEach { it.delete() }
    }
}
