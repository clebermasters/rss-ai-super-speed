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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RssAiApp(::openUrl)
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

@Composable
fun RssAiApp(openUrl: (String) -> Unit) {
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("rss-ai", Context.MODE_PRIVATE)
    var apiBase by remember { mutableStateOf(prefs.getString("apiBase", BuildConfig.DEFAULT_RSS_API_BASE_URL).orEmpty()) }
    var apiToken by remember { mutableStateOf(prefs.getString("apiToken", BuildConfig.DEFAULT_RSS_API_TOKEN).orEmpty()) }
    var feeds by remember { mutableStateOf<List<Feed>>(emptyList()) }
    var articles by remember { mutableStateOf<List<Article>>(emptyList()) }
    var selected by remember { mutableStateOf<Article?>(null) }
    var readerArticles by remember { mutableStateOf<List<Article>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Configure API or refresh feeds.") }
    var loading by remember { mutableStateOf(false) }
    var preparingArticleIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentScreen by remember { mutableStateOf(RssScreen.Feeds) }
    var settings by remember {
        mutableStateOf(
            Settings(
                llmProvider = BuildConfig.DEFAULT_LLM_PROVIDER,
                aiModel = BuildConfig.DEFAULT_AI_MODEL,
                codexModel = BuildConfig.DEFAULT_CODEX_MODEL,
                aiContentFormattingEnabled = BuildConfig.DEFAULT_AI_CONTENT_FORMATTING_ENABLED,
                browserBypassEnabled = BuildConfig.DEFAULT_BROWSER_BYPASS_ENABLED,
            )
        )
    }
    var providers by remember { mutableStateOf<List<ProviderInfo>>(emptyList()) }
    var showSettings by remember { mutableStateOf(apiBase.isBlank() || apiToken.isBlank()) }
    var showAddFeed by remember { mutableStateOf(false) }
    var manageFeed by remember { mutableStateOf<Feed?>(null) }
    val scope = rememberCoroutineScope()

    fun client() = RssApiClient(apiBase, apiToken)

    fun mergeArticle(base: Article, incoming: Article): Article =
        incoming.copy(
            summary = incoming.summary ?: base.summary,
            content = incoming.content ?: base.content,
            contentPreview = incoming.contentPreview ?: base.contentPreview,
            sourceFeedId = incoming.sourceFeedId ?: base.sourceFeedId,
            score = incoming.score ?: base.score,
            comments = incoming.comments ?: base.comments,
        )

    fun updateArticleEverywhere(incoming: Article) {
        articles = articles.map { article ->
            if (article.articleId == incoming.articleId) mergeArticle(article, incoming) else article
        }
        readerArticles = readerArticles.map { article ->
            if (article.articleId == incoming.articleId) mergeArticle(article, incoming) else article
        }
        selected = selected?.let { article ->
            if (article.articleId == incoming.articleId) mergeArticle(article, incoming) else article
        }
    }

    fun latestArticleSnapshot(article: Article): Article {
        val local = selected?.takeIf { it.articleId == article.articleId }
            ?: readerArticles.firstOrNull { it.articleId == article.articleId }
            ?: articles.firstOrNull { it.articleId == article.articleId }
            ?: return article
        return mergeArticle(article, local)
    }

    fun activeReaderQueue(): List<Article> =
        readerArticles.ifEmpty { articles }.ifEmpty { selected?.let { listOf(it) } ?: emptyList() }

    fun adjacentArticle(from: Article, delta: Int): Article? {
        val queue = activeReaderQueue()
        val index = queue.indexOfFirst { it.articleId == from.articleId }
        return queue.getOrNull(index + delta)
    }

    suspend fun awaitContentResult(initial: FetchContentResponse, background: Boolean, actionLabel: String): FetchContentResponse {
        var result = initial
        val jobId = result.jobId
        if (result.status != "completed" && !jobId.isNullOrBlank()) {
            if (!background) {
                status = result.message ?: "$actionLabel queued"
            }
            repeat(30) {
                if (result.status != "completed" && result.status != "failed") {
                    delay(if (background) 5000 else 3000)
                    result = client().contentJob(jobId)
                    if (!background) {
                        status = result.message ?: "$actionLabel ${result.status}"
                    }
                }
            }
        }
        return result
    }

    suspend fun prepareArticleForReading(
        article: Article,
        background: Boolean = false,
        markRead: Boolean = true,
        forceFetch: Boolean = false,
        formatOnly: Boolean = false,
    ): Article? {
        if (apiBase.isBlank() || apiToken.isBlank()) {
            if (!background) showSettings = true
            return null
        }
        if (background && article.articleId in preparingArticleIds) {
            return null
        }
        val currentArticle = latestArticleSnapshot(article)
        val hasFullContent = !currentArticle.content.isNullOrBlank()
        val needsFormatting = settings.aiContentFormattingEnabled && !currentArticle.contentAiFormatted
        if (!forceFetch && !formatOnly && hasFullContent && !needsFormatting) {
            return currentArticle
        }
        if (formatOnly && (currentArticle.content ?: currentArticle.contentPreview ?: currentArticle.summary).isNullOrBlank()) {
            if (!background) {
                status = "No article content available. Fetch full content first."
            }
            return null
        }

        preparingArticleIds = preparingArticleIds + article.articleId
        if (!background) {
            loading = true
            status = if (formatOnly) "Formatting article for mobile reading..." else "Fetching full article content..."
        }
        return try {
            val shouldFormatExisting = formatOnly || (!forceFetch && hasFullContent && needsFormatting)
            val actionLabel = if (shouldFormatExisting) "AI formatting" else "Full-content fetch"
            var result = if (shouldFormatExisting) {
                client().formatContent(currentArticle.articleId, markRead = markRead)
            } else {
                client().fetchContent(
                    currentArticle.articleId,
                    formatWithAi = settings.aiContentFormattingEnabled,
                    markRead = markRead,
                )
            }
            result = awaitContentResult(result, background = background, actionLabel = actionLabel)
            if (result.status == "failed") {
                if (!background) {
                    status = result.message ?: "$actionLabel failed"
                }
                return null
            }
            if (result.status != "completed") {
                if (!background) {
                    status = result.message ?: "$actionLabel is still running"
                }
                return null
            }
            val updated = result.article
                ?: if (result.content.isNotBlank()) {
                    currentArticle.copy(
                        content = result.content,
                        contentAiFormatted = result.contentAiFormatted,
                        isRead = currentArticle.isRead || markRead,
                    )
                } else {
                    runCatching { client().article(currentArticle.articleId) }.getOrNull()
                }
            updated?.let {
                val finalArticle = if (markRead) it.copy(isRead = true) else it
                updateArticleEverywhere(finalArticle)
                if (!background) {
                    status = when {
                        result.contentAiFormatted -> "Article is fetched and AI formatted"
                        result.contentFormattingAttempted && result.contentFormattingError != null ->
                            "Article fetched; AI formatting failed: ${result.contentFormattingError}"
                        result.contentFormattingAttempted -> "Article fetched; AI formatting was not applied"
                        shouldFormatExisting -> "AI formatting complete"
                        else -> "Full article content ready"
                    }
                }
                finalArticle
            }
        } catch (exc: Exception) {
            if (!background) {
                status = exc.message ?: "Article preparation failed"
            }
            null
        } finally {
            preparingArticleIds = preparingArticleIds - currentArticle.articleId
            if (!background) {
                loading = false
            }
        }
    }

    fun prewarmAdjacentArticle(article: Article, delta: Int) {
        val next = adjacentArticle(article, delta) ?: return
        if (next.articleId in preparingArticleIds) return
        scope.launch {
            prepareArticleForReading(next, background = true, markRead = false)
        }
    }

    fun prewarmAdjacentArticles(article: Article) {
        prewarmAdjacentArticle(article, -1)
        prewarmAdjacentArticle(article, 1)
    }

    fun openArticle(article: Article, queue: List<Article> = activeReaderQueue(), prewarmDelta: Int = 1) {
        val scopedQueue = queue.takeIf { items -> items.any { it.articleId == article.articleId } }
            ?: articles.takeIf { items -> items.any { it.articleId == article.articleId } }
            ?: listOf(article)
        readerArticles = scopedQueue
        val localArticle = latestArticleSnapshot(article).copy(isRead = true)
        selected = localArticle
        currentScreen = RssScreen.Reader
        updateArticleEverywhere(localArticle)
        scope.launch {
            loading = true
            val opened = runCatching {
                client().markRead(localArticle.articleId)
                client().article(localArticle.articleId).copy(isRead = true)
            }.getOrElse {
                localArticle
            }
            updateArticleEverywhere(opened)
            loading = false
            val currentPreparation = launch {
                prepareArticleForReading(opened, background = false, markRead = true)
            }
            prewarmAdjacentArticles(opened)
            currentPreparation.join()
            val prepared = latestArticleSnapshot(opened)
            prewarmAdjacentArticles(prepared)
            prewarmAdjacentArticle(prepared, prewarmDelta)
        }
    }

    fun navigateArticle(delta: Int) {
        val current = selected ?: return
        val target = adjacentArticle(current, delta) ?: return
        openArticle(target, queue = activeReaderQueue(), prewarmDelta = delta)
    }

    fun loadData(refreshFirst: Boolean = false, searchQuery: String = query) {
        if (apiBase.isBlank() || apiToken.isBlank()) {
            showSettings = true
            return
        }
        scope.launch {
            loading = true
            runCatching {
                if (refreshFirst) client().refresh()
                val bootstrap = client().bootstrap()
                feeds = bootstrap.feeds
                settings = bootstrap.settings
                providers = client().providers().providers
                articles = client().articles(searchQuery).articles
            }.onSuccess {
                status = "Loaded ${articles.size} articles"
            }.onFailure {
                status = it.message ?: "Load failed"
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (apiBase.isNotBlank() && apiToken.isNotBlank()) {
            loadData()
        }
    }

    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            background = RssColors.Ink,
            surface = RssColors.Panel,
            primary = RssColors.Blue,
            secondary = RssColors.Violet,
            onBackground = RssColors.Text,
            onSurface = RssColors.Text,
        ),
    ) {
        Surface(Modifier.fillMaxSize()) {
            ModernRssLayout(
                feeds = feeds,
                providers = providers,
                articles = articles,
                readerArticles = readerArticles,
                query = query,
                onQuery = {
                    query = it
                    loadData(searchQuery = it)
                },
                selected = selected,
                screen = currentScreen,
                status = status,
                loading = loading,
                settings = settings,
                openUrl = openUrl,
                onScreen = { currentScreen = it },
                onRefresh = { loadData(refreshFirst = true) },
                onSettings = { showSettings = true },
                onAddFeed = { showAddFeed = true },
                onManageFeed = { manageFeed = it },
                onSelectFeed = { feed ->
                    val feedQuery = feed?.name.orEmpty()
                    query = feedQuery
                    currentScreen = RssScreen.Articles
                    loadData(searchQuery = feedQuery)
                },
                onSelect = { article, queue ->
                    openArticle(article, queue = queue)
                },
                onNavigateArticle = { delta -> navigateArticle(delta) },
                onToggleSave = {
                    scope.launch {
                        selected?.let { article ->
                            runCatching { client().toggleSave(article.articleId) }.onSuccess {
                                updateArticleEverywhere(it)
                            }
                        }
                    }
                },
                onFetchContent = {
                    scope.launch {
                        selected?.let { article ->
                            val prepared = prepareArticleForReading(article, background = false, markRead = true, forceFetch = true)
                            prewarmAdjacentArticles(prepared ?: article)
                        }
                    }
                },
                onFormatContent = {
                    scope.launch {
                        selected?.let { article ->
                            val currentContent = article.content ?: article.contentPreview ?: article.summary
                            if (currentContent.isNullOrBlank()) {
                                status = "No article content available. Fetch full content first."
                                return@let
                            }
                            val prepared = prepareArticleForReading(article, background = false, markRead = true, formatOnly = true)
                            prewarmAdjacentArticles(prepared ?: article)
                        }
                    }
                },
                onSummarize = {
                    scope.launch {
                        selected?.let { article ->
                            loading = true
                            runCatching { client().summarize(article.articleId) }.onSuccess {
                                updateArticleEverywhere(article.copy(summary = it.summary))
                                status = "Summary ready"
                            }.onFailure {
                                status = it.message ?: "Summary failed"
                            }
                            loading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (showSettings) {
                SettingsDialog(
                    apiBase = apiBase,
                    apiToken = apiToken,
                    settings = settings,
                    providers = providers,
                    onDismiss = { showSettings = false },
                    onSave = { newBase, newToken, newSettings ->
                        apiBase = newBase
                        apiToken = newToken
                        settings = newSettings
                        prefs.edit()
                            .putString("apiBase", apiBase)
                            .putString("apiToken", apiToken)
                            .apply()
                        showSettings = false
                        scope.launch {
                            runCatching { client().updateSettings(newSettings) }
                            loadData()
                        }
                    },
                )
            }
            if (showAddFeed) {
                AddFeedDialog(
                    onDismiss = { showAddFeed = false },
                    onSave = { request ->
                        if (apiBase.isBlank() || apiToken.isBlank()) {
                            showAddFeed = false
                            showSettings = true
                            return@AddFeedDialog
                        }
                        showAddFeed = false
                        scope.launch {
                            loading = true
                            status = "Adding RSS feed..."
                            runCatching {
                                val created = client().createFeed(request)
                                val refreshError = if (created.enabled) {
                                    runCatching { client().refreshFeed(created.feedId) }.exceptionOrNull()
                                } else {
                                    null
                                }
                                val bootstrap = client().bootstrap()
                                feeds = bootstrap.feeds
                                settings = bootstrap.settings
                                providers = client().providers().providers
                                query = ""
                                articles = client().articles().articles
                                currentScreen = RssScreen.Feeds
                                created to refreshError
                            }.onSuccess {
                                val refreshError = it.second
                                status = if (!it.first.enabled) {
                                    "Added ${it.first.name}"
                                } else if (refreshError == null) {
                                    "Added and refreshed ${it.first.name}"
                                } else {
                                    "Added ${it.first.name}; refresh failed"
                                }
                            }.onFailure {
                                status = it.message ?: "Add feed failed"
                            }
                            loading = false
                        }
                    },
                )
            }
            manageFeed?.let { feed ->
                ManageFeedDialog(
                    feed = feed,
                    onDismiss = { manageFeed = null },
                    onSave = { request ->
                        manageFeed = null
                        scope.launch {
                            loading = true
                            status = "Updating RSS feed..."
                            runCatching {
                                val updated = client().updateFeed(feed.feedId, request)
                                val refreshError = if (updated.enabled) {
                                    runCatching { client().refreshFeed(updated.feedId) }.exceptionOrNull()
                                } else {
                                    null
                                }
                                val bootstrap = client().bootstrap()
                                feeds = bootstrap.feeds
                                settings = bootstrap.settings
                                providers = client().providers().providers
                                query = ""
                                articles = client().articles().articles
                                currentScreen = RssScreen.Feeds
                                updated to refreshError
                            }.onSuccess {
                                status = if (!it.first.enabled) {
                                    "Updated ${it.first.name}"
                                } else if (it.second == null) {
                                    "Updated and refreshed ${it.first.name}"
                                } else {
                                    "Updated ${it.first.name}; refresh failed"
                                }
                            }.onFailure {
                                status = it.message ?: "Update feed failed"
                            }
                            loading = false
                        }
                    },
                    onDelete = {
                        manageFeed = null
                        scope.launch {
                            loading = true
                            status = "Removing RSS feed..."
                            runCatching {
                                client().deleteFeed(feed.feedId)
                                val bootstrap = client().bootstrap()
                                feeds = bootstrap.feeds
                                settings = bootstrap.settings
                                providers = client().providers().providers
                                if (query.equals(feed.name, ignoreCase = true)) {
                                    query = ""
                                }
                                articles = client().articles(query).articles
                                currentScreen = RssScreen.Feeds
                                feed
                            }.onSuccess {
                                status = "Removed ${it.name}"
                            }.onFailure {
                                status = it.message ?: "Remove feed failed"
                            }
                            loading = false
                        }
                    },
                )
            }
        }
    }
}

private enum class RssScreen(val label: String) {
    Feeds("Feeds"),
    Articles("Articles"),
    Reader("Reader"),
    Saved("Saved"),
}

private object RssColors {
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
private fun ModernRssLayout(
    feeds: List<Feed>,
    providers: List<ProviderInfo>,
    articles: List<Article>,
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
                        onAddFeed = onAddFeed,
                        onManageFeed = onManageFeed,
                        onSelectFeed = onSelectFeed,
                        modifier = Modifier.fillMaxSize(),
                    )
                    RssScreen.Articles -> ArticlesDashboard(
                        articles = articles,
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
private fun ModernTopBar(
    screen: RssScreen,
    status: String,
    unreadCount: Int,
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
            Text("·", color = RssColors.Dim)
            Text(status, color = RssColors.Dim, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ModernBottomBar(screen: RssScreen, onScreen: (RssScreen) -> Unit) {
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

@Composable
private fun FeedsDashboard(
    feeds: List<Feed>,
    providers: List<ProviderInfo>,
    articles: List<Article>,
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
            FeedOverviewCard(
                title = "All Articles",
                subtitle = "All feeds combined",
                unread = unreadArticles,
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
            FeedOverviewCard(
                title = feed.name,
                subtitle = feed.url,
                unread = if (hasBackendCount) feed.unreadCount else feedArticles.count { !it.isRead },
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
private fun AddFeedCard(onClick: () -> Unit) {
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
private fun FeedOverviewCard(
    title: String,
    subtitle: String,
    unread: Int,
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
private fun ProviderStatusCard(providers: List<ProviderInfo>) {
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

@Composable
private fun ArticlesDashboard(
    articles: List<Article>,
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
                item { FilterPill("${filtered.size} shown", true, onClick = {}) }
            }
        }
        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(title = emptyTitle, body = emptyBody)
            }
        }
        items(filtered, key = { it.articleId }) { article ->
            ArticleListCard(article = article, onClick = { onSelect(article, filtered) })
        }
    }
}

@Composable
private fun FilterPill(text: String, active: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (active) Color(0x3326A7FF) else RssColors.PanelSoft),
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
private fun ArticleListCard(article: Article, onClick: () -> Unit) {
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
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (article.isRead) RssColors.Dim else RssColors.Violet),
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

@Composable
private fun ReaderDashboard(
    article: Article?,
    readerArticles: List<Article>,
    settings: Settings,
    openUrl: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateArticle: (Int) -> Unit,
    onToggleSave: () -> Unit,
    onFetchContent: () -> Unit,
    onFormatContent: () -> Unit,
    onSummarize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (article == null) {
        LazyColumn(
            modifier,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                EmptyStateCard(
                    title = "Select an article",
                    body = "Open Articles, tap a story, then swipe left or right to keep reading through that list.",
                    action = "Open Articles",
                    onAction = onBack,
                )
            }
        }
        return
    }

    val currentIndex = readerArticles.indexOfFirst { it.articleId == article.articleId }
    val canGoPrevious = currentIndex > 0
    val canGoNext = currentIndex >= 0 && currentIndex < readerArticles.lastIndex
    val positionLabel = if (currentIndex >= 0 && readerArticles.isNotEmpty()) {
        "${currentIndex + 1} of ${readerArticles.size}"
    } else {
        "Reader"
    }
    val swipeModifier = modifier.pointerInput(article.articleId, canGoPrevious, canGoNext) {
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
            onDragEnd = {
                when {
                    totalDrag < -120f && canGoNext -> onNavigateArticle(1)
                    totalDrag > 120f && canGoPrevious -> onNavigateArticle(-1)
                }
            },
        )
    }

    LazyColumn(
        swipeModifier,
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = RssColors.Text)
                }
                Text(
                    "$positionLabel · ${article.source} · ${shortDate(article.publishedAt)}",
                    modifier = Modifier.weight(1f),
                    color = RssColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = onToggleSave) {
                    Icon(
                        if (article.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Save",
                        tint = if (article.isSaved) RssColors.Violet else RssColors.Text,
                    )
                }
            }
            Text(
                article.title,
                style = MaterialTheme.typography.headlineSmall,
                color = RssColors.Text,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                article.score?.let { CountPill(text = it.toString(), active = true) }
                article.comments?.let { Text("$it comments", color = RssColors.Muted) }
                Text(settings.llmProvider, color = RssColors.Violet, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        onClick = { onNavigateArticle(-1) },
                        enabled = canGoPrevious,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Previous")
                    }
                    CountPill(text = "Swipe left/right", active = true)
                    TextButton(
                        onClick = { onNavigateArticle(1) },
                        enabled = canGoNext,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Next")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { openUrl(article.link) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Open")
                    }
                    Button(onClick = onToggleSave, modifier = Modifier.weight(1f)) {
                        Text(if (article.isSaved) "Unsave" else "Save")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onFetchContent, modifier = Modifier.weight(1f)) { Text("Fetch Full") }
                    Button(
                        onClick = onFormatContent,
                        enabled = !(article.content ?: article.contentPreview ?: article.summary).isNullOrBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Format")
                    }
                    Button(onClick = onSummarize, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("AI")
                    }
                }
            }
        }
        item {
            AiSummaryCard(summary = article.summary, onSummarize = onSummarize)
        }
        item {
            ReaderContentCard(
                content = article.content ?: article.contentPreview ?: article.summary,
                aiFormatted = article.contentAiFormatted,
            )
        }
    }
}

@Composable
private fun AiSummaryCard(summary: String?, onSummarize: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RssColors.PanelSoft.copy(alpha = 0.86f)),
        border = BorderStroke(1.dp, RssColors.Line),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = RssColors.Violet)
                Text("AI Summary", style = MaterialTheme.typography.titleLarge, color = RssColors.Violet, fontWeight = FontWeight.Black)
            }
            if (summary.isNullOrBlank()) {
                Text("No AI summary yet. Generate one from the configured backend provider.", color = RssColors.Muted)
                TextButton(onClick = onSummarize) { Text("Generate summary") }
            } else {
                RichArticleText(
                    content = summary,
                    modifier = Modifier.fillMaxWidth(),
                    textSizeSp = 18f,
                    lineSpacingExtra = 8f,
                )
            }
        }
    }
}

@Composable
private fun ReaderContentCard(content: String?, aiFormatted: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, RssColors.Line),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Article Content", style = MaterialTheme.typography.titleLarge, color = RssColors.Text, fontWeight = FontWeight.Black)
                if (aiFormatted) {
                    CountPill(text = "AI formatted", active = true)
                }
            }
            RichArticleText(
                content = content?.takeIf { it.isNotBlank() }
                    ?: "No full content yet. Use Fetch Full to run direct extraction, browser bypass, and Wayback fallback.",
                modifier = Modifier.fillMaxWidth(),
                textSizeSp = 18f,
                lineSpacingExtra = 9f,
            )
        }
    }
}

@Composable
private fun RichArticleText(
    content: String,
    modifier: Modifier = Modifier,
    textSizeSp: Float = 18f,
    lineSpacingExtra: Float = 8f,
) {
    val html = remember(content) { safeRichHtml(content) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                setTextColor(RssColors.Text.toArgb())
                setLinkTextColor(RssColors.Violet.toArgb())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setLineSpacing(lineSpacingExtra, 1.08f)
                includeFontPadding = true
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
            }
        },
        update = { view ->
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            view.setLineSpacing(lineSpacingExtra, 1.08f)
            view.text = htmlToSpanned(html)
            Linkify.addLinks(view, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
            view.movementMethod = LinkMovementMethod.getInstance()
        },
    )
}

private fun htmlToSpanned(html: String): CharSequence {
    return runCatching {
        Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
    }.getOrElse {
        Html.fromHtml(plainTextToHtml(stripMarkdownArtifacts(html)), Html.FROM_HTML_MODE_COMPACT)
    }
}

private enum class HtmlListMode(val tag: String) {
    Ordered("ol"),
    Unordered("ul"),
}

private fun safeRichHtml(content: String): String {
    val trimmed = content.trim()
    if (trimmed.isBlank()) {
        return plainTextToHtml("")
    }
    return runCatching {
        if (looksLikeHtml(trimmed)) {
            sanitizeExistingHtml(trimmed)
        } else {
            markdownToHtml(trimmed)
        }
    }.getOrElse {
        plainTextToHtml(stripMarkdownArtifacts(trimmed))
    }
}

private fun looksLikeHtml(content: String): Boolean {
    return Regex(
        """</?(p|br|h[1-6]|ul|ol|li|strong|b|em|i|a|blockquote|pre|code)\b""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(content)
}

private fun sanitizeExistingHtml(content: String): String {
    var html = content
        .replace(Regex("""(?is)<script\b[^>]*>.*?</script>"""), "")
        .replace(Regex("""(?is)<style\b[^>]*>.*?</style>"""), "")
        .replace(Regex("""(?is)<svg\b[^>]*>.*?</svg>"""), "")
        .replace(Regex("""(?is)<head\b[^>]*>.*?</head>"""), "")
        .replace(Regex("""(?i)\son\w+\s*=\s*(['"]).*?\1"""), "")
        .replace(Regex("""(?i)javascript:"""), "")
    Regex("""(?is)<body\b[^>]*>(.*?)</body>""").find(html)?.let {
        html = it.groupValues[1]
    }
    html = html.replace(Regex("""(?is)<img\b[^>]*>""")) { match ->
        val src = Regex("""(?i)\bsrc\s*=\s*(['"])(.*?)\1""").find(match.value)?.groupValues?.getOrNull(2).orEmpty()
        val alt = Regex("""(?i)\balt\s*=\s*(['"])(.*?)\1""").find(match.value)?.groupValues?.getOrNull(2).orEmpty()
        val label = escapeHtml(alt.ifBlank { "Open image" })
        val href = safeHref(src)
        if (href == "#") "" else """<p><a href="$href">$label</a></p>"""
    }
    return html.ifBlank { plainTextToHtml(stripMarkdownArtifacts(content)) }
}

private fun markdownToHtml(content: String): String {
    val html = StringBuilder()
    val paragraph = mutableListOf<String>()
    var listMode: HtmlListMode? = null
    var inCodeBlock = false
    val codeBlock = StringBuilder()

    fun closeList() {
        listMode?.let { html.append("</").append(it.tag).append("><br>") }
        listMode = null
    }

    fun openList(mode: HtmlListMode) {
        if (listMode != mode) {
            closeList()
            html.append("<").append(mode.tag).append(">")
            listMode = mode
        }
    }

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        closeList()
        html.append("<p>")
            .append(inlineMarkdownToHtml(paragraph.joinToString(" ").trim()))
            .append("</p><br>")
        paragraph.clear()
    }

    fun flushCodeBlock() {
        if (codeBlock.isEmpty()) return
        flushParagraph()
        closeList()
        html.append("<pre><code>")
            .append(escapeHtml(codeBlock.toString().trimEnd()))
            .append("</code></pre>")
        codeBlock.clear()
    }

    content
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    inCodeBlock = false
                    flushCodeBlock()
                } else {
                    flushParagraph()
                    closeList()
                    inCodeBlock = true
                }
                return@forEach
            }

            if (inCodeBlock) {
                codeBlock.append(rawLine).append('\n')
                return@forEach
            }

            if (trimmed.isBlank()) {
                flushParagraph()
                closeList()
                return@forEach
            }

            Regex("""^(#{1,6})\s+(.+)$""").matchEntire(trimmed)?.let { match ->
                flushParagraph()
                closeList()
                val level = match.groupValues[1].length.coerceIn(2, 4)
                html.append("<br><h").append(level).append(">")
                    .append(inlineMarkdownToHtml(match.groupValues[2]))
                    .append("</h").append(level).append("><br>")
                return@forEach
            }

            if (Regex("""^[-*_]{3,}$""").matches(trimmed)) {
                flushParagraph()
                closeList()
                html.append("<hr>")
                return@forEach
            }

            Regex("""^!\[([^]]*)]\(([^)\s]+)(?:\s+"[^"]*")?\)$""").matchEntire(trimmed)?.let { match ->
                flushParagraph()
                closeList()
                val label = escapeHtml(match.groupValues[1].ifBlank { "Open image" })
                html.append("""<p><a href="${safeHref(match.groupValues[2])}">$label</a></p>""")
                return@forEach
            }

            Regex("""^[-*+•]\s+(.+)$""").matchEntire(trimmed)?.let { match ->
                flushParagraph()
                openList(HtmlListMode.Unordered)
                html.append("<li>").append(inlineMarkdownToHtml(match.groupValues[1])).append("</li><br>")
                return@forEach
            }

            Regex("""^\d+[.)]\s+(.+)$""").matchEntire(trimmed)?.let { match ->
                flushParagraph()
                openList(HtmlListMode.Ordered)
                html.append("<li>").append(inlineMarkdownToHtml(match.groupValues[1])).append("</li><br>")
                return@forEach
            }

            Regex("""^>\s?(.+)$""").matchEntire(trimmed)?.let { match ->
                flushParagraph()
                closeList()
                html.append("<blockquote>")
                    .append(inlineMarkdownToHtml(match.groupValues[1]))
                    .append("</blockquote>")
                return@forEach
            }

            paragraph.add(trimmed)
        }

    if (inCodeBlock) flushCodeBlock()
    flushParagraph()
    closeList()
    return html.toString().ifBlank { plainTextToHtml(stripMarkdownArtifacts(content)) }
}

private fun inlineMarkdownToHtml(text: String): String {
    var html = escapeHtml(text)
    html = Regex("""!\[([^]]*)]\(([^)\s]+)(?:\s+&quot;[^&]*&quot;)?\)""").replace(html) { match ->
        val label = match.groupValues[1].ifBlank { "Open image" }
        """<a href="${safeHref(match.groupValues[2])}">${escapeHtml(label)}</a>"""
    }
    html = Regex("""(?<!!)\[([^]]+)]\(([^)\s]+)(?:\s+&quot;[^&]*&quot;)?\)""").replace(html) { match ->
        """<a href="${safeHref(match.groupValues[2])}">${match.groupValues[1]}</a>"""
    }
    html = Regex("""\*\*([^*]+)\*\*""").replace(html) { "<strong>${it.groupValues[1]}</strong>" }
    html = Regex("""__([^_]+)__""").replace(html) { "<strong>${it.groupValues[1]}</strong>" }
    html = Regex("""`([^`]+)`""").replace(html) { "<code>${it.groupValues[1]}</code>" }
    html = Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)""").replace(html) { "<em>${it.groupValues[1]}</em>" }
    html = Regex("""(?<!\w)_([^_\n]+)_(?!\w)""").replace(html) { "<em>${it.groupValues[1]}</em>" }
    return stripLooseMarkdownMarkers(html)
}

private fun stripLooseMarkdownMarkers(html: String): String {
    return html
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("""(^|\s)#{1,6}\s+"""), "$1")
}

private fun stripMarkdownArtifacts(content: String): String {
    var text = content
        .replace(Regex("""!\[([^]]*)]\(([^)]+)\)""")) { match ->
            match.groupValues[1].ifBlank { match.groupValues[2] }
        }
        .replace(Regex("""(?<!!)\[([^]]+)]\(([^)]+)\)""")) { match ->
            "${match.groupValues[1]} (${match.groupValues[2]})"
        }
    text = text.lines().joinToString("\n") { line ->
        line
            .replace(Regex("""^\s{0,3}#{1,6}\s+"""), "")
            .replace(Regex("""^\s*[-*+]\s+"""), "• ")
            .replace(Regex("""^\s*\d+[.)]\s+"""), "• ")
            .replace(Regex("""^\s*>\s?"""), "")
    }
    return text
        .replace("**", "")
        .replace("__", "")
        .replace("```", "")
        .replace("`", "")
        .trim()
}

private fun plainTextToHtml(content: String): String {
    if (content.isBlank()) return "<p></p>"
    return content
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split(Regex("""\n{2,}"""))
        .joinToString("") { paragraph ->
            "<p>${escapeHtml(paragraph.trim()).replace("\n", "<br>")}</p>"
        }
}

private fun safeHref(raw: String): String {
    val href = raw
        .trim()
        .trim('"', '\'')
        .replace("&amp;", "&")
    if (!href.startsWith("http://", ignoreCase = true)
        && !href.startsWith("https://", ignoreCase = true)
        && !href.startsWith("mailto:", ignoreCase = true)
    ) {
        return "#"
    }
    return escapeAttribute(href)
}

private fun escapeHtml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun escapeAttribute(value: String): String {
    return escapeHtml(value)
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

@Composable
private fun EmptyStateCard(title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RssColors.PanelSoft.copy(alpha = 0.82f)),
        border = BorderStroke(1.dp, RssColors.Line),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = RssColors.Text, fontWeight = FontWeight.Black)
            Text(body, color = RssColors.Muted)
            if (action != null && onAction != null) {
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
private fun CountPill(text: String, active: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) RssColors.Blue else RssColors.Line)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = RssColors.Text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SourceBadge(source: String, accent: Color = sourceAccent(source), size: Int = 48) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp))
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            sourceInitials(source),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
        )
    }
}

private fun sourceInitials(source: String): String {
    val normalized = source.trim()
    return when {
        normalized.contains("hacker", ignoreCase = true) -> "Y"
        normalized.contains("all articles", ignoreCase = true) -> "RSS"
        normalized.contains("techcrunch", ignoreCase = true) -> "TC"
        normalized.contains("venture", ignoreCase = true) -> "VB"
        normalized.contains("openai", ignoreCase = true) -> "AI"
        normalized.contains("product", ignoreCase = true) -> "P"
        normalized.isBlank() -> "R"
        else -> normalized.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.take(2)
    }
}

private fun sourceAccent(source: String): Color {
    return when {
        source.contains("hacker", ignoreCase = true) -> RssColors.Orange
        source.contains("techcrunch", ignoreCase = true) -> RssColors.Green
        source.contains("venture", ignoreCase = true) -> RssColors.Red
        source.contains("openai", ignoreCase = true) -> Color(0xFF0E766E)
        source.contains("product", ignoreCase = true) -> Color(0xFFE8562A)
        source.contains("all articles", ignoreCase = true) -> RssColors.Blue
        else -> listOf(RssColors.Blue, RssColors.Violet, RssColors.Green, RssColors.Orange)[kotlin.math.abs(source.hashCode()) % 4]
    }
}

private fun shortDate(value: String?): String {
    val text = value.orEmpty()
    if (text.isBlank()) return "updated"
    val normalized = text
        .replace("T", " ")
        .replace("Z", "")
    if ("," in normalized) {
        return normalized.substringAfter(",").trim().split(" ").take(3).joinToString(" ")
    }
    return normalized.take(16)
}

@Composable
private fun AdaptiveReaderLayout(
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
private fun MobileReaderLayout(
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
private fun TabletReaderLayout(
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
private fun DesktopReaderLayout(
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
private fun FeedStrip(feeds: List<Feed>, providers: List<ProviderInfo>) {
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
private fun FeedChip(title: String, subtitle: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBF4))) {
        Column(Modifier.width(180.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBar(loading: Boolean, status: String, onRefresh: () -> Unit, onSettings: () -> Unit) {
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
private fun FeedPane(feeds: List<Feed>, providers: List<ProviderInfo>, modifier: Modifier = Modifier) {
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
private fun ArticlePane(
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
private fun ReaderPane(
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

@Composable
private fun AddFeedDialog(
    onDismiss: () -> Unit,
    onSave: (CreateFeedRequest) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("20") }
    var enabled by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val cleanUrl = url.trim()
        val parsedLimit = limit.trim().toIntOrNull()
        error = when {
            cleanUrl.isBlank() -> "RSS URL is required."
            !cleanUrl.startsWith("https://", ignoreCase = true)
                && !cleanUrl.startsWith("http://", ignoreCase = true) -> "RSS URL must start with http:// or https://."
            parsedLimit == null || parsedLimit !in 1..100 -> "Article limit must be a number from 1 to 100."
            else -> null
        }
        if (error != null) {
            return
        }
        onSave(
            CreateFeedRequest(
                name = name.trim().ifBlank { null },
                url = cleanUrl,
                enabled = enabled,
                tags = tags.split(",").map { it.trim() }.filter { it.isNotBlank() },
                limit = parsedLimit ?: 20,
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = ::submit) { Text("Add feed") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add RSS Feed") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Paste the RSS feed URL for the site you want to follow.", color = RssColors.Muted)
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text("RSS URL") },
                    placeholder = { Text("https://example.com/feed.xml") },
                    isError = error != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name, optional") },
                    placeholder = { Text("Site name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it.filter(Char::isDigit).take(3) },
                    label = { Text("Articles per refresh") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags, optional") },
                    placeholder = { Text("tech, ai, security") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(enabled, { enabled = it })
                    Text("Enable this feed")
                }
                error?.let {
                    Text(it, color = RssColors.Red, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

@Composable
private fun ManageFeedDialog(
    feed: Feed,
    onDismiss: () -> Unit,
    onSave: (CreateFeedRequest) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(feed.feedId) { mutableStateOf(feed.name) }
    var url by remember(feed.feedId) { mutableStateOf(feed.url) }
    var tags by remember(feed.feedId) { mutableStateOf(feed.tags.joinToString(", ")) }
    var limit by remember(feed.feedId) { mutableStateOf(feed.limit.toString()) }
    var enabled by remember(feed.feedId) { mutableStateOf(feed.enabled) }
    var confirmDelete by remember(feed.feedId) { mutableStateOf(false) }
    var error by remember(feed.feedId) { mutableStateOf<String?>(null) }

    fun submit() {
        val cleanUrl = url.trim()
        val parsedLimit = limit.trim().toIntOrNull()
        error = when {
            cleanUrl.isBlank() -> "RSS URL is required."
            !cleanUrl.startsWith("https://", ignoreCase = true)
                && !cleanUrl.startsWith("http://", ignoreCase = true) -> "RSS URL must start with http:// or https://."
            parsedLimit == null || parsedLimit !in 1..100 -> "Article limit must be a number from 1 to 100."
            else -> null
        }
        if (error != null) {
            return
        }
        onSave(
            CreateFeedRequest(
                name = name.trim().ifBlank { feed.name },
                url = cleanUrl,
                enabled = enabled,
                tags = tags.split(",").map { it.trim() }.filter { it.isNotBlank() },
                limit = parsedLimit ?: feed.limit.coerceIn(1, 100),
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = ::submit) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Edit RSS Feed") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text("RSS URL") },
                    isError = error != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it.filter(Char::isDigit).take(3) },
                    label = { Text("Articles per refresh") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags") },
                    placeholder = { Text("tech, ai, security") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(enabled, { enabled = it })
                    Text("Enable this feed")
                }
                error?.let {
                    Text(it, color = RssColors.Red, style = MaterialTheme.typography.bodySmall)
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = RssColors.Red.copy(alpha = 0.10f)),
                    border = BorderStroke(1.dp, RssColors.Red.copy(alpha = 0.42f)),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Danger zone", color = RssColors.Red, fontWeight = FontWeight.Bold)
                        Text("Removing this feed stops future refreshes. Existing articles may remain until cleanup.", color = RssColors.Muted)
                        if (confirmDelete) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
                                TextButton(onClick = onDelete) { Text("Delete permanently", color = RssColors.Red) }
                            }
                        } else {
                            TextButton(onClick = { confirmDelete = true }) { Text("Remove feed", color = RssColors.Red) }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SettingsDialog(
    apiBase: String,
    apiToken: String,
    settings: Settings,
    providers: List<ProviderInfo>,
    onDismiss: () -> Unit,
    onSave: (String, String, Settings) -> Unit,
) {
    var base by remember { mutableStateOf(apiBase) }
    var token by remember { mutableStateOf(apiToken) }
    var llmProvider by remember { mutableStateOf(settings.llmProvider) }
    var aiModel by remember { mutableStateOf(settings.aiModel) }
    var codexModel by remember { mutableStateOf(settings.codexModel) }
    var embeddingModel by remember { mutableStateOf(settings.embeddingModel) }
    var aiContentFormatting by remember { mutableStateOf(settings.aiContentFormattingEnabled) }
    var browserBypass by remember { mutableStateOf(settings.browserBypassEnabled) }
    var browserMode by remember { mutableStateOf(settings.browserBypassMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        base,
                        token,
                        settings.copy(
                            llmProvider = llmProvider,
                            aiModel = aiModel,
                            codexModel = codexModel,
                            embeddingModel = embeddingModel,
                            aiContentFormattingEnabled = aiContentFormatting,
                            browserBypassEnabled = browserBypass,
                            browserBypassMode = browserMode,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("RSS AI Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(base, { base = it }, label = { Text("API base URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("API token") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(llmProvider, { llmProvider = it }, label = { Text("LLM provider") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(aiModel, { aiModel = it }, label = { Text("OpenAI-compatible model") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(codexModel, { codexModel = it }, label = { Text("Codex model") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(embeddingModel, { embeddingModel = it }, label = { Text("Embedding model") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(aiContentFormatting, { aiContentFormatting = it })
                    Text("AI readability formatting for fetched article content")
                }
                OutlinedTextField(browserMode, { browserMode = it }, label = { Text("Bypass mode: on_blocked or always") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(browserBypass, { browserBypass = it })
                    Text("Enable browser bot-bypass Lambda")
                }
                providers.forEach {
                    Text("${it.id}: ${if (it.configured) "configured" else "not configured"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}
