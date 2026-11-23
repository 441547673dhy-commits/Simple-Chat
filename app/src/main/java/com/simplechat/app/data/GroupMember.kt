package com.simplechat.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 群成员（② 引擎的一行配置）。
 *
 * 名字要和她注入文件里声明的身份一致（比如「2号」），
 * 否则收件人写回 `→2号` 时对不上人。
 */
data class GroupMember(
    val name: String,
    /** 她的上下文文件 URI（SAF，读得到就够）。 */
    val injectUri: String,
    /** 她的会话 id：引擎读"最近对话"用。空 = 只按注入文件说话。 */
    val conversationId: String? = null,
    /**
     * 记录/显示她的思考过程（只给你看，不回传给她）。
     *
     * **默认关**：思考 token 是输出的 1~2 倍（花钱），且回读容易让她表演思考。
     * 他 2026-11-23 定的规矩：有影响的东西默认不开，除不开启影响更大——那种要提前说。
     */
    val thinking: Boolean = false,
)

object GroupMembers {

    fun encode(list: List<GroupMember>): String = JSONArray().apply {
        list.forEach { m ->
            put(JSONObject().apply {
                put("name", m.name)
                put("inject", m.injectUri)
                m.conversationId?.let { put("conv", it) }
                put("think", m.thinking)
            })
        }
    }.toString()

    fun decode(raw: String): List<GroupMember> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val inject = o.optString("inject").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GroupMember(
                name = name,
                injectUri = inject,
                conversationId = o.optString("conv").takeIf { it.isNotBlank() },
                thinking = o.optBoolean("think", false),
            )
        }
    }.getOrDefault(emptyList())
}
