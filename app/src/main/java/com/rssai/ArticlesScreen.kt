package com.rssai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.TypedValue
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rssai.data.Article
import com.rssai.data.CreateFeedRequest
import com.rssai.data.Feed
import com.rssai.data.FetchContentResponse
import com.rssai.data.ProviderInfo
import com.rssai.data.RssApiClient
import com.rssai.data.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ArticlesDashboard(
    articles: List<Article>,
    brandNewArticleIds: Set<String>,
    query: String,
    onQuery: (String) -> Unit,
    onSelect: (Article, List<Article>) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "All Articles",
    emptyTitle: String = "No articles found",
    emptyBody: String = "Try refreshing feeds or changing the search.",
) {
    var unreadOnly by remember { mutableStateOf(false) }
    var savedOnly by remember { mutableStateOf(false) }
    var summarizedOnly by remember { mutableStateOf(false) }
    val filtered = articles.filter { article ->
        (!unreadOnly || !article.isRead)
            && (!savedOnly || article.isSaved)
            && (!summarizedOnly || !article.summary.isNullOrBlank())
    }
    val filteredNewCount = filtered.count { it.articleId in brandNewArticleIds }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = RssColors.Text, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = RssColors.Violet) },
                placeholder = { Text("Search articles, sources, summaries") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = RssColors.Text,
                    unfocusedTextColor = RssColors.Text,
                    focusedContainerColor = RssColors.PanelSoft,
                    unfocusedContainerColor = RssColors.PanelSoft,
                    focusedBorderColor = RssColors.Violet,
                    unfocusedBorderColor = RssColors.Line,
                    focusedPlaceholderColor = RssColors.Muted,
                    unfocusedPlaceholderColor = RssColors.Muted,
                    cursorColor = RssColors.Blue,
                ),
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item { FilterPill("Unread", unreadOnly, onClick = { unreadOnly = !unreadOnly }) }
                item { FilterPill("Saved", savedOnly, onClick = { savedOnly = !savedOnly }) }
                item { FilterPill("AI summaries", summarizedOnly, onClick = { summarizedOnly = !summarizedOnly }) }
                if (filteredNewCount > 0) {
                    item { FilterPill("$filteredNewCount new", true, onClick = {}) }
                }
                item { FilterPill("${filtered.size} shown", true, onClick = {}) }
            }
        }
        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(title = emptyTitle, body = emptyBody)
            }
        }
        items(filtered, key = { it.articleId }) { article ->
            ArticleListCard(
                article = article,
                isBrandNew = article.articleId in brandNewArticleIds,
                onClick = { onSelect(article, filtered) },
            )
        }
    }
}

@Composable
fun FilterPill(text: String, active: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (active) RssColors.Selected else RssColors.PanelSoft),
        border = BorderStroke(1.dp, if (active) RssColors.Violet else RssColors.Line),
        shape = RoundedCornerShape(50.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            color = if (active) RssColors.Text else RssColors.Muted,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
fun ArticleListCard(article: Article, isBrandNew: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = RssColors.Panel.copy(alpha = if (article.isRead) 0.72f else 0.96f)),
        border = BorderStroke(1.dp, RssColors.Line),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(if (isBrandNew) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isBrandNew -> RssColors.Blue
                            article.isRead -> RssColors.Dim
                            else -> RssColors.Violet
                        },
                    ),
            )
            SourceBadge(source = article.source, accent = sourceAccent(article.source), size = 48)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    article.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = RssColors.Text,
                    fontWeight = FontWeight.Black,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${article.source} · ${shortDate(article.publishedAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = sourceAccent(article.source),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isBrandNew) {
                    CountPill(text = "NEW", active = true)
                }
                article.score?.let {
                    Text(it.toString(), color = RssColors.Violet, fontWeight = FontWeight.Bold)
                }
                article.comments?.let {
                    Text("$it c", color = RssColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Icon(
                    if (article.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = null,
                    tint = if (article.isSaved) RssColors.Violet else RssColors.Muted,
                )
            }
        }
    }
}
