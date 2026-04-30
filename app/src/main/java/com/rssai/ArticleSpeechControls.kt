package com.rssai

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rssai.data.SpeechAudio
import com.rssai.data.SpeechRequest
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun ArticleSpeechControls(
    articleId: String,
    ttsModel: String,
    ttsVoice: String,
    hasArticleText: Boolean,
    hasSummary: Boolean,
    onLoadSpeech: suspend (SpeechRequest) -> SpeechAudio,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember(articleId) { context.getSharedPreferences("rss-ai-speech", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentFile by remember { mutableStateOf<File?>(null) }
    var loadingTarget by remember { mutableStateOf<String?>(null) }
    var playingTarget by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var segmentPercent by remember(articleId) { mutableStateOf(prefs.getInt("percent:$articleId", 30)) }
    var segmentIndex by remember(articleId, segmentPercent) { mutableStateOf(prefs.getInt("segment:$articleId:$segmentPercent", 0)) }
    var segmentCount by remember(articleId, segmentPercent) { mutableStateOf(prefs.getInt("count:$articleId:$segmentPercent", 1).coerceAtLeast(1)) }
    var lastCacheStatus by remember { mutableStateOf("") }

    fun releasePlayback() {
        player?.runCatchingRelease()
        player = null
        playingTarget = null
        currentFile = null
    }

    DisposableEffect(Unit) {
        onDispose { releasePlayback() }
    }

    fun play(target: String, forceRefresh: Boolean = false) {
        scope.launch {
            releasePlayback()
            loadingTarget = target
            error = null
            runCatching {
                val request = SpeechRequest(
                    target = target,
                    segmentPercent = if (target == "summary") 100 else segmentPercent,
                    segmentIndex = if (target == "summary") 0 else segmentIndex,
                    forceRefresh = forceRefresh,
                )
                val spec = speechCacheSpec(articleId, request, ttsModel, ttsVoice)
                val audio = if (!forceRefresh) {
                    loadCachedSpeech(context, spec)?.copy(segmentCount = segmentCount)
                        ?: onLoadSpeech(request).also { saveCachedSpeech(context, spec, it) }
                } else {
                    onLoadSpeech(request).also { saveCachedSpeech(context, spec, it) }
                }
                if (target == "content") {
                    segmentIndex = audio.segmentIndex
                    segmentCount = audio.segmentCount.coerceAtLeast(1)
                    prefs.edit()
                        .putInt("percent:$articleId", segmentPercent)
                        .putInt("segment:$articleId:$segmentPercent", segmentIndex)
                        .putInt("count:$articleId:$segmentPercent", segmentCount)
                        .apply()
                }
                lastCacheStatus = audio.cacheStatus
                val tempFile = saveCachedSpeech(context, spec, audio)
                currentFile = tempFile
                val mediaPlayer = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    setOnCompletionListener { completed ->
                        completed.runCatchingRelease()
                        if (player === completed) {
                            player = null
                            playingTarget = null
                        }
                        if (target == "content") {
                            val nextSegment = (audio.segmentIndex + 1).coerceAtMost(audio.segmentCount - 1)
                            prefs.edit().putInt("segment:$articleId:$segmentPercent", nextSegment).apply()
                            segmentIndex = nextSegment
                        }
                    }
                    prepare()
                    start()
                }
                player = mediaPlayer
                playingTarget = target
            }.onFailure {
                error = it.safeUserMessage("Text-to-speech failed")
                releasePlayback()
            }
            loadingTarget = null
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = RssColors.PanelSoft.copy(alpha = 0.74f)),
        border = BorderStroke(1.dp, RssColors.Line),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Listen with OpenAI voice",
                color = RssColors.Text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
            )
            Text(
                "Cached by segment. OpenAI API key stays in Lambda, never on the phone.",
                color = RssColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SpeechSegmentPill("20%", segmentPercent == 20) { segmentPercent = 20 } }
                item { SpeechSegmentPill("30%", segmentPercent == 30) { segmentPercent = 30 } }
                item { SpeechSegmentPill("All", segmentPercent == 100) { segmentPercent = 100 } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (segmentPercent == 100) "Article: all content" else "Article part ${segmentIndex + 1} of $segmentCount",
                    color = RssColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = segmentIndex > 0 && loadingTarget == null,
                    onClick = { segmentIndex = (segmentIndex - 1).coerceAtLeast(0) },
                ) { Text("Prev") }
                TextButton(
                    enabled = segmentIndex < segmentCount - 1 && loadingTarget == null,
                    onClick = { segmentIndex = (segmentIndex + 1).coerceAtMost(segmentCount - 1) },
                ) { Text("Next") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { play("content") },
                    enabled = hasArticleText && loadingTarget == null,
                    modifier = Modifier.weight(1f),
                ) {
                    SpeechButtonContent("Read Article", loadingTarget == "content")
                }
                Button(
                    onClick = { play("summary") },
                    enabled = hasSummary && loadingTarget == null,
                    modifier = Modifier.weight(1f),
                ) {
                    SpeechButtonContent("Read Summary", loadingTarget == "summary")
                }
            }
            TextButton(
                onClick = { play("content", forceRefresh = true) },
                enabled = hasArticleText && loadingTarget == null,
            ) {
                Text("Regenerate current article part")
            }
            if (playingTarget != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Playing ${if (playingTarget == "summary") "summary" else "article"}${if (lastCacheStatus.isNotBlank()) " · $lastCacheStatus cache" else ""}",
                        color = RssColors.Violet,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { releasePlayback() }) { Text("Stop") }
                }
            }
            error?.let {
                Text(it, color = RssColors.Red, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SpeechSegmentPill(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = if (active) RssColors.Violet else RssColors.Muted, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun SpeechButtonContent(label: String, loading: Boolean) {
    if (loading) {
        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
    } else {
        Icon(Icons.Default.AutoAwesome, contentDescription = null)
        Spacer(Modifier.width(6.dp))
    }
    Text(label)
}

fun SpeechAudio.extension(): String =
    when {
        contentType.contains("wav", ignoreCase = true) -> "wav"
        contentType.contains("aac", ignoreCase = true) -> "aac"
        contentType.contains("opus", ignoreCase = true) -> "opus"
        contentType.contains("flac", ignoreCase = true) -> "flac"
        else -> "mp3"
    }

fun MediaPlayer.runCatchingRelease() {
    runCatching {
        if (isPlaying) stop()
    }
    runCatching { release() }
}
