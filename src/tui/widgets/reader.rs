use ratatui::{
    buffer::Buffer,
    layout::Rect,
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{Block, Borders, Paragraph, Widget, Wrap},
};

use crate::tui::{app::App, theme};

pub struct Reader<'a> {
    pub app: &'a App,
    pub active: bool,
}

impl<'a> Reader<'a> {
    pub fn new(app: &'a App, active: bool) -> Self {
        Self { app, active }
    }
}

/// Check if the terminal supports OSC 8 hyperlinks.
pub fn supports_osc8() -> bool {
    let term_program = std::env::var("TERM_PROGRAM").unwrap_or_default();
    let vte = std::env::var("VTE_VERSION").is_ok();
    let colorterm = std::env::var("COLORTERM")
        .unwrap_or_default()
        .to_lowercase();

    vte || colorterm == "truecolor"
        || matches!(
            term_program.as_str(),
            "iTerm.app" | "WezTerm" | "Hyper" | "Tabby" | "kitty" | "alacritty"
        )
}

/// Wrap a URL in OSC 8 escape sequences for terminal hyperlinks.
pub fn osc8_link(url: &str, text: &str) -> String {
    format!("\x1b]8;;{}\x1b\\{}\x1b]8;;\x1b\\", url, text)
}

fn html_to_text(html: &str) -> String {
    html2text::from_read(html.as_bytes(), 80)
}

impl Widget for Reader<'_> {
    fn render(self, area: Rect, buf: &mut Buffer) {
        let border_style = if self.active {
            theme::style_border_active()
        } else {
            theme::style_border_inactive()
        };

        let block = Block::default()
            .borders(Borders::ALL)
            .border_style(border_style)
            .title(Span::styled(" Reader ", theme::style_title()));

        let inner = block.inner(area);
        block.render(area, buf);

        let Some(article) = self.app.selected_article_ref() else {
            let p = Paragraph::new(
                "Select an article to read.\n\nPress j/k to navigate, Enter to open.",
            )
            .style(theme::style_read())
            .wrap(Wrap { trim: true });
            p.render(inner, buf);
            return;
        };

        let width = inner.width as usize;
        let mut lines: Vec<Line> = Vec::new();

        // Title
        for tl in wrap_text(&article.title, width) {
            lines.push(Line::from(Span::styled(
                tl,
                theme::style_unread().add_modifier(Modifier::BOLD),
            )));
        }
        lines.push(Line::from(""));

        // Meta row
        let score_part = article
            .score
            .map(|s| format!("{}pts  ", s))
            .unwrap_or_default();
        let saved_part = if article.is_saved { "★ saved" } else { "" };
        let meta = format!(
            "{}  {}  {}{}",
            article.source,
            article.published.as_deref().unwrap_or("no date"),
            score_part,
            saved_part,
        );
        lines.push(Line::from(Span::styled(meta, theme::style_source())));

        // Link row
        if supports_osc8() {
            let linked = osc8_link(&article.link, &article.link);
            lines.push(Line::from(Span::styled(linked, theme::style_date())));
        } else {
            let link_text = truncate_str(&article.link, width);
            lines.push(Line::from(Span::styled(link_text, theme::style_date())));
        }
        lines.push(Line::from(""));
        lines.push(Line::from(Span::styled(
            "─".repeat(width.min(60)),
            theme::style_border_inactive(),
        )));
        lines.push(Line::from(""));

        // AI Summary if present
        if let Some(summary) = &article.summary {
            lines.push(Line::from(Span::styled("✨ AI Summary", theme::style_ai())));
            lines.push(Line::from(""));
            for sl in wrap_text(summary, width) {
                lines.push(Line::from(Span::styled(sl, theme::style_ai())));
            }
            lines.push(Line::from(""));
            lines.push(Line::from(Span::styled(
                "─".repeat(width.min(60)),
                theme::style_border_inactive(),
            )));
            lines.push(Line::from(""));
        }

        // Full content or placeholder
        if let Some(content) = &article.content {
            let text = if content.contains('<') {
                html_to_text(content)
            } else {
                content.clone()
            };
            render_markdown_lines(&text, width, &mut lines);
        } else {
            lines.push(Line::from(Span::styled(
                "No full content. Press 'f' to fetch.",
                theme::style_read(),
            )));
            lines.push(Line::from(""));
            lines.push(Line::from(Span::styled(
                "Press 'a' for AI summary, 'o' to open in browser.",
                theme::style_read(),
            )));
        }

        let paragraph = Paragraph::new(lines)
            .scroll((self.app.reader_scroll, 0))
            .wrap(Wrap { trim: false });

        paragraph.render(inner, buf);
    }
}

/// Render markdown-formatted text into styled ratatui Lines.
///
/// Handles: # headers, ## subheaders, - lists, > blockquotes,
/// --- rules, *[status]* markers, and plain paragraphs.
fn render_markdown_lines(text: &str, width: usize, lines: &mut Vec<Line<'static>>) {
    for raw in text.lines() {
        if raw.starts_with("# ") {
            // H1 — bold yellow
            let title = raw[2..].trim().to_string();
            lines.push(Line::from(Span::styled(
                title,
                Style::default()
                    .fg(Color::Yellow)
                    .add_modifier(Modifier::BOLD),
            )));
        } else if raw.starts_with("## ") {
            // H2 — bold cyan
            let title = raw[3..].trim().to_string();
            lines.push(Line::from(Span::styled(
                title,
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            )));
        } else if raw.starts_with("### ") {
            // H3 — bold
            let title = raw[4..].trim().to_string();
            lines.push(Line::from(Span::styled(
                title,
                Style::default().add_modifier(Modifier::BOLD),
            )));
        } else if raw.starts_with("#### ") || raw.starts_with("##### ") {
            // H4/H5 — italic
            let level = if raw.starts_with("#### ") { 5 } else { 6 };
            let title = raw[level..].trim().to_string();
            lines.push(Line::from(Span::styled(
                title,
                Style::default().add_modifier(Modifier::ITALIC),
            )));
        } else if raw.starts_with("- ") || raw.starts_with("* ") {
            // Unordered list item
            let item = format!("  • {}", raw[2..].trim());
            for wl in wrap_text(&item, width) {
                lines.push(Line::from(wl));
            }
        } else if raw.len() > 2 && raw.chars().next().map(|c| c.is_ascii_digit()).unwrap_or(false)
            && raw.contains(". ")
        {
            // Numbered list item (e.g. "1. foo")
            let item = format!("  {}", raw.trim());
            for wl in wrap_text(&item, width) {
                lines.push(Line::from(wl));
            }
        } else if raw.starts_with("> ") {
            // Blockquote — indented with ai color
            let quote = format!("  ┃ {}", raw[2..].trim());
            lines.push(Line::from(Span::styled(quote, theme::style_ai())));
        } else if raw.starts_with("---") || raw.starts_with("===") || raw.starts_with("___") {
            // Horizontal rule
            lines.push(Line::from(Span::styled(
                "─".repeat(width.min(60)),
                theme::style_border_inactive(),
            )));
        } else if raw.starts_with("```") {
            // Code fence
            lines.push(Line::from(Span::styled(
                raw.to_string(),
                Style::default().fg(Color::DarkGray),
            )));
        } else if raw.starts_with("    ") || raw.starts_with('\t') {
            // Indented code block
            lines.push(Line::from(Span::styled(
                raw.to_string(),
                Style::default().fg(Color::DarkGray),
            )));
        } else if (raw.starts_with("*\\[") || raw.starts_with("*["))
            && (raw.contains("browser") || raw.contains("Wayback") || raw.contains("via"))
        {
            // Status marker (e.g. *[Fetched via browser automation]*)
            lines.push(Line::from(Span::styled(
                raw.trim_matches('\\').to_string(),
                theme::style_ai(),
            )));
        } else if raw.trim().is_empty() {
            lines.push(Line::from(""));
        } else {
            // Plain paragraph — wrap normally
            for wl in wrap_text(raw.trim(), width) {
                lines.push(Line::from(wl));
            }
        }
    }
}

fn wrap_text(text: &str, width: usize) -> Vec<String> {
    if width == 0 {
        return vec![text.to_string()];
    }
    let mut lines = Vec::new();
    let words: Vec<&str> = text.split_whitespace().collect();
    let mut current = String::new();

    for word in words {
        if current.is_empty() {
            current.push_str(word);
        } else if current.len() + 1 + word.len() <= width {
            current.push(' ');
            current.push_str(word);
        } else {
            lines.push(current.clone());
            current = word.to_string();
        }
    }

    if !current.is_empty() {
        lines.push(current);
    }

    if lines.is_empty() {
        lines.push(String::new());
    }

    lines
}

fn truncate_str(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        let truncated: String = s.chars().take(max.saturating_sub(1)).collect();
        format!("{}…", truncated)
    }
}
