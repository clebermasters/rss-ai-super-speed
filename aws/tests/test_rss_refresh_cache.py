from __future__ import annotations

import sys
import unittest
from email.message import Message
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

import app  # noqa: E402
import rss_fetcher  # noqa: E402


RSS_BODY = b"""<?xml version="1.0"?>
<rss version="2.0">
  <channel>
    <title>Example Feed</title>
    <item>
      <title>New item</title>
      <link>https://example.test/new-item</link>
      <description>Useful summary</description>
      <pubDate>Fri, 01 May 2026 12:00:00 GMT</pubDate>
    </item>
  </channel>
</rss>
"""


class FakeHttpResponse:
    status = 200

    def __init__(self, body: bytes = RSS_BODY) -> None:
        self.body = body
        self.headers = {"ETag": '"feed-v2"', "Last-Modified": "Fri, 01 May 2026 12:01:00 GMT"}

    def __enter__(self) -> "FakeHttpResponse":
        return self

    def __exit__(self, *_: object) -> None:
        return None

    def read(self) -> bytes:
        return self.body

    def getcode(self) -> int:
        return self.status


class RefreshStorage:
    def __init__(self) -> None:
        self.feeds = [
            {"feedId": "feed-a", "name": "A", "url": "https://example.test/a", "enabled": True},
            {"feedId": "feed-b", "name": "B", "url": "https://example.test/b", "enabled": True},
        ]
        self.updates: dict[str, dict] = {}

    def bootstrap_defaults(self) -> None:
        return None

    def list_feeds(self) -> list[dict]:
        return self.feeds

    def save_articles_detailed(self, articles: list[dict]) -> dict:
        self.saved_articles = articles
        return {"saved": 1, "duplicates": max(0, len(articles) - 1), "savedByFeed": {"feed-a": 1}}

    def update_feed(self, feed_id: str, updates: dict) -> dict:
        self.updates[feed_id] = updates
        return {**next(feed for feed in self.feeds if feed["feedId"] == feed_id), **updates}


class RssRefreshCacheTest(unittest.TestCase):
    def test_fetch_feed_sends_conditional_headers_and_stores_validators(self) -> None:
        captured_headers: dict[str, str] = {}

        def fake_urlopen(request, timeout: int):  # noqa: ANN001
            self.assertEqual(timeout, 30)
            captured_headers.update(dict(request.header_items()))
            return FakeHttpResponse()

        feed = {
            "feedId": "feed-a",
            "name": "A",
            "url": "https://example.test/feed.xml",
            "rssEtag": '"feed-v1"',
            "rssLastModified": "Thu, 30 Apr 2026 12:00:00 GMT",
        }
        with patch.object(rss_fetcher, "urlopen", side_effect=fake_urlopen):
            result = rss_fetcher.fetch_feed_result(feed)

        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["entriesChecked"], 1)
        self.assertEqual(result["etag"], '"feed-v2"')
        self.assertEqual(result["lastModified"], "Fri, 01 May 2026 12:01:00 GMT")
        self.assertEqual(captured_headers["If-none-match"], '"feed-v1"')
        self.assertEqual(captured_headers["If-modified-since"], "Thu, 30 Apr 2026 12:00:00 GMT")

    def test_fetch_feed_304_skips_parsing_and_entries(self) -> None:
        headers = Message()
        headers["ETag"] = '"feed-v1"'
        error = HTTPError("https://example.test/feed.xml", 304, "Not Modified", headers, None)
        feed = {
            "feedId": "feed-a",
            "name": "A",
            "url": "https://example.test/feed.xml",
            "rssEtag": '"feed-v1"',
        }

        with patch.object(rss_fetcher, "urlopen", side_effect=error):
            result = rss_fetcher.fetch_feed_result(feed)

        self.assertEqual(result["status"], "not_modified")
        self.assertTrue(result["unchanged"])
        self.assertEqual(result["entriesChecked"], 0)
        self.assertEqual(result["articles"], [])
        self.assertEqual(result["etag"], '"feed-v1"')

    def test_refresh_feeds_persists_validator_metadata_and_clear_counters(self) -> None:
        storage = RefreshStorage()
        articles = [
            {"articleId": "article-a", "link": "https://example.test/a", "sourceFeedId": "feed-a"},
            {"articleId": "article-b", "link": "https://example.test/b", "sourceFeedId": "feed-a"},
        ]
        feed_results = [
            {
                "feedId": "feed-a",
                "name": "A",
                "enabled": True,
                "status": "ok",
                "httpStatus": 200,
                "entriesChecked": 2,
                "fetched": 2,
                "etag": '"feed-a-v2"',
                "lastModified": "Fri, 01 May 2026 12:01:00 GMT",
                "durationMs": 120,
                "unchanged": False,
            },
            {
                "feedId": "feed-b",
                "name": "B",
                "enabled": True,
                "status": "not_modified",
                "httpStatus": 304,
                "entriesChecked": 0,
                "fetched": 0,
                "durationMs": 15,
                "unchanged": True,
            },
        ]

        with patch.object(app, "fetch_feeds_detailed", return_value=(articles, [], feed_results)):
            result = app.refresh_feeds(storage)

        self.assertEqual(result["fetched"], 2)
        self.assertEqual(result["entriesChecked"], 2)
        self.assertEqual(result["saved"], 1)
        self.assertEqual(result["newArticlesSaved"], 1)
        self.assertEqual(result["duplicateArticles"], 1)
        self.assertEqual(result["feedsChanged"], 1)
        self.assertEqual(result["feedsUnchanged"], 1)
        self.assertEqual(storage.updates["feed-a"]["rssEtag"], '"feed-a-v2"')
        self.assertEqual(storage.updates["feed-a"]["lastEntriesChecked"], 2)
        self.assertEqual(storage.updates["feed-a"]["lastNewArticlesSaved"], 1)
        self.assertFalse(storage.updates["feed-a"]["lastFetchUnchanged"])
        self.assertEqual(storage.updates["feed-b"]["lastFetchStatus"], "not_modified")
        self.assertEqual(storage.updates["feed-b"]["lastFetchHttpStatus"], 304)
        self.assertTrue(storage.updates["feed-b"]["lastFetchUnchanged"])


if __name__ == "__main__":
    unittest.main()
