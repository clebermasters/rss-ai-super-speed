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
            .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .timeout(std::time::Duration::from_secs(15))
            .build()
            .unwrap_or_else(|_| Client::new());

        Self { client }
    }

    /// Strip clearly noisy elements before markdown conversion.
    /// NOTE: `header` is intentionally excluded — many sites wrap article
    /// headlines in `<header class="article-header">` which we want to keep.
    fn preprocess_html(html: &str) -> String {
        let noise_tags = [
            "script", "style", "nav", "footer", "aside",
            "menu", "noscript", "iframe",
        ];

        noise_tags.iter().fold(html.to_string(), |acc, tag| {
            let pattern = format!(r"(?is)<{tag}(?:\s[^>]*)?>.*?</{tag}>", tag = tag);
            if let Ok(re) = regex::Regex::new(&pattern) {
                re.replace_all(&acc, " ").into_owned()
            } else {
                acc
            }
        })
    }

    /// Convert HTML to Markdown.
    /// Strategy: preprocess → htmd; on failure fall back to soup text.
    /// For very large HTML (>500 KB) we pre-extract article/main first to
    /// avoid htmd OOM or timeout on megabyte-scale pages.
    pub fn html_to_markdown(html: &str) -> String {
        // For oversized pages, extract the core content section first
        let working_html: String = if html.len() > 500_000 {
            Self::extract_core_html(html).unwrap_or_else(|| html.to_string())
        } else {
            html.to_string()
        };

        let cleaned = Self::preprocess_html(&working_html);

        let md = match htmd::convert(&cleaned) {
            Ok(m) if !m.trim().is_empty() => m,
            _ => {
                // Fallback: soup text extraction with paragraph-break heuristic
                let soup = Soup::new(&working_html);
                let text = if let Some(article) = soup.tag("article").find() {
                    article.text()
                } else if let Some(main) = soup.tag("main").find() {
                    main.text()
                } else {
                    soup.text()
                };
                // Re-join as paragraphs
                text.lines()
                    .map(|l| l.trim())
                    .filter(|l| !l.is_empty())
                    .collect::<Vec<_>>()
                    .join("\n\n")
            }
        };

        // Collapse excessive blank lines (max 2 consecutive)
        let mut result = String::new();
        let mut blank_count = 0usize;
        for line in md.lines() {
            if line.trim().is_empty() {
                blank_count += 1;
                if blank_count <= 2 {
                    result.push('\n');
                }
            } else {
                blank_count = 0;
                result.push_str(line);
                result.push('\n');
            }
        }
        result.trim().to_string()
    }

    /// Extract the most content-rich block from HTML.
    ///
    /// Priority order:
    /// 1. `<article>` — semantic article tag
    /// 2. `<main>` — main content area
    /// 3. `<section>` with substantial text (≥ 500 chars inside)
    /// 4. `<div class="...content...">` / `post-body` / `article-body` patterns
    /// 5. Full HTML as fallback
    fn extract_core_html(html: &str) -> Option<String> {
        // Try semantic tags first
        for tag in &["article", "main"] {
            let open = format!("<{}", tag);
            let close = format!("</{}>", tag);
            if let Some(start) = html.find(&open) {
                if let Some(rel_end) = html[start..].rfind(&close) {
                    let end = start + rel_end + close.len();
                    let candidate = &html[start..end];
                    if candidate.len() > 200 {
                        return Some(candidate.to_string());
                    }
                }
            }
        }

        // Try to find a content-class div or section
        let content_patterns = [
            "post-content", "article-content", "article-body",
            "entry-content", "story-body", "post-body",
            "main-content", "page-content", "content-body",
        ];

        for pat in &content_patterns {
            // Match <tag class="...{pat}..."> or <tag id="...{pat}...">
            let re_str = format!(r#"(?is)<(?:div|section|article)[^>]+(?:class|id)="[^"]*{pat}[^"]*"[^>]*>(.*?)</(?:div|section|article)>"#);
            if let Ok(re) = regex::Regex::new(&re_str) {
                if let Some(cap) = re.captures(html) {
                    if cap[0].len() > 300 {
                        return Some(cap[0].to_string());
                    }
                }
            }
        }

        None
    }

    /// Fetch raw HTML from URL.
    /// Note: Accept-Encoding is intentionally omitted — reqwest handles
    /// gzip/deflate decompression automatically when those features are enabled.
    async fn fetch_html_raw(&self, url: &str) -> Result<String, FetchError> {
        let response = self.client
            .get(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .send()
            .await?;
        let html = response.text().await?;
        Ok(html)
    }

    /// Returns true if text looks like a JS-only redirect / challenge page
    /// rather than real article content.
    fn is_trivial_content(text: &str) -> bool {
        if text.len() < 400 {
            return true;
        }
        let lower = text.to_lowercase();
        lower.contains("window.location")
            || lower.contains("const target =")
            || lower.contains("document.write")
            || lower.contains("please enable javascript")
            || lower.contains("enable js and disable any ad blocker")
    }

    /// Fetch article content as Markdown, trying multiple bypass strategies:
    /// 1. Direct HTTP fetch
    /// 2. agent-browser (vercel-labs/agent-browser)
    /// 3. Wayback Machine archive
    pub async fn fetch_with_fallbacks(&self, url: &str) -> Result<String, FetchError> {
        // Strategy 1: Direct HTTP
        match self.fetch_html_raw(url).await {
            Ok(html) if !Self::is_blocked(&html) => {
                let md = Self::html_to_markdown(&html);
                if !md.trim().is_empty() && !Self::is_trivial_content(&md) {
                    return Ok(md);
                }
                // Content too short or JS-only — fall through to browser
            }
            Ok(_) => {} // blocked — fall through
            Err(_) => {} // network error — fall through
        }

        // Strategy 2: agent-browser (vercel-labs/agent-browser)
        match Self::fetch_via_agent_browser(url).await {
            Ok(content) => return Ok(content),
            Err(_) => {} // not available or failed — fall through
        }

        // Strategy 3: Wayback Machine
        match Self::fetch_via_wayback(&self.client, url).await {
            Ok(content) => return Ok(content),
            Err(_) => {}
        }

        Err(FetchError::Parse(
            "BLOCKED: Content is protected. All bypass strategies failed.\nTry pressing 'o' to open in your browser.".to_string(),
        ))
    }

    /// Try to fetch via agent-browser (vercel-labs/agent-browser).
    ///
    /// Correct flow:
    ///   open --args AutomationControlled → wait networkidle → get html → close
    ///
    /// The `wait --load networkidle` step is critical: Cloudflare's JS challenge
    /// runs in the browser and redirects to the real page once solved. Without
    /// waiting for networkidle we'd capture the challenge page, not the article.
    async fn fetch_via_agent_browser(url: &str) -> Result<String, FetchError> {
        use tokio::process::Command;

        // Unique session so concurrent fetches don't collide
        let session = format!(
            "rss-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis()
        );

        // Hide Playwright's automation fingerprint (key for Cloudflare bypass)
        let browser_args = "--disable-blink-features=AutomationControlled,--no-sandbox";

        // ── Steps 1-3: open → wait → get html body (single shell chain) ────
        // Chaining with && in one sh invocation avoids 3 separate process
        // spawn round-trips, cutting latency from ~3.5 s to ~1.1 s.
        // AGENT_BROWSER_DEFAULT_TIMEOUT=5000 is short enough to fail fast on
        // missing selectors while still handling Cloudflare JS challenges.
        let chain_cmd = format!(
            "AGENT_BROWSER_DEFAULT_TIMEOUT=30000 AGENT_BROWSER_MAX_OUTPUT=150000 \
             agent-browser --session '{session}' --args '{browser_args}' open '{url}' && \
             AGENT_BROWSER_DEFAULT_TIMEOUT=15000 \
             agent-browser --session '{session}' wait --load load && \
             AGENT_BROWSER_DEFAULT_TIMEOUT=5000 AGENT_BROWSER_MAX_OUTPUT=150000 \
             agent-browser --session '{session}' get html body",
            session = session,
            browser_args = browser_args,
            url = url,
        );

        let output = Command::new("sh")
            .args(["-c", &chain_cmd])
            .output()
            .await
            .map_err(|e| {
                if e.kind() == std::io::ErrorKind::NotFound {
                    FetchError::Parse("sh not found".to_string())
                } else {
                    FetchError::Parse(format!("agent-browser: {}", e))
                }
            })?;

        // ── Step 4: Always close session ───────────────────────────────────
        let _ = Self::agent_browser_close(&session).await;

        // Check for hard errors (DNS failure, not installed, etc.)
        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr).to_lowercase();
            if stderr.contains("not found")
                || stderr.contains("err_name_not_resolved")
                || stderr.contains("cannot find")
            {
                return Err(FetchError::Parse(format!(
                    "agent-browser: {}",
                    stderr.lines().next().unwrap_or("failed")
                )));
            }
            // Non-zero exit is acceptable if stdout has content (partial load)
        }

        let html = String::from_utf8_lossy(&output.stdout).to_string();

        if html.trim().is_empty() {
            return Err(FetchError::Parse(
                "agent-browser: no readable content extracted".to_string(),
            ));
        }

        let md = Self::html_to_markdown(&html);
        if md.trim().is_empty() {
            return Err(FetchError::Parse(
                "agent-browser: markdown conversion produced no output".to_string(),
            ));
        }

        Ok(format!("*\\[Fetched via browser automation\\]*\n\n{}", md))
    }

    async fn agent_browser_close(session: &str) -> Result<(), FetchError> {
        use tokio::process::Command;
        Command::new("agent-browser")
            .args(["--session", session, "close"])
            .output()
            .await
            .map_err(|e| FetchError::Parse(e.to_string()))?;
        Ok(())
    }

    /// Try to fetch an archived version from the Wayback Machine.
    async fn fetch_via_wayback(client: &Client, url: &str) -> Result<String, FetchError> {
        // Check availability
        let check: serde_json::Value = client
            .get("https://archive.org/wayback/available")
            .query(&[("url", url)])
            .timeout(std::time::Duration::from_secs(10))
            .send()
            .await?
            .json()
            .await?;

        let available = check
            .pointer("/archived_snapshots/closest/available")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);

        if !available {
            return Err(FetchError::Parse(
                "Wayback Machine: no snapshot available".to_string(),
            ));
        }

        let archived_url = check
            .pointer("/archived_snapshots/closest/url")
            .and_then(|v| v.as_str())
            .ok_or_else(|| FetchError::Parse("Wayback Machine: missing URL".to_string()))?
            .to_string();

        let html = client
            .get(&archived_url)
            .timeout(std::time::Duration::from_secs(15))
            .send()
            .await?
            .text()
            .await?;

        let md = Self::html_to_markdown(&html);
        if md.trim().is_empty() {
            return Err(FetchError::Parse(
                "Wayback Machine: empty content".to_string(),
            ));
        }

        Ok(format!("*\\[Retrieved from Wayback Machine\\]*\n\n{}", md))
    }

    // ── Public test helpers (used by --test-fetch CLI flag) ───────────────

    /// Expose browser strategy for CLI testing.
    pub async fn fetch_via_browser_test(url: &str) -> Result<String, FetchError> {
        Self::fetch_via_agent_browser(url).await
    }

    /// Expose Wayback Machine strategy for CLI testing.
    pub async fn fetch_via_wayback_test(url: &str) -> Result<String, FetchError> {
        let client = Client::new();
        Self::fetch_via_wayback(&client, url).await
    }

    // ── Legacy methods kept for CLI compatibility ──────────────────────────

    pub async fn fetch_article(&self, url: &str) -> Result<String, FetchError> {
        let response = self.client.get(url).send().await?;
        let html = response.text().await?;

        if Self::is_blocked(&html) {
            return Err(FetchError::Parse("BLOCKED".to_string()));
        }

        let soup = Soup::new(&html);
        let text = if let Some(article) = soup.tag("article").find() {
            article.text()
        } else if let Some(main) = soup.tag("main").find() {
            main.text()
        } else if let Some(div) = soup.tag("div").find() {
            div.text()
        } else {
            soup.text()
        };

        let lines: Vec<&str> = text
            .lines()
            .map(|l| l.trim())
            .filter(|l| !l.is_empty())
            .collect();

        Ok(lines.join("\n"))
    }

    pub fn is_blocked(html: &str) -> bool {
        let blocked_patterns = [
            "verifying your browser",
            "cloudflare",
            "checking your browser",
            "please wait",
            "ddos-guard",
            "incapsula",
            "security check",
            "captcha",
            "access denied",
            "enable javascript",
            "ray id",
        ];

        let html_lower = html.to_lowercase();
        blocked_patterns
            .iter()
            .any(|pattern| html_lower.contains(pattern))
    }

    pub async fn fetch_and_summarize(
        &self,
        url: &str,
        api_key: &str,
    ) -> Result<ArticleContent, FetchError> {
        let content = self.fetch_article(url).await?;

        let client = reqwest::Client::new();

        let prompt = format!(
            r#"Extract the main article title and content from this web page content.

Page content:
{}

Return as JSON with:
- "title": The article headline/title
- "content": The main article paragraphs (not navigation, ads, etc)"#,
            &content[..content.len().min(5000)]
        );

        let payload = serde_json::json!({
            "model": "MiniMax-M2.5-highspeed",
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

        let result: serde_json::Value = response
            .json()
            .await
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

        let json_str = content_str
            .trim_start_matches("```json")
            .trim_start_matches("```")
            .trim_end_matches("```")
            .trim();

        let parsed: Result<serde_json::Value, _> = serde_json::from_str(json_str);

        let (title, article_content) = match parsed {
            Ok(json) => (
                json.get("title")
                    .and_then(|v| v.as_str())
                    .unwrap_or("Extracted Article")
                    .to_string(),
                json.get("content")
                    .and_then(|v| v.as_str())
                    .unwrap_or(&content)
                    .to_string(),
            ),
            Err(_) => {
                let title = extract_json_field(&content_str, "title")
                    .unwrap_or_else(|| "Extracted Article".to_string());
                let article_content =
                    extract_json_field(&content_str, "content").unwrap_or(content);
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
    let pattern = format!("\"{}\":", field);
    if let Some(pos) = json_str.find(&pattern) {
        let rest = &json_str[pos..];
        if let Some(quote_start) = rest.find('"') {
            let after_quote = &rest[quote_start + 1..];
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
        write!(
            f,
            "# {}\n\n**Source:** {}\n\n---\n\n{}",
            self.title, self.url, self.content
        )
    }
}
