from __future__ import annotations

import json
import os
import time
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

    def request_any(self, method: str, path: str, payload: dict | None = None, expected: set[int] | None = None) -> tuple[int, dict]:
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
        if expected is not None and status not in expected:
            raise AssertionError(f"{method} {path} returned {status}, expected one of {sorted(expected)}: {response_body}")
        return status, json.loads(response_body) if response_body else {}


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

    def test_fetch_full_content_job_completes(self) -> None:
        articles = self.client.request("GET", "/v1/articles?query=TensorRT&limit=5").get("articles") or []
        if not articles:
            articles = self.client.request("GET", "/v1/articles?limit=20").get("articles") or []
        article = next((item for item in articles if str(item.get("link") or "").startswith("http")), None)
        if not article:
            raise unittest.SkipTest("No HTTP article is available for Fetch Full smoke test")

        format_with_ai = os.environ.get("RSS_E2E_FETCH_CONTENT_FORMAT", "").lower() in {"1", "true", "yes"}
        status, body = self.client.request_any(
            "POST",
            f"/v1/articles/{article['articleId']}/fetch-content",
            {"formatWithAi": format_with_ai, "forceBrowser": True, "markRead": False},
            expected={200, 202},
        )
        if status == 202:
            job_id = body.get("jobId")
            self.assertTrue(job_id)
            deadline = time.time() + int(os.environ.get("RSS_E2E_FETCH_CONTENT_TIMEOUT", "240"))
            while time.time() < deadline:
                time.sleep(2)
                body = self.client.request("GET", f"/v1/content-jobs/{job_id}")
                if body.get("status") == "completed":
                    break
                if body.get("status") == "failed":
                    self.fail(f"Fetch Full job failed: {body.get('message')} {body.get('errors')}")
            else:
                self.fail(f"Fetch Full job did not complete before timeout; last status: {body}")

        self.assertEqual(body.get("status"), "completed")
        content = str(body.get("content") or (body.get("article") or {}).get("content") or "")
        self.assertGreater(len(content), 500)
        self.assertNotIn("target=", content)
        self.assertNotIn("rel=", content)
        self.assertNotIn("Decorative image", content)
        if format_with_ai:
            self.assertTrue(body.get("contentAiFormatted"))


if __name__ == "__main__":
    unittest.main()
