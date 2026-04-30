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
fun AdaptiveReaderLayout(
    feeds: List<Feed>,
    providers: List<ProviderInfo>,
    articles: List<Article>,
    query: String,
    onQuery: (String) -> Unit,
    selected: Article?,
    onBackToArticles: () -> Unit,
    onSelect: (Article) -> Unit,
    settings: Settings,
    openUrl: (String) -> Unit,
    onToggleSave: () -> Unit,
    onFetchContent: () -> Unit,
    onSummarize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.padding(16.dp)) {
        when {
            maxWidth < 720.dp -> MobileReaderLayout(
                feeds = feeds,
                providers = providers,
                articles = articles,
                query = query,
                onQuery = onQuery,
                selected = selected,
                onBackToArticles = onBackToArticles,
                onSelect = onSelect,
                settings = settings,
                openUrl = openUrl,
                onToggleSave = onToggleSave,
                onFetchContent = onFetchContent,
                onSummarize = onSummarize,
                modifier = Modifier.fillMaxSize(),
            )
            maxWidth < 1100.dp -> TabletReaderLayout(
                feeds = feeds,
                providers = providers,
                articles = articles,
                query = query,
                onQuery = onQuery,
                selected = selected,
                onSelect = onSelect,
                settings = settings,
                openUrl = openUrl,
                onToggleSave = onToggleSave,
                onFetchContent = onFetchContent,
                onSummarize = onSummarize,
                modifier = Modifier.fillMaxSize(),
            )
            else -> DesktopReaderLayout(
                feeds = feeds,
                providers = providers,
                articles = articles,
                query = query,
                onQuery = onQuery,
                selected = selected,
                onSelect = onSelect,
                settings = settings,
                openUrl = openUrl,
                onToggleSave = onToggleSave,
                onFetchContent = onFetchContent,
                onSummarize = onSummarize,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun MobileReaderLayout(
    feeds: List<Feed>,
    providers: List<ProviderInfo>,
    articles: List<Article>,
    query: String,
    onQuery: (String) -> Unit,
    selected: Article?,
    onBackToArticles: () -> Unit,
    onSelect: (Article) -> Unit,
    settings: Settings,
    openUrl: (String) -> Unit,
    onToggleSave: () -> Unit,
    onFetchContent: () -> Unit,
    onSummarize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (selected == null) {
            FeedStrip(feeds = feeds, providers = providers)
            ArticlePane(
                articles = articles,
                query = query,
                onQuery = onQuery,
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            TextButton(onClick = onBackToArticles, modifier = Modifier.fillMaxWidth()) {
                Text("Back to articles")
            }
            ReaderPane(
                article = selected,
                settings = settings,
                openUrl = openUrl,
                onToggleSave = onToggleSave,
                onFetchContent = onFetchContent,
                onSummarize = onSummarize,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
fun TabletReaderLayout(
    feeds: List<Feed>,
    providers: List<ProviderInfo>,
    articles: List<Article>,
    query: String,
    onQuery: (String) -> Unit,
    selected: Article?,
    onSelect: (Article) -> Unit,
    settings: Settings,
    openUrl: (String) -> Unit,
    onToggleSave: () -> Unit,
    onFetchContent: () -> Unit,
    onSummarize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FeedStrip(feeds = feeds, providers = providers)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ArticlePane(
                articles = articles,
                query = query,
                onQuery = onQuery,
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.weight(0.45f).fillMaxHeight(),
            )
            ReaderPane(
                article = selected,
                settings = settings,
                openUrl = openUrl,
                onToggleSave = onToggleSave,
                onFetchContent = onFetchContent,
                onSummarize = onSummarize,
                modifier = Modifier.weight(0.55f).fillMaxHeight(),
            )
        }
    }
}

@Composable
fun DesktopReaderLayout(
    feeds: List<Feed>,
    providers: List<ProviderInfo>,
    articles: List<Article>,
    query: String,
    onQuery: (String) -> Unit,
    selected: Article?,
    onSelect: (Article) -> Unit,
    settings: Settings,
    openUrl: (String) -> Unit,
    onToggleSave: () -> Unit,
    onFetchContent: () -> Unit,
    onSummarize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FeedPane(feeds = feeds, providers = providers, modifier = Modifier.weight(0.22f).fillMaxHeight())
        ArticlePane(
            articles = articles,
            query = query,
            onQuery = onQuery,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.weight(0.38f).fillMaxHeight(),
        )
        ReaderPane(
            article = selected,
            settings = settings,
            openUrl = openUrl,
            onToggleSave = onToggleSave,
            onFetchContent = onFetchContent,
            onSummarize = onSummarize,
            modifier = Modifier.weight(0.4f).fillMaxHeight(),
        )
    }
}

@Composable
fun FeedStrip(feeds: List<Feed>, providers: List<ProviderInfo>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Feeds", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                FeedChip(title = "All Articles", subtitle = "${feeds.size} feeds")
            }
            items(feeds, key = { it.feedId }) { feed ->
                FeedChip(title = feed.name, subtitle = if (feed.enabled) "enabled" else "paused")
            }
            items(providers, key = { it.id }) { provider ->
                FeedChip(title = provider.label, subtitle = if (provider.configured) "AI ready" else "missing")
            }
        }
    }
}

@Composable
fun FeedChip(title: String, subtitle: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBF4))) {
        Column(Modifier.width(180.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(loading: Boolean, status: String, onRefresh: () -> Unit, onSettings: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text("RSS AI", fontWeight = FontWeight.Black)
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        },
        actions = {
            if (loading) CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFFE2B8)),
    )
}

@Composable
fun FeedPane(feeds: List<Feed>, providers: List<ProviderInfo>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Feeds", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBF4))) {
            Column(Modifier.padding(12.dp)) {
                Text("All Articles", fontWeight = FontWeight.Bold)
                feeds.forEach { feed ->
                    Text("${if (feed.enabled) "●" else "○"} ${feed.name}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text("LLM", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        providers.forEach {
            Text("${it.label}: ${if (it.configured) "configured" else "missing"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ArticlePane(
    articles: List<Article>,
    query: String,
    onQuery: (String) -> Unit,
    selected: Article?,
    onSelect: (Article) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = query, onValueChange = onQuery, label = { Text("Search") }, modifier = Modifier.fillMaxWidth())
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(articles, key = { it.articleId }) { article ->
                val active = selected?.articleId == article.articleId
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(article) },
                    colors = CardDefaults.cardColors(containerColor = if (active) Color(0xFFFFD08A) else Color(0xFFFFFBF4)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${if (article.isRead) " " else "●"} ${article.title}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("${article.source} ${article.score?.let { " · ${it}pts" } ?: ""}", style = MaterialTheme.typography.bodyLarge)
                        if (article.isSaved) Text("★ saved", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderPane(
    article: Article?,
    settings: Settings,
    openUrl: (String) -> Unit,
    onToggleSave: () -> Unit,
    onFetchContent: () -> Unit,
    onSummarize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBF4))) {
        if (article == null) {
            Column(Modifier.padding(24.dp)) {
                Text("Select an article", style = MaterialTheme.typography.headlineSmall)
                Text("Then fetch full content, summarize, save, or open it in the browser.")
            }
            return@Card
        }
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(article.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text("${article.source} · ${article.publishedAt ?: "no date"} · ${settings.llmProvider}", style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Button(onClick = { openUrl(article.link) }) { Text("Open") } }
                item { Button(onClick = onToggleSave) { Text(if (article.isSaved) "Unsave" else "Save") } }
                item { Button(onClick = onFetchContent) { Text("Fetch Full") } }
                item { Button(onClick = onSummarize) { Text("AI") } }
            }
            article.summary?.takeIf { it.isNotBlank() }?.let {
                Text("AI Summary", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(it)
            }
            Text("Content", fontWeight = FontWeight.Bold)
            Text(article.content ?: article.contentPreview ?: article.summary ?: "No full content yet. Tap Fetch Full.")
        }
    }
}
