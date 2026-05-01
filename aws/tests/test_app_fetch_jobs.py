from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

import app  # noqa: E402


class FakeStorage:
    def __init__(self, settings: dict | None = None, article: dict | None = None) -> None:
        self.saved_content = ""
        self.article_updates: list[dict] = []
        self.jobs: list[dict] = []
        self.settings = settings or {"browserBypassEnabled": True, "browserBypassMode": "on_blocked"}
        self.article = article or {"articleId": "article-1", "link": "https://example.com/article"}

    def get_article(self, article_id: str, include_content: bool = False) -> dict:
        article = dict(self.article)
        article["articleId"] = article_id
        if include_content and self.saved_content:
            article["content"] = self.saved_content
        return article

    def get_settings(self) -> dict:
        return self.settings

    def save_article_content(self, article_id: str, content: str) -> None:
        del article_id
        self.saved_content = content

    def update_article(self, article_id: str, updates: dict) -> dict:
        del article_id
        self.article_updates.append(updates)
        return updates

    def create_content_job(self, article_id: str, url: str, options: dict, errors: list[str]) -> dict:
        job = {
            "jobId": "job-1",
            "articleId": article_id,
            "url": url,
            "options": options,
            "errors": errors,
        }
        self.jobs.append(job)
        return job


class FakeJobStorage:
    def __init__(self, job: dict) -> None:
        self.job = dict(job)
        self.updates: list[dict] = []

    def get_content_job(self, job_id: str) -> dict:
        del job_id
        return dict(self.job)

    def update_content_job(self, job_id: str, updates: dict) -> dict:
        del job_id
        self.updates.append(dict(updates))
        self.job.update(updates)
        return dict(self.job)


class FetchJobRoutingTest(unittest.TestCase):
    def test_direct_success_completes_without_queue(self) -> None:
        storage = FakeStorage()
        with patch.object(app, "fetch_direct", return_value="content"), \
            patch.object(app, "_invoke_content_job_async") as invoke:
            status, body = app.handle_fetch_content(storage, "article-1", {})
        self.assertEqual(status, 200)
        self.assertEqual(body["status"], "completed")
        self.assertEqual(storage.saved_content, "content")
        invoke.assert_not_called()

    def test_direct_prefetch_can_keep_article_unread(self) -> None:
        storage = FakeStorage()
        with patch.object(app, "fetch_direct", return_value="content"), \
            patch.object(app, "_invoke_content_job_async") as invoke:
            status, body = app.handle_fetch_content(storage, "article-1", {"markRead": False})
        self.assertEqual(status, 200)
        self.assertEqual(body["status"], "completed")
        self.assertEqual(storage.saved_content, "content")
        self.assertNotIn("isRead", storage.article_updates[-1])
        invoke.assert_not_called()

    def test_direct_failure_queues_async_job(self) -> None:
        storage = FakeStorage()
        with patch.object(app, "fetch_direct", side_effect=RuntimeError("blocked")), \
            patch.object(app, "_invoke_content_job_async") as invoke:
            status, body = app.handle_fetch_content(storage, "article-1", {})
        self.assertEqual(status, 202)
        self.assertEqual(body["status"], "queued")
        self.assertFalse(body["formattingRequested"])
        self.assertEqual(body["jobId"], "job-1")
        invoke.assert_called_once_with("job-1")

    def test_async_prefetch_can_keep_article_unread(self) -> None:
        storage = FakeStorage()
        with patch.object(app, "fetch_direct", side_effect=RuntimeError("blocked")), \
            patch.object(app, "_invoke_content_job_async"):
            app.handle_fetch_content(storage, "article-1", {"markRead": False})
        self.assertFalse(storage.jobs[-1]["options"]["markRead"])

    def test_ai_formatting_setting_queues_async_job_without_direct_fetch(self) -> None:
        storage = FakeStorage({"browserBypassEnabled": True, "browserBypassMode": "on_blocked", "aiContentFormattingEnabled": True})
        with patch.object(app, "fetch_direct") as direct, \
            patch.object(app, "_invoke_content_job_async") as invoke:
            status, body = app.handle_fetch_content(storage, "article-1", {})
        self.assertEqual(status, 202)
        self.assertEqual(body["status"], "queued")
        self.assertTrue(body["formattingRequested"])
        self.assertTrue(storage.jobs[-1]["options"]["formatWithAi"])
        direct.assert_not_called()
        invoke.assert_called_once_with("job-1")

    def test_ai_formatting_payload_queues_async_job_without_saved_setting(self) -> None:
        storage = FakeStorage({"browserBypassEnabled": True, "browserBypassMode": "on_blocked", "aiContentFormattingEnabled": False})
        with patch.object(app, "fetch_direct") as direct, \
            patch.object(app, "_invoke_content_job_async") as invoke:
            status, body = app.handle_fetch_content(storage, "article-1", {"formatWithAi": True})
        self.assertEqual(status, 202)
        self.assertTrue(body["formattingRequested"])
        self.assertTrue(storage.jobs[-1]["options"]["formatWithAi"])
        direct.assert_not_called()
        invoke.assert_called_once_with("job-1")

    def test_format_existing_content_queues_async_job_without_fetching(self) -> None:
        storage = FakeStorage(article={"articleId": "article-1", "link": "https://example.com/article", "content": "raw content"})
        with patch.object(app, "_invoke_content_job_async") as invoke:
            status, body = app.handle_format_existing_content(storage, "article-1", {})
        self.assertEqual(status, 202)
        self.assertEqual(body["status"], "queued")
        self.assertTrue(body["formattingRequested"])
        self.assertTrue(storage.jobs[-1]["options"]["formatWithAi"])
        self.assertTrue(storage.jobs[-1]["options"]["formatExistingOnly"])
        invoke.assert_called_once_with("job-1")

    def test_format_existing_content_requires_content(self) -> None:
        storage = FakeStorage(article={"articleId": "article-1", "link": "https://example.com/article"})
        with patch.object(app, "_invoke_content_job_async") as invoke:
            status, body = app.handle_format_existing_content(storage, "article-1", {})
        self.assertEqual(status, 400)
        self.assertIn("No article content", body["error"])
        invoke.assert_not_called()

    def test_content_fetch_job_formats_when_setting_enabled(self) -> None:
        storage = FakeStorage({"browserBypassEnabled": True, "browserBypassMode": "on_blocked", "aiContentFormattingEnabled": True})
        job = {"articleId": "article-1", "options": {}, "errors": []}
        with patch.object(app, "fetch_with_fallbacks", return_value={"strategy": "direct", "content": "raw article text " * 200, "errors": []}), \
            patch.object(app, "format_article_content_for_mobile", return_value="## Better Reading\n\nFormatted article text"):
            result = app._run_content_fetch_job(storage, job)
        self.assertEqual(storage.saved_content, "## Better Reading\n\nFormatted article text")
        self.assertTrue(storage.article_updates[-1]["contentAiFormatted"])
        self.assertTrue(result["contentAiFormatted"])
        self.assertTrue(result["contentFormattingAttempted"])
        self.assertEqual(result["content"], "## Better Reading\n\nFormatted article text")

    def test_content_fetch_job_prefetch_does_not_mark_read(self) -> None:
        storage = FakeStorage()
        job = {"articleId": "article-1", "options": {"markRead": False}, "errors": []}
        with patch.object(app, "fetch_with_fallbacks", return_value={"strategy": "direct", "content": "raw article text", "errors": []}):
            result = app._run_content_fetch_job(storage, job)
        self.assertEqual(result["content"], "raw article text")
        self.assertNotIn("isRead", storage.article_updates[-1])

    def test_content_fetch_job_reports_formatting_failure_and_keeps_original(self) -> None:
        storage = FakeStorage({"browserBypassEnabled": True, "browserBypassMode": "on_blocked", "aiContentFormattingEnabled": True})
        job = {"articleId": "article-1", "options": {"formatWithAi": True}, "errors": []}
        with patch.object(app, "fetch_with_fallbacks", return_value={"strategy": "direct", "content": "raw article text " * 200, "errors": []}), \
            patch.object(app, "format_article_content_for_mobile", side_effect=RuntimeError("provider unavailable")):
            result = app._run_content_fetch_job(storage, job)
        self.assertEqual(storage.saved_content, ("raw article text " * 200).strip())
        self.assertFalse(storage.article_updates[-1]["contentAiFormatted"])
        self.assertFalse(result["contentAiFormatted"])
        self.assertTrue(result["contentFormattingAttempted"])
        self.assertEqual(result["contentFormattingError"], "provider unavailable")
        self.assertIn("ai_format: provider unavailable", result["errors"])

    def test_format_existing_job_uses_stored_content_without_network_fetch(self) -> None:
        storage = FakeStorage(
            {"browserBypassEnabled": True, "browserBypassMode": "on_blocked", "aiContentFormattingEnabled": False},
            article={"articleId": "article-1", "link": "https://example.com/article", "content": "existing article text " * 200},
        )
        job = {"articleId": "article-1", "options": {"formatWithAi": True, "formatExistingOnly": True}, "errors": []}
        with patch.object(app, "fetch_with_fallbacks") as fetch, \
            patch.object(app, "format_article_content_for_mobile", return_value="## Formatted\n\nExisting article text"):
            result = app._run_content_fetch_job(storage, job)
        fetch.assert_not_called()
        self.assertEqual(result["strategy"], "existing")
        self.assertTrue(result["contentAiFormatted"])
        self.assertEqual(storage.saved_content, "## Formatted\n\nExisting article text")


class ContentJobStatusTest(unittest.TestCase):
    def test_get_content_job_marks_stale_running_job_failed(self) -> None:
        stale_updated_at = app.now_ms() - 421_000
        storage = FakeJobStorage(
            {
                "jobId": "job-1",
                "articleId": "article-1",
                "status": "running",
                "message": "Fetching full article content",
                "errors": [],
                "updatedAt": stale_updated_at,
                "createdAt": stale_updated_at,
                "options": {},
            }
        )
        with patch.dict(app.os.environ, {"CONTENT_JOB_STALE_SECONDS": "420"}):
            body = app.handle_get_content_job(storage, "job-1")

        self.assertEqual(body["status"], "failed")
        self.assertIn("backend timeout", body["message"])
        self.assertEqual(storage.updates[-1]["status"], "failed")

    def test_get_content_job_keeps_recent_running_job_active(self) -> None:
        storage = FakeJobStorage(
            {
                "jobId": "job-1",
                "articleId": "article-1",
                "status": "running",
                "message": "Fetching full article content",
                "errors": [],
                "updatedAt": app.now_ms(),
                "createdAt": app.now_ms(),
                "options": {},
            }
        )
        with patch.dict(app.os.environ, {"CONTENT_JOB_STALE_SECONDS": "420"}):
            body = app.handle_get_content_job(storage, "job-1")

        self.assertEqual(body["status"], "running")
        self.assertEqual(storage.updates, [])


if __name__ == "__main__":
    unittest.main()
