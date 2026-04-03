use ratatui::{
    buffer::Buffer,
    layout::Rect,
    text::{Line, Span},
    widgets::Widget,
};

use crate::tui::theme;

pub struct SearchBar<'a> {
    pub query: &'a str,
    pub active: bool,
}

impl<'a> SearchBar<'a> {
    pub fn new(query: &'a str, active: bool) -> Self {
        Self { query, active }
    }
}

impl Widget for SearchBar<'_> {
    fn render(self, area: Rect, buf: &mut Buffer) {
        let prefix = "/ ";
        let cursor = if self.active { "█" } else { "" };
        let text = format!("{}{}{}", prefix, self.query, cursor);

        let style = if self.active {
            theme::style_border_active()
        } else {
            theme::style_read()
        };

        let line = Line::from(Span::styled(text, style));
        line.render(area, buf);
    }
}
