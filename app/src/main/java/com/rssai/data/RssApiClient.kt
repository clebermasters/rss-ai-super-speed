package com.rssai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    suspend fun articles(query: String = ""): ArticlesResponse {
        val suffix = if (query.isBlank()) "" else "?query=${query.urlEncode()}"
        return get("/v1/articles$suffix")
    }

    suspend fun article(articleId: String): Article = get("/v1/articles/$articleId")

    suspend fun refresh(): RefreshResponse = post("/v1/sync/refresh", "{}")

    suspend fun markRead(articleId: String): Article = post("/v1/articles/$articleId/mark-read", "{}")

    suspend fun markUnread(articleId: String): Article = post("/v1/articles/$articleId/mark-unread", "{}")

    suspend fun toggleSave(articleId: String): Article = post("/v1/articles/$articleId/toggle-save", "{}")

    suspend fun fetchContent(articleId: String): FetchContentResponse =
        post("/v1/articles/$articleId/fetch-content", "{}")

    suspend fun contentJob(jobId: String): FetchContentResponse =
        get("/v1/content-jobs/$jobId")

    suspend fun summarize(articleId: String): SummaryResponse =
        post("/v1/articles/$articleId/summarize", "{}")

    suspend fun providers(): ProvidersResponse = get("/v1/llm/providers")

    suspend fun codexAuth(): CodexAuthResponse = get("/v1/llm/codex-auth")

    suspend fun settings(): Settings = get("/v1/settings")

    suspend fun updateSettings(settings: Settings): Settings =
        put("/v1/settings", json.encodeToString(settings))

    private suspend inline fun <reified T> get(path: String): T = request("GET", path)

    private suspend inline fun <reified T> post(path: String, body: String): T = request("POST", path, body)

    private suspend inline fun <reified T> put(path: String, body: String): T = request("PUT", path, body)

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
