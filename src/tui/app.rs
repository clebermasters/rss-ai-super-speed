use std::sync::Arc;

use crate::database::{ArticleWithState, Database, DbArticleFilter, Feed};
use crate::Article;
use tokio::sync::mpsc;

#[derive(Debug, Clone, PartialEq)]
pub enum Mode {
    Normal,
    Search,
    FeedInput,
    Help,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Panel {
    Feeds,
    Articles,
    Reader,
}

impl Panel {
    pub fn next(&self) -> Panel {
        match self {
            Panel::Feeds => Panel::Articles,
            Panel::Articles => Panel::Reader,
            Panel::Reader => Panel::Feeds,
        }
    }
}

#[derive(Debug, Clone)]
pub enum Action {
    // User-triggered background ops
    RefreshFeeds,
    FetchContent(String),
    AiSummarize(String),
    AddFeed(String),
    DeleteFeed(String),
    // Results from background tasks
    FeedsRefreshed(Vec<ArticleWithState>),
    ContentFetched(String, String),
    SummaryReady(String, String),
    FeedListRefreshed(Vec<Feed>),
    Error(String),
    Info(String),
}

/// Async wrapper around Database that runs all blocking calls on the thread pool.
#[derive(Clone)]
pub struct AsyncDatabase(pub Arc<Database>);

impl AsyncDatabase {
    pub fn new(db: Database) -> Self {
        Self(Arc::new(db))
    }

    pub async fn get_articles(
        &self,
        filter: DbArticleFilter,
    ) -> rusqlite::Result<Vec<ArticleWithState>> {
        let db = Arc::clone(&self.0);
        tokio::task::spawn_blocking(move || db.get_articles(filter))
            .await
            .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?
    }

    pub async fn get_feeds(&self) -> rusqlite::Result<Vec<Feed>> {
        let db = Arc::clone(&self.0);
        tokio::task::spawn_blocking(move || db.get_feeds())
            .await
            .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?
    }

    pub async fn mark_read(&self, id: String) -> rusqlite::Result<()> {
        let db = Arc::clone(&self.0);
        tokio::task::spawn_blocking(move || db.mark_read(&id))
            .await
            .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?
    }

    pub async fn mark_unread(&self, id: String) -> rusqlite::Result<()> {
        let db = Arc::clone(&self.0);
        tokio::task::spawn_blocking(move || db.mark_unread(&id))
            .await
            .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?
    }

    pub async fn toggle_save(&self, id: String) -> rusqlite::Result<bool> {
        let db = Arc::clone(&self.0);
        tokio::task::spawn_blocking(move || db.toggle_save(&id))
            .await
            .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?
    }

    pub async fn update_article_summary(
        &self,
        id: String,
        summary: String,
    ) -> rusqlite::Result<()> {
        let db = Arc::clone(&self.0);
        tokio::task::spawn_blocking(move || db.update_article_summary(&id, &summary))
            .await
            .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?
    }

    pub async fn update_article_content(
        &self,
        id: String,
        content: String,
    ) -> rusqlite::Result<()> {
        let db = Arc::clone(&self.0);
        tokio::task::spawn_blocking(move || db.update_article_content(&id, &content))
            .await
            .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?
    }

    pub async fn add_feed(&self, name: String, url: String) -> rusqlite::Result<i64> {
        let db = Arc::clone(&self.0);
        tokio::task::spawn_blocking(move || db.add_feed(&name, &url, &[]))
            .await
            .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?
    }

    pub async fn delete_feed(&self, url: String) -> rusqlite::Result<()> {
        let db = Arc::clone(&self.0);
        tokio::task::spawn_blocking(move || db.delete_feed(&url))
            .await
            .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?
    }

    pub async fn save_articles(&self, articles: Vec<Article>) -> rusqlite::Result<usize> {
        let db = Arc::clone(&self.0);
        tokio::task::spawn_blocking(move || db.save_articles(&articles))
            .await
            .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?
    }
}

pub struct App {
    pub mode: Mode,
    pub focus: Panel,
    pub feeds: Vec<Feed>,
    pub articles: Vec<ArticleWithState>,
    pub filtered_indices: Vec<usize>,
    pub selected_feed: usize,
    pub selected_article: usize,
    pub reader_scroll: u16,
    pub search_query: String,
    pub feed_input: String,
    pub status_message: Option<String>,
    pub status_is_error: bool,
    pub loading: bool,
    pub spinner_tick: u8,
    pub db: AsyncDatabase,
    pub action_tx: mpsc::Sender<Action>,
    pub should_quit: bool,
    pub hidden_ids: std::collections::HashSet<String>,
}

impl App {
    pub fn new(db: AsyncDatabase, action_tx: mpsc::Sender<Action>) -> Self {
        Self {
            mode: Mode::Normal,
            focus: Panel::Articles,
            feeds: Vec::new(),
            articles: Vec::new(),
            filtered_indices: Vec::new(),
            selected_feed: 0,
            selected_article: 0,
            reader_scroll: 0,
            search_query: String::new(),
            feed_input: String::new(),
            status_message: None,
            status_is_error: false,
            loading: false,
            spinner_tick: 0,
            db,
            action_tx,
            should_quit: false,
            hidden_ids: std::collections::HashSet::new(),
        }
    }

    pub fn set_status(&mut self, msg: impl Into<String>, is_error: bool) {
        self.status_message = Some(msg.into());
        self.status_is_error = is_error;
    }

    pub fn tick_spinner(&mut self) {
        self.spinner_tick = self.spinner_tick.wrapping_add(1);
    }

    pub fn spinner_char(&self) -> char {
        let frames = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];
        frames[(self.spinner_tick as usize) % frames.len()]
    }

    pub fn apply_filter(&mut self) {
        if self.search_query.is_empty() {
            self.filtered_indices = (0..self.articles.len())
                .filter(|i| !self.hidden_ids.contains(&self.articles[*i].id))
                .collect();
        } else {
            let q = self.search_query.to_lowercase();
            self.filtered_indices = self
                .articles
                .iter()
                .enumerate()
                .filter(|(_, a)| {
                    !self.hidden_ids.contains(&a.id)
                        && (a.title.to_lowercase().contains(&q)
                            || a.source.to_lowercase().contains(&q)
                            || a.summary
                                .as_deref()
                                .unwrap_or("")
                                .to_lowercase()
                                .contains(&q))
                })
                .map(|(i, _)| i)
                .collect();
        }
        if !self.filtered_indices.is_empty()
            && self.selected_article >= self.filtered_indices.len()
        {
            self.selected_article = self.filtered_indices.len() - 1;
        }
    }

    pub fn selected_article_ref(&self) -> Option<&ArticleWithState> {
        let idx = self.filtered_indices.get(self.selected_article)?;
        self.articles.get(*idx)
    }

    pub fn selected_article_mut(&mut self) -> Option<&mut ArticleWithState> {
        let idx = *self.filtered_indices.get(self.selected_article)?;
        self.articles.get_mut(idx)
    }

    pub fn unread_count(&self) -> usize {
        self.articles.iter().filter(|a| !a.is_read).count()
    }

    pub fn move_next_unread(&mut self) {
        let start = self.selected_article + 1;
        for i in start..self.filtered_indices.len() {
            let idx = self.filtered_indices[i];
            if !self.articles[idx].is_read {
                self.selected_article = i;
                return;
            }
        }
    }

    pub fn move_prev_unread(&mut self) {
        if self.selected_article == 0 {
            return;
        }
        for i in (0..self.selected_article).rev() {
            let idx = self.filtered_indices[i];
            if !self.articles[idx].is_read {
                self.selected_article = i;
                return;
            }
        }
    }
}
