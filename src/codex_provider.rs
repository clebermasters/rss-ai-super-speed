use crate::{Result, RssAiError};
use reqwest::{Client, Response, StatusCode};
use serde_json::{json, Value};
use std::{env, path::PathBuf, process::Command, time::Duration};

const BASE_URL: &str = "https://chatgpt.com/backend-api/codex";
const REFRESH_URL: &str = "https://auth.openai.com/oauth/token";
const CLIENT_ID: &str = "app_EMoamEEZ73f0CkXaXp7hrann";
const DEFAULT_CLIENT_VERSION: &str = "0.118.0";
const RETRYABLE_STATUS_CODES: &[StatusCode] = &[
    StatusCode::TOO_MANY_REQUESTS,
    StatusCode::INTERNAL_SERVER_ERROR,
    StatusCode::BAD_GATEWAY,
    StatusCode::SERVICE_UNAVAILABLE,
    StatusCode::GATEWAY_TIMEOUT,
];

#[derive(Clone)]
pub struct CodexSubscriptionProvider {
    client: Client,
    auth_path: PathBuf,
    base_url: String,
    model: String,
    max_retries: usize,
    retry_delay: Duration,
}

impl CodexSubscriptionProvider {
    pub fn new(model: String) -> Self {
        Self {
            client: Client::new(),
            auth_path: auth_path(),
            base_url: env::var("OPENAI_CODEX_BASE_URL")
                .unwrap_or_else(|_| BASE_URL.to_string())
                .trim_end_matches('/')
                .to_string(),
            model,
            max_retries: parse_env("OPENAI_CODEX_MAX_RETRIES", 2),
            retry_delay: Duration::from_secs_f64(parse_env("OPENAI_CODEX_RETRY_DELAY", 2.0)),
        }
    }

    pub fn configured() -> bool {
        let path = auth_path();
        path.exists()
            && std::fs::read_to_string(path)
                .ok()
                .and_then(|content| serde_json::from_str::<Value>(&content).ok())
                .and_then(|payload| {
                    payload
                        .get("tokens")
                        .and_then(|tokens| tokens.get("access_token"))
                        .and_then(Value::as_str)
                        .map(|token| !token.is_empty())
                })
                .unwrap_or(false)
    }

    pub async fn generate(
        &self,
        messages: Vec<ChatMessage>,
        max_tokens: usize,
        reasoning_effort: Option<String>,
    ) -> Result<String> {
        let payload = self.messages_to_payload(messages, max_tokens, reasoning_effort);
        let response = self
            .request_with_retries("POST", "/responses", Some(payload), 180)
            .await?;
        let body = response.text().await?;
        parse_sse_response(&body)
    }

    fn messages_to_payload(
        &self,
        messages: Vec<ChatMessage>,
        max_tokens: usize,
        reasoning_effort: Option<String>,
    ) -> Value {
        let mut instructions = Vec::new();
        let mut input = Vec::new();

        for message in messages {
            if message.role == "system" {
                if !message.content.is_empty() {
                    instructions.push(message.content);
                }
                continue;
            }

            input.push(json!({
                "role": message.role,
                "content": [{"type": "input_text", "text": message.content}],
            }));
        }

        if input.is_empty() {
            input.push(json!({
                "role": "user",
                "content": [{"type": "input_text", "text": ""}],
            }));
        }

        let mut payload = json!({
            "model": self.model,
            "instructions": if instructions.is_empty() {
                "You are a helpful assistant.".to_string()
            } else {
                instructions.join("\n\n")
            },
            "input": input,
            "parallel_tool_calls": false,
            "store": false,
            "stream": true,
        });

        if let Some(verbosity) = default_verbosity_for_max_tokens(max_tokens) {
            payload["text"] = json!({ "verbosity": verbosity });
        }

        if let Some(effort) = reasoning_effort.filter(|value| !value.is_empty()) {
            payload["reasoning"] = json!({ "effort": effort });
        }

        payload
    }

    async fn request_with_retries(
        &self,
        method: &str,
        path: &str,
        json_body: Option<Value>,
        timeout_secs: u64,
    ) -> Result<Response> {
        let mut auth_payload = self.load_auth_payload()?;
        let mut last_error = None;

        for attempt in 0..=self.max_retries {
            let response = self
                .send_request(method, path, json_body.clone(), &auth_payload, timeout_secs)
                .await;

            let response = match response {
                Ok(response) => response,
                Err(err) => {
                    last_error = Some(err.to_string());
                    if attempt < self.max_retries {
                        self.sleep_before_retry(attempt).await;
                        continue;
                    }
                    return Err(err.into());
                }
            };

            if response.status() == StatusCode::UNAUTHORIZED && attempt < self.max_retries {
                self.refresh_access_token(&mut auth_payload).await?;
                auth_payload = self.load_auth_payload()?;
                continue;
            }

            if RETRYABLE_STATUS_CODES.contains(&response.status()) && attempt < self.max_retries {
                self.sleep_before_retry(attempt).await;
                continue;
            }

            if response.status().is_client_error() || response.status().is_server_error() {
                let status = response.status();
                let message = extract_error_message_from_response(response).await;
                return Err(RssAiError::AiApi(format!(
                    "Codex request failed: {} {}",
                    status, message
                )));
            }

            return Ok(response);
        }

        Err(RssAiError::AiApi(format!(
            "Codex request failed{}",
            last_error
                .map(|err| format!(": {}", err))
                .unwrap_or_else(|| " without a response".to_string())
        )))
    }

    async fn send_request(
        &self,
        method: &str,
        path: &str,
        json_body: Option<Value>,
        auth_payload: &Value,
        timeout_secs: u64,
    ) -> reqwest::Result<Response> {
        let token = auth_payload
            .get("tokens")
            .and_then(|tokens| tokens.get("access_token"))
            .and_then(Value::as_str)
            .unwrap_or("");

        let request = match method {
            "POST" => self.client.post(format!("{}{}", self.base_url, path)),
            "GET" => self.client.get(format!("{}{}", self.base_url, path)),
            _ => self.client.request(
                method.parse().unwrap_or(reqwest::Method::GET),
                format!("{}{}", self.base_url, path),
            ),
        }
        .bearer_auth(token)
        .header("Content-Type", "application/json")
        .timeout(Duration::from_secs(timeout_secs));

        if let Some(body) = json_body {
            request.json(&body).send().await
        } else {
            request.send().await
        }
    }

    async fn refresh_access_token(&self, auth_payload: &mut Value) -> Result<()> {
        let refresh_token = auth_payload
            .get("tokens")
            .and_then(|tokens| tokens.get("refresh_token"))
            .and_then(Value::as_str)
            .ok_or_else(|| {
                RssAiError::AiApi(
                    "Codex auth payload does not contain a refresh token. Run `codex login` again."
                        .to_string(),
                )
            })?;

        let response = self
            .client
            .post(REFRESH_URL)
            .header("Content-Type", "application/json")
            .timeout(Duration::from_secs(30))
            .json(&json!({
                "client_id": CLIENT_ID,
                "grant_type": "refresh_token",
                "refresh_token": refresh_token,
            }))
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let message = extract_error_message_from_response(response).await;
            return Err(RssAiError::AiApi(format!(
                "Failed to refresh Codex OAuth token: {} {}",
                status, message
            )));
        }

        let refreshed: Value = response.json().await?;
        let tokens = auth_payload
            .get_mut("tokens")
            .and_then(Value::as_object_mut)
            .ok_or_else(|| RssAiError::AiApi("Codex auth payload is missing tokens".to_string()))?;

        for key in ["id_token", "access_token", "refresh_token"] {
            if let Some(value) = refreshed.get(key).cloned().filter(|value| !value.is_null()) {
                tokens.insert(key.to_string(), value);
            }
        }

        auth_payload["last_refresh"] = Value::String(chrono::Utc::now().to_rfc3339());
        self.persist_auth_payload(auth_payload)
    }

    fn load_auth_payload(&self) -> Result<Value> {
        let content = std::fs::read_to_string(&self.auth_path).map_err(|err| {
            RssAiError::AiApi(format!(
                "Codex auth file not found at {}: {}. Run `codex login` first.",
                self.auth_path.display(),
                err
            ))
        })?;

        let payload: Value = serde_json::from_str(&content).map_err(|err| {
            RssAiError::AiApi(format!("Failed to parse Codex auth JSON: {}", err))
        })?;

        let has_access_token = payload
            .get("tokens")
            .and_then(|tokens| tokens.get("access_token"))
            .and_then(Value::as_str)
            .map(|token| !token.is_empty())
            .unwrap_or(false);

        if !has_access_token {
            return Err(RssAiError::AiApi(
                "Codex auth file does not contain tokens.access_token".to_string(),
            ));
        }

        Ok(payload)
    }

    fn persist_auth_payload(&self, payload: &Value) -> Result<()> {
        let content = serde_json::to_string_pretty(payload).map_err(|err| {
            RssAiError::AiApi(format!("Failed to serialize Codex auth JSON: {}", err))
        })?;
        std::fs::write(&self.auth_path, content)?;
        Ok(())
    }

    async fn sleep_before_retry(&self, attempt: usize) {
        tokio::time::sleep(self.retry_delay.mul_f64((attempt + 1) as f64)).await;
    }
}

#[derive(Clone)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
}

pub fn default_codex_model() -> String {
    env::var("OPENAI_CODEX_MODEL").unwrap_or_else(|_| "gpt-5.4".to_string())
}

pub fn detect_client_version() -> String {
    env::var("OPENAI_CODEX_CLIENT_VERSION").unwrap_or_else(|_| {
        Command::new("codex")
            .arg("--version")
            .output()
            .ok()
            .and_then(|output| {
                let stdout = String::from_utf8_lossy(&output.stdout);
                stdout.split_whitespace().nth(1).map(str::to_string)
            })
            .unwrap_or_else(|| DEFAULT_CLIENT_VERSION.to_string())
    })
}

fn auth_path() -> PathBuf {
    if let Ok(path) = env::var("OPENAI_CODEX_AUTH_PATH") {
        return expand_home(path);
    }

    env::var("HOME")
        .map(|home| PathBuf::from(home).join(".codex").join("auth.json"))
        .unwrap_or_else(|_| PathBuf::from(".codex/auth.json"))
}

fn expand_home(path: String) -> PathBuf {
    if let Some(rest) = path.strip_prefix("~/") {
        if let Ok(home) = env::var("HOME") {
            return PathBuf::from(home).join(rest);
        }
    }
    PathBuf::from(path)
}

fn parse_env<T>(key: &str, default: T) -> T
where
    T: std::str::FromStr,
{
    env::var(key)
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(default)
}

fn default_verbosity_for_max_tokens(max_tokens: usize) -> Option<&'static str> {
    if max_tokens <= 1200 {
        Some("low")
    } else if max_tokens <= 2500 {
        Some("medium")
    } else {
        None
    }
}

async fn extract_error_message_from_response(response: Response) -> String {
    let text = response
        .text()
        .await
        .unwrap_or_else(|_| "unknown error".to_string());
    extract_error_message(&text)
}

fn extract_error_message(text: &str) -> String {
    let Ok(payload) = serde_json::from_str::<Value>(text) else {
        return if text.is_empty() {
            "unknown error".to_string()
        } else {
            text.to_string()
        };
    };

    if let Some(detail) = payload.get("detail").and_then(Value::as_str) {
        return detail.to_string();
    }

    if let Some(error) = payload.get("error") {
        if let Some(message) = error.get("message").and_then(Value::as_str) {
            return message.to_string();
        }
        if let Some(code) = error.get("code").and_then(Value::as_str) {
            return code.to_string();
        }
        if let Some(message) = error.as_str() {
            return message.to_string();
        }
    }

    text.to_string()
}

fn parse_sse_response(body: &str) -> Result<String> {
    let mut text_parts = Vec::new();

    for line in body.lines() {
        let Some(data) = line.strip_prefix("data: ") else {
            continue;
        };
        if data == "[DONE]" {
            continue;
        }

        let Ok(event) = serde_json::from_str::<Value>(data) else {
            continue;
        };

        match event.get("type").and_then(Value::as_str) {
            Some("response.output_text.delta") => {
                if let Some(delta) = event.get("delta").and_then(Value::as_str) {
                    text_parts.push(delta.to_string());
                }
            }
            Some("response.output_text.done") if text_parts.is_empty() => {
                if let Some(text) = event.get("text").and_then(Value::as_str) {
                    text_parts.push(text.to_string());
                }
            }
            Some("response.failed") => {
                let message = event
                    .get("response")
                    .and_then(|response| response.get("error"))
                    .and_then(|error| error.get("message"))
                    .and_then(Value::as_str)
                    .unwrap_or("Codex response failed");
                return Err(RssAiError::AiApi(message.to_string()));
            }
            _ => {}
        }
    }

    let content = text_parts.join("").trim().to_string();
    if content.is_empty() {
        return Err(RssAiError::AiApi(
            "Codex returned an empty response body".to_string(),
        ));
    }

    Ok(content)
}
