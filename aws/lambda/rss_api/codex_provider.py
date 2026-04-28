from __future__ import annotations

import json
import os
import time
from typing import Any

import boto3
import requests


class CodexProviderError(RuntimeError):
    pass


class CodexAuthError(CodexProviderError):
    pass


class CodexSubscriptionProvider:
    BASE_URL = "https://chatgpt.com/backend-api/codex"
    REFRESH_URL = "https://auth.openai.com/oauth/token"
    CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    RETRYABLE_STATUS_CODES = {429, 500, 502, 503, 504}

    def __init__(
        self,
        bucket: str | None = None,
        auth_key: str | None = None,
        model: str | None = None,
        client_version: str | None = None,
    ) -> None:
        self.bucket = bucket or os.environ["APP_BUCKET"]
        self.auth_key = auth_key or os.environ.get("CODEX_AUTH_S3_KEY", "codex/auth.json")
        self.base_url = os.environ.get("OPENAI_CODEX_BASE_URL", self.BASE_URL).rstrip("/")
        self.model = model or os.environ.get("OPENAI_CODEX_MODEL", "gpt-5.4")
        self.client_version = client_version or os.environ.get("OPENAI_CODEX_CLIENT_VERSION", "0.118.0")
        self.max_retries = int(os.environ.get("OPENAI_CODEX_MAX_RETRIES", "2"))
        self.retry_delay = float(os.environ.get("OPENAI_CODEX_RETRY_DELAY", "2.0"))
        self.s3 = boto3.client("s3")

    def configured(self) -> bool:
        try:
            self._load_auth_payload()
            return True
        except CodexAuthError:
            return False

    def _load_auth_payload(self) -> dict[str, Any]:
        try:
            response = self.s3.get_object(Bucket=self.bucket, Key=self.auth_key)
            payload = json.loads(response["Body"].read().decode("utf-8"))
        except Exception as exc:
            raise CodexAuthError(
                f"Codex auth JSON not configured at s3://{self.bucket}/{self.auth_key}"
            ) from exc
        tokens = payload.get("tokens") or {}
        if not tokens.get("access_token"):
            raise CodexAuthError("Codex auth JSON does not contain tokens.access_token")
        return payload

    def _persist_auth_payload(self, payload: dict[str, Any]) -> None:
        self.s3.put_object(
            Bucket=self.bucket,
            Key=self.auth_key,
            Body=json.dumps(payload).encode("utf-8"),
            ContentType="application/json",
            ServerSideEncryption="AES256",
        )

    def _refresh_access_token(self, payload: dict[str, Any]) -> None:
        refresh_token = (payload.get("tokens") or {}).get("refresh_token")
        if not refresh_token:
            raise CodexAuthError("Codex auth JSON does not contain a refresh token")
        response = requests.post(
            self.REFRESH_URL,
            headers={"Content-Type": "application/json"},
            json={
                "client_id": self.CLIENT_ID,
                "grant_type": "refresh_token",
                "refresh_token": refresh_token,
            },
            timeout=30,
        )
        if not response.ok:
            raise CodexAuthError(
                f"Failed to refresh Codex OAuth token: {response.status_code} {self._extract_error_message(response)}"
            )
        tokens = payload.setdefault("tokens", {})
        for key in ("id_token", "access_token", "refresh_token"):
            value = response.json().get(key)
            if value:
                tokens[key] = value
        payload["last_refresh"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        self._persist_auth_payload(payload)

    def _headers(self, payload: dict[str, Any]) -> dict[str, str]:
        token = (payload.get("tokens") or {}).get("access_token")
        return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    @staticmethod
    def _extract_error_message(response: requests.Response) -> str:
        try:
            payload = response.json()
        except ValueError:
            return response.text or "unknown error"
        if isinstance(payload, dict):
            detail = payload.get("detail")
            if detail:
                return str(detail)
            error = payload.get("error")
            if isinstance(error, dict):
                return str(error.get("message") or error.get("code") or "unknown error")
            if error:
                return str(error)
        return response.text or "unknown error"

    def _request(
        self,
        method: str,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        json_body: dict[str, Any] | None = None,
        timeout: int = 120,
        stream: bool = False,
    ) -> requests.Response:
        auth_payload = self._load_auth_payload()
        last_error: Exception | None = None
        for attempt in range(self.max_retries + 1):
            try:
                response = requests.request(
                    method,
                    f"{self.base_url}{path}",
                    headers=self._headers(auth_payload),
                    params=params,
                    json=json_body,
                    timeout=timeout,
                    stream=stream,
                )
            except requests.RequestException as exc:
                last_error = exc
                if attempt < self.max_retries:
                    time.sleep(self.retry_delay * (attempt + 1))
                    continue
                raise CodexProviderError(f"Codex request failed: {exc}") from exc

            if response.status_code == 401 and attempt < self.max_retries:
                self._refresh_access_token(auth_payload)
                auth_payload = self._load_auth_payload()
                continue
            if response.status_code in self.RETRYABLE_STATUS_CODES and attempt < self.max_retries:
                time.sleep(self.retry_delay * (attempt + 1))
                continue
            if response.status_code >= 400:
                message = self._extract_error_message(response)
                if response.status_code in (401, 403):
                    raise CodexAuthError(f"Codex auth failed: {response.status_code} {message}")
                raise CodexProviderError(f"Codex request failed: {response.status_code} {message}")
            return response
        if last_error:
            raise CodexProviderError(f"Codex request failed: {last_error}") from last_error
        raise CodexProviderError("Codex request failed without a response")

    def list_models(self) -> list[dict[str, Any]]:
        response = self._request(
            "GET",
            "/models",
            params={"client_version": self.client_version},
            timeout=30,
        )
        models = []
        for model in response.json().get("models", []):
            if not model.get("supported_in_api"):
                continue
            models.append(
                {
                    "id": model.get("slug"),
                    "label": model.get("display_name") or model.get("slug"),
                    "description": model.get("description"),
                    "visibility": model.get("visibility", "list"),
                    "priority": model.get("priority", 9999),
                    "defaultReasoningLevel": model.get("default_reasoning_level"),
                    "reasoningLevels": [
                        level.get("effort")
                        for level in model.get("supported_reasoning_levels", [])
                        if level.get("effort")
                    ],
                }
            )
        models.sort(key=lambda item: (item.get("visibility") != "list", item.get("priority", 9999), item.get("label") or ""))
        return models

    def generate(
        self,
        messages: list[dict[str, str]],
        *,
        model: str | None = None,
        max_tokens: int = 4000,
        reasoning_effort: str | None = None,
        timeout: int = 180,
    ) -> dict[str, Any]:
        used_model = model or self.model
        payload = self._messages_to_payload(messages, used_model, max_tokens, reasoning_effort)
        response = self._request("POST", "/responses", json_body=payload, timeout=timeout, stream=True)
        return self._parse_sse_response(response, used_model)

    def _messages_to_payload(
        self,
        messages: list[dict[str, str]],
        model: str,
        max_tokens: int,
        reasoning_effort: str | None,
    ) -> dict[str, Any]:
        instructions: list[str] = []
        normalized: list[dict[str, Any]] = []
        for message in messages:
            role = message.get("role", "user")
            content = message.get("content", "")
            if role == "system":
                if content:
                    instructions.append(content)
                continue
            normalized.append({"role": role, "content": [{"type": "input_text", "text": content}]})
        if not normalized:
            normalized.append({"role": "user", "content": [{"type": "input_text", "text": ""}]})
        payload: dict[str, Any] = {
            "model": model,
            "instructions": "\n\n".join(instructions) or "You are a helpful assistant.",
            "input": normalized,
            "parallel_tool_calls": False,
            "store": False,
            "stream": True,
        }
        verbosity = self._default_verbosity_for_max_tokens(max_tokens)
        if verbosity:
            payload["text"] = {"verbosity": verbosity}
        if reasoning_effort:
            payload["reasoning"] = {"effort": reasoning_effort}
        return payload

    @staticmethod
    def _default_verbosity_for_max_tokens(max_tokens: int) -> str | None:
        if max_tokens <= 1200:
            return "low"
        if max_tokens <= 2500:
            return "medium"
        return None

    def _parse_sse_response(self, response: requests.Response, model: str) -> dict[str, Any]:
        text_parts: list[str] = []
        completed: dict[str, Any] = {}
        for raw_line in response.iter_lines(decode_unicode=True):
            if isinstance(raw_line, bytes):
                raw_line = raw_line.decode("utf-8", errors="ignore")
            if not raw_line or not raw_line.startswith("data: "):
                continue
            try:
                event = json.loads(raw_line[6:])
            except json.JSONDecodeError:
                continue
            event_type = event.get("type")
            if event_type == "response.output_text.delta":
                text_parts.append(event.get("delta", ""))
            elif event_type == "response.output_text.done" and not text_parts:
                text_parts.append(event.get("text", ""))
            elif event_type == "response.completed":
                completed = event.get("response", {}) or {}
            elif event_type == "response.failed":
                error = (event.get("response") or {}).get("error") or {}
                raise CodexProviderError(error.get("message") or "Codex response failed")
        content = "".join(text_parts).strip()
        if not content:
            raise CodexProviderError("Codex returned an empty response body")
        usage = completed.get("usage") or {}
        return {
            "id": completed.get("id", f"codex-{int(time.time())}"),
            "model": completed.get("model", model),
            "content": content,
            "usage": {
                "promptTokens": usage.get("input_tokens"),
                "completionTokens": usage.get("output_tokens"),
                "totalTokens": usage.get("total_tokens"),
            },
        }
