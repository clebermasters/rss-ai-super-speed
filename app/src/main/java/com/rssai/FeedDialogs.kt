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
fun AddFeedDialog(
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
fun ManageFeedDialog(
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
