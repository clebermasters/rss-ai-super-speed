from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

import content_fetcher  # noqa: E402


class ContentFetcherTest(unittest.TestCase):
    def test_direct_success_stops_fallback_chain(self) -> None:
        with patch.object(content_fetcher, "fetch_direct", return_value="direct content") as direct, \
            patch.object(content_fetcher, "fetch_browser") as browser, \
            patch.object(content_fetcher, "fetch_wayback") as wayback:
            result = content_fetcher.fetch_with_fallbacks("https://example.com")
        self.assertEqual(result["strategy"], "direct")
        self.assertEqual(result["content"], "direct content")
        direct.assert_called_once()
        browser.assert_not_called()
        wayback.assert_not_called()

    def test_direct_blocked_uses_browser(self) -> None:
        with patch.object(content_fetcher, "fetch_direct", side_effect=content_fetcher.ContentFetchError("blocked")), \
            patch.object(content_fetcher, "fetch_browser", return_value="browser content"), \
            patch.object(content_fetcher, "fetch_wayback") as wayback:
            result = content_fetcher.fetch_with_fallbacks("https://example.com")
        self.assertEqual(result["strategy"], "browser")
        self.assertIn("Fetched via browser automation", result["content"])
        wayback.assert_not_called()

    def test_browser_failure_uses_wayback(self) -> None:
        with patch.object(content_fetcher, "fetch_direct", side_effect=content_fetcher.ContentFetchError("blocked")), \
            patch.object(content_fetcher, "fetch_browser", side_effect=content_fetcher.ContentFetchError("browser failed")), \
            patch.object(content_fetcher, "fetch_wayback", return_value="wayback content"):
            result = content_fetcher.fetch_with_fallbacks("https://example.com")
        self.assertEqual(result["strategy"], "wayback")
        self.assertEqual(result["content"], "wayback content")

    def test_browser_disabled_by_settings_uses_wayback(self) -> None:
        with patch.object(content_fetcher, "fetch_direct", side_effect=content_fetcher.ContentFetchError("blocked")), \
            patch.dict(content_fetcher.os.environ, {"BROWSER_FETCHER_FUNCTION": "fn", "BROWSER_BYPASS_ENABLED": "true"}), \
            patch.object(content_fetcher, "fetch_wayback", return_value="wayback content"):
            result = content_fetcher.fetch_with_fallbacks(
                "https://example.com",
                {"browserBypassEnabled": False},
            )
        self.assertEqual(result["strategy"], "wayback")

    def test_all_strategies_fail(self) -> None:
        with patch.object(content_fetcher, "fetch_direct", side_effect=content_fetcher.ContentFetchError("blocked")), \
            patch.object(content_fetcher, "fetch_browser", side_effect=content_fetcher.ContentFetchError("browser failed")), \
            patch.object(content_fetcher, "fetch_wayback", side_effect=content_fetcher.ContentFetchError("archive failed")):
            with self.assertRaises(content_fetcher.ContentFetchError):
                content_fetcher.fetch_with_fallbacks("https://example.com")


if __name__ == "__main__":
    unittest.main()
