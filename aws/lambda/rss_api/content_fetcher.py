from __future__ import annotations

import json
import os
import re
import uuid
from typing import Any
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import boto3
import html2text
from bs4 import BeautifulSoup
from botocore.config import Config

from content_sanitizer import normalize_article_text


USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
BLOCKED_PATTERNS = [
    "verifying your browser",
    "cloudflare",
    "checking your browser",
    "please wait",
    "ddos-guard",
    "incapsula",
    "security check",
    "captcha",
    "access denied",
    "enable javascript",
    "ray id",
]


class ContentFetchError(RuntimeError):
    pass


def _browser_lambda_client():
    read_timeout = int(os.environ.get("BROWSER_FETCHER_INVOKE_TIMEOUT", "145"))
    return boto3.client(
        "lambda",
        config=Config(
            connect_timeout=5,
            read_timeout=read_timeout,
            retries={"max_attempts": 1},
        ),
    )


def is_blocked(text: str) -> bool:
    lower = text.lower()
    return any(pattern in lower for pattern in BLOCKED_PATTERNS)


def is_trivial_content(text: str) -> bool:
    lower = text.lower()
    return len(text.strip()) < 400 or any(
        marker in lower
        for marker in (
            "window.location",
            "const target =",
            "document.write",
            "please enable javascript",
            "enable js and disable any ad blocker",
        )
    )


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
    markdown = re.sub(r"\n{3,}", "\n\n", markdown).strip()
    return normalize_article_text(markdown)


def fetch_direct(url: str) -> str:
    request = Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.8",
        },
    )
    with urlopen(request, timeout=20) as response:
        html = response.read().decode("utf-8", errors="replace")
    if is_blocked(html):
        raise ContentFetchError("direct fetch blocked")
    markdown = html_to_markdown(html)
    if is_trivial_content(markdown):
        raise ContentFetchError("direct fetch produced trivial content")
    return markdown


def fetch_browser(url: str, settings: dict[str, Any] | None = None) -> str:
    settings = settings or {}
    function_name = os.environ.get("BROWSER_FETCHER_FUNCTION")
    enabled_by_env = os.environ.get("BROWSER_BYPASS_ENABLED", "true").lower() == "true"
    enabled_by_settings = bool(settings.get("browserBypassEnabled", True))
    if not function_name or not enabled_by_env or not enabled_by_settings:
        raise ContentFetchError("browser bypass disabled")
    payload = {"url": url, "requestId": str(uuid.uuid4())}
    response = _browser_lambda_client().invoke(
        FunctionName=function_name,
        InvocationType="RequestResponse",
        Payload=json.dumps(payload).encode("utf-8"),
    )
    raw = response["Payload"].read().decode("utf-8")
    result = json.loads(raw or "{}")
    if result.get("error"):
        raise ContentFetchError(result["error"])
    if result.get("content"):
        return normalize_article_text(result["content"])
    if result.get("s3Key"):
        bucket = os.environ["APP_BUCKET"]
        obj = boto3.client("s3").get_object(Bucket=bucket, Key=result["s3Key"])
        body = json.loads(obj["Body"].read().decode("utf-8"))
        return normalize_article_text(body.get("content") or "")
    raise ContentFetchError("browser bypass returned no content")


def fetch_wayback(url: str) -> str:
    availability_url = "https://archive.org/wayback/available?" + urlencode({"url": url})
    with urlopen(Request(availability_url, headers={"User-Agent": USER_AGENT}), timeout=15) as response:
        payload = json.loads(response.read().decode("utf-8"))
    closest = payload.get("archived_snapshots", {}).get("closest", {})
    if not closest.get("available") or not closest.get("url"):
        raise ContentFetchError("Wayback Machine has no snapshot")
    with urlopen(Request(closest["url"], headers={"User-Agent": USER_AGENT}), timeout=20) as response:
        html = response.read().decode("utf-8", errors="replace")
    markdown = html_to_markdown(html)
    if not markdown.strip():
        raise ContentFetchError("Wayback snapshot had no usable content")
    return normalize_article_text(f"*[Retrieved from Wayback Machine]*\n\n{markdown}")


def fetch_with_fallbacks(
    url: str,
    settings: dict[str, Any] | None = None,
    *,
    force_browser: bool = False,
) -> dict[str, Any]:
    settings = settings or {}
    browser_mode = str(settings.get("browserBypassMode") or "on_blocked")
    errors: list[str] = []
    if not force_browser and browser_mode != "always":
        try:
            return {"strategy": "direct", "content": fetch_direct(url)}
        except Exception as exc:
            errors.append(f"direct: {exc}")
    try:
        content = fetch_browser(url, settings)
        if not content.startswith("*[Fetched via browser automation]*"):
            content = f"*[Fetched via browser automation]*\n\n{content}"
        content = normalize_article_text(content)
        return {"strategy": "browser", "content": content, "errors": errors}
    except Exception as exc:
        errors.append(f"browser: {exc}")
    try:
        return {"strategy": "wayback", "content": fetch_wayback(url), "errors": errors}
    except Exception as exc:
        errors.append(f"wayback: {exc}")
    raise ContentFetchError("All content fetch strategies failed: " + "; ".join(errors))
