use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};

use crate::tui::app::{Action, App, Mode, Panel};

/// Silently dispatch FetchContent for the article 3 positions ahead if it
/// has no content yet and is not already being fetched.
async fn schedule_prefetch(app: &mut App) {
    if let Some(id) = app.prefetch_target(3) {
        app.prefetching_ids.insert(id.clone());
        let _ = app.action_tx.send(Action::FetchContent(id)).await;
    }
}

/// Handle a keyboard event, possibly sending actions to the background channel.
/// Returns true if the app should quit.
pub async fn handle_key(app: &mut App, key: KeyEvent) {
    match app.mode {
        Mode::Help => handle_help_keys(app, key),
        Mode::Search => handle_search_keys(app, key).await,
        Mode::FeedInput => handle_feed_input_keys(app, key).await,
        Mode::Normal => match app.focus {
            Panel::Feeds => handle_feeds_keys(app, key).await,
            Panel::Articles => handle_articles_keys(app, key).await,
            Panel::Reader => handle_reader_keys(app, key).await,
        },
    }
}

fn handle_help_keys(app: &mut App, key: KeyEvent) {
    match key.code {
        KeyCode::Char('?') | KeyCode::Char('q') | KeyCode::Esc => {
            app.mode = Mode::Normal;
        }
        _ => {}
    }
}

async fn handle_search_keys(app: &mut App, key: KeyEvent) {
    match key.code {
        KeyCode::Esc => {
            app.search_query.clear();
            app.mode = Mode::Normal;
            app.apply_filter();
        }
        KeyCode::Enter => {
            app.mode = Mode::Normal;
            app.apply_filter();
        }
        KeyCode::Backspace => {
            app.search_query.pop();
            app.apply_filter();
        }
        KeyCode::Char(c) => {
            app.search_query.push(c);
            app.apply_filter();
        }
        _ => {}
    }
}

async fn handle_feed_input_keys(app: &mut App, key: KeyEvent) {
    match key.code {
        KeyCode::Esc => {
            app.feed_input.clear();
            app.mode = Mode::Normal;
        }
        KeyCode::Enter => {
            let url = app.feed_input.trim().to_string();
            if !url.is_empty() {
                let _ = app.action_tx.send(Action::AddFeed(url)).await;
            }
            app.feed_input.clear();
            app.mode = Mode::Normal;
        }
        KeyCode::Backspace => {
            app.feed_input.pop();
        }
        KeyCode::Char(c) => {
            app.feed_input.push(c);
        }
        _ => {}
    }
}

async fn handle_feeds_keys(app: &mut App, key: KeyEvent) {
    match key.code {
        KeyCode::Char('j') | KeyCode::Down => {
            let max = app.feeds.len(); // 0 = All, 1..=feeds.len()
            if app.selected_feed < max {
                app.selected_feed += 1;
            }
        }
        KeyCode::Char('k') | KeyCode::Up => {
            if app.selected_feed > 0 {
                app.selected_feed -= 1;
            }
        }
        KeyCode::Char('g') => {
            app.selected_feed = 0;
        }
        KeyCode::Char('G') => {
            app.selected_feed = app.feeds.len();
        }
        KeyCode::Char('a') => {
            app.mode = Mode::FeedInput;
        }
        KeyCode::Char('d') => {
            if app.selected_feed > 0 {
                let feed_idx = app.selected_feed - 1;
                if let Some(feed) = app.feeds.get(feed_idx) {
                    let _ = app.action_tx.send(Action::DeleteFeed(feed.url.clone())).await;
                }
            }
        }
        KeyCode::Char('r') | KeyCode::Char('R') => {
            let _ = app.action_tx.send(Action::RefreshFeeds).await;
        }
        KeyCode::Char('l') | KeyCode::Right | KeyCode::Tab => {
            app.focus = Panel::Articles;
        }
        KeyCode::Char('?') => {
            app.mode = Mode::Help;
        }
        KeyCode::Char('q') | KeyCode::Char('Q') => {
            app.should_quit = true;
        }
        _ => {}
    }
}

async fn handle_articles_keys(app: &mut App, key: KeyEvent) {
    // Ctrl+C always quits
    if key.modifiers == KeyModifiers::CONTROL && key.code == KeyCode::Char('c') {
        app.should_quit = true;
        return;
    }

    match key.code {
        KeyCode::Char('j') | KeyCode::Down => {
            if !app.filtered_indices.is_empty()
                && app.selected_article < app.filtered_indices.len() - 1
            {
                app.selected_article += 1;
                app.reader_scroll = 0;
                schedule_prefetch(app).await;
            }
        }
        KeyCode::Char('k') | KeyCode::Up => {
            if app.selected_article > 0 {
                app.selected_article -= 1;
                app.reader_scroll = 0;
                schedule_prefetch(app).await;
            }
        }
        KeyCode::Char('g') => {
            app.selected_article = 0;
            app.reader_scroll = 0;
            schedule_prefetch(app).await;
        }
        KeyCode::Char('G') => {
            if !app.filtered_indices.is_empty() {
                app.selected_article = app.filtered_indices.len() - 1;
                app.reader_scroll = 0;
                schedule_prefetch(app).await;
            }
        }
        KeyCode::Char('n') => { app.move_next_unread(); schedule_prefetch(app).await; }
        KeyCode::Char('p') => { app.move_prev_unread(); schedule_prefetch(app).await; }
        KeyCode::Enter | KeyCode::Char(' ') => {
            // Focus reader and mark as read
            app.focus = Panel::Reader;
            app.reader_scroll = 0;
            if let Some(article) = app.selected_article_ref() {
                if !article.is_read {
                    let id = article.id.clone();
                    if let Some(a) = app.selected_article_mut() {
                        a.is_read = true;
                    }
                    let db = app.db.clone();
                    tokio::spawn(async move {
                        let _ = db.mark_read(id).await;
                    });
                }
            }
        }
        KeyCode::Char('o') => open_in_browser(app),
        KeyCode::Char('r') => toggle_read(app).await,
        KeyCode::Char('s') => toggle_save(app).await,
        KeyCode::Char('a') => {
            if let Some(article) = app.selected_article_ref() {
                let id = article.id.clone();
                let _ = app.action_tx.send(Action::AiSummarize(id)).await;
            }
        }
        KeyCode::Char('f') => {
            if let Some(article) = app.selected_article_ref() {
                let id = article.id.clone();
                let _ = app.action_tx.send(Action::FetchContent(id)).await;
            }
        }
        KeyCode::Char('d') => {
            if let Some(article) = app.selected_article_ref() {
                let id = article.id.clone();
                app.hidden_ids.insert(id);
                app.apply_filter();
            }
        }
        KeyCode::Char('/') => {
            app.mode = Mode::Search;
        }
        KeyCode::Esc => {
            if !app.search_query.is_empty() {
                app.search_query.clear();
                app.apply_filter();
            }
        }
        KeyCode::Char('R') => {
            let _ = app.action_tx.send(Action::RefreshFeeds).await;
        }
        KeyCode::Char('h') | KeyCode::Left => {
            app.focus = Panel::Feeds;
        }
        KeyCode::Char('l') | KeyCode::Right => {
            app.focus = Panel::Reader;
        }
        // Scroll the reader panel without leaving the article list
        KeyCode::PageDown => {
            app.reader_scroll = app.reader_scroll.saturating_add(20);
        }
        KeyCode::PageUp => {
            app.reader_scroll = app.reader_scroll.saturating_sub(20);
        }
        KeyCode::Tab => {
            app.focus = app.focus.next();
        }
        KeyCode::Char('?') => {
            app.mode = Mode::Help;
        }
        KeyCode::Char('q') | KeyCode::Char('Q') => {
            app.should_quit = true;
        }
        _ => {}
    }
}

async fn handle_reader_keys(app: &mut App, key: KeyEvent) {
    match key.code {
        KeyCode::Char('j') | KeyCode::Down => {
            app.reader_scroll = app.reader_scroll.saturating_add(1);
        }
        KeyCode::Char('k') | KeyCode::Up => {
            app.reader_scroll = app.reader_scroll.saturating_sub(1);
        }
        KeyCode::Char('d') => {
            app.reader_scroll = app.reader_scroll.saturating_add(10);
        }
        KeyCode::Char('u') => {
            app.reader_scroll = app.reader_scroll.saturating_sub(10);
        }
        KeyCode::Char('g') => {
            app.reader_scroll = 0;
        }
        KeyCode::PageDown => {
            app.reader_scroll = app.reader_scroll.saturating_add(20);
        }
        KeyCode::PageUp => {
            app.reader_scroll = app.reader_scroll.saturating_sub(20);
        }
        KeyCode::Home => {
            app.reader_scroll = 0;
        }
        KeyCode::Char('o') => open_in_browser(app),
        KeyCode::Char('r') => toggle_read(app).await,
        KeyCode::Char('s') => toggle_save(app).await,
        KeyCode::Char('a') => {
            if let Some(article) = app.selected_article_ref() {
                let id = article.id.clone();
                let _ = app.action_tx.send(Action::AiSummarize(id)).await;
            }
        }
        KeyCode::Char('f') => {
            if let Some(article) = app.selected_article_ref() {
                let id = article.id.clone();
                let _ = app.action_tx.send(Action::FetchContent(id)).await;
            }
        }
        KeyCode::Esc | KeyCode::Char('h') | KeyCode::Left => {
            app.focus = Panel::Articles;
        }
        KeyCode::Tab => {
            app.focus = app.focus.next();
        }
        KeyCode::Char('?') => {
            app.mode = Mode::Help;
        }
        KeyCode::Char('q') | KeyCode::Char('Q') => {
            app.should_quit = true;
        }
        _ => {}
    }
}

fn open_in_browser(app: &App) {
    let url = app
        .selected_article_ref()
        .map(|a| a.link.clone())
        .unwrap_or_default();
    if url.is_empty() {
        return;
    }

    #[cfg(target_os = "linux")]
    let _ = std::process::Command::new("xdg-open").arg(&url).spawn();
    #[cfg(target_os = "macos")]
    let _ = std::process::Command::new("open").arg(&url).spawn();
    #[cfg(target_os = "windows")]
    let _ = std::process::Command::new("cmd")
        .args(["/c", "start", "", &url])
        .spawn();
}

async fn toggle_read(app: &mut App) {
    if let Some(article) = app.selected_article_ref() {
        let id = article.id.clone();
        let currently_read = article.is_read;
        if let Some(a) = app.selected_article_mut() {
            a.is_read = !currently_read;
        }
        let db = app.db.clone();
        tokio::spawn(async move {
            let _ = if currently_read {
                db.mark_unread(id).await
            } else {
                db.mark_read(id).await
            };
        });
    }
}

async fn toggle_save(app: &mut App) {
    if let Some(article) = app.selected_article_ref() {
        let id = article.id.clone();
        let currently_saved = article.is_saved;
        if let Some(a) = app.selected_article_mut() {
            a.is_saved = !currently_saved;
        }
        let db = app.db.clone();
        tokio::spawn(async move {
            let _ = db.toggle_save(id).await;
        });
    }
}
