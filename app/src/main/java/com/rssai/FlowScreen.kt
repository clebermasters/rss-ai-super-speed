package com.rssai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rssai.data.Article

@Composable
fun FlowDashboard(
    articles: List<Article>,
    brandNewArticleIds: Set<String>,
    availableTags: List<String>,
    selectedTag: String,
    onTag: (String) -> Unit,
    onOpen: (Article, List<Article>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val flowArticles = remember(articles, selectedTag) {
        if (selectedTag.isBlank()) {
            articles
        } else {
            articles.filter { selectedTag in normalizeTags(it.tags) }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                if (selectedTag.isBlank()) "Flow" else "Flow · #$selectedTag",
                style = MaterialTheme.typography.headlineSmall,
                color = RssColors.Text,
                fontWeight = FontWeight.Black,
            )
            Text(
                "Scroll through the filtered reading queue without jumping back to the article list.",
                color = RssColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            TagFilterRow(tags = availableTags, selectedTag = selectedTag, onTag = onTag)
        }
        if (flowArticles.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No articles for this flow",
                    body = "Pick another tag, clear the tag filter, or refresh feeds.",
                )
            }
        }
        items(flowArticles, key = { it.articleId }) { article ->
            FlowArticleCard(
                article = article,
                isBrandNew = article.articleId in brandNewArticleIds,
                onClick = { onOpen(article, flowArticles) },
            )
        }
    }
}

@Composable
private fun FlowArticleCard(
    article: Article,
    isBrandNew: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = RssColors.Panel.copy(alpha = if (article.isRead) 0.78f else 0.96f)),
        border = BorderStroke(1.dp, if (isBrandNew) RssColors.Blue else RssColors.Line),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceBadge(source = article.source, size = 46)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isBrandNew) CountPill("NEW", active = true)
                        CountPill(shortDate(article.publishedAt), active = false)
                    }
                    Text(
                        article.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = RssColors.Text,
                        fontWeight = FontWeight.Black,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(article.source, color = sourceAccent(article.source), fontWeight = FontWeight.Bold)
                }
            }
            val preview = article.contentPreview ?: article.summary ?: article.content
            if (!preview.isNullOrBlank()) {
                Text(
                    preview,
                    color = RssColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (article.tags.isNotEmpty()) {
                ArticleTagsRow(article = article, onEditTags = {}, modifier = Modifier.fillMaxWidth(), showEdit = false)
            }
        }
    }
}
