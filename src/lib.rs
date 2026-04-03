use serde::{Deserialize, Serialize};
use thiserror::Error;

pub mod fetcher;
pub mod fetcher_html;
pub mod filter;
pub mod search;
pub mod summarizer;
pub mod output;
pub mod database;
pub mod vector;
#[cfg(feature = "tui")]
pub mod tui;

/// Load a `.env` file by searching common locations, then set env vars.
/// Search order: cwd/.env → executable-dir/.env → ~/.rss-ai/.env
/// Lines starting with `#` are skipped. Already-set vars are NOT overwritten.
pub fn load_env() {
    let candidates: Vec<std::path::PathBuf> = {
        let mut v = Vec::new();

        // 1. Current working directory
        if let Ok(cwd) = std::env::current_dir() {
            v.push(cwd.join(".env"));
        }

        // 2. Directory of the running executable
        if let Ok(exe) = std::env::current_exe() {
            if let Some(dir) = exe.parent() {
                v.push(dir.join(".env"));
            }
        }

        // 3. ~/.rss-ai/.env
        if let Ok(home) = std::env::var("HOME") {
            v.push(std::path::PathBuf::from(home).join(".rss-ai").join(".env"));
        }

        v
    };

    for path in candidates {
        if let Ok(content) = std::fs::read_to_string(&path) {
            for line in content.lines() {
                let line = line.trim();
                if line.is_empty() || line.starts_with('#') {
                    continue;
                }
                if let Some((key, value)) = line.split_once('=') {
                    let key = key.trim();
                    let value = value.trim().trim_matches('"').trim_matches('\'');
                    // Don't overwrite already-set env vars
                    if std::env::var(key).is_err() {
                        std::env::set_var(key, value);
                    }
                }
            }
            // Stop at first .env found
            break;
        }
    }
}

// Re-export types for convenience
pub use fetcher::FeedFetcher;
pub use fetcher_html::{ArticleFetcher, ArticleContent};
pub use filter::ArticleFilter;
pub use search::ArticleSearch;
pub use summarizer::AiSummarizer;
pub use output::OutputFormatter;
pub use database::{Database, ArticleWithState, Feed};
pub use vector::VectorStore;

#[derive(Error, Debug)]
pub enum RssAiError {
    #[error("Network error: {0}")]
    Network(#[from] reqwest::Error),
    
    #[error("Parse error: {0}")]
    Parse(String),
    
    #[error("AI API error: {0}")]
    AiApi(String),
    
    #[error("Config error: {0}")]
    Config(String),
    
    #[error("Database error: {0}")]
    Database(#[from] rusqlite::Error),
    
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}

pub type Result<T> = std::result::Result<T, RssAiError>;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Article {
    pub id: String,
    pub title: String,
    pub link: String,
    pub summary: Option<String>,
    pub content: Option<String>,
    pub published: Option<String>,
    pub source: String,
    pub source_url: String,
    pub score: Option<i32>,
    pub comments: Option<i32>,
    pub tags: Vec<String>,
}

impl Article {
    pub fn new(
        title: String,
        link: String,
        source: String,
        source_url: String,
    ) -> Self {
        Self {
            id: uuid_v4(&link),
            title,
            link,
            summary: None,
            content: None,
            published: None,
            source,
            source_url,
            score: None,
            comments: None,
            tags: Vec::new(),
        }
    }
    
    pub fn text_for_search(&self) -> String {
        format!(
            "{} {} {} {}",
            self.title,
            self.source,
            self.summary.as_deref().unwrap_or(""),
            self.tags.join(" ")
        )
    }
}

fn uuid_v4(s: &str) -> String {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    let mut hasher = DefaultHasher::new();
    s.hash(&mut hasher);
    format!("{:016x}-{:04x}-{:04x}-{:04x}-{:012x}",
        hasher.finish() & 0xffffffff,
        (hasher.finish() >> 32) & 0xffff,
        (hasher.finish() >> 48) & 0xfff | 0x4000,
        (hasher.finish() >> 60) & 0x3fff | 0x8000,
        hasher.finish() & 0xffffffffffff
    )
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Config {
    pub feeds: Vec<FeedConfig>,
    pub filters: FilterConfig,
    pub search: SearchConfig,
    pub ai: AiConfig,
    pub output: OutputConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeedConfig {
    pub name: String,
    pub url: String,
    pub enabled: bool,
    pub tags: Vec<String>,
    pub limit: Option<usize>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct FilterConfig {
    pub min_score: Option<i32>,
    pub keywords_include: Vec<String>,
    pub keywords_exclude: Vec<String>,
    pub time_range_hours: Option<i64>,
    pub sources: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SearchConfig {
    pub fuzzy: bool,
    pub case_sensitive: bool,
    pub max_results: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AiConfig {
    pub enabled: bool,
    pub model: String,
    pub temperature: f32,
    pub max_tokens: usize,
    pub custom_prompt: Option<String>,
}

impl Default for AiConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            model: "MiniMax-M2.5-highspeed".to_string(),
            temperature: 0.7,
            max_tokens: 2048,
            custom_prompt: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct OutputConfig {
    pub format: OutputFormat,
    pub pretty: bool,
    pub include_summary: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub enum OutputFormat {
    #[default]
    Json,
    Markdown,
    Html,
    Csv,
}

impl Config {
    pub fn from_file(path: &str) -> Result<Self> {
        let content = std::fs::read_to_string(path)?;
        let config: Config = serde_yaml::from_str(&content)
            .map_err(|e| RssAiError::Config(e.to_string()))?;
        Ok(config)
    }
    
    pub fn default_feeds() -> Self {
        Self {
            feeds: vec![
                FeedConfig {
                    name: "Hacker News".to_string(),
                    url: "https://hnrss.org/best".to_string(),
                    enabled: true,
                    tags: vec!["tech".to_string(), "hackernews".to_string()],
                    limit: Some(20),
                },
                FeedConfig {
                    name: "TechCrunch".to_string(),
                    url: "https://techcrunch.com/feed/".to_string(),
                    enabled: true,
                    tags: vec!["tech".to_string(), "startup".to_string()],
                    limit: Some(10),
                },
                FeedConfig {
                    name: "VentureBeat".to_string(),
                    url: "https://venturebeat.com/feed/".to_string(),
                    enabled: true,
                    tags: vec!["ai".to_string(), "business".to_string()],
                    limit: Some(10),
                },
            ],
            filters: FilterConfig::default(),
            search: SearchConfig::default(),
            ai: AiConfig::default(),
            output: OutputConfig::default(),
        }
    }
}

pub fn get_default_config() -> Config {
    Config::default_feeds()
}
