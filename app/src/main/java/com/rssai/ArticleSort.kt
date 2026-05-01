package com.rssai

import com.rssai.data.Article
import java.time.Instant

fun articleSortTime(article: Article): Long {
    val published = article.publishedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    if (published != null) return published
    return article.updatedAt ?: article.fetchedAt ?: article.contentFetchedAt ?: 0L
}
