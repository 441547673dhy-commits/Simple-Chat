package com.simplechat.app.data

import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.ModelInfo
import com.simplechat.app.net.OutMessageDto
import com.simplechat.app.net.StreamEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList

/**
 * ② 群聊引擎：让成员**自己**在群里说话。
 *
 * 一次 [runRound] = 按顺序给每个成员发一次请求，把他/她的话写回群聊记录。
 * 每人的请求 = 她自己的上下文文件 + 群聊最近 N 条（带群标签）。
 *
 * 刻意做的限制（他定的）：
 * - 单条 ≤80 字（硬截断，不靠提示词）
 * - `[END]`/`[QUIT]`/`[SKIP]` 表示这次不发言
 * - 一个人只进一个群（MVP 限制）
 */
class GroupRunner(
    private val chatApi: ChatApi,
    private val settingsStore: SettingsStore,
) {

    /** 一轮的进度回调（给界面显示"谁在说话"）。 */
    fun interface Progress {
        fun onSpeaking(name: String)
    }

    /**
     * 跑一轮。
     *
     * @param groupUri 群聊文件
     * @param members 成员（顺序即发言顺序）
     * @param random 打乱顺序
     * @param hostName 群主（他自己的名字，写回记录用）
     * @return 实际写下发言的成员数
     */
    suspend fun runRound(
        groupUri: String,
        members: List<GroupMember>,
        random: Boolean,
        hostName: String = "大馋鬼",
        progress: Progress? = null,
    ): Int {
        if (members.isEmpty()) return 0
        val stored = settingsStore.flow.first()
        val order = if (random) members.shuffled() else members
        var spoken = 0

        order.forEach { member ->
            progress?.onSpeaking(member.name)
            val reply = runCatching {
                askOnce(stored, member, groupUri, hostName)
            }.getOrNull() ?: return@forEach

            val (to, body) = GroupRules.parseTarget(reply, fallback = null)
            if (body.isBlank()) return@forEach
            if (body.contains("[END]") || body.contains("[QUIT]") || body.contains("[SKIP]")) return@forEach

            val line = GroupRules.buildLine(
                from = member.name,
                to = to,
                text = GroupRules.truncate(body, 80),
            )
            if (settingsStore.appendGroupLine(groupUri, line)) spoken++
        }
        return spoken
    }

    /** 对她发一次请求，拿回她说的那句。 */
    private suspend fun askOnce(
        stored: StoredSettings,
        member: GroupMember,
        groupUri: String,
        hostName: String,
    ): String {
        val persona = settingsStore.readTextFrom(android.net.Uri.parse(member.injectUri))
        val history = GroupChat.parse(settingsStore.readTextFrom(android.net.Uri.parse(groupUri)))
            .takeLast(30)
        val transcript = GroupRules.renderTranscript(history, member.name)

        val system = buildString {
            if (persona.isNotBlank()) append(persona.trim()).appendLine().appendLine()
            appendLine("## 群聊规则")
            appendLine("- 这是群聊，除了你还有其他人，也有他本人（$hostName）。")
            appendLine("- 要指定跟谁说，开头写 `→名字`；对所有人说写 `→全体`。")
            appendLine("- 一条回复最多 80 字，别写成小作文。")
            appendLine("- 不想说，只回 `[END]`。")
        }

        val model = ModelInfo(id = stored.modelId.ifBlank { "deepseek-chat" })
        val messages = listOf(
            OutMessageDto(role = "system", content = kotlinx.serialization.json.JsonPrimitive(system)),
            OutMessageDto(
                role = "user",
                content = kotlinx.serialization.json.JsonPrimitive(
                    "群里最近的对话：\n\n" + transcript + "\n\n轮到你说了。",
                ),
            ),
        )

        val chunks = chatApi.streamChat(
            baseUrl = stored.baseUrl,
            apiKey = stored.apiKey,
            model = model,
            messages = messages,
            thinkingEnabled = false,
            maxTokens = 300,
            temperature = 0.8,
        ).toList()

        return chunks.filterIsInstance<StreamEvent.Delta>()
            .joinToString("") { it.text }
            .trim()
    }
}

/** 群聊的文本规则（引擎与界面共用）。 */
object GroupRules {

    private val target = Regex("^\\s*(?:→|->)\\s*(\\S+?)\\s+")

    /** 解析 `→名字 正文`；没有箭头返回 (fallback, 原文)。 */
    fun parseTarget(text: String, fallback: String?): Pair<String?, String> {
        val m = target.find(text) ?: return fallback to text.trim()
        val name = m.groupValues[1].trim('：', ':', '，', ',', '。')
        val body = text.substring(m.range.last + 1).trim()
        return if (name in setOf("全体", "大家", "所有人")) null to body else name to body
    }

    fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    /** 把记录渲染成她能读的一段（她自己说的标「你」）。 */
    fun renderTranscript(lines: List<GroupLine>, selfName: String): String =
        lines.joinToString("\n") { line ->
            val who = if (line.from == selfName) "你" else line.from
            val to = line.to?.let { "→$it" } ?: ""
            "$who$to：${line.text}"
        }

    /** JSON 字符串转义（只处理群聊会出现的字符）。 */
    private fun esc(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("?") else append(c)
            }
        }
        append('"')
    }

    /** 造一行 JSONL。 */
    fun buildLine(from: String, to: String?, text: String): String {
        val ts = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        val seq = (System.currentTimeMillis() / 1000).toInt()
        return "{\"seq\":" + seq + ",\"ts\":\"" + ts + "\",\"from\":" + esc(from) +
            ",\"to\":" + (to?.let { esc(it) } ?: "null") +
            ",\"text\":" + esc(text) + ",\"kind\":\"say\"}"
    }
}
