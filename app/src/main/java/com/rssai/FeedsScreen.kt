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
fun FeedsDashboard(
    feeds: List<Feed>,
    providers: List<ProviderInfo>,
    articles: List<Article>,
    brandNewArticleIds: Set<String>,
    onAddFeed: () -> Unit,
    onManageFeed: (Feed) -> Unit,
    onSelectFeed: (Feed?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AddFeedCard(onClick = onAddFeed)
        }
        item {
            val hasBackendCounts = feeds.any { it.articleCount > 0 || it.unreadCount > 0 }
            val totalArticles = if (hasBackendCounts) feeds.sumOf { it.articleCount } else articles.size
            val unreadArticles = if (hasBackendCounts) feeds.sumOf { it.unreadCount } else articles.count { !it.isRead }
            val newArticles = articles.count { it.articleId in brandNewArticleIds }
            FeedOverviewCard(
                title = "All Articles",
                subtitle = "All feeds combined",
                unread = unreadArticles,
                newCount = newArticles,
                total = totalArticles,
                accent = RssColors.Blue,
                enabled = true,
                onClick = { onSelectFeed(null) },
            )
        }
        items(feeds, key = { it.feedId }) { feed ->
            val feedArticles = articles.filter { article ->
                article.sourceFeedId == feed.feedId || article.source.equals(feed.name, ignoreCase = true)
            }
            val hasBackendCount = feed.articleCount > 0 || feed.unreadCount > 0
            val newArticles = feedArticles.count { it.articleId in brandNewArticleIds }
            FeedOverviewCard(
                title = feed.name,
                subtitle = feed.url,
                unread = if (hasBackendCount) feed.unreadCount else feedArticles.count { !it.isRead },
                newCount = newArticles,
                total = feed.articleCount.takeIf { it > 0 } ?: feedArticles.size,
                accent = sourceAccent(feed.name),
                enabled = feed.enabled,
                onClick = { onSelectFeed(feed) },
                onManage = { onManageFeed(feed) },
            )
        }
        item {
            ProviderStatusCard(providers = providers)
        }
    }
}

@Composable
fun AddFeedCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = RssColors.Blue.copy(alpha = 0.16f)),
        border = BorderStroke(1.dp, RssColors.Blue.copy(alpha = 0.74f)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(RssColors.Blue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Add RSS Feed", style = MaterialTheme.typography.titleLarge, color = RssColors.Text, fontWeight = FontWeight.Black)
                Text("Subscribe to a new site by pasting its RSS URL.", style = MaterialTheme.typography.bodyMedium, color = RssColors.Muted)
            }
        }
    }
}

@Composable
fun FeedOverviewCard(
    title: String,
    subtitle: String,
    unread: Int,
    newCount: Int,
    total: Int,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    onManage: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = RssColors.Panel.copy(alpha = if (enabled) 0.92f else 0.54f)),
        border = BorderStroke(1.dp, if (title == "All Articles") RssColors.Blue else RssColors.Line),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SourceBadge(source = title, accent = accent, size = 56)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = RssColors.Text, fontWeight = FontWeight.Black)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = RssColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (newCount > 0) {
                    CountPill(text = "$newCount new", active = true)
                }
                CountPill(text = unread.toString(), active = unread > 0)
                Text("$total total", style = MaterialTheme.typography.bodySmall, color = RssColors.Muted)
                Text(if (enabled) "ON" else "OFF", color = if (enabled) RssColors.Blue else RssColors.Dim, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                if (onManage != null) {
                    TextButton(onClick = onManage) { Text("Edit") }
                }
            }
        }
    }
}

@Composable
fun ProviderStatusCard(providers: List<ProviderInfo>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RssColors.PanelSoft.copy(alpha = 0.78f)),
        border = BorderStroke(1.dp, RssColors.Line),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI Providers", style = MaterialTheme.typography.titleMedium, color = RssColors.Text, fontWeight = FontWeight.Black)
            if (providers.isEmpty()) {
                Text("Refresh to load provider status.", color = RssColors.Muted)
            }
            providers.forEach { provider ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (provider.configured) RssColors.Green else RssColors.Red),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(provider.label, color = RssColors.Text, modifier = Modifier.weight(1f))
                    Text(if (provider.configured) "ready" else "missing", color = RssColors.Muted)
                }
            }
        }
    }
}
