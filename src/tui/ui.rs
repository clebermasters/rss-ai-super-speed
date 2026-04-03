use ratatui::{
    layout::{Constraint, Direction, Layout, Rect},
    Frame,
};

use crate::tui::{
    app::{App, Mode, Panel},
    widgets::{
        article_list::ArticleList,
        feed_list::FeedList,
        help::{centered_rect, HelpOverlay},
        reader::Reader,
        search_bar::SearchBar,
        status_bar::StatusBar,
    },
};

pub fn draw(frame: &mut Frame, app: &App) {
    let size = frame.area();

    // Minimum terminal size guard
    if size.width < 40 || size.height < 10 {
        let msg = ratatui::widgets::Paragraph::new(
            "Terminal too small.\nMin size: 40×10.",
        )
        .style(ratatui::style::Style::default());
        frame.render_widget(msg, size);
        return;
    }

    // Reserve bottom line for status bar
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Min(1), Constraint::Length(1)])
        .split(size);

    let main_area = chunks[0];
    let status_area = chunks[1];

    // Responsive panel layout
    let panels = if size.width >= 120 {
        // Three panels: Feeds | Articles | Reader
        Layout::default()
            .direction(Direction::Horizontal)
            .constraints([
                Constraint::Percentage(18),
                Constraint::Percentage(35),
                Constraint::Percentage(47),
            ])
            .split(main_area)
    } else if size.width >= 80 {
        // Two panels: Articles | Reader (no feeds sidebar)
        Layout::default()
            .direction(Direction::Horizontal)
            .constraints([
                Constraint::Percentage(35),
                Constraint::Percentage(65),
            ])
            .split(main_area)
    } else {
        // Single panel based on focus
        Layout::default()
            .direction(Direction::Horizontal)
            .constraints([Constraint::Percentage(100)])
            .split(main_area)
    };

    if size.width >= 120 {
        let feeds_active = app.focus == Panel::Feeds;
        let articles_active = app.focus == Panel::Articles;
        let reader_active = app.focus == Panel::Reader;

        let (feeds_area, articles_area, reader_area) = (panels[0], panels[1], panels[2]);

        // Feeds panel
        frame.render_widget(FeedList::new(app, feeds_active), feeds_area);

        // Articles panel with optional search bar
        render_articles_panel(frame, app, articles_area, articles_active);

        // Reader panel
        frame.render_widget(Reader::new(app, reader_active), reader_area);
    } else if size.width >= 80 {
        let articles_active = app.focus != Panel::Reader;
        let reader_active = app.focus == Panel::Reader;

        let (articles_area, reader_area) = (panels[0], panels[1]);

        render_articles_panel(frame, app, articles_area, articles_active);
        frame.render_widget(Reader::new(app, reader_active), reader_area);
    } else {
        // Single panel mode
        match app.focus {
            Panel::Feeds => {
                frame.render_widget(FeedList::new(app, true), panels[0]);
            }
            Panel::Articles => {
                render_articles_panel(frame, app, panels[0], true);
            }
            Panel::Reader => {
                frame.render_widget(Reader::new(app, true), panels[0]);
            }
        }
    }

    // Status bar
    frame.render_widget(StatusBar::new(app), status_area);

    // Help overlay (rendered on top)
    if app.mode == Mode::Help {
        let popup = centered_rect(70, 80, size);
        frame.render_widget(HelpOverlay, popup);
    }

    // Feed input overlay
    if app.mode == Mode::FeedInput {
        let popup = centered_rect(60, 20, size);
        render_feed_input(frame, app, popup);
    }
}

fn render_articles_panel(frame: &mut Frame, app: &App, area: Rect, active: bool) {
    let show_search = app.mode == Mode::Search || !app.search_query.is_empty();

    if show_search {
        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([Constraint::Min(1), Constraint::Length(1)])
            .split(area);

        // Adjust the block area to not include the search row
        let list_area = chunks[0];
        let search_area = chunks[1];

        frame.render_widget(ArticleList::new(app, active), list_area);
        frame.render_widget(
            SearchBar::new(&app.search_query, app.mode == Mode::Search),
            search_area,
        );
    } else {
        frame.render_widget(ArticleList::new(app, active), area);
    }
}

fn render_feed_input(frame: &mut Frame, app: &App, area: Rect) {
    use ratatui::{
        text::{Line, Span},
        widgets::{Block, Borders, Clear, Paragraph},
    };
    use crate::tui::theme;

    Clear.render_stateless(area, frame.buffer_mut());

    let block = Block::default()
        .borders(Borders::ALL)
        .border_style(theme::style_border_active())
        .title(Span::styled(" Add Feed URL ", theme::style_title()));

    let inner = block.inner(area);
    frame.render_widget(block, area);

    let prompt = Line::from(vec![
        Span::styled("URL: ", theme::style_source()),
        Span::styled(&app.feed_input, theme::style_unread()),
        Span::styled("█", theme::style_border_active()),
    ]);

    frame.render_widget(Paragraph::new(prompt), inner);
}

// Provide render_stateless as an extension method shim
trait RenderStateless {
    fn render_stateless(&self, area: Rect, buf: &mut ratatui::buffer::Buffer);
}

impl RenderStateless for ratatui::widgets::Clear {
    fn render_stateless(&self, area: Rect, buf: &mut ratatui::buffer::Buffer) {
        use ratatui::widgets::Widget;
        ratatui::widgets::Clear.render(area, buf);
    }
}
