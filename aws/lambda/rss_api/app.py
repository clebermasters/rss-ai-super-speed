from __future__ import annotations

import base64
import hashlib
import json
import os
import time
from typing import Any
from urllib.parse import parse_qs

import boto3

from ai_client import generate_embedding, list_models, list_providers, summarize_articles
from content_formatter import format_article_content_for_mobile, should_format_content_with_ai
from content_fetcher import fetch_direct, fetch_with_fallbacks
from formatters import format_articles
from rss_fetcher import fetch_feeds
from storage import RssStorage, now_ms


def handler(event: dict[str, Any], _context: Any) -> dict[str, Any]:
    if event.get("source") == "rss-ai.scheduler":
        return _handle_scheduled_refresh()
    if event.get("source") == "rss-ai.content-fetch-job":
        return _handle_content_fetch_job(event)

    method = event.get("requestContext", {}).get("http", {}).get("method", "GET").upper()
    path = event.get("rawPath", "/")

    if method == "OPTIONS":
        return response(204, "")
    if not _authorized(event):
        return response(401, {"error": "Unauthorized"})

    try:
        return route(method, path, event)
    except ValueError as exc:
        return response(400, {"error": str(exc)})
    except RuntimeError as exc:
        return response(502, {"error": str(exc)})
    except Exception as exc:
        return response(500, {"error": f"Unhandled server error: {exc}"})


def route(method: str, path: str, event: dict[str, Any]) -> dict[str, Any]:
    storage = RssStorage()
    query = parse_qs(event.get("rawQueryString", ""))

    if method == "GET" and path == "/v1/health":
        return response(200, {"ok": True, "table": storage.table_name, "bucket": storage.bucket_name})

    if method == "GET" and path == "/v1/bootstrap":
        storage.bootstrap_defaults()
        return response(200, {"settings": storage.get_settings(), "feeds": storage.list_feeds()})

    if method == "GET" and path == "/v1/settings":
        return response(200, storage.get_settings())

    if method == "PUT" and path == "/v1/settings":
        return response(200, storage.update_settings(parse_json_body(event)))

    if method == "GET" and path == "/v1/feeds":
        return response(200, {"feeds": storage.list_feeds()})

    if method == "POST" and path == "/v1/feeds":
        payload = parse_json_body(event)
        if not payload.get("url"):
            raise ValueError("Feed url is required")
        return response(201, storage.create_feed(payload))

    if method == "POST" and path == "/v1/sync/refresh":
        return response(200, refresh_feeds(storage))

    if method == "GET" and path == "/v1/sync/pull":
        since = int(_query_one(query, "since", "0") or 0)
        articles = [article for article in storage.list_articles({"limit": 1000}) if int(article.get("updatedAt") or 0) > since]
        return response(200, {"cursor": now_ms(), "articles": articles, "deletions": []})

    if method == "POST" and path == "/v1/sync/push":
        return response(200, handle_sync_push(storage, parse_json_body(event)))

    if method == "GET" and path == "/v1/articles":
        filters = {
            "limit": int(_query_one(query, "limit", "50")),
            "unread": _bool(_query_one(query, "unread")),
            "saved": _bool(_query_one(query, "saved")),
            "source": _query_one(query, "source"),
            "hours": _query_one(query, "hours"),
            "minScore": _query_one(query, "minScore"),
            "include": _query_one(query, "include"),
            "exclude": _query_one(query, "exclude"),
            "query": _query_one(query, "query"),
        }
        return response(200, {"articles": storage.list_articles(filters), "cursor": now_ms()})

    if method == "POST" and path == "/v1/articles/mark-all-read":
        return response(200, {"count": storage.mark_all_read()})

    if method == "DELETE" and path == "/v1/articles/cleanup":
        days = int(_query_one(query, "days", "30"))
        return response(200, {"count": storage.cleanup_old_read(days)})

    if method == "GET" and path == "/v1/stats":
        return response(200, storage.stats())

    if method == "GET" and path == "/v1/export":
        articles = storage.list_articles({"limit": int(_query_one(query, "limit", "100"))})
        body, content_type = format_articles(articles, _query_one(query, "format", "json"))
        return response(200, body, content_type=content_type)

    if method == "GET" and path == "/v1/llm/providers":
        return response(200, {"providers": list_providers()})

    if method == "GET" and path == "/v1/llm/models":
        provider = _query_one(query, "provider", "openai_compatible")
        return response(200, {"provider": provider, "models": list_models(provider)})

    if path == "/v1/llm/codex-auth":
        key = os.environ.get("CODEX_AUTH_S3_KEY", "codex/auth.json")
        if method == "GET":
            return response(200, {"configured": storage.get_codex_auth(key) is not None, "s3Key": key})
        if method == "PUT":
            payload = parse_json_body(event)
            tokens = payload.get("tokens") or {}
            if not tokens.get("access_token") or not tokens.get("refresh_token"):
                raise ValueError("Codex auth JSON must include tokens.access_token and tokens.refresh_token")
            storage.put_codex_auth(payload, key)
            return response(200, {"configured": True, "s3Key": key})
        if method == "DELETE":
            storage.delete_codex_auth(key)
            return response(200, {"configured": False, "s3Key": key})

    segments = [segment for segment in path.strip("/").split("/") if segment]
    if len(segments) == 3 and segments[0] == "v1" and segments[1] == "content-jobs" and method == "GET":
        return response(200, handle_get_content_job(storage, segments[2]))

    if len(segments) >= 3 and segments[0] == "v1" and segments[1] == "feeds":
        feed_id = segments[2]
        if len(segments) == 3 and method == "GET":
            feed = storage.get_feed(feed_id)
            return response(200 if feed else 404, feed or {"error": "Feed not found"})
        if len(segments) == 3 and method == "PUT":
            feed = storage.update_feed(feed_id, parse_json_body(event))
            return response(200 if feed else 404, feed or {"error": "Feed not found"})
        if len(segments) == 3 and method == "DELETE":
            storage.delete_feed(feed_id)
            return response(200, {"deleted": True})
        if len(segments) == 4 and segments[3] == "toggle-enabled" and method == "POST":
            feed = storage.get_feed(feed_id)
            if not feed:
                return response(404, {"error": "Feed not found"})
            return response(200, storage.update_feed(feed_id, {"enabled": not feed.get("enabled", True)}))
        if len(segments) == 4 and segments[3] == "refresh" and method == "POST":
            feed = storage.get_feed(feed_id)
            if not feed:
                return response(404, {"error": "Feed not found"})
            return response(200, refresh_feeds(storage, [feed]))

    if len(segments) >= 3 and segments[0] == "v1" and segments[1] == "articles":
        article_id = segments[2]
        if len(segments) == 3 and method == "GET":
            article = storage.get_article(article_id, include_content=True)
            return response(200 if article else 404, article or {"error": "Article not found"})
        if len(segments) == 3 and method == "PATCH":
            article = storage.update_article(article_id, parse_json_body(event))
            return response(200 if article else 404, article or {"error": "Article not found"})
        if len(segments) == 4 and method == "POST":
            action = segments[3]
            if action == "mark-read":
                return response(200, storage.update_article(article_id, {"isRead": True}) or {"error": "Article not found"})
            if action == "mark-unread":
                return response(200, storage.update_article(article_id, {"isRead": False}) or {"error": "Article not found"})
            if action == "toggle-save":
                article = storage.get_article(article_id)
                if not article:
                    return response(404, {"error": "Article not found"})
                return response(200, storage.update_article(article_id, {"isSaved": not article.get("isSaved", False)}))
            if action == "hide":
                return response(200, storage.update_article(article_id, {"isHidden": True}) or {"error": "Article not found"})
            if action == "fetch-content":
                status_code, body = handle_fetch_content(storage, article_id, parse_json_body(event, default={}))
                return response(status_code, body)
            if action == "format-content":
                status_code, body = handle_format_existing_content(storage, article_id, parse_json_body(event, default={}))
                return response(status_code, body)
            if action == "summarize":
                return response(200, handle_summarize_article(storage, article_id, parse_json_body(event, default={})))
            if action == "embedding":
                status_code, body = handle_article_embedding(storage, article_id, parse_json_body(event, default={}))
                return response(status_code, body)
        if len(segments) == 4 and segments[3] == "export" and method == "GET":
            article = storage.get_article(article_id, include_content=True)
            if not article:
                return response(404, {"error": "Article not found"})
            body, content_type = format_articles([article], _query_one(query, "format", "markdown"))
            return response(200, body, content_type=content_type)

    if path == "/v1/articles/summarize" and method == "POST":
        payload = parse_json_body(event, default={})
        articles = storage.list_articles({"limit": int(payload.get("limit") or 20)})
        summary = summarize_articles(articles, storage.get_settings(), payload)
        return response(200, {"summary": summary})

    if path == "/v1/search/semantic" and method == "POST":
        payload = parse_json_body(event, default={})
        return response(200, handle_semantic_search(storage, payload))

    if path == "/v1/search/semantic-index" and method == "DELETE":
        rebuild = _bool(_query_one(query, "rebuild"))
        return response(200, handle_semantic_index(storage, {"clear": True, "rebuild": rebuild}))

    if path == "/v1/search/semantic-index" and method == "POST":
        return response(200, handle_semantic_index(storage, parse_json_body(event, default={"rebuild": True})))

    return response(404, {"error": "Not found"})


def refresh_feeds(storage: RssStorage, feeds: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    storage.bootstrap_defaults()
    feeds = feeds or storage.list_feeds()
    articles, errors = fetch_feeds(feeds)
    saved = storage.save_articles(articles)
    for feed in feeds:
        status = "error" if any(err.get("feedId") == feed.get("feedId") for err in errors) else "ok"
        storage.update_feed(feed["feedId"], {"lastFetchedAt": now_ms(), "lastFetchStatus": status})
    return {"fetched": len(articles), "saved": saved, "errors": errors}


def handle_sync_push(storage: RssStorage, payload: dict[str, Any]) -> dict[str, Any]:
    updated = []
    for mutation in payload.get("mutations", []):
        article_id = mutation.get("articleId")
        updates = mutation.get("updates") or {}
        if article_id and updates:
            article = storage.update_article(article_id, updates)
            if article:
                updated.append(article)
    return {"cursor": now_ms(), "articles": updated}


def handle_fetch_content(storage: RssStorage, article_id: str, payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
    article = storage.get_article(article_id)
    if not article:
        return 404, {"error": "Article not found"}
    settings = storage.get_settings()
    direct_errors: list[str] = []
    force_browser = _boolish(payload.get("forceBrowser"))
    mark_read = _should_mark_read(payload)
    format_with_ai = should_format_content_with_ai(settings, payload)
    browser_mode = str(settings.get("browserBypassMode") or "on_blocked")
    if not force_browser and browser_mode != "always" and not format_with_ai:
        try:
            content = fetch_direct(article["link"])
            storage.save_article_content(article_id, content)
            updates = {"contentAiFormatted": False}
            if mark_read:
                updates["isRead"] = True
            storage.update_article(article_id, updates)
            return 200, {
                "articleId": article_id,
                "status": "completed",
                "strategy": "direct",
                "content": content,
                "formattingRequested": False,
                "contentFormattingAttempted": False,
                "contentAiFormatted": False,
                "errors": [],
            }
        except Exception as exc:
            direct_errors.append(f"direct: {exc}")

    job_options = dict(payload)
    job_options["formatWithAi"] = format_with_ai
    job_options["markRead"] = mark_read
    job = storage.create_content_job(article_id, article["link"], job_options, direct_errors)
    _invoke_content_job_async(job["jobId"])
    return 202, {
        "articleId": article_id,
        "jobId": job["jobId"],
        "status": "queued",
        "strategy": "async",
        "content": "",
        "formattingRequested": format_with_ai,
        "contentFormattingAttempted": False,
        "contentAiFormatted": False,
        "errors": direct_errors,
        "message": "Full-content fetch queued for extraction and AI formatting" if format_with_ai else "Full-content fetch queued for browser/Wayback processing",
    }


def handle_format_existing_content(storage: RssStorage, article_id: str, payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
    article = storage.get_article(article_id, include_content=True)
    if not article:
        return 404, {"error": "Article not found"}
    if not _existing_article_content(article):
        return 400, {"error": "No article content available to format. Fetch full content first."}

    job_options = dict(payload)
    job_options["formatWithAi"] = True
    job_options["formatExistingOnly"] = True
    job_options["markRead"] = _should_mark_read(payload)
    job = storage.create_content_job(article_id, article["link"], job_options, [])
    _invoke_content_job_async(job["jobId"])
    return 202, {
        "articleId": article_id,
        "jobId": job["jobId"],
        "status": "queued",
        "strategy": "async",
        "content": "",
        "formattingRequested": True,
        "contentFormattingAttempted": False,
        "contentAiFormatted": False,
        "errors": [],
        "message": "AI readability formatting queued for existing article content",
    }


def handle_get_content_job(storage: RssStorage, job_id: str) -> dict[str, Any]:
    job = storage.get_content_job(job_id)
    if not job:
        return {"error": "Content job not found"}
    body: dict[str, Any] = {
        "jobId": job_id,
        "articleId": job.get("articleId", ""),
        "status": job.get("status", "unknown"),
        "strategy": job.get("strategy"),
        "content": "",
        "formattingRequested": bool((job.get("options") or {}).get("formatWithAi") or (job.get("options") or {}).get("formatContent") or (job.get("options") or {}).get("aiContentFormattingEnabled")),
        "contentFormattingAttempted": bool(job.get("contentFormattingAttempted", False)),
        "contentAiFormatted": bool(job.get("contentAiFormatted", False)),
        "contentFormattingError": job.get("contentFormattingError"),
        "errors": job.get("errors", []),
        "message": job.get("message"),
    }
    if job.get("status") == "completed" and job.get("articleId"):
        article = storage.get_article(str(job["articleId"]), include_content=True)
        if article:
            body["article"] = article
            body["content"] = article.get("content") or ""
            body["contentAiFormatted"] = bool(article.get("contentAiFormatted", body["contentAiFormatted"]))
    return body


def _invoke_content_job_async(job_id: str) -> None:
    function_name = os.environ.get("AWS_LAMBDA_FUNCTION_NAME")
    if not function_name:
        raise RuntimeError("AWS_LAMBDA_FUNCTION_NAME is required for async content fetch jobs")
    boto3.client("lambda").invoke(
        FunctionName=function_name,
        InvocationType="Event",
        Payload=json.dumps({"source": "rss-ai.content-fetch-job", "jobId": job_id}).encode("utf-8"),
    )


def _handle_content_fetch_job(event: dict[str, Any]) -> dict[str, Any]:
    storage = RssStorage()
    job_id = str(event.get("jobId") or "")
    job = storage.get_content_job(job_id)
    if not job:
        return {"ok": False, "error": "job not found", "jobId": job_id}
    running_message = "Formatting existing article content" if (job.get("options") or {}).get("formatExistingOnly") else "Fetching full article content"
    storage.update_content_job(job_id, {"status": "running", "message": running_message})
    try:
        result = _run_content_fetch_job(storage, job)
        storage.update_content_job(
            job_id,
            {
                "status": "completed",
                "strategy": result.get("strategy"),
                "contentFormattingAttempted": bool(result.get("contentFormattingAttempted", False)),
                "contentAiFormatted": bool(result.get("contentAiFormatted", False)),
                "contentFormattingError": result.get("contentFormattingError"),
                "errors": result.get("errors", []),
                "message": _content_job_success_message(result),
                "completedAt": now_ms(),
            },
        )
        return {"ok": True, "jobId": job_id, "strategy": result.get("strategy")}
    except Exception as exc:
        storage.update_content_job(
            job_id,
            {
                "status": "failed",
                "message": str(exc),
                "errors": (job.get("errors") or []) + [str(exc)],
                "failedAt": now_ms(),
            },
        )
        return {"ok": False, "jobId": job_id, "error": str(exc)}


def _content_job_success_message(result: dict[str, Any]) -> str:
    if result.get("contentAiFormatted"):
        if result.get("strategy") == "existing":
            return "Existing content formatted with AI readability"
        return "Content fetch completed with AI readability formatting"
    if result.get("contentFormattingAttempted"):
        error = result.get("contentFormattingError")
        return f"Content fetch completed; AI formatting failed: {error}" if error else "Content fetch completed; AI formatting was not applied"
    return "Content fetch completed"


def _run_content_fetch_job(storage: RssStorage, job: dict[str, Any]) -> dict[str, Any]:
    article_id = str(job["articleId"])
    payload = job.get("options") or {}
    article = storage.get_article(article_id, include_content=bool(payload.get("formatExistingOnly")))
    if not article:
        raise RuntimeError("Article not found")
    settings = storage.get_settings()
    if payload.get("formatExistingOnly"):
        content = _existing_article_content(article)
        if not content:
            raise RuntimeError("No article content available to format. Fetch full content first.")
        fetched = {"strategy": "existing", "errors": []}
    else:
        fetched = fetch_with_fallbacks(article["link"], settings, force_browser=_boolish(payload.get("forceBrowser")))
        content = fetched["content"]
    errors = list(fetched.get("errors", []))
    formatting_attempted = should_format_content_with_ai(settings, payload)
    formatted = False
    formatting_error = None
    if formatting_attempted:
        try:
            content = format_article_content_for_mobile(
                content,
                article=article,
                settings=settings,
                overrides=payload,
            )
            formatted = True
        except Exception as exc:
            formatting_error = str(exc)
            errors.append(f"ai_format: {formatting_error}")
    storage.save_article_content(article_id, content)
    updates = {
        "contentAiFormatted": formatted,
        "contentAiFormattedAt": now_ms() if formatted else None,
    }
    if _should_mark_read(payload):
        updates["isRead"] = True
    storage.update_article(article_id, updates)
    return {
        "articleId": article_id,
        "strategy": fetched.get("strategy"),
        "content": content,
        "formattingRequested": formatting_attempted,
        "contentFormattingAttempted": formatting_attempted,
        "contentAiFormatted": formatted,
        "contentFormattingError": formatting_error,
        "errors": errors,
    }


def _existing_article_content(article: dict[str, Any]) -> str:
    return str(article.get("content") or article.get("contentPreview") or article.get("summary") or "").strip()


def _should_mark_read(payload: dict[str, Any]) -> bool:
    if "markRead" not in payload:
        return True
    return _boolish(payload.get("markRead"))


def handle_summarize_article(storage: RssStorage, article_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    article = storage.get_article(article_id, include_content=True)
    if not article:
        return {"error": "Article not found"}
    summary = summarize_articles([article], storage.get_settings(), payload)
    storage.update_article(article_id, {"summary": summary, "summaryGeneratedAt": now_ms()})
    return {"articleId": article_id, "summary": summary}


def handle_article_embedding(storage: RssStorage, article_id: str, payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
    article = storage.get_article(article_id, include_content=True)
    if not article:
        return 404, {"error": "Article not found"}
    embedding = _ensure_article_embedding(storage, article, storage.get_settings(), payload, force=True)
    return 200, {
        "articleId": article_id,
        "model": embedding.get("model"),
        "dims": embedding.get("dims"),
        "updatedAt": embedding.get("updatedAt"),
    }


def handle_semantic_search(storage: RssStorage, payload: dict[str, Any]) -> dict[str, Any]:
    query = str(payload.get("query") or "").strip()
    if not query:
        raise ValueError("Semantic search query is required")
    settings = storage.get_settings()
    limit = int(payload.get("limit") or 20)
    index_limit = int(payload.get("indexLimit") or 100)
    if payload.get("indexMissing", True):
        for article in storage.list_articles({"limit": index_limit}):
            _ensure_article_embedding(storage, article, settings, payload)
    query_embedding = generate_embedding(query, settings, payload)
    query_vector = query_embedding["embedding"]
    ranked = []
    for item in storage.list_embeddings(limit=max(index_limit, 1000)):
        vector = [float(value) for value in item.get("embedding", [])]
        if not vector:
            continue
        ranked.append((cosine_similarity(query_vector, vector), item["articleId"]))
    ranked.sort(reverse=True, key=lambda pair: pair[0])
    results = []
    for score, article_id in ranked[:limit]:
        article = storage.get_article(article_id)
        if article:
            article["semanticScore"] = score
            results.append(article)
    return {
        "query": query,
        "embeddingModel": query_embedding.get("model"),
        "results": results,
        "indexed": len(ranked),
    }


def handle_semantic_index(storage: RssStorage, payload: dict[str, Any]) -> dict[str, Any]:
    cleared = storage.clear_embeddings() if payload.get("clear", True) else 0
    rebuilt = 0
    if payload.get("rebuild", True):
        settings = storage.get_settings()
        for article in storage.list_articles({"limit": int(payload.get("limit") or 100)}):
            _ensure_article_embedding(storage, article, settings, payload, force=True)
            rebuilt += 1
    return {"cleared": cleared, "rebuilt": rebuilt}


def _ensure_article_embedding(
    storage: RssStorage,
    article: dict[str, Any],
    settings: dict[str, Any],
    payload: dict[str, Any],
    *,
    force: bool = False,
) -> dict[str, Any]:
    text = _embedding_text(article)
    text_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()
    current = storage.get_embedding(article["articleId"])
    if current and not force and current.get("textHash") == text_hash:
        return current
    result = generate_embedding(text[:12000], settings, payload)
    storage.save_embedding(article["articleId"], result["embedding"], result["model"], text_hash)
    return storage.get_embedding(article["articleId"]) or {}


def _embedding_text(article: dict[str, Any]) -> str:
    return "\n".join(
        str(article.get(key) or "")
        for key in ("title", "source", "summary", "contentPreview", "content")
    ).strip()


def cosine_similarity(a: list[float], b: list[float]) -> float:
    if not a or not b:
        return 0.0
    size = min(len(a), len(b))
    dot = sum(float(a[i]) * float(b[i]) for i in range(size))
    mag_a = sum(float(a[i]) * float(a[i]) for i in range(size)) ** 0.5
    mag_b = sum(float(b[i]) * float(b[i]) for i in range(size)) ** 0.5
    if not mag_a or not mag_b:
        return 0.0
    return dot / (mag_a * mag_b)


def _handle_scheduled_refresh() -> dict[str, Any]:
    return {"ok": True, "result": refresh_feeds(RssStorage())}


def parse_json_body(event: dict[str, Any], default: Any | None = None) -> Any:
    body = event.get("body")
    if body is None or body == "":
        if default is not None:
            return default
        raise ValueError("JSON body is required")
    if event.get("isBase64Encoded"):
        body = base64.b64decode(body).decode("utf-8")
    return json.loads(body)


def response(status: int, body: Any, content_type: str = "application/json") -> dict[str, Any]:
    if content_type == "application/json" and not isinstance(body, str):
        body = json.dumps(body, default=str)
    return {
        "statusCode": status,
        "headers": {
            "content-type": content_type,
            "access-control-allow-origin": "*",
            "access-control-allow-headers": "content-type,x-rss-ai-token",
            "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
        },
        "body": body,
    }


def _authorized(event: dict[str, Any]) -> bool:
    token = os.environ.get("RSS_AI_API_TOKEN", "")
    if not token:
        return True
    headers = {k.lower(): v for k, v in (event.get("headers") or {}).items()}
    return headers.get("x-rss-ai-token") == token


def _query_one(query: dict[str, list[str]], key: str, default: str | None = None) -> str | None:
    values = query.get(key)
    return values[0] if values else default


def _bool(value: str | None) -> bool:
    return str(value or "").lower() in {"1", "true", "yes", "on"}


def _boolish(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value or "").lower() in {"1", "true", "yes", "on"}
