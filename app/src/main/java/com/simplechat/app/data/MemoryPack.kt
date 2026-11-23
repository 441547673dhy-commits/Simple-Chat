package com.simplechat.app.data

import android.content.Context
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 记忆包：离线建好的检索索引（int8 量化向量 + 原文），只读。
 *
 * 为什么是单文件：x号-全史 有几十万字，17k 段向量用 SQLite 存是 114MB；
 * 量化成 int8 后整包 30MB，一次读进内存的只有向量部分（17.8MB），
 * 原文按偏移量按需取。
 *
 * 格式见 `4-工具/导出索引.py`，两边必须一致：
 * ```
 * "MEMPACK1" | uint32 N | N × (ownerLen|owner, tsLen|ts, srcLen|src,
 *                              textLen|text, float32 scale, u8×1024)
 * ```
 */
class MemoryPack private constructor(
    val owners: Array<String>,
    val timestamps: Array<String>,
    val sources: Array<String>,
    private val textOffsets: IntArray,
    private val texts: ByteArray,
    private val scales: FloatArray,
    private val vectors: ByteArray,
) {

    /** 每段向量范数（int8 域），用于把点积还原成余弦。加载时算一次。 */
    private val norms: FloatArray = FloatArray(vectors.size / DIM) { i ->
        var s = 0.0
        val base = i * DIM
        for (d in 0 until DIM) {
            val x = vectors[base + d].toInt()
            s += (x * x).toDouble()
        }
        kotlin.math.sqrt(s).toFloat()
    }

    val size: Int get() = owners.size

    data class Hit(val index: Int, val score: Float)

    /** 原文。命中后注入给她的就是这一段。 */
    fun textAt(i: Int): String {
        val start = textOffsets[i]
        val end = if (i + 1 < textOffsets.size) textOffsets[i + 1] else texts.size
        return String(texts, start, end - start, Charsets.UTF_8)
    }

    fun ownerAt(i: Int): String = owners[i]

    /**
     * 向量检索：只扫 [owner] 那些段。
     *
     * 用 int8 点积近似余弦（向量已按自身最大值缩放到 -127..127，所以点积大小
     * 与余弦同序；再乘各自 scale 修正）。
     */
    fun search(query: FloatArray, owner: String?, k: Int, queryText: String = ""): List<Hit> {
        require(query.size == DIM) { "query 维度应为 $DIM" }
        var qmax = 0f
        for (v in query) if (kotlin.math.abs(v) > qmax) qmax = kotlin.math.abs(v)
        if (qmax == 0f) return emptyList()
        val qscale = qmax / 127f
        val q = IntArray(DIM) { (query[it] / qscale).toInt().coerceIn(-127, 127) }
        var qn = 0.0
        for (v in q) qn += (v * v).toDouble()
        qn = kotlin.math.sqrt(qn)

        val qb = bigrams(queryText)
        val hits = ArrayList<Hit>(k * 4)
        for (i in 0 until size) {
            if (owner != null && owners[i] != owner) continue
            val base = i * DIM
            var dot = 0.0
            for (d in 0 until DIM) dot += q[d] * vectors[base + d].toDouble()
            val cos = (dot / ((qn + 1e-9) * (norms[i] + 1e-9))).toFloat()
            // 中文 2-gram 词面加权：实测 recall@5 从 42% 提到 58%
            val kw = if (qb.isEmpty()) 0f else {
                val rb = bigrams(textAt(i))
                var n = 0
                for (g in qb) if (rb.contains(g)) n++
                KW_WEIGHT * n / qb.size
            }
            hits.add(Hit(i, cos + kw))
        }
        hits.sortByDescending { it.score }
        return if (hits.size > k) hits.subList(0, k) else hits
    }

    /** 中文按字切 2-gram（不分词也能捕捉词面重合）。 */
    private fun bigrams(s: String): Set<String> {
        val clean = s.filter { !it.isWhitespace() }
        if (clean.length < 2) return emptySet()
        val out = HashSet<String>(clean.length)
        for (i in 0 until clean.length - 1) out.add(clean.substring(i, i + 2))
        return out
    }

    companion object {
        const val DIM = 1024
        private const val KW_WEIGHT = 0.15f
        private const val MAGIC = "MEMPACK1"
        private const val FILE = "memory.pack"

        fun file(context: Context): File = File(context.filesDir, FILE)

        fun exists(context: Context): Boolean = file(context).exists()

        /** 把用户选的 pack 复制进私有目录（30MB，一次性）。 */
        fun import(context: Context, bytes: ByteArray): Int {
            file(context).writeBytes(bytes)
            return load(context)?.size ?: 0
        }

        fun load(context: Context): MemoryPack? = runCatching {
            parse(file(context).readBytes())
        }.getOrNull()

        fun parse(bytes: ByteArray): MemoryPack? {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8)
            buf.get(magic)
            if (String(magic) != MAGIC) return null
            val n = buf.int
            val owners = arrayOfNulls<String>(n)
            val times = arrayOfNulls<String>(n)
            val sources = arrayOfNulls<String>(n)
            val textOffsets = IntArray(n)
            val textBuf = java.io.ByteArrayOutputStream()
            val scales = FloatArray(n)
            val vec = ByteArray(n * DIM)
            for (i in 0 until n) {
                fun str(): String {
                    val len = buf.int
                    val b = ByteArray(len)
                    buf.get(b)
                    return String(b, Charsets.UTF_8)
                }
                owners[i] = str()
                times[i] = str()
                sources[i] = str()
                textOffsets[i] = textBuf.size()
                textBuf.write(buf.let { val len = it.int; val b = ByteArray(len); it.get(b); b })
                scales[i] = buf.float
                buf.get(vec, i * DIM, DIM)
            }
            return MemoryPack(
                owners = owners.map { it!! }.toTypedArray(),
                timestamps = times.map { it!! }.toTypedArray(),
                sources = sources.map { it!! }.toTypedArray(),
                textOffsets = textOffsets,
                texts = textBuf.toByteArray(),
                scales = scales,
                vectors = vec,
            )
        }
    }
}
