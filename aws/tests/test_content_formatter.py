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

    def test_sanitizes_before_prompt_and_after_model_output(self) -> None:
        noisy_prefix = (
            'developer-blogs.nvidia.com/wp-content/uploads/2025/05/TensorRT-RTX-1024x576.png" '
            'target="_blank" rel="noreferrer">Decorative image.\n\n'
            'By [Homam Bahnassi](developer.nvidia.com/blog/author/hbahnassi/ "Posts by Homam Bahnassi")\n\n'
        )
        raw_body = noisy_prefix + " ".join(
            "This detailed article sentence preserves facts and context for the formatter."
            for _ in range(45)
        )
        model_output = (
            "## Section\n\n"
            + " ".join("This detailed article sentence preserves facts and context for the formatter." for _ in range(45))
            + "\n\n"
            + noisy_prefix
        )
        with patch.object(content_formatter, "generate_completion", return_value={"content": model_output}) as generate:
            result = content_formatter.format_article_content_for_mobile(
                raw_body,
                article={"title": "Title", "source": "Source", "link": "https://example.com"},
                settings={"aiContentFormattingEnabled": True},
            )

        user_prompt = generate.call_args.args[0][1]["content"]
        self.assertNotIn("TensorRT-RTX-1024x576.png", user_prompt)
        self.assertNotIn("Posts by Homam", user_prompt)
        self.assertIn("## Section", result)
        self.assertNotIn("TensorRT-RTX-1024x576.png", result)
        self.assertNotIn("target=", result)
        self.assertNotIn("Posts by Homam", result)

    def test_short_content_skips_ai(self) -> None:
        with patch.object(content_formatter, "generate_completion") as generate:
            result = content_formatter.format_article_content_for_mobile("Tiny article.", settings={"aiContentFormattingEnabled": True})
        self.assertEqual(result, "Tiny article.")
        generate.assert_not_called()


if __name__ == "__main__":
    unittest.main()
