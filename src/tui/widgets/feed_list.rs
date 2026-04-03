use ratatui::{
    buffer::Buffer,
    layout::Rect,
    text::{Line, Span},
    widgets::{Block, Borders, List, ListItem, ListState, StatefulWidget, Widget},
};

use crate::tui::{app::App, theme};

pub struct FeedList<'a> {
    pub app: &'a App,
    pub active: bool,
}

impl<'a> FeedList<'a> {
    pub fn new(app: &'a App, active: bool) -> Self {
        Self { app, active }
    }
}

impl Widget for FeedList<'_> {
    fn render(self, area: Rect, buf: &mut Buffer) {
        let border_style = if self.active {
            theme::style_border_active()
        } else {
            theme::style_border_inactive()
        };

        let title_style = theme::style_title();

        let block = Block::default()
            .borders(Borders::ALL)
            .border_style(border_style)
            .title(Span::styled(" Feeds ", title_style));

        let inner = block.inner(area);
        block.render(area, buf);

        // Build list items: "All Articles" + each feed
        let mut items: Vec<ListItem> = Vec::new();

        let all_label = format!(
            " 📰 All Articles ({})",
            self.app.articles.len()
        );
        let all_style = if self.app.selected_feed == 0 && self.active {
            theme::style_selected()
        } else {
            theme::style_unread()
        };
        items.push(ListItem::new(Line::from(Span::styled(all_label, all_style))));

        for (i, feed) in self.app.feeds.iter().enumerate() {
            let feed_idx = i + 1;
            let count = self
                .app
                .articles
                .iter()
                .filter(|a| a.source == feed.name)
                .count();
            let unread = self
                .app
                .articles
                .iter()
                .filter(|a| a.source == feed.name && !a.is_read)
                .count();

            let enabled_icon = if feed.enabled { "●" } else { "○" };
            let label = format!(" {} {} ({}/{})", enabled_icon, feed.name, unread, count);

            let style = if self.app.selected_feed == feed_idx && self.active {
                theme::style_selected()
            } else if unread > 0 {
                theme::style_unread()
            } else {
                theme::style_read()
            };

            items.push(ListItem::new(Line::from(Span::styled(label, style))));
        }

        if self.app.feeds.is_empty() {
            items.push(ListItem::new(Line::from(Span::styled(
                " No feeds. Press 'a' to add.",
                theme::style_read(),
            ))));
        }

        let mut state = ListState::default();
        state.select(Some(self.app.selected_feed));

        StatefulWidget::render(
            List::new(items)
                .highlight_style(theme::style_selected())
                .highlight_symbol("▶ "),
            inner,
            buf,
            &mut state,
        );
    }
}
