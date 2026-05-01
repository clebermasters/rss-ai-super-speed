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
data class CreateFeedRequest(
    val name: String? = null,
    val url: String,
    val enabled: Boolean = true,
    val tags: List<String> = emptyList(),
    val limit: Int = 20,
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
    val sourceFeedId: String? = null,
    val tags: List<String> = emptyList(),
    val score: Int? = null,
    val comments: Int? = null,
    val isRead: Boolean = false,
    val isSaved: Boolean = false,
    val contentAiFormatted: Boolean = false,
)

@Serializable
data class ArticleTagsRequest(
    val tags: List<String> = emptyList(),
)

@Serializable
data class Settings(
    val llmProvider: String = "openai_compatible",
    val aiModel: String = "gpt-5.4",
    val aiApiBase: String = "https://api.openai.com/v1",
    val codexModel: String = "gpt-5.4",
    val codexReasoningEffort: String = "medium",
    val embeddingModel: String = "text-embedding-3-small",
    val ttsModel: String = "gpt-4o-mini-tts-2025-12-15",
    val ttsVoice: String = "marin",
    val ttsInstructions: String = "Read this as a calm, clear personal news reader. Use natural pacing, short pauses between paragraphs, and a warm but neutral tone.",
    val aiContentFormattingEnabled: Boolean = false,
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
data class DeleteFeedResponse(val deleted: Boolean = false)

@Serializable
data class SummaryResponse(val articleId: String = "", val summary: String = "")

@Serializable
data class SpeechRequest(
    val target: String = "content",
    val segmentPercent: Int = 100,
    val segmentIndex: Int = 0,
    val forceRefresh: Boolean = false,
)

data class SpeechAudio(
    val bytes: ByteArray,
    val contentType: String = "audio/mpeg",
    val cacheStatus: String = "",
    val segmentIndex: Int = 0,
    val segmentCount: Int = 1,
    val segmentPercent: Int = 100,
)

@Serializable
data class FetchContentResponse(
    val articleId: String = "",
    val jobId: String? = null,
    val status: String = "completed",
    val strategy: String? = null,
    val content: String = "",
    val article: Article? = null,
    val formattingRequested: Boolean = false,
    val contentFormattingAttempted: Boolean = false,
    val contentAiFormatted: Boolean = false,
    val contentFormattingError: String? = null,
    val errors: List<String> = emptyList(),
    val message: String? = null,
)
