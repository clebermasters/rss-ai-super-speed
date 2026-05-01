package com.rssai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RssApiClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mediaType = "application/json".toMediaType()

    suspend fun bootstrap(): BootstrapResponse = get("/v1/bootstrap")

    suspend fun feeds(): FeedsResponse = get("/v1/feeds")

    suspend fun createFeed(request: CreateFeedRequest): Feed =
        post("/v1/feeds", json.encodeToString(request))

    suspend fun updateFeed(feedId: String, request: CreateFeedRequest): Feed =
        put("/v1/feeds/$feedId", json.encodeToString(request))

    suspend fun deleteFeed(feedId: String): DeleteFeedResponse =
        delete("/v1/feeds/$feedId")

    suspend fun refreshFeed(feedId: String): RefreshResponse =
        post("/v1/feeds/$feedId/refresh", "{}")

    suspend fun articles(query: String = "", source: String = "", tag: String = "", limit: Int = 200): ArticlesResponse {
        val params = buildList {
            add("limit=${limit.coerceIn(1, 500)}")
            if (query.isNotBlank()) add("query=${query.urlEncode()}")
            if (source.isNotBlank()) add("source=${source.urlEncode()}")
            if (tag.isNotBlank()) add("tag=${tag.urlEncode()}")
        }.joinToString("&")
        return get("/v1/articles?$params")
    }

    suspend fun article(articleId: String): Article = get("/v1/articles/$articleId")

    suspend fun refresh(): RefreshResponse = post("/v1/sync/refresh", "{}")

    suspend fun markRead(articleId: String): Article = post("/v1/articles/$articleId/mark-read", "{}")

    suspend fun markUnread(articleId: String): Article = post("/v1/articles/$articleId/mark-unread", "{}")

    suspend fun toggleSave(articleId: String): Article = post("/v1/articles/$articleId/toggle-save", "{}")

    suspend fun updateArticleTags(articleId: String, tags: List<String>): Article =
        patch("/v1/articles/$articleId", json.encodeToString(ArticleTagsRequest(tags)))

    suspend fun highlights(limit: Int = 500): HighlightsResponse =
        get("/v1/highlights?limit=${limit.coerceIn(1, 1000)}")

    suspend fun articleHighlights(articleId: String): HighlightsResponse =
        get("/v1/articles/$articleId/highlights")

    suspend fun createHighlight(articleId: String, text: String): ArticleHighlight =
        post("/v1/articles/$articleId/highlights", json.encodeToString(CreateHighlightRequest(text)))

    suspend fun deleteHighlight(articleId: String, highlightId: String): DeleteHighlightResponse =
        delete("/v1/articles/$articleId/highlights/$highlightId")

    suspend fun fetchContent(articleId: String, formatWithAi: Boolean = false, markRead: Boolean = true): FetchContentResponse =
        post("/v1/articles/$articleId/fetch-content", """{"formatWithAi":$formatWithAi,"markRead":$markRead}""")

    suspend fun formatContent(articleId: String, markRead: Boolean = true): FetchContentResponse =
        post("/v1/articles/$articleId/format-content", """{"formatWithAi":true,"markRead":$markRead}""")

    suspend fun contentJob(jobId: String): FetchContentResponse =
        get("/v1/content-jobs/$jobId")

    suspend fun summarize(articleId: String): SummaryResponse =
        post("/v1/articles/$articleId/summarize", "{}")

    suspend fun articleSpeech(articleId: String, request: SpeechRequest): SpeechAudio =
        requestBytes("POST", "/v1/articles/$articleId/tts", json.encodeToString(request))

    suspend fun providers(): ProvidersResponse = get("/v1/llm/providers")

    suspend fun codexAuth(): CodexAuthResponse = get("/v1/llm/codex-auth")

    suspend fun settings(): Settings = get("/v1/settings")

    suspend fun updateSettings(settings: Settings): Settings =
        put("/v1/settings", json.encodeToString(settings))

    private suspend inline fun <reified T> get(path: String): T = request("GET", path)

    private suspend inline fun <reified T> post(path: String, body: String): T = request("POST", path, body)

    private suspend inline fun <reified T> patch(path: String, body: String): T = request("PATCH", path, body)

    private suspend inline fun <reified T> put(path: String, body: String): T = request("PUT", path, body)

    private suspend inline fun <reified T> delete(path: String): T = request("DELETE", path)

    private suspend fun requestBytes(method: String, path: String, body: String? = null): SpeechAudio =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .header("x-rss-ai-token", token)
                .header("accept", "audio/mpeg")
            if (body == null) {
                builder.method(method, null)
            } else {
                builder.method(method, body.toRequestBody(mediaType))
                    .header("content-type", "application/json")
            }
            val response = http.newCall(builder.build()).execute()
            val responseBytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${responseBytes.toString(Charsets.UTF_8)}")
            }
            SpeechAudio(
                bytes = responseBytes,
                contentType = response.header("content-type") ?: "audio/mpeg",
                cacheStatus = response.header("x-rss-ai-cache").orEmpty(),
                segmentIndex = response.header("x-rss-ai-segment-index")?.toIntOrNull() ?: 0,
                segmentCount = response.header("x-rss-ai-segment-count")?.toIntOrNull() ?: 1,
                segmentPercent = response.header("x-rss-ai-segment-percent")?.toIntOrNull() ?: 100,
            )
        }

    private suspend inline fun <reified T> request(method: String, path: String, body: String? = null): T =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .header("x-rss-ai-token", token)
                .header("accept", "application/json")
            if (body == null) {
                builder.method(method, null)
            } else {
                builder.method(method, body.toRequestBody(mediaType))
                    .header("content-type", "application/json")
            }
            val response = http.newCall(builder.build()).execute()
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: $responseBody")
            }
            json.decodeFromString<T>(responseBody)
        }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
