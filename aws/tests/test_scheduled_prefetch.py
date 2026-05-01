from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

import app  # noqa: E402


class SchedulerStorage:
    def __init__(self, settings: dict, feeds: list[dict], articles: list[dict]) -> None:
        self.settings = settings
        self.feeds = feeds
        self.articles = {article["articleId"]: dict(article) for article in articles}
        self.jobs: list[dict] = []
        self.updates: list[tuple[str, dict]] = []
        self.saved_content = ""

    def get_settings(self) -> dict:
        return self.settings

    def list_feeds(self) -> list[dict]:
        return self.feeds

    def list_articles(self, filters: dict | None = None) -> list[dict]:
        filters = filters or {}
        requested_tags = {
            tag.strip()
            for tag in str(filters.get("tags") or filters.get("tag") or "").split(",")
            if tag.strip()
        }
        articles = list(self.articles.values())
        if requested_tags:
            articles = [
                article
                for article in articles
                if requested_tags.intersection(set(article.get("tags") or []))
            ]
        return articles[: int(filters.get("limit") or len(articles))]

    def get_article(self, article_id: str, include_content: bool = False) -> dict:
        article = dict(self.articles[article_id])
        if include_content and self.saved_content:
            article["content"] = self.saved_content
        return article

    def create_content_job(self, article_id: str, url: str, options: dict, errors: list[str]) -> dict:
        job = {
            "jobId": f"job-{len(self.jobs) + 1}",
            "articleId": article_id,
            "url": url,
            "options": dict(options),
            "errors": list(errors),
        }
        self.jobs.append(job)
        return job

    def update_article(self, article_id: str, updates: dict) -> dict:
        self.updates.append((article_id, dict(updates)))
        self.articles[article_id].update(updates)
        return dict(self.articles[article_id])

    def save_article_content(self, article_id: str, content: str) -> None:
        self.saved_content = content
        self.update_article(article_id, {"contentChunkCount": 1, "contentPreview": content[:1000]})


class ScheduledPrefetchTest(unittest.TestCase):
    def test_scheduler_exits_when_disabled(self) -> None:
        storage = SchedulerStorage({"scheduledAiPrefetchEnabled": False, "scheduledRefreshEnabled": False}, [], [])
        with patch.object(app, "RssStorage", return_value=storage), patch.object(app, "refresh_feeds") as refresh:
            result = app._handle_scheduled_refresh()

        self.assertTrue(result["ok"])
        self.assertIn("disabled", result["skipped"])
        refresh.assert_not_called()

    def test_prefetch_refreshes_matching_feeds_and_queues_articles(self) -> None:
        settings = {
            "scheduledAiPrefetchEnabled": True,
            "scheduledAiPrefetchTags": ["ai"],
            "scheduledAiPrefetchLimit": 2,
            "scheduledAiPrefetchContent": True,
            "scheduledAiPrefetchSummaries": True,
            "scheduledAiPrefetchRetryMinutes": 60,
        }
        ai_feed = {"feedId": "feed-ai", "name": "AI", "enabled": True, "tags": ["ai"]}
        tech_feed = {"feedId": "feed-tech", "name": "Tech", "enabled": True, "tags": ["tech"]}
        article = {
            "articleId": "article-1",
            "link": "https://example.com/1",
            "tags": ["ai"],
            "contentChunkCount": 0,
            "contentAiFormatted": False,
        }
        storage = SchedulerStorage(settings, [ai_feed, tech_feed], [article])

        with patch.object(app, "refresh_feeds", return_value={"fetched": 1, "saved": 1, "errors": []}) as refresh, \
            patch.object(app, "_invoke_content_job_async") as invoke:
            result = app.handle_scheduled_ai_prefetch(storage, settings)

        refresh.assert_called_once_with(storage, [ai_feed])
        invoke.assert_called_once_with("job-1")
        self.assertEqual(result["feeds"], 1)
        self.assertEqual(result["queued"][0]["articleId"], "article-1")
        self.assertTrue(storage.jobs[0]["options"]["formatWithAi"])
        self.assertTrue(storage.jobs[0]["options"]["summarizeWithAi"])
        self.assertFalse(storage.jobs[0]["options"]["markRead"])
        self.assertEqual(storage.articles["article-1"]["prefetchStatus"], "queued")

    def test_recent_prefetch_queue_is_not_duplicated(self) -> None:
        settings = {
            "scheduledAiPrefetchEnabled": True,
            "scheduledAiPrefetchTags": ["ai"],
            "scheduledAiPrefetchRetryMinutes": 60,
        }
        article = {
            "articleId": "article-1",
            "link": "https://example.com/1",
            "tags": ["ai"],
            "contentChunkCount": 0,
            "contentAiFormatted": False,
            "prefetchQueuedAt": app.now_ms(),
        }
        storage = SchedulerStorage(settings, [{"feedId": "feed-ai", "enabled": True, "tags": ["ai"]}], [article])

        with patch.object(app, "refresh_feeds", return_value={"fetched": 0, "saved": 0, "errors": []}), \
            patch.object(app, "_invoke_content_job_async") as invoke:
            result = app.handle_scheduled_ai_prefetch(storage, settings)

        self.assertEqual(result["queued"], [])
        self.assertEqual(result["skipped"][0]["reason"], "recently queued")
        invoke.assert_not_called()

    def test_expired_content_cache_is_prefetched_again(self) -> None:
        settings = {
            "scheduledAiPrefetchEnabled": True,
            "scheduledAiPrefetchTags": ["ai"],
            "scheduledAiPrefetchRetryMinutes": 60,
        }
        article = {
            "articleId": "article-1",
            "link": "https://example.com/1",
            "tags": ["ai"],
            "contentChunkCount": 1,
            "contentAiFormatted": True,
            "contentExpiresAt": 1,
        }
        storage = SchedulerStorage(settings, [{"feedId": "feed-ai", "enabled": True, "tags": ["ai"]}], [article])

        with patch.object(app, "refresh_feeds", return_value={"fetched": 0, "saved": 0, "errors": []}), \
            patch.object(app, "_invoke_content_job_async") as invoke:
            result = app.handle_scheduled_ai_prefetch(storage, settings)

        self.assertEqual(result["queued"][0]["articleId"], "article-1")
        self.assertTrue(storage.jobs[-1]["options"]["formatWithAi"])
        invoke.assert_called_once_with("job-1")

    def test_prefetch_content_job_formats_summarizes_and_keeps_unread(self) -> None:
        settings = {"aiContentFormattingEnabled": False, "browserBypassEnabled": True, "browserBypassMode": "on_blocked"}
        article = {"articleId": "article-1", "link": "https://example.com/1", "tags": ["ai"], "isRead": False}
        storage = SchedulerStorage(settings, [], [article])
        job = {
            "articleId": "article-1",
            "options": {"prefetch": True, "formatWithAi": True, "summarizeWithAi": True, "markRead": False},
            "errors": [],
        }

        with patch.object(app, "fetch_with_fallbacks", return_value={"strategy": "direct", "content": "raw article text " * 200, "errors": []}), \
            patch.object(app, "format_article_content_for_mobile", return_value="## Formatted\n\nReadable text"), \
            patch.object(app, "summarize_articles", return_value="AI summary"):
            result = app._run_content_fetch_job(storage, job)

        self.assertTrue(result["contentAiFormatted"])
        self.assertTrue(result["summaryGenerated"])
        self.assertEqual(storage.articles["article-1"]["summary"], "AI summary")
        self.assertEqual(storage.articles["article-1"]["prefetchStatus"], "completed")
        self.assertNotIn("isRead", storage.updates[-1][1])


if __name__ == "__main__":
    unittest.main()
