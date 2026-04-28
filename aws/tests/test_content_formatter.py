from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

import content_formatter  # noqa: E402


class ContentFormatterTest(unittest.TestCase):
    def test_setting_controls_ai_formatting(self) -> None:
        self.assertFalse(content_formatter.should_format_content_with_ai({"aiContentFormattingEnabled": False}, {}))
        self.assertTrue(content_formatter.should_format_content_with_ai({"aiContentFormattingEnabled": True}, {}))
        self.assertTrue(content_formatter.should_format_content_with_ai({}, {"formatWithAi": True}))
        self.assertFalse(content_formatter.should_format_content_with_ai({"aiContentFormattingEnabled": True}, {"formatWithAi": False}))

    def test_formats_long_article_and_preserves_fetch_marker(self) -> None:
        raw_body = "\n\n".join(
            [
                "This is a detailed paragraph with facts, numbers, quotes, and context that should be preserved."
                for _ in range(80)
            ]
        )
        formatted_body = "\n\n".join(
            [
                "## Section",
                *[
                    "This is a detailed paragraph with facts, numbers, quotes, and context that should be preserved."
                    for _ in range(80)
                ],
            ]
        )
        with patch.object(content_formatter, "generate_completion", return_value={"content": formatted_body}) as generate:
            result = content_formatter.format_article_content_for_mobile(
                f"*[Fetched via browser automation]*\n\n{raw_body}",
                article={"title": "Title", "source": "Source", "link": "https://example.com"},
                settings={"aiContentFormattingEnabled": True},
            )

        self.assertTrue(result.startswith("*[Fetched via browser automation]*\n\n## Section"))
        system_prompt = generate.call_args.args[0][0]["content"]
        self.assertIn("Do not summarize", system_prompt)

    def test_rejects_summary_like_output(self) -> None:
        raw = " ".join(f"word{i}" for i in range(300))
        with patch.object(content_formatter, "generate_completion", return_value={"content": "A short summary."}):
            with self.assertRaises(content_formatter.ContentFormattingError):
                content_formatter.format_article_content_for_mobile(raw, settings={"aiContentFormattingEnabled": True})

    def test_short_content_skips_ai(self) -> None:
        with patch.object(content_formatter, "generate_completion") as generate:
            result = content_formatter.format_article_content_for_mobile("Tiny article.", settings={"aiContentFormattingEnabled": True})
        self.assertEqual(result, "Tiny article.")
        generate.assert_not_called()


if __name__ == "__main__":
    unittest.main()
