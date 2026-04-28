package com.rssai.data

import kotlinx.serialization.Serializable

@Serializable
data class Feed(
    val feedId: String = "",
    val name: String = "",
    val url: String = "",
    val enabled: Boolean = true,
    val tags: List<String> = emptyList(),
    val limit: Int = 20,
    val articleCount: Int = 0,
    val unreadCount: Int = 0,
)

@Serializable
data class Article(
    val articleId: String = "",
    val title: String = "",
    val link: String = "",
    val summary: String? = null,
    val content: String? = null,
    val contentPreview: String? = null,
    val publishedAt: String? = null,
    val source: String = "",
    val score: Int? = null,
    val comments: Int? = null,
    val isRead: Boolean = false,
    val isSaved: Boolean = false,
)

@Serializable
data class Settings(
    val llmProvider: String = "openai_compatible",
    val aiModel: String = "gpt-5.4",
    val aiApiBase: String = "https://api.openai.com/v1",
    val codexModel: String = "gpt-5.4",
    val codexReasoningEffort: String = "medium",
    val embeddingModel: String = "text-embedding-3-small",
    val browserBypassEnabled: Boolean = true,
    val browserBypassMode: String = "on_blocked",
)

@Serializable
data class ProviderInfo(
    val id: String = "",
    val label: String = "",
    val configured: Boolean = false,
)

@Serializable
data class FeedsResponse(val feeds: List<Feed> = emptyList())

@Serializable
data class ArticlesResponse(val articles: List<Article> = emptyList(), val cursor: Long = 0)

@Serializable
data class BootstrapResponse(val feeds: List<Feed> = emptyList(), val settings: Settings = Settings())

@Serializable
data class ProvidersResponse(val providers: List<ProviderInfo> = emptyList())

@Serializable
data class CodexAuthResponse(val configured: Boolean = false, val s3Key: String = "")

@Serializable
data class RefreshResponse(val fetched: Int = 0, val saved: Int = 0)

@Serializable
data class SummaryResponse(val articleId: String = "", val summary: String = "")

@Serializable
data class FetchContentResponse(
    val articleId: String = "",
    val jobId: String? = null,
    val status: String = "completed",
    val strategy: String? = null,
    val content: String = "",
    val errors: List<String> = emptyList(),
    val message: String? = null,
)
