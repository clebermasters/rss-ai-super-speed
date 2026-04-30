from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

import app  # noqa: E402


class FakeStorage:
    def __init__(self, article: dict | None = None) -> None:
        self.article = article or {
            "articleId": "article-1",
            "link": "https://example.com/article",
            "summary": "AI summary text",
            "content": "Full article content",
        }
        self.cached_audio: dict | None = None
        self.saved_audio: dict | None = None
        self.jobs: dict[str, dict] = {}

    def get_article(self, article_id: str, include_content: bool = False) -> dict:
        del include_content
        return {**self.article, "articleId": article_id}

    def get_settings(self) -> dict:
        return {"ttsModel": "gpt-4o-mini-tts-2025-12-15", "ttsVoice": "marin"}

    def get_tts_audio(self, key: str) -> dict | None:
        del key
        return self.cached_audio

    def put_tts_audio(self, key: str, audio: bytes, content_type: str, metadata: dict | None = None) -> None:
        self.saved_audio = {"key": key, "audio": audio, "contentType": content_type, "metadata": metadata or {}}

    def create_content_job(self, article_id: str, url: str, options: dict | None = None, errors: list[str] | None = None) -> dict:
        job_id = f"job-{len(self.jobs) + 1}"
        job = {
            "jobId": job_id,
            "articleId": article_id,
            "url": url,
            "options": options or {},
            "errors": errors or [],
            "status": "queued",
        }
        self.jobs[job_id] = job
        return job

    def get_content_job(self, job_id: str) -> dict | None:
        return self.jobs.get(job_id)

    def update_content_job(self, job_id: str, updates: dict) -> dict | None:
        if job_id not in self.jobs:
            return None
        self.jobs[job_id].update(updates)
        return self.jobs[job_id]


class AppTtsTest(unittest.TestCase):
    def test_reads_article_content(self) -> None:
        storage = FakeStorage()
        with patch.object(app, "synthesize_speech", return_value={"audio": b"mp3", "contentType": "audio/mpeg"}) as speech:
            status, body, content_type, headers = app.handle_article_tts(storage, "article-1", {"target": "content"})
        self.assertEqual(status, 200)
        self.assertEqual(body, b"mp3")
        self.assertEqual(content_type, "audio/mpeg")
        self.assertEqual(speech.call_args.args[0], "Full article content")
        self.assertEqual(headers["x-rss-ai-cache"], "miss")
        self.assertIsNotNone(storage.saved_audio)

    def test_reads_summary(self) -> None:
        storage = FakeStorage()
        with patch.object(app, "synthesize_speech", return_value={"audio": b"mp3", "contentType": "audio/mpeg"}) as speech:
            status, _, _, _ = app.handle_article_tts(storage, "article-1", {"target": "summary"})
        self.assertEqual(status, 200)
        self.assertEqual(speech.call_args.args[0], "AI summary text")

    def test_summary_requires_generated_summary(self) -> None:
        status, body, _, _ = app.handle_article_tts(FakeStorage({"articleId": "a1", "content": "body"}), "a1", {"target": "summary"})
        self.assertEqual(status, 400)
        self.assertIn("No AI summary", body["error"])

    def test_uses_cached_audio_without_openai_call(self) -> None:
        storage = FakeStorage()
        storage.cached_audio = {"audio": b"cached", "contentType": "audio/mpeg", "metadata": {}}
        with patch.object(app, "synthesize_speech") as speech:
            status, body, _, headers = app.handle_article_tts(storage, "article-1", {"target": "content"})
        self.assertEqual(status, 200)
        self.assertEqual(body, b"cached")
        self.assertEqual(headers["x-rss-ai-cache"], "hit")
        speech.assert_not_called()

    def test_segments_article_by_percent(self) -> None:
        storage = FakeStorage({"articleId": "article-1", "content": " ".join(f"word{i}" for i in range(100))})
        with patch.object(app, "synthesize_speech", return_value={"audio": b"mp3", "contentType": "audio/mpeg"}) as speech:
            _, _, _, headers = app.handle_article_tts(
                storage,
                "article-1",
                {"target": "content", "segmentPercent": 20, "segmentIndex": 1},
            )
        self.assertEqual(headers["x-rss-ai-segment-index"], "1")
        self.assertEqual(headers["x-rss-ai-segment-count"], "5")
        self.assertTrue(str(speech.call_args.args[0]).startswith("word20 "))

    def test_async_miss_queues_audio_job(self) -> None:
        storage = FakeStorage()
        with patch.object(app, "_invoke_tts_job_async") as invoke:
            status, body, content_type, headers = app.handle_article_tts(storage, "article-1", {"target": "summary", "async": True})
        self.assertEqual(status, 202)
        self.assertEqual(content_type, "application/json")
        self.assertEqual(body["status"], "queued")
        self.assertEqual(body["target"], "summary")
        self.assertEqual(headers["x-rss-ai-cache"], "miss")
        invoke.assert_called_once_with("job-1")

    def test_audio_job_generates_and_caches_audio(self) -> None:
        storage = FakeStorage()
        job = storage.create_content_job(
            "article-1",
            "https://example.com/article",
            {
                "target": "summary",
                "ttsCacheKey": "tts-cache/key.mp3",
                "preparedInput": {
                    "text": "AI summary text",
                    "segmentIndex": 0,
                    "segmentCount": 1,
                    "segmentPercent": 100,
                    "inputChars": 15,
                    "sourceChars": 15,
                },
            },
            [],
        )
        with patch.object(app, "RssStorage", return_value=storage), \
            patch.object(app, "synthesize_speech", return_value={"audio": b"mp3", "contentType": "audio/mpeg"}):
            result = app._handle_tts_job({"jobId": job["jobId"]})
        self.assertTrue(result["ok"])
        self.assertEqual(storage.saved_audio["key"], "tts-cache/key.mp3")
        self.assertNotIn("text", storage.saved_audio["metadata"])
        self.assertEqual(storage.saved_audio["metadata"]["segmentPercent"], 100)
        self.assertEqual(storage.jobs[job["jobId"]]["status"], "completed")


if __name__ == "__main__":
    unittest.main()
