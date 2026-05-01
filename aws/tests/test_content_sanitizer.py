from __future__ import annotations

import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

from content_sanitizer import normalize_article_text  # noqa: E402


class ContentSanitizerTest(unittest.TestCase):
    def test_removes_nvidia_image_byline_and_date_noise(self) -> None:
        raw = """*[Fetched via browser automation]*

Speed Up Unreal Engine NNE Inference with NVIDIA TensorRT for RTX Runtime

developer-blogs.nvidia.com/wp-content/uploads/2025/05/TensorRT-RTX-1024x576.png" target="_blank" rel="noreferrer">Decorative image.

*Apr 30, 2026*

By [Homam Bahnassi](developer.nvidia.com/blog/author/hbahnassi/ "Posts by Homam Bahnassi")

## Introduction

Neural network techniques are increasingly used in computer graphics to boost image quality.
"""
        cleaned = normalize_article_text(raw)

        self.assertTrue(cleaned.startswith("*[Fetched via browser automation]*"))
        self.assertIn("Speed Up Unreal Engine NNE Inference", cleaned)
        self.assertIn("## Introduction", cleaned)
        self.assertIn("Neural network techniques", cleaned)
        self.assertNotIn("TensorRT-RTX-1024x576.png", cleaned)
        self.assertNotIn("target=", cleaned)
        self.assertNotIn("rel=", cleaned)
        self.assertNotIn("Decorative image", cleaned)
        self.assertNotIn("Posts by Homam", cleaned)
        self.assertNotIn("*Apr 30, 2026*", cleaned)

    def test_repairs_markdown_links_with_titles(self) -> None:
        cleaned = normalize_article_text(
            'Read the [TensorRT docs](developer.nvidia.com/tensorrt "NVIDIA TensorRT docs") for setup details.'
        )

        self.assertEqual(
            cleaned,
            "Read the [TensorRT docs](https://developer.nvidia.com/tensorrt) for setup details.",
        )

    def test_converts_safe_raw_anchors_and_removes_media_anchors(self) -> None:
        cleaned = normalize_article_text(
            '<a href="https://example.com/report" target="_blank">Read the report</a>\n'
            '<a href="https://example.com/hero.png" target="_blank">Decorative image.</a>\n'
            "The article continues with real prose."
        )

        self.assertIn("[Read the report](https://example.com/report)", cleaned)
        self.assertIn("The article continues with real prose.", cleaned)
        self.assertNotIn("hero.png", cleaned)
        self.assertNotIn("Decorative image", cleaned)

    def test_removes_orphan_href_media_fragment(self) -> None:
        cleaned = normalize_article_text(
            'href="https://example.com/uploads/chart.webp" target="_blank" rel="noreferrer">Decorative image.\n'
            "Important text remains."
        )

        self.assertEqual(cleaned, "Important text remains.")


if __name__ == "__main__":
    unittest.main()
