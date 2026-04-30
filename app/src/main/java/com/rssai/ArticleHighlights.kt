package com.rssai

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class ArticleHighlight(
    val id: String = "",
    val text: String = "",
    val createdAt: Long = 0,
)

private val highlightsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Composable
fun rememberArticleHighlights(articleId: String): MutableState<List<ArticleHighlight>> {
    val context = LocalContext.current
    return remember(articleId) {
        mutableStateOf(loadArticleHighlights(context, articleId))
    }
}

fun saveArticleHighlight(
    context: Context,
    articleId: String,
    highlights: MutableState<List<ArticleHighlight>>,
    text: String,
) {
    val normalized = text.trim().replace(Regex("""\s+"""), " ")
    if (normalized.isBlank()) return
    val current = highlights.value
    if (current.any { it.text == normalized }) return
    val updated = listOf(
        ArticleHighlight(
            id = UUID.randomUUID().toString(),
            text = normalized,
            createdAt = System.currentTimeMillis(),
        ),
    ) + current
    persistArticleHighlights(context, articleId, updated)
    highlights.value = updated
}

fun deleteArticleHighlight(
    context: Context,
    articleId: String,
    highlights: MutableState<List<ArticleHighlight>>,
    highlightId: String,
) {
    val updated = highlights.value.filterNot { it.id == highlightId }
    persistArticleHighlights(context, articleId, updated)
    highlights.value = updated
}

@Composable
fun ArticleHighlightsCard(
    highlights: List<ArticleHighlight>,
    onDelete: (ArticleHighlight) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (highlights.isEmpty()) return
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = RssColors.PanelSoft.copy(alpha = 0.72f)),
        border = BorderStroke(1.dp, RssColors.Line),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Bookmark, contentDescription = null, tint = RssColors.Violet)
                Text(
                    "Highlights",
                    color = RssColors.Text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                )
            }
            highlights.forEach { highlight ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(highlight.text, color = RssColors.Text, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onDelete(highlight) }) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

private fun loadArticleHighlights(context: Context, articleId: String): List<ArticleHighlight> {
    val raw = highlightsPrefs(context).getString(articleId, null) ?: return emptyList()
    return runCatching {
        highlightsJson.decodeFromString(ListSerializer(ArticleHighlight.serializer()), raw)
    }.getOrDefault(emptyList())
}

private fun persistArticleHighlights(context: Context, articleId: String, highlights: List<ArticleHighlight>) {
    val raw = highlightsJson.encodeToString(ListSerializer(ArticleHighlight.serializer()), highlights)
    highlightsPrefs(context).edit().putString(articleId, raw).apply()
}

private fun highlightsPrefs(context: Context) =
    context.getSharedPreferences("rss-ai-highlights", Context.MODE_PRIVATE)
