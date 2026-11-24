package com.simplechat.app.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 群聊同步的现场记录。
 *
 * 为什么需要一个文件：同步失败时的 Toast 一闪就没，**排查时看不到**；
 * 而写库成功但界面没刷新、和写库直接抛异常，现象都是"消息没出现"，
 * 光看界面分不出来。所以把参数与结果落一行，事后能读。
 *
 * 写到「下载/DSH-Go/」——这是他和我都能打开的地方。
 */
object GroupSyncLog {

    /**
     * 把任意文本写到 下载/DSH-Go/（成员表、用量流水这类"要能搬走的配置"用）。
     *
     * 返回**是否真的写成功**：以前返回 Unit，失败了界面照样说"已导出"，
     * 我去下载目录找不到文件才发现是安静失败（2026-11-25 导出用量流水那次）。
     */
    fun writeTextFile(context: Context, fileName: String, content: String): Boolean {
        return runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                // 后缀跟 MIME 对得上，下载目录才不会把它当"损坏的 json"
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(fileName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/DSH-Go")
            }
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            val out = resolver.openOutputStream(target, "w") ?: return@runCatching false
            out.use { it.write(content.toByteArray()) }
            true
        }.getOrDefault(false)
    }

    private fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "csv" -> "text/csv"
        "md" -> "text/markdown"
        "txt" -> "text/plain"
        else -> "application/json"
    }

    fun write(
        context: Context,
        conversationId: String?,
        selfName: String,
        totalLines: Int,
        pending: Int,
        afterSeq: Int,
        result: String,
    ) {
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val line = buildString {
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                append(" conv=").append(conversationId?.take(8))
                append(" self=").append(selfName)
                append(" lines=").append(totalLines)
                append(" pending=").append(pending)
                append(" afterSeq=").append(afterSeq)
                append(" result=").append(result)
                append("\n")
            }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "群聊同步-$stamp.log")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/DSH-Go")
            }
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching
            resolver.openOutputStream(target, "w")?.use { it.write(line.toByteArray()) }
        }
    }
}
