package com.rssai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rssai.data.ArticleHighlight

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

@Composable
fun HighlightsReviewScreen(
    highlights: List<ArticleHighlight>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (ArticleHighlight) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = RssColors.Text)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Highlights",
                        color = RssColors.Text,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                    )
                    Text("${highlights.size} saved text selections", color = RssColors.Muted)
                }
            }
        }
        if (highlights.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No highlights yet",
                    body = "Select article text and tap Save highlight. Highlights sync through the backend.",
                )
            }
        }
        items(highlights, key = { it.highlightId }) { highlight ->
            Card(
                colors = CardDefaults.cardColors(containerColor = RssColors.PanelSoft.copy(alpha = 0.82f)),
                border = BorderStroke(1.dp, RssColors.Line),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(highlight.articleSource, color = RssColors.Violet, fontWeight = FontWeight.Bold)
                    Text(
                        highlight.articleTitle,
                        color = RssColors.Text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                    )
                    Text(highlight.text, color = RssColors.Text, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { onOpen(highlight.articleId) }) {
                            Text("Open article")
                        }
                        TextButton(onClick = { onDelete(highlight) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }
}
