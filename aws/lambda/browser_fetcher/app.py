from __future__ import annotations

import json
import os
import re
import time
import uuid
from typing import Any

import boto3
import html2text
from bs4 import BeautifulSoup
from playwright.sync_api import sync_playwright


USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"


def handler(event: dict[str, Any], _context: Any) -> dict[str, Any]:
    started_at = time.monotonic()
    url = str(event.get("url") or "").strip()
    if not url:
        _log_event("browser_fetch_rejected", error="url is required", level="WARNING")
        return {"error": "url is required"}

    request_id = str(event.get("requestId") or uuid.uuid4())
    _log_event("browser_fetch_started", requestId=request_id)
    try:
        markdown = fetch_with_browser(url)
        content = f"*[Fetched via browser automation]*\n\n{markdown}"
        inline_max = int(os.environ.get("BROWSER_RESULT_INLINE_MAX", "180000"))
        if len(content.encode("utf-8")) <= inline_max:
            _log_event(
                "browser_fetch_completed",
                requestId=request_id,
                delivery="inline",
                contentChars=len(content),
                durationMs=_elapsed_ms(started_at),
            )
            return {"requestId": request_id, "content": content}
        key = f"browser-results/{request_id}.json"
        boto3.client("s3").put_object(
            Bucket=os.environ["APP_BUCKET"],
            Key=key,
            Body=json.dumps({"url": url, "content": content}).encode("utf-8"),
            ContentType="application/json",
            ServerSideEncryption="AES256",
        )
        _log_event(
            "browser_fetch_completed",
            requestId=request_id,
            delivery="s3",
            s3Key=key,
            contentChars=len(content),
            durationMs=_elapsed_ms(started_at),
        )
        return {"requestId": request_id, "s3Key": key}
    except Exception as exc:
        _log_event("browser_fetch_failed", requestId=request_id, error=str(exc), durationMs=_elapsed_ms(started_at), level="ERROR")
        return {"requestId": request_id, "error": str(exc)}


def fetch_with_browser(url: str) -> str:
    started_at = time.monotonic()
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            headless=True,
            args=[
                "--disable-blink-features=AutomationControlled",
                "--disable-dev-shm-usage",
                "--no-sandbox",
                "--single-process",
            ],
        )
        try:
            context = browser.new_context(
                user_agent=USER_AGENT,
                viewport={"width": 1365, "height": 900},
                locale="en-US",
                extra_http_headers={"Accept-Language": "en-US,en;q=0.8"},
            )
            page = context.new_page()
            page.goto(url, wait_until="load", timeout=45000)
            try:
                page.wait_for_load_state("networkidle", timeout=15000)
            except Exception:
                pass
            html = page.locator("body").evaluate("node => node.outerHTML", timeout=10000)
            _log_event("browser_fetch_html_loaded", htmlChars=len(html), durationMs=_elapsed_ms(started_at))
        finally:
            browser.close()
    markdown = html_to_markdown(html)
    if not markdown.strip():
        raise RuntimeError("browser produced no readable content")
    _log_event("browser_fetch_markdown_extracted", markdownChars=len(markdown), durationMs=_elapsed_ms(started_at))
    return markdown


def html_to_markdown(html: str) -> str:
    soup = BeautifulSoup(html, "html.parser")
    for tag in soup(["script", "style", "nav", "footer", "aside", "menu", "noscript", "iframe"]):
        tag.decompose()
    root = soup.find("article") or soup.find("main") or soup.body or soup
    converter = html2text.HTML2Text()
    converter.ignore_links = False
    converter.ignore_images = True
    converter.body_width = 0
    markdown = converter.handle(str(root))
    markdown = re.sub(r"(?im)^!\[[^\]]*]\([^)]+\)\s*$", "", markdown)
    markdown = re.sub(r"\n{3,}", "\n\n", markdown).strip()
    return markdown


def _log_event(event: str, *, level: str = "INFO", **fields: Any) -> None:
    if not _should_log(level):
        return
    payload = {
        "level": level.upper(),
        "event": event,
        "service": "rss-browser-fetcher",
        "timestampMs": int(time.time() * 1000),
    }
    payload.update({key: _log_safe_value(value) for key, value in fields.items() if value is not None})
    print(json.dumps(payload, default=str, sort_keys=True))


def _should_log(level: str) -> bool:
    order = {"DEBUG": 10, "INFO": 20, "WARNING": 30, "ERROR": 40}
    configured = os.environ.get("RSS_AI_LOG_LEVEL", "INFO").upper()
    return order.get(level.upper(), 20) >= order.get(configured, 20)


def _log_safe_value(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): _log_safe_value(item) for key, item in value.items() if _log_safe_key(str(key))}
    if isinstance(value, list):
        return [_log_safe_value(item) for item in value[:50]]
    if isinstance(value, str):
        return value[:700]
    return value


def _log_safe_key(key: str) -> bool:
    lowered = key.lower()
    return not any(secret in lowered for secret in ("token", "secret", "password", "authorization", "apikey", "api_key"))


def _elapsed_ms(started_at: float) -> int:
    return int((time.monotonic() - started_at) * 1000)
