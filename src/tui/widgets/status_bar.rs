use ratatui::{
    buffer::Buffer,
    layout::Rect,
    text::{Line, Span},
    widgets::Widget,
};

use crate::tui::{
    app::{App, Mode, Panel},
    theme,
};

pub struct StatusBar<'a> {
    pub app: &'a App,
}

impl<'a> StatusBar<'a> {
    pub fn new(app: &'a App) -> Self {
        Self { app }
    }
}

impl Widget for StatusBar<'_> {
    fn render(self, area: Rect, buf: &mut Buffer) {
        let bar_style = theme::style_status_bar();

        // Left: mode + spinner or status message
        let left = if self.app.loading {
            format!(" {} Loading… ", self.app.spinner_char())
        } else if let Some(ref msg) = self.app.status_message {
            format!(" {} ", msg)
        } else {
            let mode_str = match self.app.mode {
                Mode::Normal => "NORMAL",
                Mode::Search => "SEARCH",
                Mode::FeedInput => "ADD FEED",
                Mode::Help => "HELP",
            };
            format!(" {} ", mode_str)
        };

        // Right: key hints based on focus
        let right = match self.app.focus {
            Panel::Feeds => " [a]Add [d]Del [e]Toggle [r]Refresh [Tab]Switch [?]Help [q]Quit ".to_string(),
            Panel::Articles => {
                if self.app.mode == Mode::Search {
                    " [Enter]Confirm [Esc]Cancel ".to_string()
                } else {
                    " [j/k]Nav [Enter]Read [o]Browser [r]Read [s]Save [a]AI [f]Fetch [/]Search [R]Refresh [?]Help ".to_string()
                }
            }
            Panel::Reader => " [j/k]Scroll [o]Browser [a]AI [Esc]Back [?]Help ".to_string(),
        };

        // Total width
        let width = area.width as usize;
        let left_len = left.chars().count();
        let right_len = right.chars().count();

        let middle_len = if width > left_len + right_len {
            width - left_len - right_len
        } else {
            0
        };

        let full = format!("{}{}{}", left, " ".repeat(middle_len), right);
        let truncated = if full.chars().count() > width {
            let s: String = full.chars().take(width).collect();
            s
        } else {
            full
        };

        let line = Line::from(Span::styled(truncated, bar_style));
        line.render(area, buf);
    }
}
