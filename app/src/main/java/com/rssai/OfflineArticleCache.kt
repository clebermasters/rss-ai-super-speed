package com.rssai

import android.content.Context
import com.rssai.data.Article
import com.rssai.data.ArticleHighlight
import com.rssai.data.Feed
import com.rssai.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

@Serializable
data class OfflineCacheSnapshot(
    val articles: List<Article> = emptyList(),
    val cachedAt: Long = 0,
    val cursor: Long = 0,
    val feeds: List<Feed> = emptyList(),
    val highlights: List<ArticleHighlight> = emptyList(),
    val settings: Settings = Settings(),
)

private val cacheJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

suspend fun loadOfflineCache(context: Context): OfflineCacheSnapshot =
    withContext(Dispatchers.IO) {
        val file = cacheFile(context)
        if (!file.exists()) return@withContext OfflineCacheSnapshot()
        runCatching { cacheJson.decodeFromString<OfflineCacheSnapshot>(file.readText()) }.getOrDefault(OfflineCacheSnapshot())
    }

suspend fun saveOfflineCache(context: Context, snapshot: OfflineCacheSnapshot, retentionDays: Int) {
    withContext(Dispatchers.IO) {
        val file = cacheFile(context)
        file.parentFile?.mkdirs()
        val pruned = snapshot.copy(cachedAt = System.currentTimeMillis()).pruned(retentionDays)
        file.writeText(cacheJson.encodeToString(pruned))
    }
}

fun mergeArticles(existing: List<Article>, incoming: List<Article>): List<Article> {
    val byId = linkedMapOf<String, Article>()
    existing.forEach { byId[it.articleId] = it }
    incoming.forEach { next ->
        val current = byId[next.articleId]
        byId[next.articleId] = if (current == null) next else mergeArticle(current, next)
    }
    return byId.values.sortedByDescending { it.cacheSortTime() }
}

fun mergeHighlights(existing: List<ArticleHighlight>, incoming: List<ArticleHighlight>): List<ArticleHighlight> {
    val byId = linkedMapOf<String, ArticleHighlight>()
    existing.forEach { byId[it.highlightId] = it }
    incoming.forEach { byId[it.highlightId] = it }
    return byId.values.sortedByDescending { it.createdAt }
}

private fun OfflineCacheSnapshot.pruned(retentionDays: Int): OfflineCacheSnapshot {
    val cutoff = System.currentTimeMillis() - retentionDays.coerceIn(1, 365) * 24L * 60L * 60L * 1000L
    val keptArticles = articles.filter { it.isSaved || it.cacheSortTime() >= cutoff }
    val keptIds = keptArticles.map { it.articleId }.toSet()
    return copy(
        articles = keptArticles,
        highlights = highlights.filter { it.articleId in keptIds },
    )
}

private fun mergeArticle(current: Article, next: Article): Article =
    next.copy(
        summary = next.summary ?: current.summary,
        content = next.content ?: current.content,
        contentPreview = next.contentPreview ?: current.contentPreview,
        sourceFeedId = next.sourceFeedId ?: current.sourceFeedId,
        tags = next.tags.ifEmpty { current.tags },
        score = next.score ?: current.score,
        comments = next.comments ?: current.comments,
    )

private fun Article.cacheSortTime(): Long {
    val published = publishedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    if (published != null) return published
    return updatedAt ?: fetchedAt ?: 0L
}

private fun cacheFile(context: Context): File =
    File(context.filesDir, "offline-cache/rss-cache.json")
