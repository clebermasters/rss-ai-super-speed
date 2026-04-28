from __future__ import annotations

import re
from typing import Any

from ai_client import generate_completion


class ContentFormattingError(RuntimeError):
    pass


SYSTEM_PROMPT = """You are a readability formatter for full news/article text on mobile screens.

Your job is formatting only. Do not summarize.
Preserve the article's original facts, quotes, numbers, names, links, argument order, and important nuance.
Do not add opinions, analysis, introductions, conclusions, disclaimers, or metadata.
Remove only obvious navigation, duplicated boilerplate, cookie prompts, newsletter promos, and unrelated page chrome.

Return clean Markdown optimized for mobile reading:
- Short paragraphs of 1 to 3 sentences.
- Clear headings only where the source text clearly supports them.
- Bullet lists only when the source text is already list-like or clearly enumerates items.
- Keep links as Markdown links when URLs are present.
- Keep code, commands, logs, and tables readable.
- Do not use emoji.
- Output only the formatted article body.
"""


def should_format_content_with_ai(settings: dict[str, Any], payload: dict[str, Any] | None = None) -> bool:
    payload = payload or {}
    for key in ("formatWithAi", "formatContent", "aiContentFormattingEnabled"):
        if key in payload:
            return _truthy(payload.get(key))
    return _truthy(settings.get("aiContentFormattingEnabled", False))


def format_article_content_for_mobile(
    content: str,
    *,
    article: dict[str, Any] | None = None,
    settings: dict[str, Any] | None = None,
    overrides: dict[str, Any] | None = None,
) -> str:
    settings = settings or {}
    overrides = overrides or {}
    article = article or {}
    marker, body = _split_status_marker(content)
    body = _normalize_input(body)
    if _word_count(body) < int(settings.get("aiContentFormattingMinWords") or 120):
        return _join_marker(marker, body)

    chunk_chars = int(settings.get("aiContentFormattingChunkChars") or overrides.get("formatChunkChars") or 8500)
    max_chunks = int(settings.get("aiContentFormattingMaxChunks") or overrides.get("formatMaxChunks") or 8)
    chunks, remainder = _split_for_llm(body, max_chars=chunk_chars, max_chunks=max_chunks)
    if not chunks:
        return _join_marker(marker, body)

    formatted_parts = []
    for index, chunk in enumerate(chunks, start=1):
        formatted = _format_chunk(
            chunk,
            article=article,
            settings=settings,
            overrides=overrides,
            part=index,
            total=len(chunks),
        )
        formatted_parts.append(formatted)

    if remainder.strip():
        formatted_parts.append(_light_cleanup(remainder))

    formatted_body = "\n\n".join(part.strip() for part in formatted_parts if part.strip()).strip()
    _validate_not_summary(body, formatted_body)
    return _join_marker(marker, formatted_body)


def _format_chunk(
    chunk: str,
    *,
    article: dict[str, Any],
    settings: dict[str, Any],
    overrides: dict[str, Any],
    part: int,
    total: int,
) -> str:
    prompt = f"""Article metadata:
Title: {article.get("title") or ""}
Source: {article.get("source") or ""}
URL: {article.get("link") or ""}
Part: {part} of {total}

Format this article text for comfortable mobile reading.
Preserve the complete meaning and details. Do not summarize or shorten.

ARTICLE TEXT:
{chunk}
"""
    completion_overrides = dict(overrides)
    completion_overrides["maxTokens"] = int(
        overrides.get("formatMaxTokens")
        or settings.get("aiContentFormattingMaxTokens")
        or completion_overrides.get("maxTokens")
        or 6000
    )
    completion_overrides["temperature"] = float(
        overrides.get("formatTemperature")
        or settings.get("aiContentFormattingTemperature")
        or completion_overrides.get("temperature")
        or 0.1
    )
    result = generate_completion(
        [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ],
        settings,
        completion_overrides,
    )
    formatted = _clean_model_output(str(result.get("content") or ""))
    if not formatted:
        raise ContentFormattingError("AI formatter returned empty content")
    _validate_not_summary(chunk, formatted)
    return formatted


def _split_status_marker(content: str) -> tuple[str, str]:
    lines = content.strip().splitlines()
    markers: list[str] = []
    index = 0
    while index < len(lines):
        line = lines[index].strip()
        if re.match(r"^\*\[[^\]]+\]\*$", line):
            markers.append(line)
            index += 1
            while index < len(lines) and not lines[index].strip():
                index += 1
            continue
        break
    return "\n\n".join(markers), "\n".join(lines[index:]).strip()


def _join_marker(marker: str, body: str) -> str:
    body = body.strip()
    if marker and body:
        return f"{marker}\n\n{body}"
    return marker or body


def _normalize_input(content: str) -> str:
    return re.sub(r"\n{3,}", "\n\n", content.replace("\r\n", "\n").replace("\r", "\n")).strip()


def _split_for_llm(content: str, *, max_chars: int, max_chunks: int) -> tuple[list[str], str]:
    paragraphs = re.split(r"\n{2,}", content)
    chunks: list[str] = []
    current: list[str] = []
    current_len = 0
    consumed = 0

    for paragraph in paragraphs:
        paragraph = paragraph.strip()
        if not paragraph:
            consumed += 1
            continue
        pieces = _hard_split(paragraph, max_chars) if len(paragraph) > max_chars else [paragraph]
        for piece in pieces:
            separator_len = 2 if current else 0
            if current and current_len + separator_len + len(piece) > max_chars:
                chunks.append("\n\n".join(current))
                current = []
                current_len = 0
                if len(chunks) >= max_chunks:
                    remaining = "\n\n".join(paragraphs[consumed:]).strip()
                    return chunks, remaining
            current.append(piece)
            current_len += separator_len + len(piece)
        consumed += 1

    if current and len(chunks) < max_chunks:
        chunks.append("\n\n".join(current))
    return chunks, ""


def _hard_split(text: str, max_chars: int) -> list[str]:
    sentences = re.split(r"(?<=[.!?])\s+", text)
    parts: list[str] = []
    current = ""
    for sentence in sentences:
        if current and len(current) + 1 + len(sentence) > max_chars:
            parts.append(current.strip())
            current = ""
        if len(sentence) > max_chars:
            parts.extend(sentence[i : i + max_chars] for i in range(0, len(sentence), max_chars))
        else:
            current = f"{current} {sentence}".strip()
    if current:
        parts.append(current)
    return parts


def _clean_model_output(content: str) -> str:
    text = content.strip()
    text = re.sub(r"^```(?:markdown|md|text)?\s*", "", text, flags=re.IGNORECASE)
    text = re.sub(r"\s*```$", "", text)
    text = re.sub(r"(?i)^here(?:'s| is) the formatted article:\s*", "", text).strip()
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def _light_cleanup(content: str) -> str:
    lines = [line.rstrip() for line in content.replace("\r\n", "\n").replace("\r", "\n").splitlines()]
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()


def _validate_not_summary(original: str, formatted: str) -> None:
    original_words = _word_count(original)
    formatted_words = _word_count(formatted)
    if original_words < 180:
        return
    if formatted_words < max(80, int(original_words * 0.42)):
        raise ContentFormattingError("AI formatter appears to have summarized instead of preserving the article")


def _word_count(text: str) -> int:
    return len(re.findall(r"\b[\w'-]+\b", text))


def _truthy(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in {"1", "true", "yes", "on", "enabled"}
