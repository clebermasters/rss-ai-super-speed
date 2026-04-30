from __future__ import annotations

import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

from storage import RssStorage  # noqa: E402


class FakeTable:
    def __init__(self, items: list[dict]) -> None:
        self.items = items

    def query(self, **_: object) -> dict:
        return {"Items": self.items}


class PaginatedFakeTable:
    def __init__(self, pages: list[dict]) -> None:
        self.pages = pages
        self.calls = 0

    def query(self, **kwargs: object) -> dict:
        expected_start_key = kwargs.get("ExclusiveStartKey")
        if self.calls == 0:
            self.assert_no_start_key(expected_start_key)
        page = self.pages[self.calls]
        self.calls += 1
        return page

    @staticmethod
    def assert_no_start_key(value: object) -> None:
        if value is not None:
            raise AssertionError(f"first query should not use ExclusiveStartKey: {value!r}")


class StorageFeedCountsTest(unittest.TestCase):
    def test_feed_article_counts_group_by_source_feed_id(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        storage.list_articles = lambda _filters=None: [
            {"articleId": "a1", "sourceFeedId": "feed-a", "isRead": False},
            {"articleId": "a2", "sourceFeedId": "feed-a", "isRead": True},
            {"articleId": "b1", "sourceFeedId": "feed-b", "isRead": False},
            {"articleId": "legacy", "source": "Legacy"},
        ]

        self.assertEqual(
            storage._feed_article_counts(),
            {
                "feed-a": {"total": 2, "unread": 1},
                "feed-b": {"total": 1, "unread": 1},
            },
        )

    def test_list_feeds_returns_dynamic_counts_instead_of_stored_zeroes(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        storage.table = FakeTable(
            [
                {
                    "pk": "USER#default",
                    "sk": "FEED#feed-a",
                    "feedId": "feed-a",
                    "name": "Feed A",
                    "articleCount": 0,
                    "unreadCount": 0,
                },
                {
                    "pk": "USER#default",
                    "sk": "FEED#feed-b",
                    "feedId": "feed-b",
                    "name": "Feed B",
                    "articleCount": 0,
                    "unreadCount": 0,
                },
            ]
        )
        storage._feed_article_counts = lambda: {
            "feed-a": {"total": 7, "unread": 3},
            "feed-b": {"total": 2, "unread": 0},
        }

        feeds = storage.list_feeds()

        self.assertEqual(
            [(feed["feedId"], feed["articleCount"], feed["unreadCount"]) for feed in feeds],
            [("feed-a", 7, 3), ("feed-b", 2, 0)],
        )

    def test_list_articles_follows_dynamodb_pagination(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        storage.table = PaginatedFakeTable(
            [
                {
                    "Items": [
                        {
                            "pk": "USER#default",
                            "sk": "ARTICLE#a1",
                            "gsi1pk": "USER#default#ARTICLES",
                            "gsi1sk": "2#a1",
                            "articleId": "a1",
                            "sourceFeedId": "feed-a",
                        }
                    ],
                    "ScannedCount": 1,
                    "LastEvaluatedKey": {"pk": "cursor", "sk": "cursor"},
                },
                {
                    "Items": [
                        {
                            "pk": "USER#default",
                            "sk": "ARTICLE#b1",
                            "gsi1pk": "USER#default#ARTICLES",
                            "gsi1sk": "1#b1",
                            "articleId": "b1",
                            "sourceFeedId": "feed-b",
                        }
                    ],
                    "ScannedCount": 1,
                },
            ]
        )

        articles = storage.list_articles({"limit": 10})

        self.assertEqual([article["articleId"] for article in articles], ["a1", "b1"])
        self.assertEqual(storage.table.calls, 2)


if __name__ == "__main__":
    unittest.main()
