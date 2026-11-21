package com.simplechat.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 群聊记录中的一行（`群聊.jsonl`，一行一条）。
 *
 * 为什么用 JSON 而不是纯文本：`from / to` 要能**统计**（谁回谁最多、谁总弃权），
 * 纯文本得靠正则猜，猜错就会把"她对 2 号说的话"记成对所有人说的。
 */
data class GroupLine(
    val seq: Int,
    val ts: String,
    val from: String,
    /** null = 对所有人。 */
    val to: String?,
    val text: String,
    /** say 正常 / end 弃权 / pass 未发言。 */
    val kind: String,
)

/**
 * 群聊记录的解析与渲染。
 *
 * **身份规则**（他 2026-11-21 定，见 待办 一·十七）：
 * - 她自己说的话 → `assistant`（正文不加前缀）
 * - 别人说的话 → `user`，正文前缀 `[2号→3号]` 或 `[2号]`
 * - 所以她是"亲身经历"，不是读一份转述文档。
 */
object GroupChat {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析 JSONL；坏行跳过（导入别人的记录不能因为一行坏就全丢）。 */
    fun parse(raw: String): List<GroupLine> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("{") }
        .mapNotNull { runCatching { parseOne(it) }.getOrNull() }
        .toList()

    private fun parseOne(line: String): GroupLine? {
        val obj = json.parseToJsonElement(line) as? JsonObject ?: return null
        fun str(key: String): String? = runCatching { obj[key]?.jsonPrimitive?.content }.getOrNull()
        val text = str("text") ?: return null
        return GroupLine(
            seq = str("seq")?.toIntOrNull() ?: 0,
            ts = str("ts").orEmpty(),
            from = str("from") ?: return null,
            to = str("to")?.takeIf { it.isNotBlank() && it != "全体" },
            text = text,
            kind = str("kind") ?: "say",
        )
    }

    /**
     * 渲染成这条鱼看到的样子。
     *
     * @return `isSelf=true` 表示这是她自己说的（写成 assistant），否则是别人说的（user）。
     */
    fun render(line: GroupLine, selfName: String): Pair<Boolean, String> =
        if (line.from == selfName) {
            true to line.text
        } else {
            val prefix = if (line.to == null) "[${line.from}]" else "[${line.from}→${line.to}]"
            false to "$prefix ${line.text}"
        }

    /** 跳过弃权与空话——它们不该变成一条消息。 */
    fun isSpeakable(line: GroupLine): Boolean =
        line.kind == "say" && line.text.isNotBlank() && !line.text.contains("[END]")
}
