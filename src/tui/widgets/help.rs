use ratatui::{
    buffer::Buffer,
    layout::{Alignment, Rect},
    text::{Line, Span},
    widgets::{Block, Borders, Clear, Paragraph, Widget, Wrap},
};

use crate::tui::theme;

pub struct HelpOverlay;

impl Widget for HelpOverlay {
    fn render(self, area: Rect, buf: &mut Buffer) {
        // Clear the background
        Clear.render(area, buf);

        let block = Block::default()
            .borders(Borders::ALL)
            .border_style(theme::style_border_active())
            .title(Span::styled(" Help — rss-ai TUI ", theme::style_title()))
            .title_alignment(Alignment::Center);

        let inner = block.inner(area);
        block.render(area, buf);

        let help_text = vec![
            Line::from(Span::styled("Navigation", theme::style_title())),
            Line::from(""),
            Line::from(vec![
                Span::styled("  j / ↓      ", theme::style_source()),
                Span::raw("Move down"),
            ]),
            Line::from(vec![
                Span::styled("  k / ↑      ", theme::style_source()),
                Span::raw("Move up"),
            ]),
            Line::from(vec![
                Span::styled("  h / ←      ", theme::style_source()),
                Span::raw("Previous panel"),
            ]),
            Line::from(vec![
                Span::styled("  l / →      ", theme::style_source()),
                Span::raw("Next panel"),
            ]),
            Line::from(vec![
                Span::styled("  Tab        ", theme::style_source()),
                Span::raw("Cycle panels"),
            ]),
            Line::from(vec![
                Span::styled("  g / G      ", theme::style_source()),
                Span::raw("Top / Bottom"),
            ]),
            Line::from(vec![
                Span::styled("  n / p      ", theme::style_source()),
                Span::raw("Next / Prev unread"),
            ]),
            Line::from(""),
            Line::from(Span::styled("Article Actions", theme::style_title())),
            Line::from(""),
            Line::from(vec![
                Span::styled("  Enter/Space", theme::style_score()),
                Span::raw(" Open in reader"),
            ]),
            Line::from(vec![
                Span::styled("  o          ", theme::style_score()),
                Span::raw(" Open in browser"),
            ]),
            Line::from(vec![
                Span::styled("  r          ", theme::style_score()),
                Span::raw(" Toggle read/unread"),
            ]),
            Line::from(vec![
                Span::styled("  s          ", theme::style_score()),
                Span::raw(" Toggle save"),
            ]),
            Line::from(vec![
                Span::styled("  a          ", theme::style_score()),
                Span::raw(" AI summarize"),
            ]),
            Line::from(vec![
                Span::styled("  f          ", theme::style_score()),
                Span::raw(" Fetch full content"),
            ]),
            Line::from(vec![
                Span::styled("  d          ", theme::style_score()),
                Span::raw(" Hide article"),
            ]),
            Line::from(""),
            Line::from(Span::styled("Global", theme::style_title())),
            Line::from(""),
            Line::from(vec![
                Span::styled("  /          ", theme::style_ai()),
                Span::raw(" Search"),
            ]),
            Line::from(vec![
                Span::styled("  R          ", theme::style_ai()),
                Span::raw(" Refresh all feeds"),
            ]),
            Line::from(vec![
                Span::styled("  ?          ", theme::style_ai()),
                Span::raw(" Toggle this help"),
            ]),
            Line::from(vec![
                Span::styled("  q / Q      ", theme::style_error()),
                Span::raw(" Quit"),
            ]),
            Line::from(vec![
                Span::styled("  Esc        ", theme::style_ai()),
                Span::raw(" Go back / Cancel"),
            ]),
        ];

        let paragraph = Paragraph::new(help_text)
            .wrap(Wrap { trim: false })
            .alignment(Alignment::Left);

        paragraph.render(inner, buf);
    }
}

/// Calculate a centered rect that fits inside `area` with given width/height.
pub fn centered_rect(percent_x: u16, percent_y: u16, area: Rect) -> Rect {
    let popup_width = area.width * percent_x / 100;
    let popup_height = area.height * percent_y / 100;
    let x = area.x + (area.width - popup_width) / 2;
    let y = area.y + (area.height - popup_height) / 2;
    Rect::new(x, y, popup_width, popup_height)
}
