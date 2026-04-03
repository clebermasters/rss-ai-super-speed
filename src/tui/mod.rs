use std::io::{self, Stdout};
use std::time::Duration;

use crossterm::{
    event::{DisableMouseCapture, EnableMouseCapture, Event, EventStream},
    execute,
    terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen},
};
use futures::StreamExt;
use ratatui::{backend::CrosstermBackend, Terminal};
use tokio::sync::mpsc;
use tokio::time::interval;

use crate::{
    database::{Database, DbArticleFilter},
    get_default_config,
    load_env,
    AiConfig,
    Article,
    ArticleFetcher,
    FeedFetcher,
};

use crate::tui::{
    app::{Action, App, AsyncDatabase},
    events::handle_key,
    ui::draw,
};

pub mod app;
pub mod events;
pub mod theme;
pub mod ui;
pub mod widgets;

type Term = Terminal<CrosstermBackend<Stdout>>;

fn setup_terminal() -> io::Result<Term> {
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen, EnableMouseCapture)?;
    Terminal::new(CrosstermBackend::new(io::stdout()))
}

fn restore_terminal(terminal: &mut Term) {
    let _ = disable_raw_mode();
    let _ = execute!(
        terminal.backend_mut(),
        LeaveAlternateScreen,
        DisableMouseCapture
    );
    let _ = terminal.show_cursor();
}

fn install_panic_hook() {
    let original = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let _ = disable_raw_mode();
        let _ = execute!(
            io::stdout(),
            LeaveAlternateScreen,
            DisableMouseCapture
        );
        original(info);
    }));
}

pub async fn run(db_path: &str) -> Result<(), Box<dyn std::error::Error>> {
    install_panic_hook();
    load_env();

    let database = Database::new(db_path)?;
    let async_db = AsyncDatabase::new(database);

    let (action_tx, mut action_rx) = mpsc::channel::<Action>(64);

    let mut app = App::new(async_db.clone(), action_tx.clone());

    // Load initial data
    let filter = DbArticleFilter {
        unread_only: false,
        saved_only: false,
        source: None,
        min_score: None,
        hours: None,
        limit: 500,
    };
    app.articles = async_db.get_articles(filter).await.unwrap_or_default();
    app.feeds = async_db.get_feeds().await.unwrap_or_default();
    app.apply_filter();

    if app.articles.is_empty() {
        app.set_status("No articles yet. Press R to fetch feeds.", false);
        let _ = action_tx.send(Action::RefreshFeeds).await;
    }

    let mut terminal = setup_terminal()?;
    let mut event_stream = EventStream::new();
    let mut tick = interval(Duration::from_millis(100));

    loop {
        terminal.draw(|f| draw(f, &app))?;

        tokio::select! {
            Some(Ok(event)) = event_stream.next() => {
                match event {
                    Event::Key(key) => {
                        handle_key(&mut app, key).await;
                        if app.should_quit {
                            break;
                        }
                    }
                    Event::Resize(_, _) => {}
                    _ => {}
                }
            }

            Some(action) = action_rx.recv() => {
                handle_action(&mut app, action, action_tx.clone()).await;
            }

            _ = tick.tick() => {
                if app.loading {
                    app.tick_spinner();
                }
            }
        }
    }

    restore_terminal(&mut terminal);
    Ok(())
}

async fn handle_action(app: &mut App, action: Action, action_tx: mpsc::Sender<Action>) {
    match action {
        Action::RefreshFeeds => {
            app.loading = true;
            app.set_status("Refreshing feeds…", false);

            let config = get_default_config();
            let db = app.db.clone();
            let db_feeds = app.feeds.clone();

            tokio::spawn(async move {
                let fetcher = FeedFetcher::new();

                let mut feed_list: Vec<(String, String, usize)> = db_feeds
                    .iter()
                    .filter(|f| f.enabled)
                    .map(|f| (f.name.clone(), f.url.clone(), 20usize))
                    .collect();

                if feed_list.is_empty() {
                    feed_list = config
                        .feeds
                        .iter()
                        .filter(|f| f.enabled)
                        .map(|f| (f.name.clone(), f.url.clone(), f.limit.unwrap_or(20)))
                        .collect();
                }

                let refs: Vec<(&str, &str, usize)> = feed_list
                    .iter()
                    .map(|(n, u, l)| (n.as_str(), u.as_str(), *l))
                    .collect();

                match fetcher.fetch_multiple(&refs).await {
                    Ok(articles) => {
                        let _ = db.save_articles(articles).await;
                        let filter = DbArticleFilter {
                            unread_only: false,
                            saved_only: false,
                            source: None,
                            min_score: None,
                            hours: None,
                            limit: 500,
                        };
                        match db.get_articles(filter).await {
                            Ok(refreshed) => {
                                let _ = action_tx.send(Action::FeedsRefreshed(refreshed)).await;
                            }
                            Err(e) => {
                                let _ = action_tx
                                    .send(Action::Error(format!("DB error: {}", e)))
                                    .await;
                            }
                        }
                    }
                    Err(e) => {
                        let _ = action_tx
                            .send(Action::Error(format!("Fetch error: {}", e)))
                            .await;
                    }
                }
            });
        }

        Action::FeedsRefreshed(articles) => {
            app.loading = false;
            let prev_len = app.articles.len();
            app.articles = articles;
            app.feeds = app.db.get_feeds().await.unwrap_or_default();
            app.apply_filter();
            let new_count = app.articles.len().saturating_sub(prev_len);
            app.set_status(format!("Refreshed. {} new article(s).", new_count), false);
        }

        Action::FetchContent(id) => {
            app.loading = true;
            app.set_status("Fetching article… (trying direct HTTP)", false);

            let db = app.db.clone();
            let url = app
                .articles
                .iter()
                .find(|a| a.id == id)
                .map(|a| a.link.clone());

            let Some(url) = url else {
                app.loading = false;
                app.set_status("Article not found", true);
                return;
            };

            tokio::spawn(async move {
                let fetcher = ArticleFetcher::new();

                // Inform the user which strategy is running
                let _ = action_tx
                    .send(Action::Info("Fetching article… (HTTP → browser → archive)".to_string()))
                    .await;

                match fetcher.fetch_with_fallbacks(&url).await {
                    Ok(markdown) => {
                        let _ = db.update_article_content(id.clone(), markdown.clone()).await;
                        let _ = action_tx
                            .send(Action::ContentFetched(id, markdown))
                            .await;
                    }
                    Err(e) => {
                        let _ = action_tx
                            .send(Action::Error(format!("Could not fetch: {}", e)))
                            .await;
                    }
                }
            });
        }

        Action::ContentFetched(id, content) => {
            app.loading = false;
            let via_browser = content.starts_with("*\\[Fetched via browser");
            let via_wayback = content.starts_with("*\\[Retrieved from Wayback");
            if let Some(a) = app.articles.iter_mut().find(|a| a.id == id) {
                a.content = Some(content);
            }
            let msg = if via_browser {
                "Content fetched via browser automation."
            } else if via_wayback {
                "Content fetched from Wayback Machine archive."
            } else {
                "Content fetched."
            };
            app.set_status(msg, false);
        }

        Action::AiSummarize(id) => {
            app.loading = true;
            app.set_status("Running AI summarization…", false);

            let db = app.db.clone();

            let article_opt: Option<Article> =
                app.articles.iter().find(|a| a.id == id).map(|a| Article {
                    id: a.id.clone(),
                    title: a.title.clone(),
                    link: a.link.clone(),
                    summary: a.summary.clone(),
                    content: a.content.clone(),
                    published: a.published.clone(),
                    source: a.source.clone(),
                    source_url: a.source_url.clone(),
                    score: a.score,
                    comments: a.comments,
                    tags: vec![],
                });

            let Some(article) = article_opt else {
                app.loading = false;
                return;
            };

            let model = std::env::var("AI_MODEL")
                .unwrap_or_else(|_| "MiniMax-M2.5-highspeed".to_string());

            tokio::spawn(async move {
                let ai_config = AiConfig {
                    enabled: true,
                    model,
                    temperature: 0.7,
                    max_tokens: 1024,
                    custom_prompt: None,
                };
                let summarizer = crate::summarizer::AiSummarizer::new(ai_config);
                match summarizer.summarize(&[article]).await {
                    Ok(summary) => {
                        let _ = db.update_article_summary(id.clone(), summary.clone()).await;
                        let _ = action_tx.send(Action::SummaryReady(id, summary)).await;
                    }
                    Err(e) => {
                        let _ = action_tx
                            .send(Action::Error(format!("AI error: {}", e)))
                            .await;
                    }
                }
            });
        }

        Action::SummaryReady(id, summary) => {
            app.loading = false;
            if let Some(a) = app.articles.iter_mut().find(|a| a.id == id) {
                a.summary = Some(summary);
            }
            app.set_status("AI summary ready.", false);
        }

        Action::AddFeed(url) => {
            let name = url
                .trim_start_matches("https://")
                .trim_start_matches("http://")
                .split('/')
                .next()
                .unwrap_or("Feed")
                .to_string();

            match app.db.add_feed(name.clone(), url.clone()).await {
                Ok(_) => {
                    app.feeds = app.db.get_feeds().await.unwrap_or_default();
                    app.set_status(format!("Added feed: {}", name), false);
                    let _ = action_tx.send(Action::RefreshFeeds).await;
                }
                Err(e) => {
                    app.set_status(format!("Error adding feed: {}", e), true);
                }
            }
        }

        Action::DeleteFeed(url) => {
            match app.db.delete_feed(url).await {
                Ok(_) => {
                    app.feeds = app.db.get_feeds().await.unwrap_or_default();
                    if app.selected_feed > 0 {
                        app.selected_feed -= 1;
                    }
                    app.set_status("Feed deleted.", false);
                }
                Err(e) => {
                    app.set_status(format!("Error deleting feed: {}", e), true);
                }
            }
        }

        Action::Error(msg) => {
            app.loading = false;
            app.set_status(msg, true);
        }

        Action::Info(msg) => {
            app.set_status(msg, false);
        }

        Action::FeedListRefreshed(feeds) => {
            app.feeds = feeds;
        }
    }
}
