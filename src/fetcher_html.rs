use reqwest::Client;
use soup::prelude::*;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum FetchError {
    #[error("HTTP error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("Parse error: {0}")]
    Parse(String),
}

pub struct ArticleFetcher {
    client: Client,
}

impl ArticleFetcher {
    pub fn new() -> Self {
        let client = Client::builder()
            .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
            .unwrap_or_else(|_| Client::new());
        
        Self { client }
    }

    pub async fn fetch_article(&self, url: &str) -> Result<String, FetchError> {
        let response = self.client.get(url).send().await?;
        let html = response.text().await?;

        let soup = Soup::new(&html);

        // Try to find article content
        let text = if let Some(article) = soup.tag("article").find() {
            article.text()
        } else if let Some(main) = soup.tag("main").find() {
            main.text()
        } else if let Some(div) = soup.tag("div").find() {
            div.text()
        } else {
            soup.text()
        };

        // Clean up the text
        let lines: Vec<&str> = text.lines()
            .map(|l| l.trim())
            .filter(|l| !l.is_empty())
            .collect();
        
        let cleaned = lines.join("\n");
        Ok(cleaned)
    }

    pub async fn fetch_and_summarize(&self, url: &str, api_key: &str) -> Result<ArticleContent, FetchError> {
        // First get the content
        let content = self.fetch_article(url).await?;

        // Now use MiniMax to clean/extract
        let client = reqwest::Client::new();
        
        let prompt = format!(r#"Extract the main article title and content from this web page content. 

Page content:
{}

Return as JSON with:
- "title": The article headline/title  
- "content": The main article paragraphs (not navigation, ads, etc)"#, 
            &content[..content.len().min(5000)]
        );

        let payload = serde_json::json!({
            "model": "MiniMax-M2.5",
            "messages": [
                {"role": "system", "content": "You extract article content from web pages. Return valid JSON only."},
                {"role": "user", "content": prompt}
            ],
            "temperature": 0.3,
            "max_tokens": 8000
        });

        let response = client
            .post("https://api.minimax.io/v1/text/chatcompletion_v2")
            .header("Authorization", format!("Bearer {}", api_key))
            .header("Content-Type", "application/json")
            .json(&payload)
            .send()
            .await
            .map_err(|e| FetchError::Parse(e.to_string()))?;

        let result: serde_json::Value = response.json().await
            .map_err(|e| FetchError::Parse(e.to_string()))?;

        let content_str = result
            .get("choices")
            .and_then(|c| c.as_array())
            .and_then(|a| a.first())
            .and_then(|c| c.get("message"))
            .and_then(|m| m.get("content"))
            .and_then(|c| c.as_str())
            .unwrap_or(&content)
            .to_string();

        // Try to parse JSON - handle markdown code blocks
        let json_str = content_str
            .trim_start_matches("```json")
            .trim_start_matches("```")
            .trim_end_matches("```")
            .trim();

        // Try direct JSON parse first
        let parsed: Result<serde_json::Value, _> = serde_json::from_str(json_str);
        
        let (title, article_content) = match parsed {
            Ok(json) => (
                json.get("title").and_then(|v| v.as_str()).unwrap_or("Extracted Article").to_string(),
                json.get("content").and_then(|v| v.as_str()).unwrap_or(&content).to_string(),
            ),
            Err(_) => {
                // Try field extraction
                let title = extract_json_field(&content_str, "title")
                    .unwrap_or_else(|| "Extracted Article".to_string());
                let article_content = extract_json_field(&content_str, "content")
                    .unwrap_or(content);
                (title, article_content)
            }
        };

        Ok(ArticleContent {
            title,
            content: article_content,
            url: url.to_string(),
        })
    }
}

fn extract_json_field(json_str: &str, field: &str) -> Option<String> {
    // Try to find "field": "value" pattern
    let pattern = format!("\"{}\":", field);
    if let Some(pos) = json_str.find(&pattern) {
        let rest = &json_str[pos..];
        // Find the opening quote
        if let Some(quote_start) = rest.find('"') {
            let after_quote = &rest[quote_start + 1..];
            // Find the closing quote (not escaped)
            let mut end = 0;
            let mut escaped = false;
            for (i, c) in after_quote.chars().enumerate() {
                if escaped {
                    escaped = false;
                } else if c == '\\' {
                    escaped = true;
                } else if c == '"' {
                    end = i;
                    break;
                }
            }
            if end > 0 {
                return Some(after_quote[..end].to_string());
            }
        }
    }
    None
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct ArticleContent {
    pub title: String,
    pub content: String,
    pub url: String,
}

impl std::fmt::Display for ArticleContent {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "# {}\n\n**Source:** {}\n\n---\n\n{}", self.title, self.url, self.content)
    }
}
