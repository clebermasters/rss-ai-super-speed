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
from content_sanitizer import normalize_article_text
from formatters import format_articles
from rss_fetcher import fetch_feeds
from storage import RssStorage, now_ms
from tts_client import prepare_tts_input, synthesize_speech, tts_cache_key


def handler(event: dict[str, Any], _context: Any) -> dict[str, Any]:
    if event.get("source") == "rss-ai.scheduler":
        return _handle_scheduled_refresh()
    if event.get("source") == "rss-ai.content-fetch-job":
        return _handle_content_fetch_job(event)
    if event.get("source") == "rss-ai.tts-job":
        return _handle_tts_job(event)

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
        return response(502, {"error": _client_safe_error(exc, "Backend provider error")})
    except Exception as exc:
        return response(500, {"error": _client_safe_error(exc, "Unhandled server error")})


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

    if method == "GET" and path == "/v1/tags":
        return response(200, {"tags": storage.list_tags()})

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
            "tag": _query_one(query, "tag"),
            "tags": _query_one(query, "tags"),
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
    if len(segments) == 3 and segments[0] == "v1" and segments[1] == "audio-jobs" and method == "GET":
        status_code, body = handle_get_tts_job(storage, segments[2])
        return response(status_code, body)

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
            if action == "tts":
                status_code, body, content_type, headers = handle_article_tts(storage, article_id, parse_json_body(event, default={}))
                if isinstance(body, bytes):
                    return binary_response(status_code, body, content_type, headers=headers)
                return response(status_code, body, headers=headers)
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
    job = _fail_stale_content_job(storage, job_id, job)
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


def _fail_stale_content_job(storage: RssStorage, job_id: str, job: dict[str, Any]) -> dict[str, Any]:
    if str(job.get("status") or "") not in {"queued", "running"}:
        return job
    stale_seconds = int(os.environ.get("CONTENT_JOB_STALE_SECONDS", "420"))
    timestamp = int(job.get("updatedAt") or job.get("createdAt") or 0)
    if timestamp and now_ms() - timestamp <= stale_seconds * 1000:
        return job
    message = "Content job exceeded the backend timeout budget. Please retry Fetch Full."
    errors = list(job.get("errors") or [])
    if message not in errors:
        errors.append(message)
    return storage.update_content_job(
        job_id,
        {
            "status": "failed",
            "message": message,
            "errors": errors,
            "failedAt": now_ms(),
        },
    ) or {**job, "status": "failed", "message": message, "errors": errors}


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
        if _boolish((job.get("options") or {}).get("prefetch")) and job.get("articleId"):
            storage.update_article(
                str(job["articleId"]),
                {
                    "prefetchStatus": "failed",
                    "prefetchError": str(exc),
                    "prefetchProcessedAt": now_ms(),
                },
            )
        return {"ok": False, "jobId": job_id, "error": str(exc)}


def _content_job_success_message(result: dict[str, Any]) -> str:
    if result.get("summaryGenerated") and result.get("contentAiFormatted"):
        return "Content fetch completed with AI summary and readability formatting"
    if result.get("summaryGenerated"):
        return "Content fetch completed with AI summary"
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
    content = normalize_article_text(content)
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
    content = normalize_article_text(content)
    storage.save_article_content(article_id, content)
    summary_generated = False
    summary_error = None
    updates = {
        "contentAiFormatted": formatted,
        "contentAiFormattedAt": now_ms() if formatted else None,
    }
    if _boolish(payload.get("summarizeWithAi")):
        try:
            if _boolish(payload.get("forceSummary")) or not article.get("summaryGeneratedAt"):
                summary_article = {
                    **article,
                    "content": content,
                    "contentPreview": content[:1000],
                }
                updates["summary"] = summarize_articles([summary_article], settings, payload)
                updates["summaryGeneratedAt"] = now_ms()
                summary_generated = True
        except Exception as exc:
            summary_error = str(exc)
            errors.append(f"ai_summary: {summary_error}")
    if _boolish(payload.get("prefetch")):
        updates["prefetchProcessedAt"] = now_ms()
        updates["prefetchStatus"] = "completed"
        if summary_error or formatting_error:
            updates["prefetchError"] = "; ".join(error for error in (summary_error, formatting_error) if error)
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
        "summaryGenerated": summary_generated,
        "summaryError": summary_error,
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


def handle_article_tts(storage: RssStorage, article_id: str, payload: dict[str, Any]) -> tuple[int, bytes | dict[str, Any], str, dict[str, str]]:
    article = storage.get_article(article_id, include_content=True)
    if not article:
        return 404, {"error": "Article not found"}, "application/json", {}
    target = str(payload.get("target") or "content").lower()
    if target == "summary":
        text = str(article.get("summary") or "").strip()
        if not text:
            return 400, {"error": "No AI summary is available to read. Generate a summary first."}, "application/json", {}
    elif target == "content":
        text = _existing_article_content(article)
        if not text:
            return 400, {"error": "No article content is available to read. Fetch full content first."}, "application/json", {}
    else:
        return 400, {"error": "TTS target must be content or summary"}, "application/json", {}

    settings = storage.get_settings()
    prepared = prepare_tts_input(text, settings=settings, overrides=payload)
    if not prepared.get("text"):
        return 400, {"error": "No readable text remains after cleaning article noise."}, "application/json", {}
    key = tts_cache_key(article_id, target, prepared_input=prepared, settings=settings, overrides=payload)
    use_cache = not _boolish(payload.get("forceRefresh"))
    headers = _tts_headers(prepared, key, cache_status="miss")
    if use_cache:
        cached = storage.get_tts_audio(key)
        if cached:
            headers = _tts_headers(prepared, key, cache_status="hit")
            return 200, cached["audio"], str(cached.get("contentType") or "audio/mpeg"), headers

    if _boolish(payload.get("async")):
        job_options = dict(payload)
        job_options.update({"target": target, "ttsCacheKey": key, "preparedInput": prepared})
        job = storage.create_content_job(article_id, str(article.get("link") or ""), job_options, [])
        _invoke_tts_job_async(job["jobId"])
        return 202, _tts_job_body(job["jobId"], article_id, target, prepared, "queued", "Audio generation queued"), "application/json", headers

    speech = synthesize_speech(str(prepared["text"]), settings=settings, overrides=payload, prepared_input=prepared)
    content_type = str(speech.get("contentType") or "audio/mpeg")
    storage.put_tts_audio(key, speech["audio"], content_type, metadata=_tts_object_metadata(prepared, target))
    return 200, speech["audio"], content_type, headers


def handle_get_tts_job(storage: RssStorage, job_id: str) -> tuple[int, dict[str, Any]]:
    job = storage.get_content_job(job_id)
    if not job:
        return 404, {"error": "Audio job not found"}
    options = job.get("options") or {}
    prepared = options.get("preparedInput") if isinstance(options.get("preparedInput"), dict) else {}
    return 200, _tts_job_body(
        job_id,
        str(job.get("articleId") or ""),
        str(options.get("target") or "content"),
        prepared,
        str(job.get("status") or "unknown"),
        str(job.get("message") or ""),
        cache_key=str(job.get("ttsCacheKey") or options.get("ttsCacheKey") or ""),
        errors=job.get("errors") or [],
    )


def _tts_headers(prepared: dict[str, Any], key: str, *, cache_status: str) -> dict[str, str]:
    return {
        "x-rss-ai-cache": cache_status,
        "x-rss-ai-cache-key": key,
        "x-rss-ai-segment-index": str(prepared.get("segmentIndex", 0)),
        "x-rss-ai-segment-count": str(prepared.get("segmentCount", 1)),
        "x-rss-ai-segment-percent": str(prepared.get("segmentPercent", 100)),
        "x-rss-ai-input-chars": str(prepared.get("inputChars", 0)),
        "x-rss-ai-source-chars": str(prepared.get("sourceChars", 0)),
    }


def _tts_object_metadata(prepared: dict[str, Any], target: str) -> dict[str, Any]:
    return {
        "target": target,
        "textHash": prepared.get("textHash"),
        "sourceHash": prepared.get("sourceHash"),
        "segmentIndex": prepared.get("segmentIndex", 0),
        "segmentCount": prepared.get("segmentCount", 1),
        "segmentPercent": prepared.get("segmentPercent", 100),
        "inputChars": prepared.get("inputChars", 0),
        "sourceChars": prepared.get("sourceChars", 0),
    }


def _tts_job_body(
    job_id: str,
    article_id: str,
    target: str,
    prepared: dict[str, Any],
    status: str,
    message: str,
    *,
    cache_key: str = "",
    errors: list[Any] | None = None,
) -> dict[str, Any]:
    return {
        "jobId": job_id,
        "articleId": article_id,
        "target": target,
        "status": status,
        "message": message,
        "cacheKey": cache_key,
        "segmentIndex": int(prepared.get("segmentIndex") or 0),
        "segmentCount": int(prepared.get("segmentCount") or 1),
        "segmentPercent": int(prepared.get("segmentPercent") or 100),
        "inputChars": int(prepared.get("inputChars") or 0),
        "sourceChars": int(prepared.get("sourceChars") or 0),
        "errors": errors or [],
    }


def _invoke_tts_job_async(job_id: str) -> None:
    function_name = os.environ.get("AWS_LAMBDA_FUNCTION_NAME")
    if not function_name:
        raise RuntimeError("AWS_LAMBDA_FUNCTION_NAME is required for async audio jobs")
    boto3.client("lambda").invoke(
        FunctionName=function_name,
        InvocationType="Event",
        Payload=json.dumps({"source": "rss-ai.tts-job", "jobId": job_id}).encode("utf-8"),
    )


def _handle_tts_job(event: dict[str, Any]) -> dict[str, Any]:
    storage = RssStorage()
    job_id = str(event.get("jobId") or "")
    job = storage.get_content_job(job_id)
    if not job:
        return {"ok": False, "error": "job not found", "jobId": job_id}
    options = job.get("options") or {}
    article_id = str(job.get("articleId") or "")
    target = str(options.get("target") or "content")
    prepared = options.get("preparedInput") if isinstance(options.get("preparedInput"), dict) else {}
    key = str(options.get("ttsCacheKey") or "")
    storage.update_content_job(job_id, {"status": "running", "message": "Generating audio with OpenAI text-to-speech"})
    try:
        settings = storage.get_settings()
        if not prepared or not key:
            article = storage.get_article(article_id, include_content=True)
            if not article:
                raise RuntimeError("Article not found")
            text = str(article.get("summary") or "").strip() if target == "summary" else _existing_article_content(article)
            if not text:
                raise RuntimeError("No readable text is available for audio")
            prepared = prepare_tts_input(text, settings=settings, overrides=options)
            key = tts_cache_key(article_id, target, prepared_input=prepared, settings=settings, overrides=options)
        if not _boolish(options.get("forceRefresh")) and storage.get_tts_audio(key):
            storage.update_content_job(
                job_id,
                {
                    "status": "completed",
                    "message": "Audio already available in cache",
                    "ttsCacheKey": key,
                    "completedAt": now_ms(),
                },
            )
            return {"ok": True, "jobId": job_id, "cache": "hit"}
        speech = synthesize_speech(str(prepared["text"]), settings=settings, overrides=options, prepared_input=prepared)
        content_type = str(speech.get("contentType") or "audio/mpeg")
        storage.put_tts_audio(key, speech["audio"], content_type, metadata=_tts_object_metadata(prepared, target))
        storage.update_content_job(
            job_id,
            {
                "status": "completed",
                "message": "Audio generation completed",
                "ttsCacheKey": key,
                "completedAt": now_ms(),
            },
        )
        return {"ok": True, "jobId": job_id, "cache": "miss"}
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
    storage = RssStorage()
    settings = storage.get_settings()
    if _boolish(settings.get("scheduledAiPrefetchEnabled")):
        return {"ok": True, "prefetch": handle_scheduled_ai_prefetch(storage, settings)}
    if _boolish(settings.get("scheduledRefreshEnabled")):
        return {"ok": True, "result": refresh_feeds(storage)}
    return {"ok": True, "skipped": "scheduled refresh and AI prefetch are disabled"}


def handle_scheduled_ai_prefetch(storage: RssStorage, settings: dict[str, Any] | None = None) -> dict[str, Any]:
    settings = settings or storage.get_settings()
    tags = _normalize_setting_tags(settings.get("scheduledAiPrefetchTags"))
    if not tags:
        return {"enabled": False, "reason": "No scheduled AI prefetch tags configured", "tags": []}

    feeds = [
        feed
        for feed in storage.list_feeds()
        if feed.get("enabled", True) and _tags_intersect(feed.get("tags"), tags)
    ]
    if not feeds:
        return {"enabled": True, "reason": "No enabled feeds match scheduled AI prefetch tags", "tags": tags, "feeds": 0, "queued": []}

    refresh_result = refresh_feeds(storage, feeds)
    candidates = _scheduled_prefetch_candidates(storage, tags, settings)
    queued: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    errors: list[dict[str, Any]] = []
    for article in candidates:
        work = _scheduled_prefetch_work(article, settings)
        if not work["needsContent"] and not work["needsSummary"]:
            skipped.append({"articleId": article.get("articleId"), "reason": "already cached"})
            continue
        if _prefetch_recently_queued(article, settings):
            skipped.append({"articleId": article.get("articleId"), "reason": "recently queued"})
            continue
        try:
            job = _queue_prefetch_content_job(storage, article, work)
            queued.append(
                {
                    "articleId": article.get("articleId"),
                    "jobId": job.get("jobId"),
                    "needsContent": work["needsContent"],
                    "needsSummary": work["needsSummary"],
                }
            )
        except Exception as exc:
            article_id = str(article.get("articleId") or "")
            if article_id:
                storage.update_article(
                    article_id,
                    {
                        "prefetchStatus": "failed",
                        "prefetchError": str(exc),
                        "prefetchProcessedAt": now_ms(),
                    },
                )
            errors.append({"articleId": article_id, "error": str(exc)})
    return {
        "enabled": True,
        "tags": tags,
        "feeds": len(feeds),
        "refresh": refresh_result,
        "candidates": len(candidates),
        "queued": queued,
        "skipped": skipped,
        "errors": errors,
    }


def _scheduled_prefetch_candidates(storage: RssStorage, tags: list[str], settings: dict[str, Any]) -> list[dict[str, Any]]:
    limit = _bounded_int(settings.get("scheduledAiPrefetchLimit"), 5, minimum=1, maximum=25)
    max_age_hours = _bounded_int(settings.get("scheduledAiPrefetchMaxAgeHours"), 24, minimum=1, maximum=168)
    articles = storage.list_articles(
        {
            "tags": ",".join(tags),
            "hours": max_age_hours,
            "limit": max(limit * 4, limit),
        }
    )
    candidates = [article for article in articles if _scheduled_prefetch_work(article, settings)["needsAny"]]
    return candidates[:limit]


def _scheduled_prefetch_work(article: dict[str, Any], settings: dict[str, Any]) -> dict[str, bool]:
    wants_content = _setting_enabled(settings, "scheduledAiPrefetchContent", True)
    wants_summary = _setting_enabled(settings, "scheduledAiPrefetchSummaries", True)
    needs_content = wants_content and (
        int(article.get("contentChunkCount") or 0) <= 0 or not bool(article.get("contentAiFormatted", False))
    )
    needs_summary = wants_summary and not bool(article.get("summaryGeneratedAt"))
    return {
        "needsContent": needs_content,
        "needsSummary": needs_summary,
        "needsAny": needs_content or needs_summary,
    }


def _queue_prefetch_content_job(storage: RssStorage, article: dict[str, Any], work: dict[str, bool]) -> dict[str, Any]:
    article_id = str(article.get("articleId") or "")
    url = str(article.get("link") or "")
    if not article_id or not url:
        raise RuntimeError("Article is missing id or link")
    options = {
        "prefetch": True,
        "markRead": False,
        "formatWithAi": work["needsContent"],
        "summarizeWithAi": work["needsSummary"],
    }
    if not work["needsContent"]:
        options["formatExistingOnly"] = True
    job = storage.create_content_job(article_id, url, options, [])
    storage.update_article(
        article_id,
        {
            "prefetchQueuedAt": now_ms(),
            "prefetchStatus": "queued",
            "prefetchJobId": job.get("jobId"),
            "prefetchError": None,
        },
    )
    _invoke_content_job_async(str(job["jobId"]))
    return job


def _prefetch_recently_queued(article: dict[str, Any], settings: dict[str, Any]) -> bool:
    queued_at = int(article.get("prefetchQueuedAt") or 0)
    if not queued_at:
        return False
    retry_minutes = _bounded_int(settings.get("scheduledAiPrefetchRetryMinutes"), 60, minimum=5, maximum=1440)
    return now_ms() - queued_at < retry_minutes * 60 * 1000


def _normalize_setting_tags(value: Any) -> list[str]:
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


def _tags_intersect(left: Any, right: list[str]) -> bool:
    return bool(set(_normalize_setting_tags(left)).intersection(right))


def _setting_enabled(settings: dict[str, Any], key: str, default: bool) -> bool:
    if key not in settings:
        return default
    return _boolish(settings.get(key))


def _bounded_int(value: Any, default: int, *, minimum: int, maximum: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        number = default
    return max(minimum, min(number, maximum))


def parse_json_body(event: dict[str, Any], default: Any | None = None) -> Any:
    body = event.get("body")
    if body is None or body == "":
        if default is not None:
            return default
        raise ValueError("JSON body is required")
    if event.get("isBase64Encoded"):
        body = base64.b64decode(body).decode("utf-8")
    return json.loads(body)


def response(status: int, body: Any, content_type: str = "application/json", headers: dict[str, str] | None = None) -> dict[str, Any]:
    if content_type == "application/json" and not isinstance(body, str):
        body = json.dumps(body, default=str)
    response_headers = {
        "content-type": content_type,
        "access-control-allow-origin": "*",
        "access-control-allow-headers": "content-type,x-rss-ai-token",
        "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
        "access-control-expose-headers": "x-rss-ai-cache,x-rss-ai-cache-key,x-rss-ai-segment-index,x-rss-ai-segment-count,x-rss-ai-segment-percent,x-rss-ai-input-chars,x-rss-ai-source-chars,content-type,content-length",
    }
    response_headers.update(headers or {})
    return {
        "statusCode": status,
        "headers": response_headers,
        "body": body,
    }


def binary_response(status: int, body: bytes, content_type: str, headers: dict[str, str] | None = None) -> dict[str, Any]:
    response_headers = {
        "content-type": content_type,
        "access-control-allow-origin": "*",
        "access-control-allow-headers": "content-type,x-rss-ai-token",
        "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
        "access-control-expose-headers": "x-rss-ai-cache,x-rss-ai-cache-key,x-rss-ai-segment-index,x-rss-ai-segment-count,x-rss-ai-segment-percent,x-rss-ai-input-chars,x-rss-ai-source-chars,content-type,content-length",
        "cache-control": "no-store",
    }
    response_headers.update(headers or {})
    return {
        "statusCode": status,
        "headers": response_headers,
        "isBase64Encoded": True,
        "body": base64.b64encode(body).decode("ascii"),
    }


def _authorized(event: dict[str, Any]) -> bool:
    token = os.environ.get("RSS_AI_API_TOKEN", "")
    if not token:
        return True
    headers = {k.lower(): v for k, v in (event.get("headers") or {}).items()}
    return headers.get("x-rss-ai-token") == token


def _client_safe_error(exc: Exception, fallback: str) -> str:
    message = str(exc)
    lowered = message.lower()
    if "accessdenied" in lowered and "s3" in lowered:
        return "Backend storage permission denied"
    if "arn:aws:" in lowered or "amazonaws.com" in lowered:
        return fallback
    return message or fallback


def _query_one(query: dict[str, list[str]], key: str, default: str | None = None) -> str | None:
    values = query.get(key)
    return values[0] if values else default


def _bool(value: str | None) -> bool:
    return str(value or "").lower() in {"1", "true", "yes", "on"}


def _boolish(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value or "").lower() in {"1", "true", "yes", "on"}
