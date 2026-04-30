from __future__ import annotations

import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lambda" / "rss_api"))

import ai_client  # noqa: E402


class FakeResponse:
    def __init__(self, status_code: int, payload: dict | None = None) -> None:
        self.status_code = status_code
        self._payload = payload or {}
        self.text = json.dumps(self._payload)

    def json(self) -> dict:
        return self._payload


class OpenAiCompatibleProviderTest(unittest.TestCase):
    def test_retries_with_max_completion_tokens_when_model_rejects_max_tokens(self) -> None:
        error = FakeResponse(
            400,
            {
                "error": {
                    "message": "Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."
                }
            },
        )
        success = FakeResponse(
            200,
            {
                "id": "chatcmpl-test",
                "model": "gpt-5.4",
                "choices": [{"message": {"content": "summary"}}],
                "usage": {"total_tokens": 12},
            },
        )
        sent_payloads: list[dict] = []

        def post_side_effect(*_: object, **kwargs: object) -> FakeResponse:
            sent_payloads.append(json.loads(json.dumps(kwargs["json"])))
            return [error, success][len(sent_payloads) - 1]

        post_mock = Mock(side_effect=post_side_effect)
        with patch.dict(os.environ, {"OPENAI_API_KEY": "test-key"}, clear=False), \
            patch.object(ai_client.requests, "post", post_mock):
            provider = ai_client.OpenAiCompatibleProvider(model="gpt-5.4")
            result = provider.generate([{"role": "user", "content": "hello"}], max_tokens=123)

        self.assertEqual(result["content"], "summary")
        first_payload = sent_payloads[0]
        second_payload = sent_payloads[1]
        self.assertEqual(first_payload["max_tokens"], 123)
        self.assertNotIn("max_completion_tokens", first_payload)
        self.assertNotIn("max_tokens", second_payload)
        self.assertEqual(second_payload["max_completion_tokens"], 123)

    def test_retries_without_temperature_when_provider_rejects_temperature(self) -> None:
        error = FakeResponse(400, {"error": {"message": "Unsupported parameter: temperature"}})
        success = FakeResponse(200, {"choices": [{"message": {"content": "ok"}}]})
        post_mock = Mock(side_effect=[error, success])
        with patch.dict(os.environ, {"OPENAI_API_KEY": "test-key"}, clear=False), \
            patch.object(ai_client.requests, "post", post_mock):
            provider = ai_client.OpenAiCompatibleProvider(model="provider-model")
            result = provider.generate([{"role": "user", "content": "hello"}])

        self.assertEqual(result["content"], "ok")
        second_payload = post_mock.call_args_list[1].kwargs["json"]
        self.assertNotIn("temperature", second_payload)


if __name__ == "__main__":
    unittest.main()
