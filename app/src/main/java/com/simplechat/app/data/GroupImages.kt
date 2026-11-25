package com.simplechat.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.flow.toList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 群聊图片：存到**公共图片目录**，App 里只记 URI。
 *
 * 为什么不用 App 私有目录：他要能在相册/文件管理里看到（他 2026-11-26 定的）。
 * 代价要如实说：公共目录对有媒体权限的 App 可见 —— 这不是隐私存储。
 *
 * 记录里只存 URI 字符串（几十字节），不存 base64：群聊文件是纯文本，
 * 内联图片会让它膨胀到几 MB，每次读写都变慢。
 */
object GroupImages {

    private const val RELATIVE = "Pictures/DSH-Go/群图"

    /** 保存一张已压缩的图，返回它的 content:// URI；失败返回 null。 */
    fun save(context: Context, bytes: ByteArray, mime: String = "image/jpeg"): String? =
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "群图-$stamp.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            uri.toString()
        }.getOrNull()

    /** 把记录里的 URI 读成可渲染的附件；读不到（图被删了）返回 null。 */
    fun load(context: Context, uriString: String): Attachment? = runCatching {
        val uri = Uri.parse(uriString)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        Attachment(
            kind = Attachment.Kind.IMAGE,
            name = uri.lastPathSegment ?: "group.jpg",
            mime = "image/jpeg",
            data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
        )
    }.getOrNull()
}

/**
 * 生成图片描述（C 方案：自动生成，他可改）。
 *
 * 只调一次小请求；之后群里所有成员读这段文字，而不是每轮重传原图
 * （原图每轮每人数千视觉 token，描述只有几百，且能缓存）。
 */
object GroupImageDescriber {

    private const val PROMPT = "客观描述这张图：主体、场景、可见文字、关键细节。150 字以内，不要评价、不要猜测情绪。"

    suspend fun describe(
        context: Context,
        chatApi: com.simplechat.app.net.ChatApi,
        store: StoredSettings,
        attachment: Attachment,
    ): String? = runCatching {
        val full = AttachmentFiles.materialize(context, attachment)
        if (full.data.isBlank()) return null
        val dataUrl = "data:" + full.mime.ifBlank { "image/jpeg" } + ";base64," + full.data
        val events = chatApi.streamChat(
            baseUrl = store.baseUrl,
            apiKey = store.apiKey,
            model = com.simplechat.app.net.ModelInfo(id = store.modelId.ifBlank { "deepseek-chat" }),
            messages = listOf(
                com.simplechat.app.net.OutMessageDto(
                    role = "system",
                    content = kotlinx.serialization.json.JsonPrimitive(PROMPT),
                ),
                com.simplechat.app.net.OutMessageDto(
                    role = "user",
                    content = com.simplechat.app.net.MessageContent.withImages(
                        "请描述这张图。",
                        listOf(com.simplechat.app.net.ImageInput(dataUrl, "high")),
                    ),
                ),
            ),
            thinkingEnabled = false,
            maxTokens = 300,
            temperature = 0.2,
        ).toList()
        events.filterIsInstance<com.simplechat.app.net.StreamEvent.Content>()
            .joinToString("") { it.text }.trim().ifBlank { null }
    }.getOrNull()
}
