from __future__ import annotations

import hashlib
import json
import os
import time
from decimal import Decimal
from typing import Any

import boto3
from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError


USER_PK = "USER#default"
ARTICLES_GSI_PK = "USER#default#ARTICLES"
DEFAULT_ARTICLE_CONTENT_CACHE_TTL_DAYS = 30


def now_ms() -> int:
    return int(time.time() * 1000)


def stable_id(value: str, prefix: str = "") -> str:
    digest = hashlib.sha256(value.encode("utf-8")).hexdigest()[:24]
    return f"{prefix}{digest}" if prefix else digest


def _clean(value: Any) -> Any:
    if isinstance(value, float):
        return Decimal(str(value))
    if isinstance(value, dict):
        return {k: _clean(v) for k, v in value.items() if v is not None}
    if isinstance(value, list):
        return [_clean(v) for v in value if v is not None]
    return value


def _normalize_tags(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        raw_tags = value.split(",")
    elif isinstance(value, (list, tuple, set)):
        raw_tags = value
    else:
        raw_tags = [value]
    normalized: list[str] = []
    seen: set[str] = set()
    for tag in raw_tags:
        clean = " ".join(str(tag).strip().lstrip("#").lower().split())
        if not clean or clean in seen:
            continue
        seen.add(clean)
        normalized.append(clean)
    return normalized


def _bounded_int(value: Any, default: int, *, minimum: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    return max(minimum, min(maximum, parsed))


def content_cache_ttl_days(settings: dict[str, Any] | None = None) -> int:
    settings = settings or {}
    return _bounded_int(
        settings.get("articleContentCacheTtlDays"),
        DEFAULT_ARTICLE_CONTENT_CACHE_TTL_DAYS,
        minimum=1,
        maximum=365,
    )


def content_cache_expires_at(settings: dict[str, Any] | None = None) -> int:
    return int(time.time()) + content_cache_ttl_days(settings) * 24 * 60 * 60


def content_cache_expired(article: dict[str, Any] | None) -> bool:
    if not article:
        return False
    expires_at = int(article.get("contentExpiresAt") or 0)
    return bool(expires_at and expires_at <= int(time.time()))


class RssStorage:
    def __init__(self, table_name: str | None = None, bucket_name: str | None = None) -> None:
        self.table_name = table_name or os.environ["TABLE_NAME"]
        self.bucket_name = bucket_name or os.environ.get("APP_BUCKET", "")
        self.table = boto3.resource("dynamodb").Table(self.table_name)
        self.s3 = boto3.client("s3")

    def get_settings(self) -> dict[str, Any]:
        item = self.table.get_item(Key={"pk": USER_PK, "sk": "SETTINGS#app"}).get("Item")
        defaults = self.default_settings()
        if item:
            item.pop("pk", None)
            item.pop("sk", None)
            return {**defaults, **item}
        return defaults

    def default_settings(self) -> dict[str, Any]:
        return {
            "llmProvider": "openai_compatible",
            "aiModel": os.environ.get("AI_MODEL", "gpt-5.4"),
            "aiApiBase": os.environ.get("OPENAI_API_BASE", "https://api.openai.com/v1"),
            "codexModel": os.environ.get("OPENAI_CODEX_MODEL", "gpt-5.4"),
            "codexReasoningEffort": "medium",
            "codexClientVersion": os.environ.get("OPENAI_CODEX_CLIENT_VERSION", "0.118.0"),
            "embeddingProvider": "openai_compatible",
            "embeddingModel": os.environ.get("EMBEDDING_MODEL", "text-embedding-3-small"),
            "ttsApiBase": os.environ.get("OPENAI_TTS_API_BASE", "https://api.openai.com/v1"),
            "ttsModel": os.environ.get("OPENAI_TTS_MODEL", "gpt-4o-mini-tts-2025-12-15"),
            "ttsVoice": os.environ.get("OPENAI_TTS_VOICE", "marin"),
            "ttsInstructions": os.environ.get(
                "OPENAI_TTS_INSTRUCTIONS",
                "Read this as a calm, clear personal news reader. Use natural pacing, short pauses between paragraphs, and a warm but neutral tone.",
            ),
            "ttsMaxInputChars": 6000,
            "ttsResponseFormat": "mp3",
            "ttsSegmentPercent": 100,
            "autoSummarize": False,
            "autoFetchContent": False,
            "aiContentFormattingEnabled": False,
            "aiContentFormattingMinWords": 120,
            "aiContentFormattingChunkChars": 8500,
            "aiContentFormattingMaxChunks": 8,
            "aiContentFormattingMaxTokens": 6000,
            "aiContentFormattingTemperature": 0.1,
            "prefetchDistance": 3,
            "browserBypassEnabled": True,
            "browserBypassMode": "on_blocked",
            "refreshOnOpen": True,
            "scheduledRefreshEnabled": False,
            "scheduledRefreshRate": "rate(6 hours)",
            "scheduledAiPrefetchEnabled": False,
            "scheduledAiPrefetchTags": [],
            "scheduledAiPrefetchLimit": 5,
            "scheduledAiPrefetchMaxAgeHours": 24,
            "scheduledAiPrefetchRetryMinutes": 60,
            "scheduledAiPrefetchSummaries": True,
            "scheduledAiPrefetchContent": True,
            "defaultArticleLimit": 50,
            "cleanupReadAfterDays": 30,
            "articleContentCacheTtlDays": DEFAULT_ARTICLE_CONTENT_CACHE_TTL_DAYS,
            "semanticSearchEnabled": False,
            "exportDefaultFormat": "markdown",
        }

    def update_settings(self, updates: dict[str, Any]) -> dict[str, Any]:
        stored_settings = self.table.get_item(Key={"pk": USER_PK, "sk": "SETTINGS#app"}).get("Item") or {}
        settings = self.get_settings()
        previous_content_ttl = content_cache_ttl_days(settings)
        had_content_ttl_setting = "articleContentCacheTtlDays" in stored_settings
        if "scheduledAiPrefetchTags" in updates:
            updates = {**updates, "scheduledAiPrefetchTags": _normalize_tags(updates.get("scheduledAiPrefetchTags"))}
        if "articleContentCacheTtlDays" in updates:
            updates = {
                **updates,
                "articleContentCacheTtlDays": _bounded_int(
                    updates.get("articleContentCacheTtlDays"),
                    DEFAULT_ARTICLE_CONTENT_CACHE_TTL_DAYS,
                    minimum=1,
                    maximum=365,
                ),
            }
        settings.update(updates)
        item = {"pk": USER_PK, "sk": "SETTINGS#app", **settings, "updatedAt": now_ms()}
        self.table.put_item(Item=_clean(item))
        if "articleContentCacheTtlDays" in updates:
            current_content_ttl = content_cache_ttl_days(settings)
            if not had_content_ttl_setting or current_content_ttl != previous_content_ttl:
                self.refresh_content_cache_ttl(current_content_ttl)
        return settings

    def default_feeds(self) -> list[dict[str, Any]]:
        return [
            {
                "name": "Hacker News",
                "url": "https://hnrss.org/best",
                "enabled": True,
                "tags": ["tech", "hackernews"],
                "limit": 20,
            },
            {
                "name": "TechCrunch",
                "url": "https://techcrunch.com/feed/",
                "enabled": True,
                "tags": ["tech", "startup"],
                "limit": 10,
            },
            {
                "name": "VentureBeat",
                "url": "https://venturebeat.com/feed/",
                "enabled": True,
                "tags": ["ai", "business"],
                "limit": 10,
            },
            {
                "name": "OpenAI Blog",
                "url": "https://openai.com/blog/rss.xml",
                "enabled": True,
                "tags": ["ai", "openai"],
                "limit": 5,
            },
        ]

    def bootstrap_defaults(self) -> None:
        if self.list_feeds():
            return
        for feed in self.default_feeds():
            self.create_feed(feed)

    def create_feed(self, payload: dict[str, Any]) -> dict[str, Any]:
        url = str(payload["url"]).strip()
        name = str(payload.get("name") or self._name_from_url(url))
        feed_id = payload.get("feedId") or stable_id(url, "feed-")
        item = {
            "pk": USER_PK,
            "sk": f"FEED#{feed_id}",
            "feedId": feed_id,
            "name": name,
            "url": url,
            "enabled": bool(payload.get("enabled", True)),
            "tags": _normalize_tags(payload.get("tags")),
            "limit": int(payload.get("limit") or 20),
            "lastFetchedAt": payload.get("lastFetchedAt"),
            "lastFetchStatus": payload.get("lastFetchStatus"),
            "lastFetchError": payload.get("lastFetchError"),
            "articleCount": int(payload.get("articleCount") or 0),
            "unreadCount": int(payload.get("unreadCount") or 0),
            "createdAt": int(payload.get("createdAt") or now_ms()),
            "updatedAt": now_ms(),
        }
        self.table.put_item(Item=_clean(item))
        return self._strip_keys(item)

    def list_feeds(self) -> list[dict[str, Any]]:
        response = self.table.query(
            KeyConditionExpression=Key("pk").eq(USER_PK) & Key("sk").begins_with("FEED#")
        )
        feeds = [self._strip_keys(item) for item in response.get("Items", [])]
        counts = self._feed_article_counts()
        for feed in feeds:
            feed_counts = counts.get(str(feed.get("feedId") or ""), {"total": 0, "unread": 0})
            feed["articleCount"] = int(feed_counts["total"])
            feed["unreadCount"] = int(feed_counts["unread"])
        feeds.sort(key=lambda item: item.get("name", "").lower())
        return feeds

    def _feed_article_counts(self) -> dict[str, dict[str, int]]:
        counts: dict[str, dict[str, int]] = {}
        for article in self.list_articles({"limit": 5000}):
            feed_id = str(article.get("sourceFeedId") or "")
            if not feed_id:
                continue
            feed_counts = counts.setdefault(feed_id, {"total": 0, "unread": 0})
            feed_counts["total"] += 1
            if not article.get("isRead"):
                feed_counts["unread"] += 1
        return counts

    def get_feed(self, feed_id: str) -> dict[str, Any] | None:
        item = self.table.get_item(Key={"pk": USER_PK, "sk": f"FEED#{feed_id}"}).get("Item")
        return self._strip_keys(item) if item else None

    def update_feed(self, feed_id: str, updates: dict[str, Any]) -> dict[str, Any] | None:
        current = self.get_feed(feed_id)
        if not current:
            return None
        clean_updates = {k: v for k, v in updates.items() if k not in {"feedId", "createdAt"}}
        tags_changed = "tags" in clean_updates
        if tags_changed:
            clean_updates["tags"] = _normalize_tags(clean_updates.get("tags"))
        current.update(clean_updates)
        current["updatedAt"] = now_ms()
        self.table.put_item(Item=_clean({"pk": USER_PK, "sk": f"FEED#{feed_id}", **current}))
        if tags_changed:
            self._sync_feed_article_tags(feed_id, current.get("tags") or [])
        return current

    def _sync_feed_article_tags(self, feed_id: str, tags: list[str]) -> int:
        count = 0
        normalized = _normalize_tags(tags)
        for article in self.list_articles({"limit": 5000}):
            if article.get("sourceFeedId") != feed_id:
                continue
            self.update_article(str(article["articleId"]), {"tags": normalized})
            count += 1
        return count

    def delete_feed(self, feed_id: str) -> None:
        self.table.delete_item(Key={"pk": USER_PK, "sk": f"FEED#{feed_id}"})

    def save_articles(self, articles: list[dict[str, Any]]) -> int:
        saved = 0
        for article in articles:
            article_id = article.get("articleId") or stable_id(article["link"], "article-")
            published_epoch = int(article.get("publishedEpoch") or now_ms())
            item = {
                "pk": USER_PK,
                "sk": f"ARTICLE#{article_id}",
                "gsi1pk": ARTICLES_GSI_PK,
                "gsi1sk": f"{published_epoch:013d}#{article_id}",
                "articleId": article_id,
                "title": article.get("title") or "No title",
                "link": article.get("link") or "",
                "canonicalLink": article.get("canonicalLink") or article.get("link") or "",
                "summary": article.get("summary"),
                "feedSummary": article.get("feedSummary") or article.get("summary"),
                "contentPreview": article.get("contentPreview"),
                "contentChunkCount": int(article.get("contentChunkCount") or 0),
                "publishedAt": article.get("publishedAt"),
                "publishedEpoch": published_epoch,
                "source": article.get("source") or "Unknown",
                "sourceUrl": article.get("sourceUrl") or "",
                "sourceFeedId": article.get("sourceFeedId"),
                "score": article.get("score"),
                "comments": article.get("comments"),
                "tags": _normalize_tags(article.get("tags")),
                "isRead": bool(article.get("isRead", False)),
                "isSaved": bool(article.get("isSaved", False)),
                "isHidden": bool(article.get("isHidden", False)),
                "fetchedAt": int(article.get("fetchedAt") or now_ms()),
                "updatedAt": now_ms(),
                "dedupeKey": stable_id(article.get("canonicalLink") or article.get("link") or article_id),
            }
            try:
                self.table.put_item(
                    Item=_clean(item),
                    ConditionExpression="attribute_not_exists(pk) AND attribute_not_exists(sk)",
                )
                saved += 1
            except ClientError as exc:
                if exc.response.get("Error", {}).get("Code") != "ConditionalCheckFailedException":
                    raise
        return saved

    def list_articles(self, filters: dict[str, Any] | None = None) -> list[dict[str, Any]]:
        filters = filters or {}
        limit = int(filters.get("limit") or 50)
        max_scan = max(limit * 5, 200)
        filtered: list[dict[str, Any]] = []
        scanned = 0
        last_key = None
        while len(filtered) < limit and scanned < max_scan:
            query_args: dict[str, Any] = {
                "IndexName": "gsi1",
                "KeyConditionExpression": Key("gsi1pk").eq(ARTICLES_GSI_PK),
                "ScanIndexForward": False,
                "Limit": max_scan - scanned,
            }
            if last_key:
                query_args["ExclusiveStartKey"] = last_key
            response = self.table.query(**query_args)
            items = response.get("Items", [])
            scanned += int(response.get("ScannedCount") or len(items))
            for item in items:
                article = self._strip_keys(item)
                if self._article_matches(article, filters):
                    filtered.append(article)
                    if len(filtered) >= limit:
                        break
            last_key = response.get("LastEvaluatedKey")
            if not last_key:
                break
        return filtered[:limit]

    def get_article(self, article_id: str, include_content: bool = False) -> dict[str, Any] | None:
        item = self.table.get_item(Key={"pk": USER_PK, "sk": f"ARTICLE#{article_id}"}).get("Item")
        if not item:
            return None
        article = self._strip_keys(item)
        if include_content and int(article.get("contentChunkCount") or 0) > 0:
            article["content"] = self.get_article_content(article_id)
        return article

    def update_article(self, article_id: str, updates: dict[str, Any]) -> dict[str, Any] | None:
        article = self.get_article(article_id)
        if not article:
            return None
        if "tags" in updates:
            updates = {**updates, "tags": _normalize_tags(updates.get("tags"))}
        article.update(updates)
        article["updatedAt"] = now_ms()
        pk_item = {"pk": USER_PK, "sk": f"ARTICLE#{article_id}", **article}
        if "gsi1pk" not in pk_item:
            published_epoch = int(pk_item.get("publishedEpoch") or now_ms())
            pk_item["gsi1pk"] = ARTICLES_GSI_PK
            pk_item["gsi1sk"] = f"{published_epoch:013d}#{article_id}"
        self.table.put_item(Item=_clean(pk_item))
        return article

    def list_highlights(self, article_id: str | None = None, limit: int = 500) -> list[dict[str, Any]]:
        prefix = f"HIGHLIGHT#{article_id}#" if article_id else "HIGHLIGHT#"
        highlights: list[dict[str, Any]] = []
        query_args: dict[str, Any] = {
            "KeyConditionExpression": Key("pk").eq(USER_PK) & Key("sk").begins_with(prefix),
        }
        while True:
            response = self.table.query(**query_args)
            highlights.extend(self._strip_keys(item) for item in response.get("Items", []))
            cursor = response.get("LastEvaluatedKey")
            if not cursor:
                break
            query_args["ExclusiveStartKey"] = cursor
        highlights.sort(key=lambda item: int(item.get("createdAt") or 0), reverse=True)
        return highlights[: max(1, min(int(limit or 500), 1000))]

    def create_highlight(self, article_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        article = self.get_article(article_id)
        if not article:
            raise ValueError("Article not found")
        text = " ".join(str(payload.get("text") or "").split())
        if not text:
            raise ValueError("Highlight text is required")
        if len(text) > 5000:
            raise ValueError("Highlight text must be 5000 characters or fewer")
        existing = self.list_highlights(article_id=article_id, limit=1000)
        for highlight in existing:
            if str(highlight.get("text") or "") == text:
                return highlight
        created_at = int(payload.get("createdAt") or now_ms())
        highlight_id = str(payload.get("highlightId") or payload.get("id") or stable_id(f"{article_id}:{text}:{created_at}", "highlight-"))
        item = {
            "pk": USER_PK,
            "sk": f"HIGHLIGHT#{article_id}#{created_at:013d}#{highlight_id}",
            "highlightId": highlight_id,
            "articleId": article_id,
            "articleTitle": article.get("title") or "",
            "articleSource": article.get("source") or "",
            "articleLink": article.get("link") or "",
            "text": text,
            "note": str(payload.get("note") or "").strip(),
            "createdAt": created_at,
            "updatedAt": now_ms(),
        }
        self.table.put_item(Item=_clean(item))
        if not article.get("isSaved"):
            self.update_article(article_id, {"isSaved": True})
        return self._strip_keys(item)

    def delete_highlight(self, article_id: str, highlight_id: str) -> bool:
        for highlight in self.list_highlights(article_id=article_id, limit=1000):
            if highlight.get("highlightId") == highlight_id:
                created_at = int(highlight.get("createdAt") or 0)
                self.table.delete_item(
                    Key={
                        "pk": USER_PK,
                        "sk": f"HIGHLIGHT#{article_id}#{created_at:013d}#{highlight_id}",
                    }
                )
                return True
        return False

    def save_article_content(self, article_id: str, content: str, content_ttl_days: Any | None = None) -> None:
        chunk_size = 300_000
        chunks = [content[i : i + chunk_size] for i in range(0, len(content), chunk_size)] or [""]
        old_count = int((self.get_article(article_id) or {}).get("contentChunkCount") or 0)
        ttl_days = content_cache_ttl_days({"articleContentCacheTtlDays": content_ttl_days} if content_ttl_days is not None else self.get_settings())
        expires_at = int(time.time()) + ttl_days * 24 * 60 * 60
        for index, chunk in enumerate(chunks):
            self.table.put_item(
                Item=_clean(
                    {
                        "pk": USER_PK,
                        "sk": f"CONTENT#{article_id}#{index:05d}",
                        "articleId": article_id,
                        "chunkIndex": index,
                        "content": chunk,
                        "updatedAt": now_ms(),
                        "expiresAt": expires_at,
                    }
                )
            )
        for index in range(len(chunks), old_count):
            self.table.delete_item(Key={"pk": USER_PK, "sk": f"CONTENT#{article_id}#{index:05d}"})
        self.update_article(
            article_id,
            {
                "contentPreview": content[:1000],
                "contentChunkCount": len(chunks),
                "contentFetchedAt": now_ms(),
                "contentExpiresAt": expires_at,
                "articleContentCacheTtlDays": ttl_days,
            },
        )

    def get_article_content(self, article_id: str) -> str:
        article = self.get_article(article_id)
        if content_cache_expired(article):
            return ""
        count = int((article or {}).get("contentChunkCount") or 0)
        now_epoch = int(time.time())
        response = self.table.query(
            KeyConditionExpression=Key("pk").eq(USER_PK)
            & Key("sk").begins_with(f"CONTENT#{article_id}#")
        )
        chunks = sorted(
            (
                item
                for item in response.get("Items", [])
                if int(item.get("expiresAt") or (now_epoch + 1)) > now_epoch
            ),
            key=lambda item: int(item.get("chunkIndex") or 0),
        )
        if count:
            chunks = [chunk for chunk in chunks if int(chunk.get("chunkIndex") or 0) < count]
        return "".join(str(item.get("content") or "") for item in chunks)

    def refresh_content_cache_ttl(self, ttl_days: Any | None = None) -> dict[str, int]:
        ttl_days = content_cache_ttl_days({"articleContentCacheTtlDays": ttl_days} if ttl_days is not None else self.get_settings())
        expires_at = int(time.time()) + ttl_days * 24 * 60 * 60
        chunk_count = 0
        article_ids: set[str] = set()
        last_key = None
        while True:
            query_args: dict[str, Any] = {
                "KeyConditionExpression": Key("pk").eq(USER_PK) & Key("sk").begins_with("CONTENT#"),
            }
            if last_key:
                query_args["ExclusiveStartKey"] = last_key
            response = self.table.query(**query_args)
            for item in response.get("Items", []):
                item["expiresAt"] = expires_at
                self.table.put_item(Item=_clean(item))
                article_id = str(item.get("articleId") or "")
                if article_id:
                    article_ids.add(article_id)
                chunk_count += 1
            last_key = response.get("LastEvaluatedKey")
            if not last_key:
                break
        for article_id in article_ids:
            self.update_article(
                article_id,
                {
                    "contentExpiresAt": expires_at,
                    "articleContentCacheTtlDays": ttl_days,
                },
            )
        return {"chunks": chunk_count, "articles": len(article_ids)}

    def mark_all_read(self) -> int:
        count = 0
        for article in self.list_articles({"limit": 1000}):
            if not article.get("isRead"):
                self.update_article(article["articleId"], {"isRead": True})
                count += 1
        return count

    def cleanup_old_read(self, days: int) -> int:
        cutoff = now_ms() - (days * 24 * 60 * 60 * 1000)
        count = 0
        for article in self.list_articles({"limit": 1000}):
            if article.get("isRead") and not article.get("isSaved") and int(article.get("fetchedAt") or 0) < cutoff:
                self.table.delete_item(Key={"pk": USER_PK, "sk": f"ARTICLE#{article['articleId']}"})
                count += 1
        return count

    def stats(self) -> dict[str, Any]:
        articles = self.list_articles({"limit": 1000})
        feeds = self.list_feeds()
        return {
            "totalArticles": len(articles),
            "unreadArticles": sum(1 for article in articles if not article.get("isRead")),
            "savedArticles": sum(1 for article in articles if article.get("isSaved")),
            "feedCount": len(feeds),
            "lastRefresh": max((feed.get("lastFetchedAt") or 0 for feed in feeds), default=0),
        }

    def list_tags(self) -> list[dict[str, Any]]:
        tag_map: dict[str, dict[str, int]] = {}
        for feed in self.list_feeds():
            for tag in _normalize_tags(feed.get("tags")):
                tag_map.setdefault(tag, {"feedCount": 0, "articleCount": 0, "unreadCount": 0})
                tag_map[tag]["feedCount"] += 1
        for article in self.list_articles({"limit": 5000}):
            for tag in _normalize_tags(article.get("tags")):
                tag_map.setdefault(tag, {"feedCount": 0, "articleCount": 0, "unreadCount": 0})
                tag_map[tag]["articleCount"] += 1
                if not article.get("isRead"):
                    tag_map[tag]["unreadCount"] += 1
        return [
            {"tag": tag, **counts}
            for tag, counts in sorted(
                tag_map.items(),
                key=lambda item: (-item[1]["articleCount"], item[0]),
            )
        ]

    def put_codex_auth(self, payload: dict[str, Any], key: str) -> None:
        self.s3.put_object(
            Bucket=self.bucket_name,
            Key=key,
            Body=__import__("json").dumps(payload).encode("utf-8"),
            ContentType="application/json",
            ServerSideEncryption="AES256",
        )

    def get_codex_auth(self, key: str) -> dict[str, Any] | None:
        try:
            response = self.s3.get_object(Bucket=self.bucket_name, Key=key)
            return __import__("json").loads(response["Body"].read().decode("utf-8"))
        except ClientError as exc:
            if exc.response.get("Error", {}).get("Code") in {"NoSuchKey", "404"}:
                return None
            raise

    def delete_codex_auth(self, key: str) -> None:
        self.s3.delete_object(Bucket=self.bucket_name, Key=key)

    def get_tts_audio(self, key: str) -> dict[str, Any] | None:
        try:
            response = self.s3.get_object(Bucket=self.bucket_name, Key=key)
            return {
                "audio": response["Body"].read(),
                "contentType": response.get("ContentType") or "audio/mpeg",
                "metadata": response.get("Metadata") or {},
            }
        except ClientError as exc:
            error = exc.response.get("Error", {})
            message = str(error.get("Message") or "")
            if error.get("Code") in {"NoSuchKey", "404"}:
                return None
            if error.get("Code") == "AccessDenied" and "ListBucket" in message:
                return None
            raise

    def put_tts_audio(self, key: str, audio: bytes, content_type: str, metadata: dict[str, Any] | None = None) -> None:
        self.s3.put_object(
            Bucket=self.bucket_name,
            Key=key,
            Body=audio,
            ContentType=content_type,
            Metadata={str(k): str(v) for k, v in (metadata or {}).items() if v is not None},
            ServerSideEncryption="AES256",
        )

    def create_content_job(
        self,
        article_id: str,
        url: str,
        options: dict[str, Any] | None = None,
        errors: list[str] | None = None,
    ) -> dict[str, Any]:
        job_id = stable_id(f"{article_id}:{url}:{now_ms()}", "job-")
        item = {
            "pk": USER_PK,
            "sk": f"JOB#{job_id}",
            "jobId": job_id,
            "type": "content_fetch",
            "status": "queued",
            "articleId": article_id,
            "url": url,
            "optionsJson": json.dumps(options or {}),
            "errors": errors or [],
            "createdAt": now_ms(),
            "updatedAt": now_ms(),
            "expiresAt": int(time.time()) + 7 * 24 * 60 * 60,
        }
        self.table.put_item(Item=_clean(item))
        return self._strip_keys(item)

    def get_content_job(self, job_id: str) -> dict[str, Any] | None:
        item = self.table.get_item(Key={"pk": USER_PK, "sk": f"JOB#{job_id}"}).get("Item")
        if not item:
            return None
        job = self._strip_keys(item)
        if isinstance(job.get("optionsJson"), str):
            try:
                job["options"] = json.loads(job["optionsJson"])
            except json.JSONDecodeError:
                job["options"] = {}
        job.pop("optionsJson", None)
        return job

    def update_content_job(self, job_id: str, updates: dict[str, Any]) -> dict[str, Any] | None:
        job = self.get_content_job(job_id)
        if not job:
            return None
        options = updates.pop("options", None)
        job.update(updates)
        if options is not None:
            job["optionsJson"] = json.dumps(options)
        elif "options" in job:
            job["optionsJson"] = json.dumps(job.pop("options") or {})
        job["updatedAt"] = now_ms()
        self.table.put_item(Item=_clean({"pk": USER_PK, "sk": f"JOB#{job_id}", **job}))
        return self.get_content_job(job_id)

    def save_embedding(self, article_id: str, embedding: list[float], model: str, text_hash: str) -> None:
        self.table.put_item(
            Item=_clean(
                {
                    "pk": USER_PK,
                    "sk": f"EMBED#{article_id}",
                    "articleId": article_id,
                    "model": model,
                    "dims": len(embedding),
                    "embedding": embedding,
                    "textHash": text_hash,
                    "updatedAt": now_ms(),
                }
            )
        )

    def get_embedding(self, article_id: str) -> dict[str, Any] | None:
        item = self.table.get_item(Key={"pk": USER_PK, "sk": f"EMBED#{article_id}"}).get("Item")
        return self._strip_keys(item) if item else None

    def list_embeddings(self, limit: int = 1000) -> list[dict[str, Any]]:
        response = self.table.query(
            KeyConditionExpression=Key("pk").eq(USER_PK) & Key("sk").begins_with("EMBED#"),
            Limit=limit,
        )
        return [self._strip_keys(item) for item in response.get("Items", [])]

    def clear_embeddings(self) -> int:
        count = 0
        for item in self.list_embeddings(limit=5000):
            self.table.delete_item(Key={"pk": USER_PK, "sk": f"EMBED#{item['articleId']}"})
            count += 1
        return count

    def _article_matches(self, article: dict[str, Any], filters: dict[str, Any]) -> bool:
        if filters.get("unread") and article.get("isRead"):
            return False
        if filters.get("saved") and not article.get("isSaved"):
            return False
        if filters.get("source") and filters["source"] not in {
            article.get("source"),
            article.get("sourceFeedId"),
        }:
            return False
        requested_tags = _normalize_tags(filters.get("tag")) + _normalize_tags(filters.get("tags"))
        if requested_tags:
            article_tags = set(_normalize_tags(article.get("tags")))
            if not article_tags.intersection(requested_tags):
                return False
        if filters.get("minScore") and int(article.get("score") or 0) < int(filters["minScore"]):
            return False
        if filters.get("hours"):
            cutoff = now_ms() - int(filters["hours"]) * 60 * 60 * 1000
            if int(article.get("publishedEpoch") or article.get("fetchedAt") or 0) < cutoff:
                return False
        haystack = " ".join(
            str(article.get(key) or "") for key in ("title", "source", "summary", "contentPreview")
        ).lower()
        query = str(filters.get("query") or "").lower().strip()
        if query and query not in haystack:
            return False
        includes = filters.get("include") or []
        if isinstance(includes, str):
            includes = [part.strip() for part in includes.split(",") if part.strip()]
        if includes and not all(term.lower() in haystack for term in includes):
            return False
        excludes = filters.get("exclude") or []
        if isinstance(excludes, str):
            excludes = [part.strip() for part in excludes.split(",") if part.strip()]
        if excludes and any(term.lower() in haystack for term in excludes):
            return False
        return True

    @staticmethod
    def _strip_keys(item: dict[str, Any]) -> dict[str, Any]:
        cleaned = dict(item)
        for key in ("pk", "sk", "gsi1pk", "gsi1sk"):
            cleaned.pop(key, None)
        return cleaned

    @staticmethod
    def _name_from_url(url: str) -> str:
        return url.replace("https://", "").replace("http://", "").split("/")[0] or "Feed"
