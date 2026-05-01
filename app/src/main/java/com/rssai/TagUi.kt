package com.rssai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rssai.data.Article

fun normalizeTags(tags: List<String>): List<String> =
    tags
        .flatMap { it.split(",") }
        .map { it.trim().removePrefix("#").lowercase().replace(Regex("\\s+"), " ") }
        .filter { it.isNotBlank() }
        .distinct()

@Composable
fun TagFilterRow(
    tags: List<String>,
    selectedTag: String,
    onTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) {
        return
    }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterPill("All tags", selectedTag.isBlank(), onClick = { onTag("") })
        }
        items(tags, key = { it }) { tag ->
            FilterPill("#$tag", selectedTag == tag, onClick = { onTag(if (selectedTag == tag) "" else tag) })
        }
    }
}

@Composable
fun ArticleTagsRow(
    article: Article,
    onEditTags: (Article) -> Unit,
    modifier: Modifier = Modifier,
    showEdit: Boolean = true,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        normalizeTags(article.tags).take(4).forEach { tag ->
            CountPill(text = "#$tag", active = true)
        }
        if (showEdit) {
            TextButton(onClick = { onEditTags(article) }) {
                Text(if (article.tags.isEmpty()) "Add tags" else "Edit tags")
            }
        }
    }
}

@Composable
fun EditArticleTagsDialog(
    article: Article,
    onDismiss: () -> Unit,
    onSave: (Article, List<String>) -> Unit,
) {
    var tags by remember(article.articleId) { mutableStateOf(normalizeTags(article.tags).joinToString(", ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(article, normalizeTags(listOf(tags))) }) {
                Text("Save tags")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Article tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(article.title, color = RssColors.Text, fontWeight = FontWeight.Bold)
                Text(
                    "Use comma-separated tags. These power filters and Flow mode.",
                    color = RssColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags") },
                    placeholder = { Text("ai, research, mobile") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        },
    )
}
