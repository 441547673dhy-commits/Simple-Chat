package com.simplechat.app.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用量流水：每次 API 请求记一行，用来跟平台账单对账。
 *
 * 为什么要它：平台的「每小时消费」只有总数，看不出是哪个会话、哪一轮烧的。
 * 他 2026-11-23 发现平台数字和看得见的活动量差 4~5 倍，必须自己记才能查。
 *
 * 文件：`filesDir/usage.csv`（**逗号分隔**，UTF-8，可直接用表格软件打开）。
 * 不写手机存储 —— 那里每写一次要一次 SAF 授权，太重；导出时一次性拷出去。
 */
object UsageLog {

    private const val FILE = "usage.csv"
    private const val HEADER =
        "time,kind,who,model,prompt,completion,cacheHit,estCostCNY"

    fun file(context: Context): File = File(context.filesDir, FILE)

    /**
     * 记一条。
     *
     * @param kind chat / group
     * @param who  会话标题或成员名
     */
    fun append(
        context: Context,
        kind: String,
        who: String,
        model: String,
        promptTokens: Int,
        completionTokens: Int,
        cacheHitTokens: Int?,
    ) {
        runCatching {
            val f = file(context)
            if (!f.exists()) f.writeText(HEADER + "\n")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val cost = estimate(promptTokens, completionTokens, cacheHitTokens)
            val row = listOf(
                time, kind, escape(who), escape(model),
                promptTokens.toString(), completionTokens.toString(),
                (cacheHitTokens ?: -1).toString(),
                String.format(Locale.US, "%.4f", cost),
            ).joinToString(",")
            f.appendText(row + "\n")
        }
    }

    /**
     * 估算费用（元）。价格按 DeepSeek 公开牌价：
     * 命中 0.02/M，未命中 1/M（空闲 00:30–08:30）或 2/M（标准），输出 4/M 或 8/M。
     */
    fun estimate(promptTokens: Int, completionTokens: Int, cacheHitTokens: Int?): Double {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val minute = java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE)
        val idle = hour < 8 || (hour == 8 && minute < 30) // 00:30–08:30 空闲
        val hit = cacheHitTokens ?: 0
        val miss = (promptTokens - hit).coerceAtLeast(0)
        val hitPrice = 0.02
        val missPrice = if (idle) 1.0 else 2.0
        val outPrice = if (idle) 4.0 else 8.0
        return (hit * hitPrice + miss * missPrice + completionTokens * outPrice) / 1_000_000.0
    }

    private fun escape(s: String) = if (s.contains(',') || s.contains('"')) {
        "\"" + s.replace("\"", "\"\"") + "\""
    } else s
}
