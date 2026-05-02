from __future__ import annotations

import re
import os
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from email.utils import parsedate_to_datetime
from typing import Any
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import feedparser

from storage import stable_id


USER_AGENT = "rss-ai/1.0 (+https://personal.local/rss-ai)"
DEFAULT_REFRESH_MAX_WORKERS = 6


def _epoch_ms(value: Any) -> int:
    if not value:
        return int(time.time() * 1000)
    try:
        if isinstance(value, str):
            return int(parsedate_to_datetime(value).timestamp() * 1000)
        if hasattr(value, "tm_year"):
            return int(time.mktime(value) * 1000)
    except Exception:
        return int(time.time() * 1000)
    return int(time.time() * 1000)


def _hn_metrics(summary: str | None) -> tuple[int | None, int | None]:
    if not summary:
        return None, None
    score = None
    comments = None
    score_match = re.search(r"Points:\s*([0-9]+)", summary)
    comments_match = re.search(r"# Comments:\s*([0-9]+)", summary)
    if score_match:
        score = int(score_match.group(1))
    if comments_match:
        comments = int(comments_match.group(1))
    return score, comments


def _response_header(headers: Any, name: str) -> str | None:
    value = headers.get(name) if headers else None
    return str(value).strip() if value else None


def _request_headers(feed: dict[str, Any]) -> dict[str, str]:
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "application/rss+xml, application/atom+xml, text/xml, */*",
    }
    etag = str(feed.get("rssEtag") or feed.get("etag") or "").strip()
    last_modified = str(feed.get("rssLastModified") or feed.get("lastModified") or "").strip()
    if etag:
        headers["If-None-Match"] = etag
    if last_modified:
        headers["If-Modified-Since"] = last_modified
    return headers


def _parse_feed_articles(feed: dict[str, Any], body: bytes) -> list[dict[str, Any]]:
    limit = int(feed.get("limit") or 20)
    parsed = feedparser.parse(body)
    source = parsed.feed.get("title") or feed.get("name") or "Unknown"
    articles: list[dict[str, Any]] = []
    for entry in parsed.entries[:limit]:
        link = entry.get("link") or ""
        if not link:
            continue
        summary = entry.get("summary") or entry.get("description") or ""
        score, comments = _hn_metrics(summary)
        published = entry.get("published") or entry.get("updated")
        published_epoch = _epoch_ms(published or entry.get("published_parsed") or entry.get("updated_parsed"))
        article_id = stable_id(link, "article-")
        articles.append(
            {
                "articleId": article_id,
                "title": entry.get("title") or "No title",
                "link": link,
                "canonicalLink": link,
                "summary": summary,
                "feedSummary": summary,
                "publishedAt": published,
                "publishedEpoch": published_epoch,
                "source": source,
                "sourceUrl": feed.get("url") or "",
                "sourceFeedId": feed.get("feedId"),
                "score": score,
                "comments": comments,
                "tags": feed.get("tags") or [],
            }
        )
    return articles


def fetch_feed_result(feed: dict[str, Any]) -> dict[str, Any]:
    started_at = time.monotonic()
    url = feed["url"]
    headers = _request_headers(feed)
    conditional = "If-None-Match" in headers or "If-Modified-Since" in headers
    request = Request(url, headers=headers)
    try:
        with urlopen(request, timeout=30) as response:
            body = response.read()
            articles = _parse_feed_articles(feed, body)
            response_headers = response.headers
            return {
                "feedId": feed.get("feedId"),
                "name": feed.get("name"),
                "enabled": True,
                "limit": int(feed.get("limit") or 20),
                "status": "ok",
                "httpStatus": getattr(response, "status", response.getcode()),
                "conditionalRequest": conditional,
                "unchanged": False,
                "fetched": len(articles),
                "entriesChecked": len(articles),
                "etag": _response_header(response_headers, "ETag"),
                "lastModified": _response_header(response_headers, "Last-Modified"),
                "durationMs": int((time.monotonic() - started_at) * 1000),
                "articles": articles,
            }
    except HTTPError as exc:
        if exc.code == 304:
            return {
                "feedId": feed.get("feedId"),
                "name": feed.get("name"),
                "enabled": True,
                "limit": int(feed.get("limit") or 20),
                "status": "not_modified",
                "httpStatus": 304,
                "conditionalRequest": conditional,
                "unchanged": True,
                "fetched": 0,
                "entriesChecked": 0,
                "etag": _response_header(exc.headers, "ETag") or feed.get("rssEtag"),
                "lastModified": _response_header(exc.headers, "Last-Modified") or feed.get("rssLastModified"),
                "durationMs": int((time.monotonic() - started_at) * 1000),
                "articles": [],
            }
        raise


def fetch_feed(feed: dict[str, Any]) -> list[dict[str, Any]]:
    return list(fetch_feed_result(feed).get("articles") or [])


def _refresh_max_workers(feed_count: int) -> int:
    raw = os.environ.get("RSS_REFRESH_MAX_WORKERS", str(DEFAULT_REFRESH_MAX_WORKERS))
    try:
        configured = int(raw)
    except ValueError:
        configured = DEFAULT_REFRESH_MAX_WORKERS
    return max(1, min(feed_count, configured, 12))


def fetch_feeds_detailed(feeds: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    articles: list[dict[str, Any]] = []
    errors: list[dict[str, Any]] = []
    feed_results: list[dict[str, Any] | None] = [None] * len(feeds)
    enabled_feeds: list[tuple[int, dict[str, Any]]] = []
    for index, feed in enumerate(feeds):
        if not feed.get("enabled", True):
            feed_results[index] = {
                "feedId": feed.get("feedId"),
                "name": feed.get("name"),
                "enabled": False,
                "fetched": 0,
                "entriesChecked": 0,
                "status": "skipped",
                "unchanged": False,
            }
            continue
        enabled_feeds.append((index, feed))

    def record_result(index: int, result: dict[str, Any]) -> None:
        result_articles = list(result.pop("articles", []) or [])
        articles.extend(result_articles)
        feed_results[index] = result
        if result.get("status") == "error":
            errors.append(
                {
                    "feedId": result.get("feedId"),
                    "name": result.get("name"),
                    "url": result.get("url"),
                    "error": result.get("error"),
                }
            )

    if enabled_feeds:
        workers = _refresh_max_workers(len(enabled_feeds))
        with ThreadPoolExecutor(max_workers=workers) as executor:
            futures = {executor.submit(fetch_feed_result, feed): (index, feed) for index, feed in enabled_feeds}
            for future in as_completed(futures):
                index, feed = futures[future]
                try:
                    record_result(index, future.result())
                except Exception as exc:
                    error = str(exc)
                    record_result(
                        index,
                        {
                            "feedId": feed.get("feedId"),
                            "name": feed.get("name"),
                            "url": feed.get("url"),
                            "enabled": True,
                            "limit": int(feed.get("limit") or 20),
                            "fetched": 0,
                            "entriesChecked": 0,
                            "status": "error",
                            "unchanged": False,
                            "error": error,
                        },
                    )

    articles.sort(key=lambda item: int(item.get("publishedEpoch") or 0), reverse=True)
    return articles, errors, [result for result in feed_results if result is not None]


def fetch_feeds(feeds: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    articles, errors, _feed_results = fetch_feeds_detailed(feeds)
    return articles, errors
