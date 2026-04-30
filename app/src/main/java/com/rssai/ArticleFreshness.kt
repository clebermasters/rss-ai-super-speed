package com.rssai

import android.content.SharedPreferences
import com.rssai.data.Article

private const val KnownArticleIdsKey = "knownArticleIds"
private const val HasArticleBaselineKey = "hasArticleBaseline"
private const val MaxKnownArticleIds = 2000

fun detectBrandNewArticleIds(prefs: SharedPreferences, loadedArticles: List<Article>): Set<String> {
    val loadedIds = loadedArticles.map { it.articleId }.filter { it.isNotBlank() }
    if (loadedIds.isEmpty()) {
        return emptySet()
    }

    val hasBaseline = prefs.getBoolean(HasArticleBaselineKey, false)
    val knownIds = prefs.getStringSet(KnownArticleIdsKey, emptySet()).orEmpty()
    val brandNewIds = if (hasBaseline) {
        loadedIds.filterNot { it in knownIds }.toSet()
    } else {
        emptySet()
    }

    val mergedKnownIds = (loadedIds + knownIds.filterNot { it in loadedIds })
        .take(MaxKnownArticleIds)
        .toSet()
    prefs.edit()
        .putBoolean(HasArticleBaselineKey, true)
        .putStringSet(KnownArticleIdsKey, mergedKnownIds)
        .apply()

    return brandNewIds
}
