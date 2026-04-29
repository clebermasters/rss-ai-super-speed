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
fun EmptyStateCard(title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null) {
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
fun CountPill(text: String, active: Boolean) {
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
fun SourceBadge(source: String, accent: Color = sourceAccent(source), size: Int = 48) {
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

fun sourceInitials(source: String): String {
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

fun sourceAccent(source: String): Color {
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

fun shortDate(value: String?): String {
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
