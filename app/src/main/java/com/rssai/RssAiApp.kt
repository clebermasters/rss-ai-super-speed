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
fun RssAiApp(openUrl: (String) -> Unit) {
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("rss-ai", Context.MODE_PRIVATE)
    var apiBase by remember { mutableStateOf(prefs.getString("apiBase", BuildConfig.DEFAULT_RSS_API_BASE_URL).orEmpty()) }
    var apiToken by remember { mutableStateOf(prefs.getString("apiToken", BuildConfig.DEFAULT_RSS_API_TOKEN).orEmpty()) }
    var themeMode by remember { mutableStateOf(RssThemeMode.from(prefs.getString("themeMode", RssThemeMode.Dark.storageValue))) }
    var feeds by remember { mutableStateOf<List<Feed>>(emptyList()) }
    var articles by remember { mutableStateOf<List<Article>>(emptyList()) }
    var brandNewArticleIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selected by remember { mutableStateOf<Article?>(null) }
    var readerArticles by remember { mutableStateOf<List<Article>>(emptyList()) }
    var selectedFeedId by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf("") }
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
    var editArticleTags by remember { mutableStateOf<Article?>(null) }
    val scope = rememberCoroutineScope()

    fun client() = RssApiClient(apiBase, apiToken)

    fun mergeArticle(base: Article, incoming: Article): Article =
        incoming.copy(
            summary = incoming.summary ?: base.summary,
            content = incoming.content ?: base.content,
            contentPreview = incoming.contentPreview ?: base.contentPreview,
            sourceFeedId = incoming.sourceFeedId ?: base.sourceFeedId,
            tags = incoming.tags.ifEmpty { base.tags },
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
                            "Article fetched; AI formatting failed: ${safeUserMessage(result.contentFormattingError, "AI formatting failed")}"
                        result.contentFormattingAttempted -> "Article fetched; AI formatting was not applied"
                        shouldFormatExisting -> "AI formatting complete"
                        else -> "Full article content ready"
                    }
                }
                finalArticle
            }
        } catch (exc: Exception) {
            if (!background) {
                status = exc.safeUserMessage("Article preparation failed")
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
        brandNewArticleIds = brandNewArticleIds - localArticle.articleId
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

    fun loadData(
        refreshFirst: Boolean = false,
        searchQuery: String = query,
        sourceFeedId: String = selectedFeedId,
        tagFilter: String = selectedTag,
    ) {
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
                articles = client().articles(searchQuery, source = sourceFeedId, tag = tagFilter).articles
                brandNewArticleIds = detectBrandNewArticleIds(prefs, articles)
            }.onSuccess {
                status = if (brandNewArticleIds.isEmpty()) {
                    "Loaded ${articles.size} articles"
                } else {
                    "Loaded ${articles.size} articles · ${brandNewArticleIds.size} new"
                }
            }.onFailure {
                status = it.safeUserMessage("Load failed")
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (apiBase.isNotBlank() && apiToken.isNotBlank()) {
            loadData()
        }
    }

    val palette = RssPalettes.forMode(themeMode)
    RssColors.palette = palette
    val availableTags = remember(feeds, articles) {
        normalizeTags(feeds.flatMap { it.tags } + articles.flatMap { it.tags }).sorted()
    }
    val colorScheme = when (themeMode) {
        RssThemeMode.Dark -> androidx.compose.material3.darkColorScheme(
            background = palette.ink,
            surface = palette.panel,
            surfaceVariant = palette.panelSoft,
            primary = palette.blue,
            secondary = palette.violet,
            outline = palette.line,
            onBackground = palette.text,
            onSurface = palette.text,
            onPrimary = Color.White,
            onSecondary = Color.White,
        )
        RssThemeMode.Light -> androidx.compose.material3.lightColorScheme(
            background = palette.ink,
            surface = palette.panel,
            surfaceVariant = palette.panelSoft,
            primary = palette.blue,
            secondary = palette.violet,
            outline = palette.line,
            onBackground = palette.text,
            onSurface = palette.text,
            onPrimary = Color.White,
            onSecondary = Color.White,
        )
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(Modifier.fillMaxSize()) {
            ModernRssLayout(
                feeds = feeds,
                providers = providers,
                articles = articles,
                brandNewArticleIds = brandNewArticleIds,
                readerArticles = readerArticles,
                availableTags = availableTags,
                selectedTag = selectedTag,
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
                    selectedFeedId = feed?.feedId.orEmpty()
                    currentScreen = RssScreen.Articles
                    loadData()
                },
                onTag = { tag ->
                    selectedTag = tag
                    loadData(tagFilter = tag)
                },
                onSelect = { article, queue ->
                    openArticle(article, queue = queue)
                },
                onEditArticleTags = { article ->
                    editArticleTags = latestArticleSnapshot(article)
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
                                status = it.safeUserMessage("Summary failed")
                            }
                            loading = false
                        }
                    }
                },
                onLoadSpeech = { target ->
                    val article = selected ?: error("No article selected")
                    client().articleSpeech(article.articleId, target)
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (showSettings) {
                SettingsDialog(
                    apiBase = apiBase,
                    apiToken = apiToken,
                    themeMode = themeMode,
                    settings = settings,
                    providers = providers,
                    onDismiss = { showSettings = false },
                    onSave = { newBase, newToken, newThemeMode, newSettings ->
                        apiBase = newBase
                        apiToken = newToken
                        themeMode = newThemeMode
                        settings = newSettings
                        prefs.edit()
                            .putString("apiBase", apiBase)
                            .putString("apiToken", apiToken)
                            .putString("themeMode", themeMode.storageValue)
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
                                articles = client().articles(query, source = selectedFeedId, tag = selectedTag).articles
                                brandNewArticleIds = detectBrandNewArticleIds(prefs, articles)
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
                                status = it.safeUserMessage("Add feed failed")
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
                                articles = client().articles(query, source = selectedFeedId, tag = selectedTag).articles
                                brandNewArticleIds = detectBrandNewArticleIds(prefs, articles)
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
                                status = it.safeUserMessage("Update feed failed")
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
                                if (selectedFeedId == feed.feedId) {
                                    selectedFeedId = ""
                                }
                                articles = client().articles(query, source = selectedFeedId, tag = selectedTag).articles
                                brandNewArticleIds = detectBrandNewArticleIds(prefs, articles)
                                currentScreen = RssScreen.Feeds
                                feed
                            }.onSuccess {
                                status = "Removed ${it.name}"
                            }.onFailure {
                                status = it.safeUserMessage("Remove feed failed")
                            }
                            loading = false
                        }
                    },
                )
            }
            editArticleTags?.let { article ->
                EditArticleTagsDialog(
                    article = article,
                    onDismiss = { editArticleTags = null },
                    onSave = { target, tags ->
                        editArticleTags = null
                        scope.launch {
                            loading = true
                            status = "Updating article tags..."
                            runCatching {
                                client().updateArticleTags(target.articleId, tags)
                            }.onSuccess {
                                updateArticleEverywhere(it)
                                status = "Article tags updated"
                            }.onFailure {
                                status = it.safeUserMessage("Update article tags failed")
                            }
                            loading = false
                        }
                    },
                )
            }
        }
    }
}
