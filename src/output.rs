use crate::{Article, OutputConfig, OutputFormat};
use serde_json;

pub struct OutputFormatter {
    config: OutputConfig,
}

impl OutputFormatter {
    pub fn new(config: OutputConfig) -> Self {
        Self { config }
    }

    pub fn format(&self, articles: &[Article], summary: Option<&str>) -> String {
        match self.config.format {
            OutputFormat::Json => self.format_json(articles, summary),
            OutputFormat::Markdown => self.format_markdown(articles, summary),
            OutputFormat::Html => self.format_html(articles, summary),
            OutputFormat::Csv => self.format_csv(articles),
        }
    }

    fn format_json(&self, articles: &[Article], summary: Option<&str>) -> String {
        let output = serde_json::json!({
            "articles": articles,
            "summary": summary,
            "count": articles.len()
        });

        if self.config.pretty {
            serde_json::to_string_pretty(&output).unwrap_or_default()
        } else {
            serde_json::to_string(&output).unwrap_or_default()
        }
    }

    fn format_markdown(&self, articles: &[Article], summary: Option<&str>) -> String {
        let mut md = String::new();

        // Header
        md.push_str("# RSS AI Summary\n\n");

        // AI Summary
        if let Some(s) = summary {
            md.push_str("## AI Analysis\n\n");
            md.push_str(s);
            md.push_str("\n\n");
        }

        // Articles
        md.push_str(&format!("## Articles ({} total)\n\n", articles.len()));

        for (i, article) in articles.iter().enumerate() {
            md.push_str(&format!("### {}. {}\n", i + 1, article.title));
            md.push_str(&format!("- **Source:** {}\n", article.source));
            if let Some(score) = article.score {
                md.push_str(&format!("- **Score:** {}\n", score));
            }
            if let Some(comments) = article.comments {
                md.push_str(&format!("- **Comments:** {}\n", comments));
            }
            if let Some(published) = &article.published {
                md.push_str(&format!("- **Published:** {}\n", published));
            }
            md.push_str(&format!("- **Link:** {}\n", article.link));

            if let Some(summary) = &article.summary {
                let clean = summary
                    .replace("<p>", "")
                    .replace("</p>", "")
                    .replace("<a href=\"", "[")
                    .replace("\">", "](")
                    .replace("</a>", ")")
                    .chars()
                    .take(200)
                    .collect::<String>();
                md.push_str(&format!("- **Summary:** {}...\n", clean));
            }

            md.push_str("\n");
        }

        md
    }

    fn format_html(&self, articles: &[Article], summary: Option<&str>) -> String {
        let mut html = String::new();

        html.push_str(r#"<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>RSS AI Summary</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; 
               max-width: 800px; margin: 0 auto; padding: 20px; background: #f5f5f5; }
        .card { background: white; padding: 20px; margin: 15px 0; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        h1 { color: #1a1a2e; }
        h2 { color: #16213e; margin-top: 30px; }
        h3 { color: #3949ab; margin: 0; }
        .meta { color: #666; font-size: 14px; margin: 5px 0; }
        .score { color: #f39c12; }
        .summary { color: #555; margin-top: 10px; }
        a { color: #667eea; }
    </style>
</head>
<body>
    <h1>📡 RSS AI Summary</h1>
"#);

        // AI Summary
        if let Some(s) = summary {
            html.push_str("<div class='card'><h2>🤖 AI Analysis</h2>");
            html.push_str(&s.replace('\n', "<br>"));
            html.push_str("</div>\n");
        }

        // Articles
        html.push_str(&format!(
            "<h2>📰 Articles ({} total)</h2>\n",
            articles.len()
        ));

        for article in articles {
            html.push_str("<div class='card'>\n");
            html.push_str(&format!("<h3>{}</h3>\n", article.title));
            html.push_str(&format!(
                "<div class='meta'>Source: {}</div>\n",
                article.source
            ));

            if let Some(score) = article.score {
                html.push_str(&format!("<div class='meta score'>Score: {}</div>\n", score));
            }
            if let Some(comments) = article.comments {
                html.push_str(&format!("<div class='meta'>Comments: {}</div>\n", comments));
            }
            if let Some(published) = &article.published {
                html.push_str(&format!(
                    "<div class='meta'>Published: {}</div>\n",
                    published
                ));
            }

            html.push_str(&format!(
                "<div class='meta'><a href=\"{}\">Read more →</a></div>\n",
                article.link
            ));

            if let Some(summary) = &article.summary {
                let clean = summary
                    .replace("<p>", "")
                    .replace("</p>", "")
                    .replace("<a href=\"", "[")
                    .replace("\">", "](")
                    .replace("</a>", ")")
                    .chars()
                    .take(200)
                    .collect::<String>();
                html.push_str(&format!("<div class='summary'>{}...</div>\n", clean));
            }

            html.push_str("</div>\n");
        }

        html.push_str("</body></html>");

        html
    }

    fn format_csv(&self, articles: &[Article]) -> String {
        let mut csv = String::from("title,source,score,comments,published,link,summary\n");

        for article in articles {
            let title = escape_csv(&article.title);
            let source = escape_csv(&article.source);
            let score = article.score.map(|s| s.to_string()).unwrap_or_default();
            let comments = article.comments.map(|c| c.to_string()).unwrap_or_default();
            let published = article.published.clone().unwrap_or_default();
            let link = escape_csv(&article.link);
            let summary = escape_csv(&article.summary.clone().unwrap_or_default());

            csv.push_str(&format!(
                "{},{},{},{},{},{},{}\n",
                title, source, score, comments, published, link, summary
            ));
        }

        csv
    }

    pub fn to_file(
        &self,
        articles: &[Article],
        summary: Option<&str>,
        path: &str,
    ) -> std::io::Result<()> {
        let content = self.format(articles, summary);
        std::fs::write(path, content)
    }
}

impl Default for OutputFormatter {
    fn default() -> Self {
        Self::new(OutputConfig::default())
    }
}

fn escape_csv(s: &str) -> String {
    if s.contains(',') || s.contains('"') || s.contains('\n') {
        format!("\"{}\"", s.replace('"', "\"\""))
    } else {
        s.to_string()
    }
}

pub fn format_articles(
    articles: &[Article],
    config: &OutputConfig,
    summary: Option<&str>,
) -> String {
    OutputFormatter::new(config.clone()).format(articles, summary)
}
