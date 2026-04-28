from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

import app  # noqa: E402


class FakeStorage:
    def __init__(self, settings: dict | None = None) -> None:
        self.saved_content = ""
        self.article_updates: list[dict] = []
        self.settings = settings or {"browserBypassEnabled": True, "browserBypassMode": "on_blocked"}

    def get_article(self, article_id: str, include_content: bool = False) -> dict:
        del include_content
        return {"articleId": article_id, "link": "https://example.com/article"}

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
        return {
            "jobId": "job-1",
            "articleId": article_id,
            "url": url,
            "options": options,
            "errors": errors,
        }


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

    def test_direct_failure_queues_async_job(self) -> None:
        storage = FakeStorage()
        with patch.object(app, "fetch_direct", side_effect=RuntimeError("blocked")), \
            patch.object(app, "_invoke_content_job_async") as invoke:
            status, body = app.handle_fetch_content(storage, "article-1", {})
        self.assertEqual(status, 202)
        self.assertEqual(body["status"], "queued")
        self.assertEqual(body["jobId"], "job-1")
        invoke.assert_called_once_with("job-1")

    def test_ai_formatting_setting_queues_async_job_without_direct_fetch(self) -> None:
        storage = FakeStorage({"browserBypassEnabled": True, "browserBypassMode": "on_blocked", "aiContentFormattingEnabled": True})
        with patch.object(app, "fetch_direct") as direct, \
            patch.object(app, "_invoke_content_job_async") as invoke:
            status, body = app.handle_fetch_content(storage, "article-1", {})
        self.assertEqual(status, 202)
        self.assertEqual(body["status"], "queued")
        direct.assert_not_called()
        invoke.assert_called_once_with("job-1")

    def test_content_fetch_job_formats_when_setting_enabled(self) -> None:
        storage = FakeStorage({"browserBypassEnabled": True, "browserBypassMode": "on_blocked", "aiContentFormattingEnabled": True})
        job = {"articleId": "article-1", "options": {}, "errors": []}
        with patch.object(app, "fetch_with_fallbacks", return_value={"strategy": "direct", "content": "raw article text " * 200, "errors": []}), \
            patch.object(app, "format_article_content_for_mobile", return_value="## Better Reading\n\nFormatted article text"):
            result = app._run_content_fetch_job(storage, job)
        self.assertEqual(storage.saved_content, "## Better Reading\n\nFormatted article text")
        self.assertTrue(storage.article_updates[-1]["contentAiFormatted"])
        self.assertEqual(result["content"], "## Better Reading\n\nFormatted article text")


if __name__ == "__main__":
    unittest.main()
