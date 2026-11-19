package com.simplechat.app.ui.chat

import com.simplechat.app.db.CompactionEntity

internal fun validCompaction(path: List<UiMessage>, compaction: CompactionEntity?): CompactionEntity? =
    compaction?.takeIf { summary -> path.any { it.id == summary.throughMessageId } }

/**
 * 从**活跃路径**与压缩状态算出实际发出去的消息序列。
 *
 * 刻意抽成纯函数：这一层错了只会表现为"模型答非所问"，从现象上几乎不可能定位。
 *
 * 路径本身已经有序（`MessageTree.activePath` 走出来的），所以这里只做筛选：
 *
 * 1. 跳过流式未定稿的，以及**既没有正文、也没有附件**的
 * 2. 被压缩覆盖的跳过 —— 摘要里已经有它们了
 * 3. **首条必须是 user** —— 以 assistant 开头等于"一个没有问题的回答"
 */
internal fun orderedOutgoingMessages(
    path: List<UiMessage>,
    compaction: CompactionEntity?,
): List<UiMessage> {
    // 摘要覆盖到路径上的哪一条；找不到说明摘要是别的分支留下的，直接忽略
    val coveredIndex = compaction
        ?.let { c -> path.indexOfFirst { it.id == c.throughMessageId } }
        ?: -1

    val filtered = path
        .filterIndexed { index, _ -> coveredIndex < 0 || index > coveredIndex }
        .filter { it.status != MessageStatus.STREAMING }
        /*
         * ⚠️ 判据是"有没有东西可发"，不是"正文是否非空"。
         *
         * 只挂了附件、没打字的用户消息（"你看看这个"的最自然写法）正文是空的 ——
         * 只按 content 判会被整条丢掉，请求里只剩下一串 assistant，
         * 服务端直接回 `Empty input messages`。
         */
        .filter { it.content.isNotBlank() || it.attachments.isNotEmpty() }

    val firstUser = filtered.indexOfFirst { it.role == MessageRole.USER }
    return when {
        firstUser < 0 -> emptyList()
        firstUser == 0 -> filtered
        else -> filtered.drop(firstUser)
    }
}

/**
 * 只保留最近的一段历史，控制在 [maxChars] 个字符以内。
 *
 * 为什么需要它：一段长会话（58 万 token ≈ 2.9 MB 文本）如果每轮全量上传，
 * 客户端要先拼一个巨型字符串再序列化 —— 卡死、流量按 MB 计，都是这么来的。
 * 这不是"上限"问题（上限该由服务端决定），而是**每次实际发送多少**的问题。
 *
 * 规则：
 * 1. 从末尾往前累加，超出预算就停 —— 丢的永远是最老的部分
 * 2. 截断之后**首条必须是 user**：以 assistant 开头等于一段没有问题的回答，
 *    服务端会直接 400，所以要么去掉，要么往前多带一条 user
 * 3. 预算小于单条消息时，**至少保留最后一条**，不返回空列表
 */
internal fun trimToRecentBudget(messages: List<UiMessage>, maxChars: Int): List<UiMessage> {
    if (maxChars <= 0 || messages.isEmpty()) return messages

    var used = 0
    var start = messages.size
    while (start > 0) {
        val next = messages[start - 1]
        val cost = next.content.length + if (next.attachments.isEmpty()) 0 else DEFAULT_ATTACHMENT_CHARS
        if (used + cost > maxChars && start < messages.size) break
        used += cost
        start--
    }

    var kept = messages.subList(start, messages.size).toList()
    val firstUser = kept.indexOfFirst { it.role == MessageRole.USER }
    kept = when {
        firstUser < 0 -> kept
        firstUser == 0 -> kept
        else -> kept.drop(firstUser)
    }
    return kept
}

/** 附件在预算里的粗估：图片按固定值算，反正一张就上兆字节。 */
private const val DEFAULT_ATTACHMENT_CHARS = 4_000
