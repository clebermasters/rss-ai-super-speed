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
fun ReaderDashboard(
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
fun AiSummaryCard(summary: String?, onSummarize: () -> Unit) {
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
fun ReaderContentCard(content: String?, aiFormatted: Boolean) {
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
