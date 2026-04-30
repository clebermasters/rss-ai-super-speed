package com.rssai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

data class RsvpToken(
    val text: String,
    val pauseMultiplier: Float,
)

@Composable
fun RsvpReaderDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit,
) {
    val tokens = remember(content) { tokenizeRsvpContent(content) }
    var index by remember(tokens) { mutableStateOf(0) }
    var playing by remember(tokens) { mutableStateOf(tokens.isNotEmpty()) }
    var wpm by remember { mutableStateOf(320f) }

    LaunchedEffect(playing, index, wpm, tokens) {
        if (!playing || tokens.isEmpty()) return@LaunchedEffect
        if (index >= tokens.lastIndex) {
            playing = false
            return@LaunchedEffect
        }
        val delayMs = ((60_000f / wpm) * tokens[index].pauseMultiplier)
            .roundToInt()
            .coerceAtLeast(60)
        delay(delayMs.toLong())
        index = (index + 1).coerceAtMost(tokens.lastIndex)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = RssColors.Panel),
            border = BorderStroke(1.dp, RssColors.Line),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Word Runner",
                            color = RssColors.Violet,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            title,
                            color = RssColors.Muted,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                RsvpWordStage(word = tokens.getOrNull(index)?.text.orEmpty())

                val currentPosition = if (tokens.isEmpty()) 0 else index + 1
                val progress = if (tokens.isEmpty()) 0f else currentPosition / tokens.size.toFloat()
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = RssColors.Violet,
                    trackColor = RssColors.Line,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$currentPosition/${tokens.size}",
                        color = RssColors.Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${wpm.roundToInt()} WPM",
                        color = RssColors.Text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Speed", color = RssColors.Muted, style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = wpm,
                        onValueChange = { wpm = it },
                        valueRange = 150f..700f,
                        steps = 10,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            index = (index - 10).coerceAtLeast(0)
                            playing = false
                        },
                        enabled = tokens.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("-10")
                    }
                    Button(
                        onClick = {
                            playing = if (index >= tokens.lastIndex) {
                                index = 0
                                true
                            } else {
                                !playing
                            }
                        },
                        enabled = tokens.isNotEmpty(),
                        modifier = Modifier.weight(1.4f),
                    ) {
                        Text(if (playing) "Pause" else if (index >= tokens.lastIndex) "Restart" else "Play")
                    }
                    TextButton(
                        onClick = {
                            index = (index + 10).coerceAtMost(tokens.lastIndex.coerceAtLeast(0))
                            playing = false
                        },
                        enabled = tokens.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("+10")
                    }
                }
            }
        }
    }
}

@Composable
fun RsvpWordStage(word: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RssColors.PanelSoft.copy(alpha = 0.92f)),
        border = BorderStroke(1.dp, RssColors.Line),
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(168.dp)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(112.dp)
                    .background(RssColors.Line.copy(alpha = 0.66f)),
            )
            RsvpWord(word = word)
        }
    }
}

@Composable
fun RsvpWord(word: String) {
    val pivot = optimalRecognitionPoint(word)
    val prefix = word.take(pivot)
    val focus = word.getOrNull(pivot)?.toString().orEmpty()
    val suffix = if (pivot + 1 <= word.length) word.drop(pivot + 1) else ""
    val textStyle = MaterialTheme.typography.displaySmall.copy(
        fontSize = 40.sp,
        fontWeight = FontWeight.Black,
    )

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            prefix,
            modifier = Modifier.weight(1f),
            color = RssColors.Text,
            textAlign = TextAlign.End,
            style = textStyle,
            maxLines = 1,
        )
        Text(
            focus,
            color = RssColors.Red,
            textAlign = TextAlign.Center,
            style = textStyle,
            maxLines = 1,
        )
        Text(
            suffix,
            modifier = Modifier.weight(1f),
            color = RssColors.Text,
            textAlign = TextAlign.Start,
            style = textStyle,
            maxLines = 1,
        )
    }
}

fun tokenizeRsvpContent(content: String): List<RsvpToken> {
    val plainText = htmlToSpanned(safeRichHtml(content))
        .toString()
        .replace('\u00A0', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (plainText.isBlank()) return emptyList()
    return plainText
        .split(' ')
        .mapNotNull { raw ->
            val token = raw.trim()
                .trim('"', '\'', '`', '“', '”', '‘', '’')
                .takeIf { it.isNotBlank() }
            token?.let { RsvpToken(text = it, pauseMultiplier = rsvpPauseMultiplier(it)) }
        }
}

fun rsvpPauseMultiplier(word: String): Float {
    val trimmed = word.trim()
    return when {
        trimmed.endsWith("...") || trimmed.endsWith("…") -> 2.6f
        trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?") -> 2.25f
        trimmed.endsWith(":") || trimmed.endsWith(";") -> 1.75f
        trimmed.endsWith(",") || trimmed.endsWith(")") -> 1.45f
        trimmed.length >= 14 -> 1.25f
        else -> 1f
    }
}

fun optimalRecognitionPoint(word: String): Int {
    if (word.isBlank()) return 0
    val start = word.indexOfFirst { it.isLetterOrDigit() }.takeIf { it >= 0 } ?: 0
    val end = word.indexOfLast { it.isLetterOrDigit() }.takeIf { it >= start } ?: start
    val coreLength = (end - start + 1).coerceAtLeast(1)
    val offset = when {
        coreLength <= 1 -> 0
        coreLength <= 5 -> 1
        coreLength <= 9 -> 2
        coreLength <= 13 -> 3
        else -> 4
    }
    return (start + offset).coerceIn(0, word.lastIndex)
}
