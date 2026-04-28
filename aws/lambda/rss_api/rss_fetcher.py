from __future__ import annotations

import re
import time
from email.utils import parsedate_to_datetime
from typing import Any
from urllib.request import Request, urlopen

import feedparser

from storage import stable_id


USER_AGENT = "rss-ai/1.0 (+https://personal.local/rss-ai)"


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


def fetch_feed(feed: dict[str, Any]) -> list[dict[str, Any]]:
    url = feed["url"]
    limit = int(feed.get("limit") or 20)
    request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/rss+xml, application/atom+xml, text/xml, */*"})
    with urlopen(request, timeout=30) as response:
        body = response.read()
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
                "sourceUrl": url,
                "sourceFeedId": feed.get("feedId"),
                "score": score,
                "comments": comments,
                "tags": feed.get("tags") or [],
            }
        )
    return articles


def fetch_feeds(feeds: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    articles: list[dict[str, Any]] = []
    errors: list[dict[str, Any]] = []
    for feed in feeds:
        if not feed.get("enabled", True):
            continue
        try:
            articles.extend(fetch_feed(feed))
        except Exception as exc:
            errors.append(
                {
                    "feedId": feed.get("feedId"),
                    "name": feed.get("name"),
                    "url": feed.get("url"),
                    "error": str(exc),
                }
            )
    articles.sort(key=lambda item: int(item.get("publishedEpoch") or 0), reverse=True)
    return articles, errors
