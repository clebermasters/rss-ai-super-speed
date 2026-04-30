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
            "summary": "AI summary text",
            "content": "Full article content",
        }
        self.cached_audio: dict | None = None
        self.saved_audio: dict | None = None

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


if __name__ == "__main__":
    unittest.main()
