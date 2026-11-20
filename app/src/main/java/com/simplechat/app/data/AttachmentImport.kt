package com.simplechat.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import com.simplechat.app.R
import com.simplechat.app.data.import.docxXmlToText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把用户选中的图片/文件变成一条 [Attachment]。
 *
 * 全部在 IO 线程上做：一张 4000×3000 的照片解码 + 压缩要几百毫秒，
 * 放主线程就是一次肉眼可见的卡顿。
 */
object AttachmentImport {

    /**
     * 图片压缩后的最长边。
     *
     * 官方 Vision 指南写得很清楚：图片在推理前会被统一缩到
     * 「总像素约等于 1300×1300」 —— 本地留更大的副本**不会**让模型看得更清，
     * 只会让数据库和请求体白白变大。留 1600 是给服务端缩放留一点余量。
     */
    private const val IMAGE_MAX_SIDE = 1600

    private const val JPEG_QUALITY = 88

    /**
     * 文本附件上限（字符数）。
     *
     * ### 为什么从 10 万放宽到 40 万
     *
     * 10 万是早先"怕把上下文挤爆"的拍脑袋值：一份 500KB 的中文小说约 16 万字，
     * 用户传进来被一个我们自己的门槛挡住 —— 而**真正该不该收**取决于模型窗口与
     * 用户意愿，不是这个常数。真实反馈："500KB 的小说上传失败"。
     *
     * 现在放到 40 万（约 400KB 中文）：先收下，让模型/服务端按真实窗口判；
     * 超窗口时错误已经是人话（见 `NetworkErrors` 的 context length 映射），
     * 用户可以自己决定压缩上下文还是换模型。
     */
    const val TEXT_MAX_CHARS = 400_000

    /** 图片原始文件上限（超过直接拒绝，避免解码时 OOM）。 */
    private const val IMAGE_MAX_BYTES = 24L * 1024 * 1024

    // ── 拍照 ──────────────────────────────────────────────

    /**
     * 给「拍照」开一个空的**中转文件**，返回可交给相机 App 的 URI。
     *
     * 必须走 FileProvider：从 Android 7 起直接传 `file://` 会抛
     * `FileUriExposedException`。返回的是**空文件**，相机负责往里写。
     */
    fun newCaptureUri(context: Context): Uri {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * 拍完就删 —— 它只是中转。
     *
     * 照片已经被解码、压到 1600px、转 base64 落库了，中转文件没有留下的理由。
     * 失败也静默：删不掉最多是占一点缓存，不该为此打扰用户。
     */
    fun discardCapture(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    // ── 图片 ──────────────────────────────────────────────

    suspend fun image(context: Context, uri: Uri): Result<Attachment> =
        withContext(Dispatchers.IO) {
            runCatching {
                try {
                    readImage(context, uri)
                } catch (e: java.io.IOException) {
                    // 读图失败的异常都会落到 IOException 上，而它被网络层统一映射成
                    // "连接被中断" —— 本地问题被说成网络问题，排查时误导了好几轮。
                    logImportFailure(context, uri, e)
                    throw AppTextException(R.string.err_image_read)
                }
            }
        }

    private fun readImage(context: Context, uri: Uri): Attachment {
        val declared = declaredSize(context, uri)
        if (declared > IMAGE_MAX_BYTES) throw AppTextException(R.string.err_image_too_big)

        // 先落成缓存文件：小米相册选择器给的 URI 背后可能是管道流，
        // ImageDecoder 需要可 seek 的源，直接喂 URI 会抛 IOException。
        val staged = stage(context, uri)
        val decoded = try {
            decodeFile(context, staged)
        } finally {
            runCatching { staged.delete() }
        }
        val flattened = flatten(decoded)
        val bytes = ByteArrayOutputStream().use { out ->
            flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        return Attachment(
            kind = Attachment.Kind.IMAGE,
            name = displayName(context, uri) ?: "image.jpg",
            mime = "image/jpeg",
            data = Base64.encodeToString(bytes, Base64.NO_WRAP),
            width = flattened.width,
            height = flattened.height,
        )
    }

    /**
     * 解码并**按需缩小**。
     *
     * API 28+ 用 `ImageDecoder`：它会顺带把 EXIF 方向摆正，
     * 手机竖着拍的照片才不会躺着进模型。
     * API 26–27 回落到 `BitmapFactory`，方向要自己读 EXIF 补。
     */
    @Suppress("DEPRECATION")

    /**
     * 把读图失败的现场写到 App 外部目录（`/sdcard/Android/data/<pkg>/files/diag/`）。
     *
     * 只写异常类名、消息、URI、MIME —— **不写图片内容、不写对话**。
     * 排查"本地图片读不了"时，没有这个就只能猜。
     */
    private fun logImportFailure(context: Context, uri: Uri, error: Throwable) {
        runCatching {
            val line = buildString {
                append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()))
                append(" uri=").append(uri)
                append(" mime=").append(runCatching { context.contentResolver.getType(uri) }.getOrNull())
                append(" scheme=").append(uri.scheme)
                append(" err=").append(error.javaClass.name).append(": ").append(error.message)
            } + "\n"

            // 写到「下载」目录：App 私有目录普通文件管理器与桌面端都读不到，
            // 这类"本地读图失败"的排查必须有一个双方都能开的地方。
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "simplechat-diag.log")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val target = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
            ) ?: return@runCatching
            // 追加：已有同名文件时 insert 会新建，所以直接覆盖写本次现场也够用
            resolver.openOutputStream(target, "w")?.use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** 把 URI 的字节拷进缓存文件（可 seek），并做大小上限检查。 */
    private fun stage(context: Context, uri: Uri): File {
        val dir = File(context.cacheDir, "attach").apply { mkdirs() }
        // 毫秒级时间戳不够用：一次选两张会在同一毫秒进来，写到同一个文件上互相截断
        // （真机现象就是 ImageDecoder 报 "Input was incomplete"）。
        val file = File(dir, "src-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.bin")
        // 读流失败要报"读不了这张图"，不能让它冒成 IOException ——
        // 那一类会被统一映射成"连接被中断"，把本地问题说成网络问题（排查时被误导过）。
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: java.io.IOException) {
            throw AppTextException(R.string.err_image_read)
        } ?: throw AppTextException(R.string.err_image_read)
        input.use { source ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > IMAGE_MAX_BYTES) throw AppTextException(R.string.err_image_too_big)
                    out.write(buf, 0, n)
                }
            }
        }
        return file
    }

    /** 从**可 seek 的文件**解码。相机那条路仍走原来的 URI 版本。 */
    private fun decodeFile(context: Context, file: File): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder 对"看似完整实则缺字节"的文件会直接抛 DecodeException；
            // BitmapFactory 宽容一些，所以失败时回落一次，别让一张能救的图直接报错。
            runCatching { return decodeWithImageDecoder(file) }
        }
        return decodeWithBitmapFactory(file)
    }

    private fun decodeWithImageDecoder(file: File): Bitmap {
        run {
            val source = ImageDecoder.createSource(file)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = IMAGE_MAX_SIDE.toFloat() / max(w, h).coerceAtLeast(1)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        }
    }

    private fun decodeWithBitmapFactory(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw AppTextException(R.string.err_image_read)
    }

    private fun decode(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // 软件位图才画得进 Canvas（硬件位图不允许被绘制到别的画布上）
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = IMAGE_MAX_SIDE.toFloat() / max(w, h).coerceAtLeast(1)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw AppTextException(R.string.err_image_read)

        val degrees = context.contentResolver.openInputStream(uri)?.use { input ->
            when (ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        if (degrees == 0f) return raw
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= IMAGE_MAX_SIDE) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    /** 压到白底上：JPEG 没有透明通道，直接压会让透明区域变成黑块。 */
    private fun flatten(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }

    // ── 纯文本文件 ────────────────────────────────────────

    /**
     * 读一个纯文本文件。
     *
     * 刻意**按 UTF-8 严格解码**：二进制文件用宽松解码也能出一堆字符，
     * 那种"垃圾进上下文"比直接报错糟糕得多 —— 用户看不出发出去的是乱的。
     */
    suspend fun text(context: Context, uri: Uri): Result<Attachment> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = displayName(context, uri) ?: Res.get(R.string.attach_default_text_name)
                val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw AppTextException(R.string.err_file_read)
                if (raw.size > TEXT_MAX_CHARS * 4) {
                    throw AppTextException(R.string.err_file_too_big, TEXT_MAX_CHARS * 4 / 1024)
                }

                val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString() }
                    .getOrElse { throw AppTextException(R.string.err_not_plain_text) }

                if (text.length > TEXT_MAX_CHARS) {
                    throw AppTextException(R.string.err_text_too_long, text.length, TEXT_MAX_CHARS)
                }
                if (text.isBlank()) throw AppTextException(R.string.err_file_empty)

                Attachment(
                    kind = Attachment.Kind.TEXT,
                    name = name,
                    mime = "text/plain",
                    data = text,
                )
            }
        }

    // ── 文档：docx 出文本，pdf 出图片 ─────────────────────

    /** PDF 最多渲染几页。每页 ≈ 1024 token（服务端上限），再多会一口吃掉上下文。 */
    private const val PDF_MAX_PAGES = 12

    private const val PDF_MAX_BYTES = 40L * 1024 * 1024

    /** 导入文档的结果：可能产出多个附件（PDF 一页一张图）。 */
    data class DocumentImport(
        val attachments: List<Attachment>,
        /** 有损的地方，如实告诉用户。null 表示无损。 */
        val note: String? = null,
    )

    /**
     * 导入一份「文档」附件。
     *
     * 两种格式走**两条完全不同的路**，因为它们的本质就不一样：
     *
     * | 格式 | 做法 | 为什么 |
     * |---|---|---|
     * | `.docx` | 从 ZIP 里抽 `word/document.xml` 的文本 | 它本来就是 XML，零依赖就能抽干净，任何模型都能读 |
     * | `.pdf` | 用系统 `PdfRenderer` **把每页渲染成图** | ⚠️ Android 自带的 PDF 库**只能渲染、不能抽文本**。要抽文本得引 PDFBox（+10MB），和一个"轻量优先"的应用不划算 |
     *
     * 所以 **PDF 需要支持识图的模型** —— 它是当成图片发出去的。
     * 这一点要么在 UI 上说清楚，要么等请求 400 才让人莫名其妙。
     *
     * 识别格式看**文件头**，不看扩展名：用户手里的文件叫什么名字都有可能。
     */
    suspend fun document(context: Context, uri: Uri): Result<DocumentImport> =
        withContext(Dispatchers.IO) {
            runCatching {
                val head = readHead(context, uri, 8)
                if (head.isEmpty()) throw AppTextException(R.string.err_file_read)

                when {
                    head.startsWithAscii("%PDF") -> importPdf(context, uri)
                    head.startsWithBytes(0x50, 0x4B) -> importZipDocument(context, uri)
                    // OLE 复合文档 = 上古 .doc / .xls / .ppt
                    head.startsWithBytes(0xD0, 0xCF, 0x11, 0xE0) ->
                        throw AppTextException(R.string.err_doc_legacy)

                    /*
                     * 剩下的**一律按纯文本试**（md / txt / json / 代码…）。
                     *
                     * 刻意不做 MIME / 扩展名白名单：`.md` 在不同设备上被报成
                     * `text/markdown`、`text/x-markdown` 甚至
                     * `application/octet-stream`，按名单过滤只会让用户
                     * "根本看不到自己的文件"。真正的把关是**严格 UTF-8 解码** ——
                     * 二进制文件会在这里直接报错。
                     */
                    else -> DocumentImport(listOf(text(context, uri).getOrThrow()))
                }
            }
        }

    private fun importPdf(context: Context, uri: Uri): DocumentImport {
        val declared = declaredSize(context, uri)
        if (declared > PDF_MAX_BYTES) throw AppTextException(R.string.err_pdf_too_big)

        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw AppTextException(R.string.err_pdf_open)
        val name = displayName(context, uri) ?: "document.pdf"

        descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val total = renderer.pageCount
                val pages = minOf(total, PDF_MAX_PAGES)
                if (pages <= 0) throw AppTextException(R.string.err_pdf_no_pages)

                val out = ArrayList<Attachment>(pages)
                for (index in 0 until pages) {
                    renderer.openPage(index).use { page ->
                        // 渲染到约 1600px 长边，和图片附件同一套规格
                        val scale = IMAGE_MAX_SIDE.toFloat() / max(page.width, page.height).coerceAtLeast(1)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        // PDF 页面本身是透明的，不铺白底会压出黑块（和图片附件同一个坑）
                        Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        val bytes = ByteArrayOutputStream().use { buffer ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)
                            buffer.toByteArray()
                        }
                        bitmap.recycle()

                        out += Attachment(
                            kind = Attachment.Kind.IMAGE,
                            name = Res.get(R.string.pdf_page_name, name, index + 1),
                            mime = "image/jpeg",
                            data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                            width = w,
                            height = h,
                        )
                    }
                }

                val note = if (total > pages) {
                    Res.get(R.string.warn_pdf_truncated, total, pages)
                } else {
                    null
                }
                return DocumentImport(out, note)
            }
        }
    }

    private fun importZipDocument(context: Context, uri: Uri): DocumentImport {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw AppTextException(R.string.err_file_read)
        if (bytes.size > PDF_MAX_BYTES) throw AppTextException(R.string.err_docx_too_big)

        val xml = zipEntry(bytes, "word/document.xml")
            ?: throw AppTextException(R.string.err_docx_not_zip)

        val name = displayName(context, uri) ?: "document.docx"
        val text = docxText(xml)
        if (text.isBlank()) throw AppTextException(R.string.err_docx_empty)
        if (text.length > TEXT_MAX_CHARS) {
            throw AppTextException(R.string.err_docx_too_long, text.length, TEXT_MAX_CHARS)
        }

        return DocumentImport(
            listOf(
                Attachment(
                    kind = Attachment.Kind.TEXT,
                    name = name,
                    mime = "text/plain",
                    data = text,
                ),
            ),
        )
    }

    /**
     * docx 正文的极简抽取。
     *
     * 实现与注意事项都在 `docxXmlToText` 里（纯函数，有单测）。
     */
    private fun docxText(xml: String): String = docxXmlToText(xml)

    /** 从 ZIP 里取一个条目的内容；找不到返回 null。 */
    private fun zipEntry(bytes: ByteArray, path: String): String? =
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name == path) {
                        return@use zip.readBytes().decodeToString()
                    }
                    zip.closeEntry()
                }
                null
            }
        }.getOrNull()

    private fun readHead(context: Context, uri: Uri, count: Int): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(count)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        } ?: ByteArray(0)

    private fun ByteArray.startsWithAscii(text: String): Boolean {
        if (size < text.length) return false
        return text.indices.all { this[it] == text[it].code.toByte() }
    }

    private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
        if (size < values.size) return false
        return values.indices.all { this[it] == values[it].toByte() }
    }

    // ── 公共 ─────────────────────────────────────────────

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: uri.lastPathSegment

    private fun declaredSize(context: Context, uri: Uri): Long =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
            } ?: 0L
        }.getOrElse { 0L }
}


