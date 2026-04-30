from __future__ import annotations

import os
from typing import Any

import requests

from codex_provider import CodexSubscriptionProvider


class AiProviderError(RuntimeError):
    pass


def list_providers() -> list[dict[str, Any]]:
    return [
        {
            "id": "openai_compatible",
            "label": "OpenAI-compatible",
            "configured": bool(os.environ.get("OPENAI_API_KEY") or os.environ.get("MINIMAX_API_KEY")),
        },
        {
            "id": "codex_subscription",
            "label": "Codex subscription",
            "configured": CodexSubscriptionProvider().configured(),
        },
    ]


def list_models(provider: str) -> list[dict[str, Any]]:
    if provider == "codex_subscription":
        return CodexSubscriptionProvider().list_models()
    return [
        {"id": os.environ.get("AI_MODEL", "gpt-5.4"), "label": os.environ.get("AI_MODEL", "gpt-5.4")},
        {"id": "MiniMax-M2.5-highspeed", "label": "MiniMax M2.5 Highspeed"},
        {"id": "gpt-5.4", "label": "GPT 5.4"},
    ]


class OpenAiCompatibleProvider:
    def __init__(self, api_key: str | None = None, api_base: str | None = None, model: str | None = None) -> None:
        self.api_key = api_key or os.environ.get("OPENAI_API_KEY") or os.environ.get("MINIMAX_API_KEY", "")
        self.api_base = (api_base or os.environ.get("OPENAI_API_BASE") or "https://api.openai.com/v1").rstrip("/")
        self.model = model or os.environ.get("AI_MODEL", "gpt-5.4")
        if not self.api_key:
            raise AiProviderError("OpenAI-compatible provider is not configured")

    def generate(
        self,
        messages: list[dict[str, str]],
        *,
        model: str | None = None,
        max_tokens: int = 2048,
        temperature: float = 0.7,
        timeout: int = 120,
    ) -> dict[str, Any]:
        payload = {
            "model": model or self.model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        response = self._post_chat_completion(payload, timeout=timeout)
        body = response.json()
        return {
            "id": body.get("id"),
            "model": body.get("model", model or self.model),
            "content": body.get("choices", [{}])[0].get("message", {}).get("content", ""),
            "usage": body.get("usage", {}),
        }

    def _post_chat_completion(self, payload: dict[str, Any], *, timeout: int) -> requests.Response:
        for _ in range(3):
            response = requests.post(
                f"{self.api_base}/chat/completions",
                headers={"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"},
                json=payload,
                timeout=timeout,
            )
            if response.status_code < 400:
                return response
            if not self._adapt_chat_payload_after_error(payload, response):
                raise AiProviderError(f"OpenAI-compatible request failed: {response.status_code} {response.text}")
        raise AiProviderError(f"OpenAI-compatible request failed after compatibility retries: {response.status_code} {response.text}")

    @staticmethod
    def _adapt_chat_payload_after_error(payload: dict[str, Any], response: requests.Response) -> bool:
        message = response.text.lower()
        changed = False
        if "max_tokens" in payload and "max_completion_tokens" in message:
            payload["max_completion_tokens"] = payload.pop("max_tokens")
            changed = True
        if "temperature" in payload and "temperature" in message and "unsupported" in message:
            payload.pop("temperature", None)
            changed = True
        return changed

    def embedding(self, text: str, *, model: str | None = None, timeout: int = 60) -> dict[str, Any]:
        used_model = model or os.environ.get("EMBEDDING_MODEL", "text-embedding-3-small")
        response = requests.post(
            f"{self.api_base}/embeddings",
            headers={"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"},
            json={"model": used_model, "input": text},
            timeout=timeout,
        )
        if response.status_code >= 400:
            raise AiProviderError(f"Embedding request failed: {response.status_code} {response.text}")
        body = response.json()
        embedding = body.get("data", [{}])[0].get("embedding")
        if not embedding:
            raise AiProviderError("Embedding provider returned no vector")
        return {"model": used_model, "embedding": [float(value) for value in embedding], "usage": body.get("usage", {})}


def generate_completion(
    messages: list[dict[str, str]],
    settings: dict[str, Any] | None = None,
    overrides: dict[str, Any] | None = None,
) -> dict[str, Any]:
    settings = settings or {}
    overrides = overrides or {}
    provider = overrides.get("provider") or settings.get("llmProvider") or "openai_compatible"
    if provider == "codex_subscription":
        return CodexSubscriptionProvider(
            model=overrides.get("model") or settings.get("codexModel"),
            client_version=settings.get("codexClientVersion"),
        ).generate(
            messages,
            model=overrides.get("model") or settings.get("codexModel"),
            max_tokens=int(overrides.get("maxTokens") or 4000),
            reasoning_effort=overrides.get("reasoningEffort") or settings.get("codexReasoningEffort"),
        )
    return OpenAiCompatibleProvider(
        api_base=overrides.get("apiBase") or settings.get("aiApiBase"),
        model=overrides.get("model") or settings.get("aiModel"),
    ).generate(
        messages,
        model=overrides.get("model") or settings.get("aiModel"),
        max_tokens=int(overrides.get("maxTokens") or 2048),
        temperature=float(overrides.get("temperature") or 0.7),
    )


def summarize_articles(articles: list[dict[str, Any]], settings: dict[str, Any], overrides: dict[str, Any] | None = None) -> str:
    prompt = (overrides or {}).get("prompt") or "Summarize these RSS articles concisely and highlight why they matter."
    article_text = "\n\n".join(
        f"Title: {article.get('title')}\nSource: {article.get('source')}\nLink: {article.get('link')}\nSummary: {article.get('summary') or article.get('contentPreview') or ''}"
        for article in articles
    )
    result = generate_completion(
        [
            {"role": "system", "content": "You summarize RSS/news articles for a personal reader."},
            {"role": "user", "content": f"{prompt}\n\n{article_text}"},
        ],
        settings,
        overrides,
    )
    return result["content"]


def generate_embedding(text: str, settings: dict[str, Any] | None = None, overrides: dict[str, Any] | None = None) -> dict[str, Any]:
    settings = settings or {}
    overrides = overrides or {}
    provider = overrides.get("embeddingProvider") or settings.get("embeddingProvider") or "openai_compatible"
    if provider == "codex_subscription":
        raise AiProviderError("Codex subscription does not expose an embeddings endpoint")
    return OpenAiCompatibleProvider(
        api_base=overrides.get("apiBase") or settings.get("aiApiBase"),
    ).embedding(
        text,
        model=overrides.get("embeddingModel") or settings.get("embeddingModel") or os.environ.get("EMBEDDING_MODEL"),
    )
