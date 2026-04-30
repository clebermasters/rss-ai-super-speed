package com.rssai

import android.content.Context
import com.rssai.data.SpeechAudio
import com.rssai.data.SpeechRequest
import java.io.File
import java.security.MessageDigest

data class SpeechCacheSpec(
    val articleId: String,
    val target: String,
    val segmentPercent: Int,
    val segmentIndex: Int,
    val ttsModel: String,
    val ttsVoice: String,
)

fun speechCacheSpec(articleId: String, request: SpeechRequest, ttsModel: String, ttsVoice: String): SpeechCacheSpec =
    SpeechCacheSpec(
        articleId = articleId,
        target = request.target,
        segmentPercent = request.segmentPercent,
        segmentIndex = request.segmentIndex,
        ttsModel = ttsModel,
        ttsVoice = ttsVoice,
    )

fun speechCacheFile(context: Context, spec: SpeechCacheSpec, extension: String = "mp3"): File {
    val dir = File(context.cacheDir, "rss-ai-speech").apply { mkdirs() }
    return File(dir, "${spec.stableKey()}.$extension")
}

fun loadCachedSpeech(context: Context, spec: SpeechCacheSpec): SpeechAudio? {
    val file = speechCacheFile(context, spec)
    if (!file.exists() || file.length() == 0L) return null
    return SpeechAudio(
        bytes = file.readBytes(),
        contentType = "audio/mpeg",
        cacheStatus = "device",
        segmentIndex = spec.segmentIndex,
        segmentPercent = spec.segmentPercent,
    )
}

fun saveCachedSpeech(context: Context, spec: SpeechCacheSpec, audio: SpeechAudio): File {
    val file = speechCacheFile(context, spec, audio.extension())
    file.writeBytes(audio.bytes)
    return file
}

fun SpeechCacheSpec.stableKey(): String =
    sha256("$articleId|$target|$segmentPercent|$segmentIndex|$ttsModel|$ttsVoice").take(32)

fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
