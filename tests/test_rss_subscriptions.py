from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "scripts" / "rss_subscriptions.py"
spec = importlib.util.spec_from_file_location("rss_subscriptions", SCRIPT_PATH)
rss_subscriptions = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(rss_subscriptions)


class RssSubscriptionsTest(unittest.TestCase):
    def test_parse_json_export_shape(self) -> None:
        feeds = rss_subscriptions.parse_json_subscriptions(
            """
            {
              "feeds": [
                {
                  "name": "Example",
                  "url": "https://example.com/feed.xml",
                  "enabled": false,
                  "tags": ["tech", "rss"],
                  "limit": 5
                }
              ]
            }
            """
        )

        self.assertEqual(
            feeds,
            [
                {
                    "name": "Example",
                    "url": "https://example.com/feed.xml",
                    "enabled": False,
                    "tags": ["tech", "rss"],
                    "limit": 5,
                }
            ],
        )

    def test_parse_opml_dedupes_and_preserves_metadata(self) -> None:
        feeds = rss_subscriptions.parse_opml(
            """
            <opml version="2.0">
              <body>
                <outline text="Tech" title="Tech" type="rss"
                  xmlUrl="https://example.com/feed.xml"
                  category="ai, startups"
                  rssAiEnabled="false"
                  rssAiLimit="9" />
                <outline text="Duplicate" type="rss" xmlUrl="https://example.com/feed.xml/" />
              </body>
            </opml>
            """
        )

        self.assertEqual(len(feeds), 1)
        self.assertEqual(feeds[0]["url"], "https://example.com/feed.xml/")
        self.assertTrue(feeds[0]["enabled"])

    def test_opml_roundtrip(self) -> None:
        opml = rss_subscriptions.feeds_to_opml(
            [
                {
                    "name": "Example",
                    "url": "https://example.com/feed.xml",
                    "enabled": True,
                    "tags": ["ai"],
                    "limit": 20,
                }
            ]
        )

        feeds = rss_subscriptions.parse_opml(opml)
        self.assertEqual(feeds[0]["name"], "Example")
        self.assertEqual(feeds[0]["url"], "https://example.com/feed.xml")
        self.assertEqual(feeds[0]["tags"], ["ai"])

    def test_safe_error_message_redacts_aws_details(self) -> None:
        message = (
            "AccessDenied for arn:aws:s3:::rss-ai-private-123456789012-us-east-1 "
            "in account 123456789012"
        )

        cleaned = rss_subscriptions.safe_error_message(message)
        self.assertNotIn("123456789012", cleaned)
        self.assertNotIn("rss-ai-private", cleaned)
        self.assertIn("[aws-resource]", cleaned)


if __name__ == "__main__":
    unittest.main()
