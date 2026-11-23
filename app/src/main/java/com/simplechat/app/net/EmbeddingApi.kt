package com.simplechat.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 向量接口（默认硅基流动的 bge-m3，免费）。
 *
 * 只做一件事：把一段文字变成 1024 维向量。检索本身在本地做（见 MemoryPack）。
 */
@Serializable
private data class EmbedRequest(
    val model: String,
    val input: List<String>,
)

@Serializable
private data class EmbedItem(val embedding: List<Float>, val index: Int = 0)

@Serializable
private data class EmbedResponse(val data: List<EmbedItem> = emptyList())

class EmbeddingApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 失败返回 null（记忆检索失败不该影响正常对话）。 */
    fun embed(baseUrl: String, apiKey: String, model: String, text: String): FloatArray? =
        runCatching {
            val body = json.encodeToString(
                EmbedRequest.serializer(),
                EmbedRequest(model = model, input = listOf(text.take(8000))),
            ).toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/embeddings")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string() ?: return null
                val parsed = json.decodeFromString(EmbedResponse.serializer(), text)
                parsed.data.firstOrNull()?.embedding?.toFloatArray()
            }
        }.getOrNull()
}
