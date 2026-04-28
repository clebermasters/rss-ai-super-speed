package com.rssai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rssai.data.Article
import com.rssai.data.Feed
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
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Configure API or refresh feeds.") }
    var loading by remember { mutableStateOf(false) }
    var settings by remember {
        mutableStateOf(
            Settings(
                llmProvider = BuildConfig.DEFAULT_LLM_PROVIDER,
                aiModel = BuildConfig.DEFAULT_AI_MODEL,
                codexModel = BuildConfig.DEFAULT_CODEX_MODEL,
                browserBypassEnabled = BuildConfig.DEFAULT_BROWSER_BYPASS_ENABLED,
            )
        )
    }
    var providers by remember { mutableStateOf<List<ProviderInfo>>(emptyList()) }
    var showSettings by remember { mutableStateOf(apiBase.isBlank() || apiToken.isBlank()) }
    val scope = rememberCoroutineScope()

    fun client() = RssApiClient(apiBase, apiToken)

    fun loadData(refreshFirst: Boolean = false) {
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
                articles = client().articles(query).articles
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
        colorScheme = androidx.compose.material3.lightColorScheme(
            background = Color(0xFFF5F0E8),
            surface = Color(0xFFFFFBF4),
            primary = Color(0xFFA44E24),
            secondary = Color(0xFF35605A),
        ),
    ) {
        Surface(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column {
                AppBar(
                    loading = loading,
                    status = status,
                    onRefresh = { loadData(refreshFirst = true) },
                    onSettings = { showSettings = true },
                )
                AdaptiveReaderLayout(
                    feeds = feeds,
                    providers = providers,
                    articles = articles,
                    query = query,
                    onQuery = {
                        query = it
                        loadData()
                    },
                    selected = selected,
                    onBackToArticles = { selected = null },
                    onSelect = { article ->
                        selected = article
                        scope.launch {
                            runCatching {
                                client().markRead(article.articleId)
                                client().article(article.articleId)
                            }.onSuccess {
                                selected = it
                                articles = articles.map { existing ->
                                    if (existing.articleId == article.articleId) existing.copy(isRead = true) else existing
                                }
                            }
                        }
                    },
                    settings = settings,
                    openUrl = openUrl,
                    onToggleSave = {
                        scope.launch {
                            selected?.let { article ->
                                runCatching { client().toggleSave(article.articleId) }.onSuccess {
                                    selected = it
                                    articles = articles.map { existing -> if (existing.articleId == it.articleId) it else existing }
                                }
                            }
                        }
                    },
                    onFetchContent = {
                        scope.launch {
                            selected?.let { article ->
                                loading = true
                                try {
                                    var result = client().fetchContent(article.articleId)
                                    val jobId = result.jobId
                                    if (result.status != "completed" && !jobId.isNullOrBlank()) {
                                        status = result.message ?: "Full-content fetch queued"
                                        repeat(30) {
                                            if (result.status != "completed" && result.status != "failed") {
                                                delay(3000)
                                                result = client().contentJob(jobId)
                                                status = result.message ?: "Fetch ${result.status}"
                                            }
                                        }
                                    }
                                    if (result.status == "failed") {
                                        status = result.message ?: "Fetch failed"
                                    } else if (result.content.isNotBlank()) {
                                        selected = selected?.copy(content = result.content)
                                        status = "Fetched via ${result.strategy ?: "unknown"}"
                                    }
                                } catch (exc: Exception) {
                                    status = exc.message ?: "Fetch failed"
                                }
                                loading = false
                            }
                        }
                    },
                    onSummarize = {
                        scope.launch {
                            selected?.let { article ->
                                loading = true
                                runCatching { client().summarize(article.articleId) }.onSuccess {
                                    selected = selected?.copy(summary = it.summary)
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
            }
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
        }
    }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(base, { base = it }, label = { Text("API base URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("API token") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(llmProvider, { llmProvider = it }, label = { Text("LLM provider") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(aiModel, { aiModel = it }, label = { Text("OpenAI-compatible model") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(codexModel, { codexModel = it }, label = { Text("Codex model") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(embeddingModel, { embeddingModel = it }, label = { Text("Embedding model") }, modifier = Modifier.fillMaxWidth())
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
