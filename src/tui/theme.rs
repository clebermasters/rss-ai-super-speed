use ratatui::style::{Color, Modifier, Style};

pub fn bg() -> Color {
    Color::Rgb(30, 30, 46)
}

pub fn surface() -> Color {
    Color::Rgb(49, 50, 68)
}

pub fn selected_bg() -> Color {
    Color::Rgb(49, 50, 68)
}

pub fn selected_fg() -> Color {
    Color::Rgb(137, 180, 250)
}

pub fn unread() -> Color {
    Color::White
}

pub fn read() -> Color {
    Color::DarkGray
}

pub fn saved() -> Color {
    Color::Yellow
}

pub fn source() -> Color {
    Color::Cyan
}

pub fn date() -> Color {
    Color::Green
}

pub fn score() -> Color {
    Color::Yellow
}

pub fn ai_accent() -> Color {
    Color::Magenta
}

pub fn error() -> Color {
    Color::Red
}

pub fn border() -> Color {
    Color::Rgb(88, 91, 112)
}

pub fn border_active() -> Color {
    Color::Rgb(137, 180, 250)
}

pub fn title() -> Color {
    Color::Rgb(203, 166, 247)
}

pub fn subtitle() -> Color {
    Color::Rgb(166, 227, 161)
}

/// Detect if we should use monochrome (NO_COLOR env var)
pub fn use_color() -> bool {
    std::env::var("NO_COLOR").is_err()
}

pub fn style_unread() -> Style {
    if use_color() {
        Style::default().fg(unread()).add_modifier(Modifier::BOLD)
    } else {
        Style::default().add_modifier(Modifier::BOLD)
    }
}

pub fn style_read() -> Style {
    if use_color() {
        Style::default().fg(read())
    } else {
        Style::default().add_modifier(Modifier::DIM)
    }
}

pub fn style_selected() -> Style {
    if use_color() {
        Style::default()
            .fg(selected_fg())
            .bg(selected_bg())
            .add_modifier(Modifier::BOLD)
    } else {
        Style::default().add_modifier(Modifier::REVERSED)
    }
}

pub fn style_border_active() -> Style {
    if use_color() {
        Style::default().fg(border_active())
    } else {
        Style::default().add_modifier(Modifier::BOLD)
    }
}

pub fn style_border_inactive() -> Style {
    if use_color() {
        Style::default().fg(border())
    } else {
        Style::default()
    }
}

pub fn style_title() -> Style {
    if use_color() {
        Style::default()
            .fg(title())
            .add_modifier(Modifier::BOLD)
    } else {
        Style::default().add_modifier(Modifier::BOLD)
    }
}

pub fn style_source() -> Style {
    if use_color() {
        Style::default().fg(source())
    } else {
        Style::default()
    }
}

pub fn style_date() -> Style {
    if use_color() {
        Style::default().fg(date())
    } else {
        Style::default()
    }
}

pub fn style_score() -> Style {
    if use_color() {
        Style::default().fg(score())
    } else {
        Style::default()
    }
}

pub fn style_ai() -> Style {
    if use_color() {
        Style::default().fg(ai_accent()).add_modifier(Modifier::ITALIC)
    } else {
        Style::default().add_modifier(Modifier::ITALIC)
    }
}

pub fn style_error() -> Style {
    if use_color() {
        Style::default().fg(error()).add_modifier(Modifier::BOLD)
    } else {
        Style::default().add_modifier(Modifier::BOLD)
    }
}

pub fn style_status_bar() -> Style {
    if use_color() {
        Style::default()
            .fg(Color::Black)
            .bg(Color::Rgb(137, 180, 250))
    } else {
        Style::default().add_modifier(Modifier::REVERSED)
    }
}
