use ratatui::{
    buffer::Buffer,
    layout::{Constraint, Direction, Layout, Rect},
    text::{Line, Span},
    widgets::{Block, Borders, List, ListItem, ListState, StatefulWidget, Widget},
};

use crate::tui::{app::App, theme};

pub struct ArticleList<'a> {
    pub app: &'a App,
    pub active: bool,
}

impl<'a> ArticleList<'a> {
    pub fn new(app: &'a App, active: bool) -> Self {
        Self { app, active }
    }
}

impl Widget for ArticleList<'_> {
    fn render(self, area: Rect, buf: &mut Buffer) {
        let border_style = if self.active {
            theme::style_border_active()
        } else {
            theme::style_border_inactive()
        };

        let title = format!(
            " Articles ({} unread) ",
            self.app.unread_count()
        );

        let block = Block::default()
            .borders(Borders::ALL)
            .border_style(border_style)
            .title(Span::styled(title, theme::style_title()));

        // If search mode active, reserve 1 line at bottom for search bar
        let (list_area, _search_area) = if self.app.mode == crate::tui::app::Mode::Search
            || !self.app.search_query.is_empty()
        {
            let chunks = Layout::default()
                .direction(Direction::Vertical)
                .constraints([Constraint::Min(1), Constraint::Length(1)])
                .split(block.inner(area));
            (chunks[0], Some(chunks[1]))
        } else {
            (block.inner(area), None)
        };

        block.render(area, buf);

        let width = list_area.width as usize;

        let items: Vec<ListItem> = self
            .app
            .filtered_indices
            .iter()
            .enumerate()
            .map(|(display_idx, &article_idx)| {
                let article = &self.app.articles[article_idx];
                let is_selected =
                    display_idx == self.app.selected_article && self.active;

                // Unread indicator
                let unread_icon = if article.is_read { "  " } else { "● " };
                let saved_icon = if article.is_saved { "★" } else { " " };

                // Format date as relative
                let date_str = format_relative_date(article.published.as_deref());

                // Score
                let score_str = article
                    .score
                    .map(|s| format!(" {}pts", s))
                    .unwrap_or_default();

                // Truncate title to fit
                let meta_len = unread_icon.len()
                    + saved_icon.len()
                    + 2  // spaces
                    + article.source.len()
                    + date_str.len()
                    + score_str.len()
                    + 4; // padding
                let max_title = if width > meta_len { width - meta_len } else { 10 };
                let title = truncate_str(&article.title, max_title);

                let base_style = if is_selected {
                    theme::style_selected()
                } else if article.is_read {
                    theme::style_read()
                } else {
                    theme::style_unread()
                };

                if is_selected {
                    // Single-color selected row
                    let text = format!(
                        "{}{:<title_width$} {} {}{}",
                        unread_icon,
                        title,
                        article.source,
                        date_str,
                        score_str,
                        title_width = max_title
                    );
                    let mut line_text = format!("{} {}", saved_icon, text);
                    // truncate to width
                    if line_text.len() > width {
                        line_text.truncate(width);
                    }
                    ListItem::new(Line::from(Span::styled(line_text, base_style)))
                } else {
                    // Multi-span row with colored metadata
                    let spans = vec![
                        Span::styled(unread_icon, base_style),
                        Span::styled(
                            format!("{:<title_width$}", title, title_width = max_title),
                            base_style,
                        ),
                        Span::raw(" "),
                        Span::styled(article.source.clone(), theme::style_source()),
                        Span::raw(" "),
                        Span::styled(date_str, theme::style_date()),
                        Span::styled(score_str, theme::style_score()),
                        Span::raw(" "),
                        Span::styled(saved_icon, theme::style_score()),
                    ];
                    ListItem::new(Line::from(spans))
                }
            })
            .collect();

        if items.is_empty() {
            let msg = if self.app.search_query.is_empty() {
                "No articles. Press R to refresh feeds.".to_string()
            } else {
                format!("No results for \"{}\"", self.app.search_query)
            };
            let item = ListItem::new(Line::from(Span::styled(msg, theme::style_read())));
            let mut state = ListState::default();
            StatefulWidget::render(List::new(vec![item]), list_area, buf, &mut state);
            return;
        }

        let mut state = ListState::default();
        state.select(Some(self.app.selected_article));

        StatefulWidget::render(
            List::new(items)
                .highlight_style(theme::style_selected())
                .highlight_symbol(""),
            list_area,
            buf,
            &mut state,
        );
    }
}

fn truncate_str(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        let truncated: String = s.chars().take(max.saturating_sub(1)).collect();
        format!("{}…", truncated)
    }
}

fn format_relative_date(published: Option<&str>) -> String {
    let Some(date_str) = published else {
        return "no date".to_string();
    };

    // Try parsing RFC3339
    if let Ok(dt) = chrono::DateTime::parse_from_rfc3339(date_str) {
        let now = chrono::Utc::now();
        let diff = now.signed_duration_since(dt.with_timezone(&chrono::Utc));
        let secs = diff.num_seconds();
        if secs < 0 {
            return "just now".to_string();
        } else if secs < 3600 {
            return format!("{}m ago", secs / 60);
        } else if secs < 86400 {
            return format!("{}h ago", secs / 3600);
        } else if secs < 86400 * 30 {
            return format!("{}d ago", secs / 86400);
        } else {
            return format!("{}w ago", secs / (86400 * 7));
        }
    }

    // Fall back to first 10 chars (date portion)
    if date_str.len() >= 10 {
        date_str[..10].to_string()
    } else {
        date_str.to_string()
    }
}
