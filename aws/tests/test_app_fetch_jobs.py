from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

import app  # noqa: E402


class FakeStorage:
    def __init__(self) -> None:
        self.saved_content = ""
        self.article_updates: list[dict] = []

    def get_article(self, article_id: str, include_content: bool = False) -> dict:
        del include_content
        return {"articleId": article_id, "link": "https://example.com/article"}

    def get_settings(self) -> dict:
        return {"browserBypassEnabled": True, "browserBypassMode": "on_blocked"}

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


if __name__ == "__main__":
    unittest.main()
