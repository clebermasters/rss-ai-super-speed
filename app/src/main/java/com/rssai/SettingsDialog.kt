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
fun SettingsDialog(
    apiBase: String,
    apiToken: String,
    themeMode: RssThemeMode,
    settings: Settings,
    providers: List<ProviderInfo>,
    availableTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String, RssThemeMode, Settings) -> Unit,
) {
    var base by remember { mutableStateOf(apiBase) }
    var token by remember { mutableStateOf(apiToken) }
    var selectedThemeMode by remember { mutableStateOf(themeMode) }
    var llmProvider by remember { mutableStateOf(settings.llmProvider) }
    var aiModel by remember { mutableStateOf(settings.aiModel) }
    var codexModel by remember { mutableStateOf(settings.codexModel) }
    var embeddingModel by remember { mutableStateOf(settings.embeddingModel) }
    var ttsModel by remember { mutableStateOf(settings.ttsModel) }
    var ttsVoice by remember { mutableStateOf(settings.ttsVoice) }
    var ttsInstructions by remember { mutableStateOf(settings.ttsInstructions) }
    var aiContentFormatting by remember { mutableStateOf(settings.aiContentFormattingEnabled) }
    var browserBypass by remember { mutableStateOf(settings.browserBypassEnabled) }
    var browserMode by remember { mutableStateOf(settings.browserBypassMode) }
    var refreshOnOpen by remember { mutableStateOf(settings.refreshOnOpen) }
    var scheduledAiPrefetch by remember { mutableStateOf(settings.scheduledAiPrefetchEnabled) }
    var scheduledAiPrefetchTags by remember { mutableStateOf(normalizeTags(settings.scheduledAiPrefetchTags).joinToString(", ")) }
    var scheduledAiPrefetchLimit by remember { mutableStateOf(settings.scheduledAiPrefetchLimit.toString()) }
    var scheduledAiPrefetchMaxAgeHours by remember { mutableStateOf(settings.scheduledAiPrefetchMaxAgeHours.toString()) }
    var scheduledAiPrefetchRetryMinutes by remember { mutableStateOf(settings.scheduledAiPrefetchRetryMinutes.toString()) }
    var scheduledAiPrefetchSummaries by remember { mutableStateOf(settings.scheduledAiPrefetchSummaries) }
    var scheduledAiPrefetchContent by remember { mutableStateOf(settings.scheduledAiPrefetchContent) }
    var articleContentCacheTtlDays by remember { mutableStateOf(settings.articleContentCacheTtlDays.toString()) }
    var localArticleCacheDays by remember { mutableStateOf(settings.localArticleCacheDays.toString()) }

    fun prefetchTags(): List<String> = normalizeTags(listOf(scheduledAiPrefetchTags))

    fun togglePrefetchTag(tag: String) {
        val current = prefetchTags().toMutableSet()
        if (tag in current) {
            current.remove(tag)
        } else {
            current.add(tag)
        }
        scheduledAiPrefetchTags = current.sorted().joinToString(", ")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        base,
                        token,
                        selectedThemeMode,
                        settings.copy(
                            llmProvider = llmProvider,
                            aiModel = aiModel,
                            codexModel = codexModel,
                            embeddingModel = embeddingModel,
                            ttsModel = ttsModel,
                            ttsVoice = ttsVoice,
                            ttsInstructions = ttsInstructions,
                            aiContentFormattingEnabled = aiContentFormatting,
                            browserBypassEnabled = browserBypass,
                            browserBypassMode = browserMode,
                            refreshOnOpen = refreshOnOpen,
                            scheduledAiPrefetchEnabled = scheduledAiPrefetch,
                            scheduledAiPrefetchTags = prefetchTags(),
                            scheduledAiPrefetchLimit = scheduledAiPrefetchLimit.toIntOrNull()?.coerceIn(1, 25) ?: 5,
                            scheduledAiPrefetchMaxAgeHours = scheduledAiPrefetchMaxAgeHours.toIntOrNull()?.coerceIn(1, 168) ?: 24,
                            scheduledAiPrefetchRetryMinutes = scheduledAiPrefetchRetryMinutes.toIntOrNull()?.coerceIn(5, 1440) ?: 60,
                            scheduledAiPrefetchSummaries = scheduledAiPrefetchSummaries,
                            scheduledAiPrefetchContent = scheduledAiPrefetchContent,
                            articleContentCacheTtlDays = articleContentCacheTtlDays.toIntOrNull()?.coerceIn(1, 365) ?: 30,
                            localArticleCacheDays = localArticleCacheDays.toIntOrNull()?.coerceIn(1, 365) ?: 30,
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
                Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = selectedThemeMode == RssThemeMode.Light,
                        onCheckedChange = { useLight ->
                            selectedThemeMode = if (useLight) RssThemeMode.Light else RssThemeMode.Dark
                        },
                    )
                    Text("Use older light theme")
                }
                OutlinedTextField(llmProvider, { llmProvider = it }, label = { Text("LLM provider") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(aiModel, { aiModel = it }, label = { Text("OpenAI-compatible model") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(codexModel, { codexModel = it }, label = { Text("Codex model") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(embeddingModel, { embeddingModel = it }, label = { Text("Embedding model") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ttsModel, { ttsModel = it }, label = { Text("OpenAI TTS model") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ttsVoice, { ttsVoice = it }, label = { Text("OpenAI TTS voice") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ttsInstructions, { ttsInstructions = it }, label = { Text("TTS reading style") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(aiContentFormatting, { aiContentFormatting = it })
                    Text("AI readability formatting for fetched article content")
                }
                OutlinedTextField(browserMode, { browserMode = it }, label = { Text("Bypass mode: on_blocked or always") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(browserBypass, { browserBypass = it })
                    Text("Enable browser bot-bypass Lambda")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(refreshOnOpen, { refreshOnOpen = it })
                    Text("Refresh feeds in the background on open (cache loads first; throttled to 15 minutes)")
                }
                Text("Scheduled AI prefetch cache", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "EventBridge runs every 5 minutes. Lambda refreshes only feeds with these tags, then caches AI summaries, full content, and AI formatting without marking articles read.",
                    color = RssColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(scheduledAiPrefetch, { scheduledAiPrefetch = it })
                    Text("Enable scheduled tag prefetch")
                }
                OutlinedTextField(
                    value = scheduledAiPrefetchTags,
                    onValueChange = { scheduledAiPrefetchTags = it },
                    label = { Text("Prefetch tags") },
                    placeholder = { Text("ai, research, openai") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (availableTags.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(availableTags, key = { it }) { tag ->
                            FilterPill(
                                "#$tag",
                                active = tag in prefetchTags(),
                                onClick = { togglePrefetchTag(tag) },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = scheduledAiPrefetchLimit,
                        onValueChange = { scheduledAiPrefetchLimit = it },
                        label = { Text("Per run") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = scheduledAiPrefetchMaxAgeHours,
                        onValueChange = { scheduledAiPrefetchMaxAgeHours = it },
                        label = { Text("Age hrs") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = scheduledAiPrefetchRetryMinutes,
                        onValueChange = { scheduledAiPrefetchRetryMinutes = it },
                        label = { Text("Retry min") },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(scheduledAiPrefetchContent, { scheduledAiPrefetchContent = it })
                    Text("Fetch full content and AI-format it")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(scheduledAiPrefetchSummaries, { scheduledAiPrefetchSummaries = it })
                    Text("Generate AI summaries")
                }
                OutlinedTextField(
                    value = articleContentCacheTtlDays,
                    onValueChange = { articleContentCacheTtlDays = it },
                    label = { Text("DynamoDB article content cache TTL days") },
                    supportingText = { Text("Full fetched/formatted content chunks expire through DynamoDB TTL. Applies at runtime after saving settings.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = localArticleCacheDays,
                    onValueChange = { localArticleCacheDays = it },
                    label = { Text("Device article cache days") },
                    supportingText = { Text("Feeds, article metadata/content, and highlights stay available offline for this many days.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                providers.forEach {
                    Text("${it.id}: ${if (it.configured) "configured" else "not configured"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}
