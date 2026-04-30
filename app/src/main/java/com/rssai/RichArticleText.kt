package com.rssai

import android.text.Html
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.max
import kotlin.math.min

private const val SAVE_HIGHLIGHT_MENU_ID = 8201

@Composable
fun RichArticleText(
    content: String,
    modifier: Modifier = Modifier,
    textSizeSp: Float = 18f,
    lineSpacingExtra: Float = 8f,
    onHighlight: ((String) -> Unit)? = null,
) {
    val html = remember(content) { safeRichHtml(content) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                setTextColor(RssColors.Text.toArgb())
                setLinkTextColor(RssColors.Violet.toArgb())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setLineSpacing(lineSpacingExtra, 1.08f)
                includeFontPadding = true
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                installHighlightAction(onHighlight)
            }
        },
        update = { view ->
            view.setTextColor(RssColors.Text.toArgb())
            view.setLinkTextColor(RssColors.Violet.toArgb())
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            view.setLineSpacing(lineSpacingExtra, 1.08f)
            view.text = htmlToSpanned(html)
            Linkify.addLinks(view, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
            view.movementMethod = LinkMovementMethod.getInstance()
            view.installHighlightAction(onHighlight)
        },
    )
}

private fun TextView.installHighlightAction(onHighlight: ((String) -> Unit)?) {
    if (onHighlight == null) {
        customSelectionActionModeCallback = null
        return
    }
    customSelectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, SAVE_HIGHLIGHT_MENU_ID, 0, "Save highlight").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != SAVE_HIGHLIGHT_MENU_ID) return false
            val view = this@installHighlightAction
            val start = min(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
            val end = max(view.selectionStart, view.selectionEnd).coerceAtMost(view.text.length)
            val selected = view.text.subSequence(start, end).toString().trim()
            if (selected.isNotBlank()) {
                onHighlight(selected)
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }
}

fun htmlToSpanned(html: String): CharSequence {
    return runCatching {
        Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
    }.getOrElse {
        Html.fromHtml(plainTextToHtml(stripMarkdownArtifacts(html)), Html.FROM_HTML_MODE_COMPACT)
    }
}

enum class HtmlListMode(val tag: String) {
    Ordered("ol"),
    Unordered("ul"),
}

fun safeRichHtml(content: String): String {
    val trimmed = content.trim()
    if (trimmed.isBlank()) {
        return plainTextToHtml("")
    }
    return runCatching {
        if (looksLikeHtml(trimmed)) {
            sanitizeExistingHtml(trimmed)
        } else {
            markdownToHtml(trimmed)
        }
    }.getOrElse {
        plainTextToHtml(stripMarkdownArtifacts(trimmed))
    }
}

fun looksLikeHtml(content: String): Boolean {
    return Regex(
        """</?(p|br|h[1-6]|ul|ol|li|strong|b|em|i|a|blockquote|pre|code)\b""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(content)
}

fun sanitizeExistingHtml(content: String): String {
    var html = content
        .replace(Regex("""(?is)<script\b[^>]*>.*?</script>"""), "")
        .replace(Regex("""(?is)<style\b[^>]*>.*?</style>"""), "")
        .replace(Regex("""(?is)<svg\b[^>]*>.*?</svg>"""), "")
        .replace(Regex("""(?is)<head\b[^>]*>.*?</head>"""), "")
        .replace(Regex("""(?i)\son\w+\s*=\s*(['"]).*?\1"""), "")
        .replace(Regex("""(?i)javascript:"""), "")
    Regex("""(?is)<body\b[^>]*>(.*?)</body>""").find(html)?.let {
        html = it.groupValues[1]
    }
    html = html.replace(Regex("""(?is)<img\b[^>]*>""")) { match ->
        val src = Regex("""(?i)\bsrc\s*=\s*(['"])(.*?)\1""").find(match.value)?.groupValues?.getOrNull(2).orEmpty()
        val alt = Regex("""(?i)\balt\s*=\s*(['"])(.*?)\1""").find(match.value)?.groupValues?.getOrNull(2).orEmpty()
        val label = escapeHtml(alt.ifBlank { "Open image" })
        val href = safeHref(src)
        if (href == "#") "" else """<p><a href="$href">$label</a></p>"""
    }
    return html.ifBlank { plainTextToHtml(stripMarkdownArtifacts(content)) }
}

fun markdownToHtml(content: String): String {
    val html = StringBuilder()
    val paragraph = mutableListOf<String>()
    var listMode: HtmlListMode? = null
    var inCodeBlock = false
    val codeBlock = StringBuilder()

    fun closeList() {
        listMode?.let { html.append("</").append(it.tag).append("><br>") }
        listMode = null
    }

    fun openList(mode: HtmlListMode) {
        if (listMode != mode) {
            closeList()
            html.append("<").append(mode.tag).append(">")
            listMode = mode
        }
    }

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        closeList()
        html.append("<p>")
            .append(inlineMarkdownToHtml(paragraph.joinToString(" ").trim()))
            .append("</p><br>")
        paragraph.clear()
    }

    fun flushCodeBlock() {
        if (codeBlock.isEmpty()) return
        flushParagraph()
        closeList()
        html.append("<pre><code>")
            .append(escapeHtml(codeBlock.toString().trimEnd()))
            .append("</code></pre>")
        codeBlock.clear()
    }

    content
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    inCodeBlock = false
                    flushCodeBlock()
                } else {
                    flushParagraph()
                    closeList()
                    inCodeBlock = true
                }
                return@forEach
            }

            if (inCodeBlock) {
                codeBlock.append(rawLine).append('\n')
                return@forEach
            }

            if (trimmed.isBlank()) {
                flushParagraph()
                closeList()
                return@forEach
            }

            Regex("""^(#{1,6})\s+(.+)$""").matchEntire(trimmed)?.let { match ->
                flushParagraph()
                closeList()
                val level = match.groupValues[1].length.coerceIn(2, 4)
                html.append("<br><h").append(level).append(">")
                    .append(inlineMarkdownToHtml(match.groupValues[2]))
                    .append("</h").append(level).append("><br>")
                return@forEach
            }

            if (Regex("""^[-*_]{3,}$""").matches(trimmed)) {
                flushParagraph()
                closeList()
                html.append("<hr>")
                return@forEach
            }

            Regex("""^!\[([^]]*)]\(([^)\s]+)(?:\s+"[^"]*")?\)$""").matchEntire(trimmed)?.let { match ->
                flushParagraph()
                closeList()
                val label = escapeHtml(match.groupValues[1].ifBlank { "Open image" })
                html.append("""<p><a href="${safeHref(match.groupValues[2])}">$label</a></p>""")
                return@forEach
            }

            Regex("""^[-*+•]\s+(.+)$""").matchEntire(trimmed)?.let { match ->
                flushParagraph()
                openList(HtmlListMode.Unordered)
                html.append("<li>").append(inlineMarkdownToHtml(match.groupValues[1])).append("</li><br>")
                return@forEach
            }

            Regex("""^\d+[.)]\s+(.+)$""").matchEntire(trimmed)?.let { match ->
                flushParagraph()
                openList(HtmlListMode.Ordered)
                html.append("<li>").append(inlineMarkdownToHtml(match.groupValues[1])).append("</li><br>")
                return@forEach
            }

            Regex("""^>\s?(.+)$""").matchEntire(trimmed)?.let { match ->
                flushParagraph()
                closeList()
                html.append("<blockquote>")
                    .append(inlineMarkdownToHtml(match.groupValues[1]))
                    .append("</blockquote>")
                return@forEach
            }

            paragraph.add(trimmed)
        }

    if (inCodeBlock) flushCodeBlock()
    flushParagraph()
    closeList()
    return html.toString().ifBlank { plainTextToHtml(stripMarkdownArtifacts(content)) }
}

fun inlineMarkdownToHtml(text: String): String {
    var html = escapeHtml(text)
    html = Regex("""!\[([^]]*)]\(([^)\s]+)(?:\s+&quot;[^&]*&quot;)?\)""").replace(html) { match ->
        val label = match.groupValues[1].ifBlank { "Open image" }
        """<a href="${safeHref(match.groupValues[2])}">${escapeHtml(label)}</a>"""
    }
    html = Regex("""(?<!!)\[([^]]+)]\(([^)\s]+)(?:\s+&quot;[^&]*&quot;)?\)""").replace(html) { match ->
        """<a href="${safeHref(match.groupValues[2])}">${match.groupValues[1]}</a>"""
    }
    html = Regex("""\*\*([^*]+)\*\*""").replace(html) { "<strong>${it.groupValues[1]}</strong>" }
    html = Regex("""__([^_]+)__""").replace(html) { "<strong>${it.groupValues[1]}</strong>" }
    html = Regex("""`([^`]+)`""").replace(html) { "<code>${it.groupValues[1]}</code>" }
    html = Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)""").replace(html) { "<em>${it.groupValues[1]}</em>" }
    html = Regex("""(?<!\w)_([^_\n]+)_(?!\w)""").replace(html) { "<em>${it.groupValues[1]}</em>" }
    return stripLooseMarkdownMarkers(html)
}

fun stripLooseMarkdownMarkers(html: String): String {
    return html
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("""(^|\s)#{1,6}\s+"""), "$1")
}

fun stripMarkdownArtifacts(content: String): String {
    var text = content
        .replace(Regex("""!\[([^]]*)]\(([^)]+)\)""")) { match ->
            match.groupValues[1].ifBlank { match.groupValues[2] }
        }
        .replace(Regex("""(?<!!)\[([^]]+)]\(([^)]+)\)""")) { match ->
            "${match.groupValues[1]} (${match.groupValues[2]})"
        }
    text = text.lines().joinToString("\n") { line ->
        line
            .replace(Regex("""^\s{0,3}#{1,6}\s+"""), "")
            .replace(Regex("""^\s*[-*+]\s+"""), "• ")
            .replace(Regex("""^\s*\d+[.)]\s+"""), "• ")
            .replace(Regex("""^\s*>\s?"""), "")
    }
    return text
        .replace("**", "")
        .replace("__", "")
        .replace("```", "")
        .replace("`", "")
        .trim()
}

fun plainTextToHtml(content: String): String {
    if (content.isBlank()) return "<p></p>"
    return content
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split(Regex("""\n{2,}"""))
        .joinToString("") { paragraph ->
            "<p>${escapeHtml(paragraph.trim()).replace("\n", "<br>")}</p>"
        }
}

fun safeHref(raw: String): String {
    val href = raw
        .trim()
        .trim('"', '\'')
        .replace("&amp;", "&")
    if (!href.startsWith("http://", ignoreCase = true)
        && !href.startsWith("https://", ignoreCase = true)
        && !href.startsWith("mailto:", ignoreCase = true)
    ) {
        return "#"
    }
    return escapeAttribute(href)
}

fun escapeHtml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

fun escapeAttribute(value: String): String {
    return escapeHtml(value)
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
