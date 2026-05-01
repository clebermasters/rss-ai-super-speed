from __future__ import annotations

import html
import re


STATUS_MARKER_RE = re.compile(r"^\*\[[^\]]+\]\*$")
IMAGE_URL_RE = re.compile(r"\.(?:png|jpe?g|gif|webp|svg|avif)(?:[?#][^\s)]*)?", re.IGNORECASE)
MARKDOWN_IMAGE_RE = re.compile(r"!\[[^\]\n]*]\([^)]+\)")
MARKDOWN_LINK_RE = re.compile(r"(?<!!)\[([^\]\n]{1,240})]\(([^)\n]+)\)")
RAW_ANCHOR_RE = re.compile(r"<a\b(?=[^>]*\bhref\s*=)([^>]*)>(.*?)</a>", re.IGNORECASE | re.DOTALL)
HREF_ATTR_RE = re.compile(r"\bhref\s*=\s*(?:([\"'])(.*?)\1|([^\s>]+))", re.IGNORECASE | re.DOTALL)
ORPHAN_HREF_RE = re.compile(
    r"\bhref\s*=\s*(?:[\"']?)([^\"'\s>]+)(?:[\"']?)"
    r"(?:\s+[\w:-]+\s*=\s*(?:\"[^\"]*\"|'[^']*'|[^\s>]+))*\s*>\s*([^\n<]+)",
    re.IGNORECASE,
)
ORPHAN_ATTR_RE = re.compile(
    r"\s+(?:target|rel|class|style|width|height|loading|decoding|srcset|sizes|"
    r"aria-[\w-]+|data-[\w-]+)\s*=\s*(?:\"[^\"]*\"|'[^']*'|[^\s>]+)",
    re.IGNORECASE,
)
DOMAIN_LIKE_RE = re.compile(r"^[a-z0-9.-]+\.[a-z]{2,}(?:/|$)", re.IGNORECASE)
DATE_LINE_RE = re.compile(
    r"^\*?\s*(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z.]*"
    r"\s+\d{1,2},\s+\d{4}\s*\*?$",
    re.IGNORECASE,
)

BOILERPLATE_PREFIXES = (
    "tags:",
    "tagged:",
    "categories:",
    "category:",
    "share this",
    "share on",
    "related posts",
    "related articles",
    "recommended reading",
    "subscribe",
    "newsletter",
    "advertisement",
    "sponsored content",
)

MEDIA_NOISE_WORDS = (
    "decorative image",
    "featured image",
    "hero image",
    "thumbnail",
    "image credit",
    "photo credit",
    "getty images",
    "shutterstock",
    "stock photo",
    "alt text",
)


def normalize_article_text(content: str) -> str:
    """Normalize fetched/formatted article text before it is saved or rendered.

    The sanitizer is intentionally conservative about prose, but aggressive
    about page chrome: image-only links, broken HTML attributes, author metadata,
    tag lists, and malformed anchor fragments should not reach the reader.
    """

    if not content:
        return ""
    text = html.unescape(str(content)).replace("\r\n", "\n").replace("\r", "\n")
    text = RAW_ANCHOR_RE.sub(_anchor_to_markdown, text)
    text = ORPHAN_HREF_RE.sub(_orphan_href_to_markdown, text)
    text = re.sub(r"(?i)<\s*br\s*/?\s*>", "\n", text)
    text = re.sub(r"(?i)</\s*(?:p|div|section|article|h[1-6]|li|ul|ol|blockquote|tr)\s*>", "\n", text)
    text = re.sub(r"(?i)<\s*li\b[^>]*>", "\n- ", text)
    text = re.sub(r"<[^>\n]+>", "", text)
    text = _remove_orphan_attrs(text)
    text = _clean_markdown_links(text)

    cleaned: list[str] = []
    meaningful_index = 0
    for raw_line in text.splitlines():
        line = _clean_line(raw_line)
        if not line:
            if cleaned and cleaned[-1] != "":
                cleaned.append("")
            continue
        if STATUS_MARKER_RE.match(line):
            cleaned.append(line)
            continue
        if _is_noise_line(line, meaningful_index):
            continue
        cleaned.append(line)
        meaningful_index += 1

    output = "\n".join(cleaned).strip()
    output = re.sub(r"[ \t]+\n", "\n", output)
    output = re.sub(r"\n{3,}", "\n\n", output)
    return output.strip()


def _anchor_to_markdown(match: re.Match[str]) -> str:
    href_match = HREF_ATTR_RE.search(match.group(1))
    href = _clean_href((href_match.group(2) or href_match.group(3)) if href_match else "")
    label = _plain_text(match.group(2))
    if not href or not label:
        return label
    if _is_decorative_label(label) or _looks_like_media_url(href):
        return ""
    return f"[{label}]({href})"


def _orphan_href_to_markdown(match: re.Match[str]) -> str:
    href = _clean_href(match.group(1))
    label = _plain_text(match.group(2))
    if not href or not label:
        return label
    if _is_decorative_label(label) or _looks_like_media_url(href):
        return ""
    return f"[{label}]({href})"


def _clean_markdown_links(text: str) -> str:
    text = MARKDOWN_IMAGE_RE.sub("", text)

    def replace(match: re.Match[str]) -> str:
        label = _plain_text(match.group(1))
        href = _clean_href(match.group(2))
        if not href:
            return label
        if _is_decorative_label(label) or _looks_like_media_url(href):
            return ""
        return f"[{label}]({href})"

    return MARKDOWN_LINK_RE.sub(replace, text)


def _clean_href(href: str) -> str:
    raw = html.unescape(str(href or "")).strip()
    raw = _remove_orphan_attrs(raw)
    raw = re.split(r"\s+(?=[\"'])", raw, maxsplit=1)[0]
    raw = re.split(r"[\"']\s+(?:target|rel|class|style|width|height)=", raw, maxsplit=1, flags=re.IGNORECASE)[0]
    raw = raw.strip(" \t\r\n\"'<>")
    raw = raw.rstrip(".,;")
    if raw.startswith("//"):
        return "https:" + raw
    if DOMAIN_LIKE_RE.match(raw):
        return "https://" + raw
    return raw


def _clean_line(line: str) -> str:
    line = html.unescape(line).strip()
    line = _remove_orphan_attrs(line)
    line = re.sub(r"\s*[/]?>\s*", " ", line)
    line = re.sub(r"\s{2,}", " ", line)
    return line.strip(" \t")


def _remove_orphan_attrs(text: str) -> str:
    return ORPHAN_ATTR_RE.sub("", text)


def _plain_text(value: str) -> str:
    text = html.unescape(str(value or ""))
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def _is_noise_line(line: str, meaningful_index: int) -> bool:
    lower = line.lower().strip()
    if not lower:
        return True
    if any(lower.startswith(prefix) for prefix in BOILERPLATE_PREFIXES):
        return True
    if _looks_like_author_byline(line) and meaningful_index < 16:
        return True
    if DATE_LINE_RE.match(line) and meaningful_index < 16:
        return True
    if _looks_like_media_line(line):
        return True
    if "target=" in lower or "rel=" in lower or "href=" in lower:
        return _link_or_attr_noise_ratio(line) > 0.35
    return False


def _looks_like_author_byline(line: str) -> bool:
    lower = line.lower()
    if not lower.startswith("by "):
        return False
    if "posts by" in lower or "/author/" in lower or "author/" in lower:
        return True
    without_links = re.sub(r"\[([^\]]+)]\([^)]+\)", r"\1", line)
    if re.fullmatch(r"By\s+[A-Z][A-Za-z .'\-]{1,80}", without_links):
        return len(without_links.split()) <= 8
    return False


def _looks_like_media_line(line: str) -> bool:
    lower = line.lower()
    if any(word in lower for word in MEDIA_NOISE_WORDS):
        return True
    if not IMAGE_URL_RE.search(line):
        return False
    words = re.findall(r"[A-Za-z]{3,}", line)
    if len(words) <= 8:
        return True
    return _link_or_attr_noise_ratio(line) > 0.45


def _looks_like_media_url(value: str) -> bool:
    return bool(IMAGE_URL_RE.search(value))


def _is_decorative_label(label: str) -> bool:
    lower = label.lower().strip()
    return not lower or any(word in lower for word in MEDIA_NOISE_WORDS) or lower in {"image", "photo", "open image"}


def _link_or_attr_noise_ratio(line: str) -> float:
    if not line:
        return 1.0
    noisy_chars = sum(len(match.group(0)) for match in re.finditer(r"https?://\S+|[a-z0-9.-]+\.[a-z]{2,}/\S+|\w+=", line, re.IGNORECASE))
    return noisy_chars / max(1, len(line))
