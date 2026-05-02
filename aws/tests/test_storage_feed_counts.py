from __future__ import annotations

import sys
import time
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

from storage import RssStorage, _normalize_tags, content_cache_expired  # noqa: E402


class FakeTable:
    def __init__(self, items: list[dict]) -> None:
        self.items = items

    def query(self, **_: object) -> dict:
        return {"Items": self.items}

    def put_item(self, Item: dict, **_: object) -> None:
        key = (Item["pk"], Item["sk"])
        self.items = [item for item in self.items if (item.get("pk"), item.get("sk")) != key]
        self.items.append(dict(Item))


class ArticleWriteFakeTable:
    def __init__(self) -> None:
        self.items: dict[tuple[str, str], dict] = {
            ("USER#default", "FEED#feed-a"): {
                "pk": "USER#default",
                "sk": "FEED#feed-a",
                "feedId": "feed-a",
                "articleCount": 0,
                "unreadCount": 0,
                "feedCountVersion": 1,
            }
        }

    def put_item(self, Item: dict, **kwargs: object) -> None:
        key = (Item["pk"], Item["sk"])
        if kwargs.get("ConditionExpression") and key in self.items:
            from botocore.exceptions import ClientError

            raise ClientError({"Error": {"Code": "ConditionalCheckFailedException"}}, "PutItem")
        self.items[key] = dict(Item)

    def update_item(self, Key: dict, ExpressionAttributeValues: dict, **_: object) -> None:
        item = self.items.setdefault((Key["pk"], Key["sk"]), {"pk": Key["pk"], "sk": Key["sk"]})
        item["feedCountVersion"] = ExpressionAttributeValues[":version"]
        item["feedCountsUpdatedAt"] = ExpressionAttributeValues[":updated"]
        item["articleCount"] = int(item.get("articleCount") or 0) + int(ExpressionAttributeValues.get(":total") or 0)
        item["unreadCount"] = int(item.get("unreadCount") or 0) + int(ExpressionAttributeValues.get(":unread") or 0)

    def query(self, **_: object) -> dict:
        return {"Items": [dict(item) for item in self.items.values()]}


class ContentCacheFakeTable:
    def __init__(self) -> None:
        self.items: dict[tuple[str, str], dict] = {
            ("USER#default", "ARTICLE#a1"): {
                "pk": "USER#default",
                "sk": "ARTICLE#a1",
                "articleId": "a1",
                "contentChunkCount": 0,
            }
        }

    def get_item(self, Key: dict) -> dict:
        item = self.items.get((Key["pk"], Key["sk"]))
        return {"Item": dict(item)} if item else {}

    def put_item(self, Item: dict, **_: object) -> None:
        self.items[(Item["pk"], Item["sk"])] = dict(Item)

    def delete_item(self, Key: dict) -> None:
        self.items.pop((Key["pk"], Key["sk"]), None)

    def query(self, **_: object) -> dict:
        return {
            "Items": [
                dict(item)
                for (_, sk), item in self.items.items()
                if sk.startswith("CONTENT#")
            ]
        }


class HighlightFakeTable:
    def __init__(self) -> None:
        self.items: dict[tuple[str, str], dict] = {
            ("USER#default", "ARTICLE#a1"): {
                "pk": "USER#default",
                "sk": "ARTICLE#a1",
                "articleId": "a1",
                "title": "Readable Article",
                "source": "Test Feed",
                "link": "https://example.test/article",
                "isSaved": False,
            }
        }

    def get_item(self, Key: dict) -> dict:
        item = self.items.get((Key["pk"], Key["sk"]))
        return {"Item": dict(item)} if item else {}

    def put_item(self, Item: dict, **_: object) -> None:
        self.items[(Item["pk"], Item["sk"])] = dict(Item)

    def delete_item(self, Key: dict) -> None:
        self.items.pop((Key["pk"], Key["sk"]), None)

    def query(self, **kwargs: object) -> dict:
        expression = str(kwargs.get("KeyConditionExpression", ""))
        prefix = "HIGHLIGHT#a1#" if "a1" in expression else "HIGHLIGHT#"
        return {
            "Items": [
                dict(item)
                for (_, sk), item in self.items.items()
                if sk.startswith(prefix)
            ]
        }


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

    def test_list_feeds_rebuilds_counts_once_when_version_is_missing(self) -> None:
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
        self.assertTrue(all(feed["feedCountVersion"] == 1 for feed in feeds))

    def test_list_feeds_uses_persisted_counts_when_version_is_current(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        storage.table = FakeTable(
            [
                {
                    "pk": "USER#default",
                    "sk": "FEED#feed-a",
                    "feedId": "feed-a",
                    "name": "Feed A",
                    "articleCount": 4,
                    "unreadCount": 2,
                    "feedCountVersion": 1,
                },
            ]
        )
        storage._feed_article_counts = lambda: (_ for _ in ()).throw(AssertionError("counts should not be recomputed"))

        feeds = storage.list_feeds()

        self.assertEqual(feeds[0]["articleCount"], 4)
        self.assertEqual(feeds[0]["unreadCount"], 2)

    def test_save_articles_records_sync_ledger_and_increments_feed_counts(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        storage.table = ArticleWriteFakeTable()

        result = storage.save_articles_detailed(
            [
                {
                    "articleId": "a1",
                    "title": "New article",
                    "link": "https://example.test/a1",
                    "sourceFeedId": "feed-a",
                    "publishedEpoch": 1000,
                    "tags": ["ai"],
                }
            ]
        )

        self.assertEqual(result["saved"], 1)
        feed = storage.table.items[("USER#default", "FEED#feed-a")]
        self.assertEqual(feed["articleCount"], 1)
        self.assertEqual(feed["unreadCount"], 1)
        sync_items = [item for (pk, _), item in storage.table.items.items() if pk == "USER#default#SYNC"]
        self.assertEqual(len(sync_items), 1)
        self.assertEqual(sync_items[0]["article"]["articleId"], "a1")

    def test_pull_article_changes_uses_sync_ledger_for_warm_cache(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        storage.table = FakeTable(
            [
                {
                    "pk": "USER#default#SYNC",
                    "sk": "0000000002000#ARTICLE#a1",
                    "articleId": "a1",
                    "updatedAt": 2000,
                    "deleted": False,
                    "article": {"articleId": "a1", "title": "Changed", "updatedAt": 2000},
                }
            ]
        )
        storage.list_articles = lambda _filters=None: (_ for _ in ()).throw(AssertionError("warm sync should not read article list"))

        result = storage.pull_article_changes(1000, cursor=3000)

        self.assertEqual(result["syncSource"], "ledger")
        self.assertEqual([article["articleId"] for article in result["articles"]], ["a1"])
        self.assertEqual(result["changes"], 1)

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

    def test_normalize_tags_lowercases_deduplicates_and_accepts_csv(self) -> None:
        self.assertEqual(_normalize_tags(" AI, #ML, ai, machine   learning "), ["ai", "ml", "machine learning"])

    def test_article_matches_single_tag_filter(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        article = {"articleId": "a1", "tags": ["AI", "Research"]}

        self.assertTrue(storage._article_matches(article, {"tag": "ai"}))
        self.assertTrue(storage._article_matches(article, {"tag": "security, research"}))
        self.assertFalse(storage._article_matches(article, {"tag": "security"}))

    def test_article_matches_multiple_tags_filter(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        article = {"articleId": "a1", "tags": ["machine learning", "papers"]}

        self.assertTrue(storage._article_matches(article, {"tags": ["ai", "papers"]}))
        self.assertTrue(storage._article_matches(article, {"tags": "ml, machine learning"}))
        self.assertFalse(storage._article_matches(article, {"tags": ["security", "ops"]}))

    def test_save_article_content_adds_dynamodb_ttl_to_chunks_and_metadata(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        storage.table = ContentCacheFakeTable()

        storage.save_article_content("a1", "Readable cached content", content_ttl_days=2)

        content_item = storage.table.items[("USER#default", "CONTENT#a1#00000")]
        article_item = storage.table.items[("USER#default", "ARTICLE#a1")]
        self.assertGreaterEqual(content_item["expiresAt"], int(time.time()) + (2 * 24 * 60 * 60) - 5)
        self.assertEqual(article_item["contentExpiresAt"], content_item["expiresAt"])
        self.assertEqual(article_item["articleContentCacheTtlDays"], 2)
        self.assertEqual(storage.get_article_content("a1"), "Readable cached content")

    def test_get_article_content_returns_empty_when_metadata_cache_is_expired(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        storage.table = ContentCacheFakeTable()
        storage.save_article_content("a1", "Old cached content", content_ttl_days=1)
        storage.table.items[("USER#default", "ARTICLE#a1")]["contentExpiresAt"] = int(time.time()) - 1

        self.assertTrue(content_cache_expired(storage.get_article("a1")))
        self.assertEqual(storage.get_article_content("a1"), "")

    def test_refresh_content_cache_ttl_rewrites_existing_content_chunks(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        storage.table = ContentCacheFakeTable()
        storage.save_article_content("a1", "Cached content", content_ttl_days=1)

        result = storage.refresh_content_cache_ttl(14)

        content_item = storage.table.items[("USER#default", "CONTENT#a1#00000")]
        article_item = storage.table.items[("USER#default", "ARTICLE#a1")]
        self.assertEqual(result, {"chunks": 1, "articles": 1})
        self.assertGreaterEqual(content_item["expiresAt"], int(time.time()) + (14 * 24 * 60 * 60) - 5)
        self.assertEqual(article_item["contentExpiresAt"], content_item["expiresAt"])
        self.assertEqual(article_item["articleContentCacheTtlDays"], 14)

    def test_update_settings_applies_new_ttl_to_existing_content_cache(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        storage.table = ContentCacheFakeTable()
        storage.save_article_content("a1", "Cached content", content_ttl_days=1)

        updated = storage.update_settings({"articleContentCacheTtlDays": 21})

        content_item = storage.table.items[("USER#default", "CONTENT#a1#00000")]
        article_item = storage.table.items[("USER#default", "ARTICLE#a1")]
        self.assertEqual(updated["articleContentCacheTtlDays"], 21)
        self.assertGreaterEqual(content_item["expiresAt"], int(time.time()) + (21 * 24 * 60 * 60) - 5)
        self.assertEqual(article_item["contentExpiresAt"], content_item["expiresAt"])

    def test_update_settings_bounds_local_article_cache_days(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        storage.table = ContentCacheFakeTable()

        too_large = storage.update_settings({"localArticleCacheDays": 999})
        too_small = storage.update_settings({"localArticleCacheDays": 0})

        self.assertEqual(too_large["localArticleCacheDays"], 365)
        self.assertEqual(too_small["localArticleCacheDays"], 1)

    def test_create_highlight_saves_parent_article_and_deduplicates_text(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        storage.table = HighlightFakeTable()

        first = storage.create_highlight("a1", {"text": "  important   quote  "})
        second = storage.create_highlight("a1", {"text": "important quote"})

        self.assertEqual(first["highlightId"], second["highlightId"])
        self.assertEqual(first["text"], "important quote")
        self.assertEqual(first["articleTitle"], "Readable Article")
        self.assertTrue(storage.get_article("a1")["isSaved"])
        self.assertEqual(len(storage.list_highlights(article_id="a1")), 1)

    def test_delete_highlight_removes_backend_record(self) -> None:
        storage = RssStorage.__new__(RssStorage)
        storage.table = HighlightFakeTable()
        highlight = storage.create_highlight("a1", {"text": "temporary highlight"})

        self.assertTrue(storage.delete_highlight("a1", highlight["highlightId"]))
        self.assertFalse(storage.delete_highlight("a1", highlight["highlightId"]))
        self.assertEqual(storage.list_highlights(article_id="a1"), [])


if __name__ == "__main__":
    unittest.main()
