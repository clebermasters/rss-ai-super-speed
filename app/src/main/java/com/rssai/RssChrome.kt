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

enum class RssScreen(val label: String) {
    Feeds("Feeds"),
    Articles("Articles"),
    Reader("Reader"),
    Saved("Saved"),
}

object RssColors {
    val Ink = Color(0xFF06111F)
    val InkDeep = Color(0xFF020814)
    val Panel = Color(0xFF101A29)
    val PanelSoft = Color(0xFF152236)
    val Line = Color(0xFF263449)
    val Text = Color(0xFFF4F7FB)
    val Muted = Color(0xFFA9B4C4)
    val Dim = Color(0xFF748096)
    val Blue = Color(0xFF26A7FF)
    val Violet = Color(0xFFA77BFF)
    val Green = Color(0xFF1FD96D)
    val Orange = Color(0xFFFF7A1A)
    val Red = Color(0xFFFF455C)
}

@Composable
fun ModernRssLayout(
    feeds: List<Feed>,
    providers: List<ProviderInfo>,
    articles: List<Article>,
    brandNewArticleIds: Set<String>,
    readerArticles: List<Article>,
    query: String,
    onQuery: (String) -> Unit,
    selected: Article?,
    screen: RssScreen,
    status: String,
    loading: Boolean,
    settings: Settings,
    openUrl: (String) -> Unit,
    onScreen: (RssScreen) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onAddFeed: () -> Unit,
    onManageFeed: (Feed) -> Unit,
    onSelectFeed: (Feed?) -> Unit,
    onSelect: (Article, List<Article>) -> Unit,
    onNavigateArticle: (Int) -> Unit,
    onToggleSave: () -> Unit,
    onFetchContent: () -> Unit,
    onFormatContent: () -> Unit,
    onSummarize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.background(
            Brush.verticalGradient(
                listOf(RssColors.Ink, RssColors.InkDeep),
            ),
        ),
    ) {
        Column(Modifier.fillMaxSize()) {
            ModernTopBar(
                screen = screen,
                status = status,
                unreadCount = articles.count { !it.isRead },
                newCount = brandNewArticleIds.size,
                loading = loading,
                onSearch = { onScreen(RssScreen.Articles) },
                onRefresh = onRefresh,
                onSettings = onSettings,
            )
            Box(Modifier.weight(1f)) {
                when (screen) {
                    RssScreen.Feeds -> FeedsDashboard(
                        feeds = feeds,
                        providers = providers,
                        articles = articles,
                        brandNewArticleIds = brandNewArticleIds,
                        onAddFeed = onAddFeed,
                        onManageFeed = onManageFeed,
                        onSelectFeed = onSelectFeed,
                        modifier = Modifier.fillMaxSize(),
                    )
                    RssScreen.Articles -> ArticlesDashboard(
                        articles = articles,
                        brandNewArticleIds = brandNewArticleIds,
                        query = query,
                        onQuery = onQuery,
                        onSelect = onSelect,
                        modifier = Modifier.fillMaxSize(),
                    )
                    RssScreen.Reader -> ReaderDashboard(
                        article = selected,
                        readerArticles = readerArticles.ifEmpty { articles },
                        settings = settings,
                        openUrl = openUrl,
                        onBack = { onScreen(RssScreen.Articles) },
                        onNavigateArticle = onNavigateArticle,
                        onToggleSave = onToggleSave,
                        onFetchContent = onFetchContent,
                        onFormatContent = onFormatContent,
                        onSummarize = onSummarize,
                        modifier = Modifier.fillMaxSize(),
                    )
                    RssScreen.Saved -> ArticlesDashboard(
                        articles = articles.filter { it.isSaved },
                        brandNewArticleIds = brandNewArticleIds,
                        query = query,
                        onQuery = onQuery,
                        onSelect = onSelect,
                        title = "Saved",
                        emptyTitle = "No saved articles yet",
                        emptyBody = "Bookmark articles from the reader and they will collect here.",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            ModernBottomBar(screen = screen, onScreen = onScreen)
        }
    }
}

@Composable
fun ModernTopBar(
    screen: RssScreen,
    status: String,
    unreadCount: Int,
    newCount: Int,
    loading: Boolean,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "RSS Reader",
                    style = MaterialTheme.typography.headlineMedium,
                    color = RssColors.Text,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    screen.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = RssColors.Blue,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = RssColors.Blue,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = RssColors.Text)
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = RssColors.Text)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = RssColors.Text)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(RssColors.Violet),
            )
            Text("$unreadCount unread", color = RssColors.Muted, style = MaterialTheme.typography.bodyMedium)
            if (newCount > 0) {
                Text("·", color = RssColors.Dim)
                Text("$newCount new", color = RssColors.Blue, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Text("·", color = RssColors.Dim)
            Text(status, color = RssColors.Dim, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ModernBottomBar(screen: RssScreen, onScreen: (RssScreen) -> Unit) {
    NavigationBar(
        modifier = Modifier.navigationBarsPadding(),
        containerColor = Color(0xEE0D1726),
        tonalElevation = 0.dp,
    ) {
        val items = listOf(RssScreen.Feeds, RssScreen.Articles, RssScreen.Reader, RssScreen.Saved)
        items.forEach { item ->
            NavigationBarItem(
                selected = screen == item,
                onClick = { onScreen(item) },
                icon = {
                    when (item) {
                        RssScreen.Feeds -> Icon(Icons.Default.Article, contentDescription = null)
                        RssScreen.Articles -> Icon(Icons.Default.Article, contentDescription = null)
                        RssScreen.Reader -> Icon(Icons.Default.Book, contentDescription = null)
                        RssScreen.Saved -> Icon(Icons.Default.BookmarkBorder, contentDescription = null)
                    }
                },
                label = { Text(item.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = RssColors.Violet,
                    selectedTextColor = RssColors.Violet,
                    indicatorColor = Color(0x3326A7FF),
                    unselectedIconColor = RssColors.Muted,
                    unselectedTextColor = RssColors.Muted,
                ),
            )
        }
    }
}
