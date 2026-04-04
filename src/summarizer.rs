use crate::{AiConfig, Article, RssAiError, Result};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::env;

pub struct AiSummarizer {
    config: AiConfig,
    client: Client,
}

#[derive(Debug, Serialize, Deserialize)]
struct MiniMaxRequest {
    model: String,
    messages: Vec<Message>,
    temperature: f32,
    #[serde(skip_serializing_if = "Option::is_none")]
    max_tokens: Option<usize>,
}

#[derive(Debug, Serialize, Deserialize)]
struct Message {
    role: String,
    content: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct MiniMaxResponse {
    #[serde(rename = "base_resp")]
    base_resp: BaseResponse,
    choices: Option<Vec<Choice>>,
}

#[derive(Debug, Serialize, Deserialize)]
struct BaseResponse {
    #[serde(rename = "status_code")]
    status_code: i32,
    #[serde(rename = "status_msg")]
    status_msg: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct Choice {
    message: ResponseMessage,
}

#[derive(Debug, Serialize, Deserialize)]
struct ResponseMessage {
    content: String,
}

impl AiSummarizer {
    pub fn new(config: AiConfig) -> Self {
        Self {
            config,
            client: Client::new(),
        }
    }
    
    pub async fn summarize(&self, articles: &[Article]) -> Result<String> {
        if !self.config.enabled {
            return Ok("AI summarization disabled".to_string());
        }
        
        let api_key = env::var("MINIMAX_API_KEY")
            .expect("MINIMAX_API_KEY must be set");
        
        let prompt = self.build_prompt(articles);
        
        let request = MiniMaxRequest {
            model: self.config.model.clone(),
            messages: vec![Message {
                role: "user".to_string(),
                content: prompt,
            }],
            temperature: self.config.temperature,
            max_tokens: Some(self.config.max_tokens),
        };
        
        let response = self.client
            .post("https://api.minimax.io/v1/text/chatcompletion_v2")
            .header("Authorization", format!("Bearer {}", api_key))
            .header("Content-Type", "application/json")
            .json(&request)
            .send()
            .await?;
        
        let result: MiniMaxResponse = response.json().await?;
        
        if result.base_resp.status_code != 0 {
            return Err(RssAiError::AiApi(result.base_resp.status_msg));
        }
        
        let summary = result
            .choices
            .and_then(|c| c.into_iter().next())
            .map(|c| c.message.content)
            .unwrap_or_default();
        
        Ok(summary)
    }
    
    pub async fn summarize_with_custom_prompt(&self, articles: &[Article], custom_prompt: &str) -> Result<String> {
        if !self.config.enabled {
            return Ok("AI summarization disabled".to_string());
        }
        
        let api_key = env::var("MINIMAX_API_KEY")
            .expect("MINIMAX_API_KEY must be set");
        
        let articles_text = self.format_articles(articles);
        
        let prompt = format!("{}\n\n{}", custom_prompt, articles_text);
        
        let request = MiniMaxRequest {
            model: self.config.model.clone(),
            messages: vec![Message {
                role: "user".to_string(),
                content: prompt,
            }],
            temperature: self.config.temperature,
            max_tokens: Some(self.config.max_tokens),
        };
        
        let response = self.client
            .post("https://api.minimax.io/v1/text/chatcompletion_v2")
            .header("Authorization", format!("Bearer {}", api_key))
            .header("Content-Type", "application/json")
            .json(&request)
            .send()
            .await?;
        
        let result: MiniMaxResponse = response.json().await?;
        
        if result.base_resp.status_code != 0 {
            return Err(RssAiError::AiApi(result.base_resp.status_msg));
        }
        
        let summary = result
            .choices
            .and_then(|c| c.into_iter().next())
            .map(|c| c.message.content)
            .unwrap_or_default();
        
        Ok(summary)
    }
    
    fn build_prompt(&self, articles: &[Article]) -> String {
        let default_prompt = r#"You are a tech news analyst. Analyze the following articles and provide:
1. A brief summary of the top 5 most important stories
2. Key themes and trends
3. Any surprising or notable items

Format your response in clear sections with markdown headers."#;
        
        let prompt = self.config.custom_prompt.as_deref().unwrap_or(default_prompt);
        let articles_text = self.format_articles(articles);
        
        format!("{}\n\n{}", prompt, articles_text)
    }
    
    fn format_articles(&self, articles: &[Article]) -> String {
        articles
            .iter()
            .enumerate()
            .map(|(i, a)| {
                let mut s = format!("## {}. {}", i + 1, a.title);
                s.push_str(&format!("\n**Source:** {}", a.source));
                if let Some(score) = a.score {
                    s.push_str(&format!(" | **Score:** {}", score));
                }
                if let Some(comments) = a.comments {
                    s.push_str(&format!(" | **Comments:** {}", comments));
                }
                s.push_str(&format!("\n**Link:** {}", a.link));
                if let Some(summary) = &a.summary {
                    // Strip HTML
                    let clean = summary
                        .replace("<p>", "")
                        .replace("</p>", "")
                        .replace("<a href=\"", "[")
                        .replace("\">", "](")
                        .replace("</a>", ")")
                        .replace("&nbsp;", " ")
                        .replace("&amp;", "&");
                    s.push_str(&format!("\n**Summary:** {}", clean));
                }
                s
            })
            .collect::<Vec<_>>()
            .join("\n\n")
    }
    
    /// Extract the main article body from raw fetched content and reformat
    /// it as clean, terminal-friendly Markdown. Returns `raw_content` unchanged
    /// when the API key is missing or the call fails, so callers never see an error.
    pub async fn format_article_content(&self, raw_content: &str) -> String {
        let api_key = match env::var("MINIMAX_API_KEY") {
            Ok(k) if !k.is_empty() => k,
            _ => return raw_content.to_string(),
        };

        // Cap to ~12 K chars to stay well inside token limits
        let content_preview: String = raw_content.chars().take(12_000).collect();

        let prompt = format!(
            "You are a news article formatter for a terminal RSS reader.\n\
             Given the raw extracted content below, do the following:\n\
             1. Extract ONLY the main article body — strip navigation links, ads,\n\
                cookie notices, footer text, unrelated link lists, and metadata clutter.\n\
             2. Reformat as clean Markdown optimised for terminal display:\n\
                - `#` for the article title if present\n\
                - `##` / `###` for major sections\n\
                - ``` fences for code blocks\n\
                - `-` for bullet lists\n\
                - `>` for blockquotes\n\
                - A blank line between paragraphs\n\
             3. Keep the FULL article content — do NOT summarise.\n\
             4. Start your reply directly with the Markdown — no preamble.\n\
             \n\
             Raw content:\n\
             ---\n\
             {}\n\
             ---",
            content_preview
        );

        let request = MiniMaxRequest {
            model: self.config.model.clone(),
            messages: vec![Message {
                role: "user".to_string(),
                content: prompt,
            }],
            temperature: 0.3, // low temp = consistent formatting
            max_tokens: Some(4096),
        };

        let response = match self
            .client
            .post("https://api.minimax.io/v1/text/chatcompletion_v2")
            .header("Authorization", format!("Bearer {}", api_key))
            .header("Content-Type", "application/json")
            .json(&request)
            .send()
            .await
        {
            Ok(r) => r,
            Err(_) => return raw_content.to_string(),
        };

        let result: MiniMaxResponse = match response.json().await {
            Ok(r) => r,
            Err(_) => return raw_content.to_string(),
        };

        if result.base_resp.status_code != 0 {
            return raw_content.to_string();
        }

        result
            .choices
            .and_then(|c| c.into_iter().next())
            .map(|c| c.message.content)
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| raw_content.to_string())
    }

    pub fn set_model(&mut self, model: String) {
        self.config.model = model;
    }
    
    pub fn set_temperature(&mut self, temperature: f32) {
        self.config.temperature = temperature;
    }
    
    pub fn enable(&mut self) {
        self.config.enabled = true;
    }
    
    pub fn disable(&mut self) {
        self.config.enabled = false;
    }
}

impl Default for AiSummarizer {
    fn default() -> Self {
        Self::new(AiConfig::default())
    }
}

pub async fn summarize_articles(articles: &[Article], config: &AiConfig) -> Result<String> {
    AiSummarizer::new(config.clone()).summarize(articles).await
}
