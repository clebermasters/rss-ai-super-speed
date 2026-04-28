from __future__ import annotations

import io
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

import codex_provider  # noqa: E402


class FakeS3:
    def __init__(self) -> None:
        self.objects = {
            ("bucket", "codex/auth.json"): {
                "auth_mode": "chatgpt",
                "tokens": {"access_token": "old-access", "refresh_token": "refresh"},
            }
        }

    def get_object(self, Bucket: str, Key: str) -> dict:
        return {"Body": io.BytesIO(json.dumps(self.objects[(Bucket, Key)]).encode("utf-8"))}

    def put_object(self, Bucket: str, Key: str, Body: bytes, **_: object) -> None:
        self.objects[(Bucket, Key)] = json.loads(Body.decode("utf-8"))


class FakeResponse:
    def __init__(self, status_code: int = 200, payload: dict | None = None, lines: list[str] | None = None) -> None:
        self.status_code = status_code
        self._payload = payload or {}
        self._lines = lines or []
        self.text = json.dumps(self._payload)
        self.ok = status_code < 400

    def json(self) -> dict:
        return self._payload

    def iter_lines(self, decode_unicode: bool = False):
        del decode_unicode
        for line in self._lines:
            yield line


class CodexProviderTest(unittest.TestCase):
    def make_provider(self, s3: FakeS3) -> codex_provider.CodexSubscriptionProvider:
        with patch.object(codex_provider.boto3, "client", return_value=s3):
            return codex_provider.CodexSubscriptionProvider(bucket="bucket", auth_key="codex/auth.json")

    def test_list_models_filters_supported_models(self) -> None:
        s3 = FakeS3()
        provider = self.make_provider(s3)
        with patch.object(
            codex_provider.requests,
            "request",
            return_value=FakeResponse(
                payload={
                    "models": [
                        {"slug": "gpt-5.4", "display_name": "GPT 5.4", "supported_in_api": True},
                        {"slug": "hidden", "supported_in_api": False},
                    ]
                }
            ),
        ):
            models = provider.list_models()
        self.assertEqual([model["id"] for model in models], ["gpt-5.4"])

    def test_generate_parses_sse(self) -> None:
        s3 = FakeS3()
        provider = self.make_provider(s3)
        lines = [
            'data: {"type":"response.output_text.delta","delta":"hello "}',
            'data: {"type":"response.output_text.delta","delta":"world"}',
            'data: {"type":"response.completed","response":{"id":"r1","model":"gpt-5.4","usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}}}',
        ]
        with patch.object(codex_provider.requests, "request", return_value=FakeResponse(lines=lines)):
            result = provider.generate([{"role": "user", "content": "hi"}], model="gpt-5.4")
        self.assertEqual(result["content"], "hello world")
        self.assertEqual(result["usage"]["totalTokens"], 3)

    def test_401_refreshes_and_persists_tokens(self) -> None:
        s3 = FakeS3()
        provider = self.make_provider(s3)
        first = FakeResponse(status_code=401, payload={"error": "expired"})
        second = FakeResponse(payload={"models": [{"slug": "gpt-5.4", "supported_in_api": True}]})
        request_mock = Mock(side_effect=[first, second])
        refresh_mock = Mock(return_value=FakeResponse(payload={"access_token": "new-access", "refresh_token": "new-refresh"}))
        with patch.object(codex_provider.requests, "request", request_mock), \
            patch.object(codex_provider.requests, "post", refresh_mock):
            provider.list_models()
        stored = s3.objects[("bucket", "codex/auth.json")]
        self.assertEqual(stored["tokens"]["access_token"], "new-access")
        self.assertEqual(stored["tokens"]["refresh_token"], "new-refresh")


if __name__ == "__main__":
    unittest.main()
