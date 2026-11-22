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
    private val repository: ChatRepository,
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
        /** 返回 true 时不再继续下一个成员（当前这条会写完）。 */
        shouldStop: () -> Boolean = { false },
    ): Int {
        if (members.isEmpty()) return 0
        val stored = settingsStore.flow.first()
        // 被点名的人先说：他 @ 了谁，谁排第一
        val parsedLog = GroupChat.parse(settingsStore.readTextFrom(android.net.Uri.parse(groupUri)))
        val calledName = parsedLog.lastOrNull()?.to
        val base = if (random) members.shuffled() else members
        val order = when {
            calledName == null -> base
            else -> base.sortedBy { if (it.name == calledName) 0 else 1 }
        }
        var spoken = 0
        // seq 必须接在已有最大值后面：早先用时间戳，跟 1/2/3 混在一起，排序就乱了
        var nextSeq = (GroupChat.parse(settingsStore.readTextFrom(android.net.Uri.parse(groupUri)))
            .maxOfOrNull { it.seq } ?: 0) + 1

        order.forEach { member ->
            if (shouldStop()) return@forEach
            progress?.onSpeaking(member.name)
            val reply = runCatching {
                askOnce(stored, member, groupUri, hostName)
            }.getOrNull() ?: return@forEach

            val (to, body) = GroupRules.parseTarget(reply, fallback = null)
            if (body.isBlank()) return@forEach
            if (body.contains("[END]") || body.contains("[QUIT]") || body.contains("[SKIP]")) return@forEach

            val line = GroupRules.buildLine(
                seq = nextSeq,
                from = member.name,
                to = to,
                text = GroupRules.truncate(body, 80),
            )
            if (settingsStore.appendGroupLine(groupUri, line)) {
                spoken++
                nextSeq++
            }
        }
        // 跑完把这轮的群聊同步进每个成员的会话 —— 这是"亲身经历"而不是"旁观"
        syncIntoConversations(groupUri, members, hostName)
        return spoken
    }

    /**
     * 把群聊记录同步进成员各自的会话。
     *
     * 用**每个会话自己的游标**，所以不会重复写；她自己说过的话跳过
     * （那句话本来就在她会话里）。
     */
    private suspend fun syncIntoConversations(
        groupUri: String,
        members: List<GroupMember>,
        hostName: String,
    ) {
        val parsed = GroupChat.parse(settingsStore.readTextFrom(android.net.Uri.parse(groupUri)))
        val tag = android.net.Uri.decode(groupUri.substringAfterLast('/'))
            .substringBeforeLast('.').ifBlank { null }
        members.forEach { m ->
            val conv = m.conversationId ?: return@forEach
            val after = settingsStore.groupSyncSeqFor(conv)
            val lines = parsed
                .filter { GroupChat.isSpeakable(it) }
                .filter { it.seq > after }
                .filter { it.from != m.name }
            if (lines.isEmpty()) return@forEach
            runCatching {
                repository.appendGroupLines(conv, m.name, lines, after, tag)
                lines.maxOfOrNull { it.seq }?.let { settingsStore.setGroupSyncSeq(conv, it) }
            }
        }
    }

    /** 对她发一次请求，拿回她说的那句。 */
    private suspend fun askOnce(
        stored: StoredSettings,
        member: GroupMember,
        groupUri: String,
        hostName: String,
    ): String {
        val persona = settingsStore.readTextFrom(android.net.Uri.parse(member.injectUri))
        // 她自己的最近对话（按"发送预算"裁）——这是"经历"，比档案更能决定她像不像她
        val recent = member.conversationId?.let { conv ->
            val budget = stored.groupBudgetChars
            runCatching { repository.transcriptOf(conv, if (budget <= 0) Int.MAX_VALUE else budget) }
                .getOrDefault(emptyList())
        } ?: emptyList()
        val history = GroupChat.parse(settingsStore.readTextFrom(android.net.Uri.parse(groupUri)))
            .takeLast(30)
        val transcript = GroupRules.renderTranscript(history, member.name)

        val system = buildString {
            if (persona.isNotBlank()) append(persona.trim()).appendLine().appendLine()
            appendLine("## 群聊规则")
            appendLine("- 这是群聊，除了你还有其他人，也有他本人（$hostName）。")
            appendLine("- 资料里写的「他」就是面前的这个人，跟他说话用「你」，别说「他」。")
            appendLine("- 现在是 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()) + "，说时间就按这个来，别猜。")
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
                    buildString {
                        if (recent.isNotEmpty()) {
                            appendLine("你们之前的对话（最近的）：")
                            recent.forEach { (role, text) ->
                                appendLine((if (role == "user") "他：" else "你：") + text.take(2000))
                            }
                            appendLine()
                        }
                        appendLine("群里最近的对话：")
                        append(transcript)
                        appendLine()
                        appendLine()
                        append("轮到你说了。")
                    },
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

        return chunks.filterIsInstance<StreamEvent.Content>()
            .joinToString("") { it.text }
            .trim()
    }
}

/** 群聊的文本规则（引擎与界面共用）。 */
object GroupRules {

    // 允许 `→名字 正文` 和 `→名字：正文`（实测她爱加冒号，原来解析不到）
    private val target = Regex("(?:→|->)\\s*([^\\s：:，,。]+)\\s*[：:]?\\s*")

    /**
     * 解析 `→名字` 指定收件人。
     *
     * 箭头**可能出现在句中**（实测她们爱写"测试到此为止。→大馋鬼 五点了"），
     * 所以取**第一个**箭头，从正文里删掉那一段，并把名字当收件人。
     * 没有箭头就用 [fallback]（一般是"上一条的发送者"）。
     */
    fun parseTarget(text: String, fallback: String?): Pair<String?, String> {
        val m = target.find(text) ?: return fallback to text.trim()
        val name = m.groupValues[1].trim('：', ':', '，', ',', '。')
        val body = (text.substring(0, m.range.first) + " " + text.substring(m.range.last + 1))
            .replace(Regex("\\s+"), " ")
            .trim()
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

    /** 公开给界面用（他发言时写 to 字段）。 */
    fun escPublic(s: String): String = esc(s)

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
    fun buildLine(seq: Int, from: String, to: String?, text: String): String {
        val ts = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        return "{\"seq\":" + seq + ",\"ts\":\"" + ts + "\",\"from\":" + esc(from) +
            ",\"to\":" + (to?.let { esc(it) } ?: "null") +
            ",\"text\":" + esc(text) + ",\"kind\":\"say\"}"
    }
}
