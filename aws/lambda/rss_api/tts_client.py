from __future__ import annotations

import html
import hashlib
import os
import re
from typing import Any

import requests


class TtsError(RuntimeError):
    pass


DEFAULT_TTS_MODEL = "gpt-4o-mini-tts-2025-12-15"
DEFAULT_TTS_VOICE = "marin"
DEFAULT_TTS_INSTRUCTIONS = (
    "Read this as a calm, clear personal news reader. Use natural pacing, "
    "short pauses between paragraphs, and a warm but neutral tone."
)


def synthesize_speech(
    text: str,
    *,
    settings: dict[str, Any] | None = None,
    overrides: dict[str, Any] | None = None,
    prepared_input: dict[str, Any] | None = None,
) -> dict[str, Any]:
    settings = settings or {}
    overrides = overrides or {}
    api_key = os.environ.get("OPENAI_API_KEY", "")
    if not api_key:
        raise TtsError("OpenAI API key is required for text-to-speech")

    api_base = str(
        overrides.get("ttsApiBase")
        or settings.get("ttsApiBase")
        or os.environ.get("OPENAI_TTS_API_BASE")
        or "https://api.openai.com/v1"
    ).rstrip("/")
    model = str(
        overrides.get("ttsModel")
        or overrides.get("model")
        or settings.get("ttsModel")
        or os.environ.get("OPENAI_TTS_MODEL")
        or DEFAULT_TTS_MODEL
    )
    voice = str(
        overrides.get("ttsVoice")
        or overrides.get("voice")
        or settings.get("ttsVoice")
        or os.environ.get("OPENAI_TTS_VOICE")
        or DEFAULT_TTS_VOICE
    )
    instructions = str(
        overrides.get("ttsInstructions")
        or overrides.get("instructions")
        or settings.get("ttsInstructions")
        or os.environ.get("OPENAI_TTS_INSTRUCTIONS")
        or DEFAULT_TTS_INSTRUCTIONS
    )
    response_format = str(overrides.get("responseFormat") or settings.get("ttsResponseFormat") or "mp3")
    prepared = prepared_input or prepare_tts_input(text, settings=settings, overrides=overrides)
    input_text = str(prepared.get("text") or "")
    if not input_text:
        raise TtsError("No readable text is available for text-to-speech")

    response = requests.post(
        f"{api_base}/audio/speech",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        json={
            "model": model,
            "voice": voice,
            "input": input_text,
            "instructions": instructions,
            "response_format": response_format,
        },
        timeout=int(overrides.get("timeout") or 90),
    )
    if response.status_code >= 400:
        raise TtsError(f"OpenAI text-to-speech failed: {response.status_code} {response.text}")

    return {
        "audio": response.content,
        "contentType": response.headers.get("content-type") or _content_type_for_format(response_format),
        "model": model,
        "voice": voice,
        "responseFormat": response_format,
        "inputChars": len(input_text),
        "textHash": prepared.get("textHash"),
        "segmentIndex": prepared.get("segmentIndex", 0),
        "segmentCount": prepared.get("segmentCount", 1),
        "segmentPercent": prepared.get("segmentPercent", 100),
    }


def tts_cache_key(
    article_id: str,
    target: str,
    *,
    prepared_input: dict[str, Any],
    settings: dict[str, Any] | None = None,
    overrides: dict[str, Any] | None = None,
) -> str:
    settings = settings or {}
    overrides = overrides or {}
    model = str(overrides.get("ttsModel") or overrides.get("model") or settings.get("ttsModel") or os.environ.get("OPENAI_TTS_MODEL") or DEFAULT_TTS_MODEL)
    voice = str(overrides.get("ttsVoice") or overrides.get("voice") or settings.get("ttsVoice") or os.environ.get("OPENAI_TTS_VOICE") or DEFAULT_TTS_VOICE)
    instructions = str(overrides.get("ttsInstructions") or overrides.get("instructions") or settings.get("ttsInstructions") or os.environ.get("OPENAI_TTS_INSTRUCTIONS") or DEFAULT_TTS_INSTRUCTIONS)
    response_format = str(overrides.get("responseFormat") or settings.get("ttsResponseFormat") or "mp3")
    fingerprint = hashlib.sha256(
        jsonish(
            {
                "articleId": article_id,
                "target": target,
                "model": model,
                "voice": voice,
                "instructionsHash": hashlib.sha256(instructions.encode("utf-8")).hexdigest(),
                "textHash": prepared_input.get("textHash"),
                "segmentIndex": prepared_input.get("segmentIndex", 0),
                "segmentPercent": prepared_input.get("segmentPercent", 100),
                "responseFormat": response_format,
            }
        ).encode("utf-8")
    ).hexdigest()[:32]
    safe_article_id = re.sub(r"[^A-Za-z0-9_.-]", "-", article_id)[:96]
    safe_target = re.sub(r"[^A-Za-z0-9_.-]", "-", target)[:32]
    segment_percent = int(prepared_input.get("segmentPercent") or 100)
    segment_index = int(prepared_input.get("segmentIndex") or 0)
    return f"tts-cache/{safe_article_id}/{safe_target}/p{segment_percent}/i{segment_index}/{fingerprint}.{response_format}"


def prepare_tts_input(
    text: str,
    *,
    settings: dict[str, Any] | None = None,
    overrides: dict[str, Any] | None = None,
) -> dict[str, Any]:
    settings = settings or {}
    overrides = overrides or {}
    max_chars = _bounded_int(overrides.get("maxChars") or settings.get("ttsMaxInputChars") or 6000, minimum=500, maximum=16000)
    segment_percent = _bounded_int(overrides.get("segmentPercent") or settings.get("ttsSegmentPercent") or 100, minimum=5, maximum=100)
    segment_index = max(0, int(overrides.get("segmentIndex") or 0))
    cleaned = clean_tts_text(text)
    segment_text, effective_index, segment_count = _slice_by_percent(cleaned, segment_index, segment_percent)
    segment_text = _truncate_at_word_boundary(segment_text, max_chars)
    return {
        "text": segment_text,
        "textHash": hashlib.sha256(segment_text.encode("utf-8")).hexdigest(),
        "sourceHash": hashlib.sha256(cleaned.encode("utf-8")).hexdigest(),
        "segmentIndex": effective_index,
        "segmentCount": segment_count,
        "segmentPercent": segment_percent,
        "maxChars": max_chars,
        "sourceChars": len(cleaned),
        "inputChars": len(segment_text),
    }


def prepare_tts_text(text: str, *, max_chars: int) -> str:
    return _truncate_at_word_boundary(clean_tts_text(text), max_chars)


def clean_tts_text(text: str) -> str:
    cleaned = _strip_markdown(_strip_html(text))
    cleaned = re.sub(r"\*\[[^\]]+\]\*", "", cleaned)
    cleaned = re.sub(r"[\U0001F300-\U0001FAFF\U00002700-\U000027BF]", " ", cleaned)
    cleaned = re.sub(r"https?://\S+|www\.\S+", " ", cleaned)
    cleaned = re.sub(r"\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b", " ", cleaned)
    lines = []
    for raw_line in cleaned.replace("\r\n", "\n").replace("\r", "\n").splitlines():
        line = re.sub(r"\s+", " ", raw_line).strip()
        if not line or _is_noise_line(line):
            continue
        lines.append(line)
    return re.sub(r"\s+", " ", " ".join(lines)).strip()


def _strip_html(text: str) -> str:
    without_blocks = re.sub(r"(?is)<(script|style|svg|head)\b[^>]*>.*?</\1>", " ", text)
    without_breaks = re.sub(r"(?i)<\s*br\s*/?>|</\s*p\s*>|</\s*h[1-6]\s*>|</\s*li\s*>", "\n", without_blocks)
    without_tags = re.sub(r"(?s)<[^>]+>", " ", without_breaks)
    return html.unescape(without_tags)


def _strip_markdown(text: str) -> str:
    cleaned = text.replace("\r\n", "\n").replace("\r", "\n")
    cleaned = re.sub(r"```[\s\S]*?```", " ", cleaned)
    cleaned = re.sub(r"!\[([^]]*)]\([^)]+\)", r"\1", cleaned)
    cleaned = re.sub(r"\[([^]]+)]\(([^)]+)\)", r"\1", cleaned)
    cleaned = re.sub(r"(?m)^\s*\*\[[^\]]+\]\*\s*$", " ", cleaned)
    cleaned = re.sub(r"(?m)^\s*#{1,6}\s+", "", cleaned)
    cleaned = re.sub(r"(?m)^\s*[-*+]\s+", "", cleaned)
    cleaned = re.sub(r"(?m)^\s*\d+\.\s+", "", cleaned)
    cleaned = re.sub(r"[*_`~>|]", "", cleaned)
    return cleaned


def _content_type_for_format(response_format: str) -> str:
    return {
        "mp3": "audio/mpeg",
        "wav": "audio/wav",
        "opus": "audio/opus",
        "aac": "audio/aac",
        "flac": "audio/flac",
        "pcm": "audio/pcm",
    }.get(response_format, "audio/mpeg")


def jsonish(value: dict[str, Any]) -> str:
    parts = []
    for key in sorted(value):
        parts.append(f"{key}={value[key]}")
    return "|".join(parts)


def _is_noise_line(line: str) -> bool:
    normalized = line.strip().lower()
    if len(normalized) <= 2:
        return True
    if re.fullmatch(r"(share|copy|link|menu|close|contact|advertisement|ad|sponsored)", normalized):
        return True
    if re.match(
        r"^(advertisement|sponsored|subscribe|sign up|sign in|log in|create account|"
        r"read more|related stories|related articles|recommended|more from|follow us|"
        r"share this|copy link|all rights reserved|privacy policy|terms of service|"
        r"cookie|newsletter|comments?|view comments|open in app)\b",
        normalized,
    ):
        return True
    alpha_count = sum(1 for char in normalized if char.isalpha())
    return len(normalized) > 8 and alpha_count / max(len(normalized), 1) < 0.35


def _slice_by_percent(text: str, segment_index: int, segment_percent: int) -> tuple[str, int, int]:
    if segment_percent >= 100:
        return text, 0, 1
    words = text.split()
    if not words:
        return "", 0, 1
    segment_count = max(1, (100 + segment_percent - 1) // segment_percent)
    effective_index = min(max(0, segment_index), segment_count - 1)
    start = round(len(words) * (effective_index * segment_percent / 100.0))
    end = len(words) if effective_index == segment_count - 1 else round(len(words) * ((effective_index + 1) * segment_percent / 100.0))
    if end <= start:
        end = min(len(words), start + 1)
    return " ".join(words[start:end]).strip(), effective_index, segment_count


def _truncate_at_word_boundary(text: str, max_chars: int) -> str:
    if len(text) <= max_chars:
        return text
    truncated = text[:max_chars].rsplit(" ", 1)[0].strip()
    return f"{truncated}..." if truncated else text[:max_chars]


def _bounded_int(value: Any, *, minimum: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = minimum
    return min(max(parsed, minimum), maximum)
