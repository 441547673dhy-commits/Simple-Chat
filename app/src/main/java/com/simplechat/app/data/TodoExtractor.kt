package com.simplechat.app.data

import android.content.Context
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.ModelInfo
import com.simplechat.app.net.OutMessageDto
import com.simplechat.app.net.StreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 从一轮对话里**抠出"他答应过要做的事"**。
 *
 * 为什么单独抽：向量检索是"按话题翻旧账"，这是"记住欠着的事"，两回事。
 * 他 2026-11-24 选了 C 方案：每轮聊完多发一次很小的请求，不依赖我在场。
 *
 * 成本：输入只是"他最后一句 + 她最后一条"，几百 token，空闲时段约 ¥0.003/轮。
 *
 * 结果追加到 `filesDir/todos.md`，用 `导出待办` 拿出来。
 */
object TodoExtractor {

    private const val FILE = "todos.md"

    private const val PROMPT = """你是待办抽取器。只看这两句话里「用户」有没有说出**以后要做的事**
（承诺、计划、约定、答应、想做某事）。有就只输出一行：
- 事项 | 时间(没提就写"未定")
没有就只输出：NO
不要解释，不要加任何其他文字。"""

    fun file(context: Context): File = File(context.filesDir, FILE)

    /**
     * 抽一次。返回写入的那一行；没有待办或失败返回 null。
     *
     * 调用方负责判断开关，并在后台协程里调用。
     */
    suspend fun extract(
        context: Context,
        chatApi: ChatApi,
        store: StoredSettings,
        userSaid: String,
        sheSaid: String,
    ): String? = runCatching {
        if (userSaid.isBlank()) return@runCatching null
        val events = chatApi.streamChat(
            baseUrl = store.baseUrl,
            apiKey = store.apiKey,
            model = ModelInfo(id = store.modelId.ifBlank { "deepseek-chat" }),
            messages = listOf(
                OutMessageDto(role = "system", content = JsonPrimitive(PROMPT)),
                OutMessageDto(
                    role = "user",
                    content = JsonPrimitive(
                        "用户：" + userSaid.take(300) + "\n她：" + sheSaid.take(300),
                    ),
                ),
            ),
            thinkingEnabled = false,
            maxTokens = 60,
            temperature = 0.0,
        ).toList()
        val text = events.filterIsInstance<StreamEvent.Content>()
            .joinToString("") { it.text }.trim()
        if (text.isBlank() || text.startsWith("NO") || text.contains("没有")) return@runCatching null
        val line = text.lines().first { it.contains("|") || it.length > 2 }.trim()
        val f = file(context)
        if (!f.exists()) f.writeText("# 自动抽取的待办（来自对话）\n\n")
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        f.appendText("- $stamp  $line\n")
        line
    }.getOrNull()
}
