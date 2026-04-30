from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

import tts_client  # noqa: E402


class FakeSpeechResponse:
    status_code = 200
    content = b"ID3" + (b"0" * 2048)
    text = ""
    headers = {"content-type": "audio/mpeg"}


class TtsClientTest(unittest.TestCase):
    def test_synthesizes_with_openai_snapshot_model(self) -> None:
        post = Mock(return_value=FakeSpeechResponse())
        with patch.dict(os.environ, {"OPENAI_API_KEY": "test-key"}, clear=False), \
            patch.object(tts_client.requests, "post", post):
            result = tts_client.synthesize_speech(
                "## Title\n\nRead [this article](https://example.com) now.",
                settings={"ttsVoice": "marin"},
            )

        self.assertEqual(result["audio"], FakeSpeechResponse.content)
        self.assertEqual(result["model"], "gpt-4o-mini-tts-2025-12-15")
        call = post.call_args
        self.assertEqual(call.args[0], "https://api.openai.com/v1/audio/speech")
        self.assertEqual(call.kwargs["headers"]["Authorization"], "Bearer test-key")
        self.assertEqual(call.kwargs["json"]["voice"], "marin")
        self.assertEqual(call.kwargs["json"]["input"], "Title Read this article now.")

    def test_requires_openai_api_key(self) -> None:
        with patch.dict(os.environ, {"OPENAI_API_KEY": ""}, clear=False):
            with self.assertRaises(tts_client.TtsError):
                tts_client.synthesize_speech("hello")

    def test_truncates_long_input(self) -> None:
        text = "word " * 100
        prepared = tts_client.prepare_tts_text(text, max_chars=24)
        self.assertLessEqual(len(prepared), 27)
        self.assertTrue(prepared.endswith("..."))

    def test_cleans_noise_links_and_markdown(self) -> None:
        cleaned = tts_client.clean_tts_text(
            """
            ## 🚀 Article title

            Share this

            Read [internal docs](https://example.com/internal) for more.

            Advertisement

            Contact me@example.com
            """
        )
        self.assertEqual(cleaned, "Article title Read internal docs for more.")

    def test_prepares_percent_segment(self) -> None:
        prepared = tts_client.prepare_tts_input(
            " ".join(f"word{i}" for i in range(100)),
            overrides={"segmentPercent": 30, "segmentIndex": 2},
        )
        self.assertEqual(prepared["segmentIndex"], 2)
        self.assertEqual(prepared["segmentCount"], 4)
        self.assertTrue(prepared["text"].startswith("word60 "))


if __name__ == "__main__":
    unittest.main()
