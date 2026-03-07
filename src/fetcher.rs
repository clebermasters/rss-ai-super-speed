use crate::{Article, RssAiError, Result};
use feed_rs::parser;
use reqwest::Client;
use std::time::Duration;

pub struct FeedFetcher {
    client: Client,
}

impl FeedFetcher {
    pub fn new() -> Self {
        let client = Client::builder()
            .timeout(Duration::from_secs(30))
            .user_agent("rss-ai/0.1.0")
            .build()
            .expect("Failed to create HTTP client");
        
        Self { client }
    }
    
    pub async fn fetch(&self, url: &str, limit: usize) -> Result<Vec<Article>> {
        let response = self.client.get(url).send().await?;
        let bytes = response.bytes().await?;
        
        let feed = parser::parse(&bytes[..])
            .map_err(|e| RssAiError::Parse(e.to_string()))?;
        
        let source_name = feed
            .title
            .as_ref()
            .map(|t| t.content.clone())
            .unwrap_or_else(|| "Unknown".to_string());
        
        let articles: Vec<Article> = feed
            .entries
            .iter()
            .take(limit)
            .map(|entry| self.parse_entry(entry, &source_name, url))
            .collect();
        
        Ok(articles)
    }
    
    pub async fn fetch_multiple(&self, feeds: &[(&str, &str, usize)]) -> Result<Vec<Article>> {
        let mut all_articles = Vec::new();
        
        for (name, url, limit) in feeds {
            match self.fetch(url, *limit).await {
                Ok(articles) => {
                    let articles: Vec<Article> = articles
                        .into_iter()
                        .map(|mut a| {
                            if a.source == "Unknown" {
                                a.source = name.to_string();
                            }
                            a
                        })
                        .collect();
                    all_articles.extend(articles);
                }
                Err(e) => {
                    eprintln!("Warning: Failed to fetch {}: {}", url, e);
                }
            }
        }
        
        // Sort by published date (newest first)
        all_articles.sort_by(|a, b| {
            match (&a.published, &b.published) {
                (Some(ta), Some(tb)) => tb.cmp(ta),
                (Some(_), None) => std::cmp::Ordering::Less,
                (None, Some(_)) => std::cmp::Ordering::Greater,
                (None, None) => std::cmp::Ordering::Equal,
            }
        });
        
        Ok(all_articles)
    }
    
    fn parse_entry(&self, entry: &feed_rs::model::Entry, source: &str, source_url: &str) -> Article {
        let title = entry
            .title
            .as_ref()
            .map(|t| t.content.clone())
            .unwrap_or_else(|| "No title".to_string());
        
        let link = entry
            .links
            .first()
            .map(|l| l.href.clone())
            .unwrap_or_else(|| "No link".to_string());
        
        let summary = entry
            .summary
            .as_ref()
            .map(|s| s.content.clone())
            .or_else(|| {
                entry
                    .content
                    .as_ref()
                    .and_then(|c| c.body.clone())
            });
        
        let published = entry
            .published
            .map(|t| t.to_string())
            .or_else(|| entry.updated.map(|t| t.to_string()));
        
        // Extract score and comments from Hacker News
        let (score, comments) = self.extract_hn_metrics(&summary);
        
        Article {
            id: uuid_from_string(&link),
            title,
            link,
            summary,
            content: None,
            published,
            source: source.to_string(),
            source_url: source_url.to_string(),
            score,
            comments,
            tags: Vec::new(),
        }
    }
    
    fn extract_hn_metrics(&self, summary: &Option<String>) -> (Option<i32>, Option<i32>) {
        let summary = match summary {
            Some(s) => s,
            None => return (None, None),
        };
        
        let mut score = None;
        let mut comments = None;
        
        if let Some(pos) = summary.find("Points: ") {
            let rest = &summary[pos + 8..];
            if let Some(end) = rest.find('<') {
                if let Ok(n) = rest[..end].trim().parse::<i32>() {
                    score = Some(n);
                }
            }
        }
        
        if let Some(pos) = summary.find("# Comments: ") {
            let rest = &summary[pos + 11..];
            if let Some(end) = rest.find('<') {
                if let Ok(n) = rest[..end].trim().parse::<i32>() {
                    comments = Some(n);
                }
            }
        }
        
        (score, comments)
    }
}

impl Default for FeedFetcher {
    fn default() -> Self {
        Self::new()
    }
}

fn uuid_from_string(s: &str) -> String {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    let mut hasher = DefaultHasher::new();
    s.hash(&mut hasher);
    format!("{:016x}", hasher.finish())
}
