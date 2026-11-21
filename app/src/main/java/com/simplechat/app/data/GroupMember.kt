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
)

object GroupMembers {

    fun encode(list: List<GroupMember>): String = JSONArray().apply {
        list.forEach { m ->
            put(JSONObject().apply {
                put("name", m.name)
                put("inject", m.injectUri)
            })
        }
    }.toString()

    fun decode(raw: String): List<GroupMember> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val inject = o.optString("inject").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GroupMember(name, inject)
        }
    }.getOrDefault(emptyList())
}
