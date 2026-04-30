package com.rssai

fun Throwable.safeUserMessage(fallback: String): String =
    safeUserMessage(message, fallback)

fun safeUserMessage(message: String?, fallback: String): String {
    val raw = message.orEmpty()
    if (raw.isBlank()) return fallback
    val lower = raw.lowercase()
    return when {
        "accessdenied" in lower && "s3" in lower ->
            "Backend storage permission error. Retry after the latest backend deploy finishes."
        "http 500" in lower ->
            "Server error while processing the request. Please retry."
        "http 502" in lower ->
            "AI/backend provider error while processing the request. Please retry."
        else -> redactInfrastructureDetails(raw)
    }
}

private fun redactInfrastructureDetails(message: String): String =
    message
        .replace(Regex("""arn:aws:[^\s"'{}]+"""), "[aws-resource]")
        .replace(Regex("""\b\d{12}\b"""), "[aws-account]")
        .replace(Regex("""rss-ai-private-[A-Za-z0-9-]+"""), "[private-bucket]")
        .replace(Regex("""[A-Za-z0-9]{20,}\.dkr\.ecr\.[A-Za-z0-9-]+\.amazonaws\.com/[^\s"'{}]+"""), "[ecr-image]")
