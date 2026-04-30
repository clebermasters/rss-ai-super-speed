from __future__ import annotations

import json
import os
import unittest
import urllib.error
import urllib.request


class RssApiClient:
    def __init__(self, base_url: str, token: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token

    def request(self, method: str, path: str, payload: dict | None = None, expected: int = 200) -> dict:
        body = json.dumps(payload).encode("utf-8") if payload is not None else None
        headers = {"x-rss-ai-token": self.token, "accept": "application/json"}
        if payload is not None:
            headers["content-type"] = "application/json"
        request = urllib.request.Request(self.base_url + path, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                status = response.getcode()
                response_body = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            status = exc.code
            response_body = exc.read().decode("utf-8")
        if status != expected:
            raise AssertionError(f"{method} {path} returned {status}, expected {expected}: {response_body}")
        return json.loads(response_body) if response_body else {}


class RssApiE2ETest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        base_url = os.environ.get("RSS_API_BASE_URL", "").strip()
        token = os.environ.get("RSS_API_TOKEN", "").strip()
        if not base_url or not token:
            raise unittest.SkipTest("RSS_API_BASE_URL and RSS_API_TOKEN must be configured")
        cls.client = RssApiClient(base_url, token)

    def test_health_bootstrap_and_llm_routes(self) -> None:
        health = self.client.request("GET", "/v1/health")
        self.assertTrue(health["ok"])
        bootstrap = self.client.request("GET", "/v1/bootstrap")
        self.assertIn("feeds", bootstrap)
        providers = self.client.request("GET", "/v1/llm/providers")
        self.assertIn("providers", providers)
        codex = self.client.request("GET", "/v1/llm/codex-auth")
        self.assertIn("configured", codex)

    def test_feed_crud(self) -> None:
        feed = self.client.request(
            "POST",
            "/v1/feeds",
            {"name": "Example", "url": "https://example.com/feed.xml"},
            expected=201,
        )
        feed_id = feed["feedId"]
        fetched = self.client.request("GET", f"/v1/feeds/{feed_id}")
        self.assertEqual(fetched["feedId"], feed_id)
        toggled = self.client.request("POST", f"/v1/feeds/{feed_id}/toggle-enabled")
        self.assertFalse(toggled["enabled"])
        self.client.request("DELETE", f"/v1/feeds/{feed_id}")


if __name__ == "__main__":
    unittest.main()
