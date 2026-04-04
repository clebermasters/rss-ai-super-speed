use rusqlite::{params, Connection, Result};
use std::sync::Mutex;

#[derive(Debug)]
pub struct Database {
    conn: Mutex<Connection>,
}

impl Database {
    pub fn new(path: &str) -> Result<Self> {
        let conn = Connection::open(path)?;
        let db = Self {
            conn: Mutex::new(conn),
        };
        db.init_tables()?;
        Ok(db)
    }

    pub fn new_in_memory() -> Result<Self> {
        let conn = Connection::open_in_memory()?;
        let db = Self {
            conn: Mutex::new(conn),
        };
        db.init_tables()?;
        Ok(db)
    }

    fn init_tables(&self) -> Result<()> {
        let conn = self.conn.lock().unwrap();

        conn.execute(
            "CREATE TABLE IF NOT EXISTS feeds (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                url TEXT NOT NULL UNIQUE,
                enabled INTEGER DEFAULT 1,
                tags TEXT,
                last_fetched TEXT
            )",
            [],
        )?;

        conn.execute(
            "CREATE TABLE IF NOT EXISTS articles (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                link TEXT NOT NULL UNIQUE,
                summary TEXT,
                content TEXT,
                published TEXT,
                source TEXT NOT NULL,
                source_url TEXT NOT NULL,
                score INTEGER,
                comments INTEGER,
                tags TEXT,
                is_read INTEGER DEFAULT 0,
                is_saved INTEGER DEFAULT 0,
                fetched_at TEXT NOT NULL,
                UNIQUE(link)
            )",
            [],
        )?;

        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_articles_source ON articles(source)",
            [],
        )?;

        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_articles_is_read ON articles(is_read)",
            [],
        )?;

        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_articles_published ON articles(published)",
            [],
        )?;

        Ok(())
    }

    // Feed operations
    pub fn add_feed(&self, name: &str, url: &str, tags: &[String]) -> Result<i64> {
        let conn = self.conn.lock().unwrap();
        let tags_str = tags.join(",");
        conn.execute(
            "INSERT OR IGNORE INTO feeds (name, url, tags) VALUES (?1, ?2, ?3)",
            params![name, url, tags_str],
        )?;
        Ok(conn.last_insert_rowid())
    }

    pub fn get_feeds(&self) -> Result<Vec<Feed>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, name, url, enabled, tags, last_fetched FROM feeds ORDER BY name",
        )?;

        let feeds = stmt
            .query_map([], |row| {
                let tags_str: String = row.get(4)?;
                let tags: Vec<String> = if tags_str.is_empty() {
                    vec![]
                } else {
                    tags_str.split(',').map(|s| s.to_string()).collect()
                };

                Ok(Feed {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    url: row.get(2)?,
                    enabled: row.get::<_, i32>(3)? == 1,
                    tags,
                    last_fetched: row.get(4)?,
                })
            })?
            .collect::<Result<Vec<_>>>()?;

        Ok(feeds)
    }

    pub fn update_feed_last_fetched(&self, url: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let now = chrono::Utc::now().to_rfc3339();
        conn.execute(
            "UPDATE feeds SET last_fetched = ?1 WHERE url = ?2",
            params![now, url],
        )?;
        Ok(())
    }

    // Article operations
    pub fn save_article(&self, article: &super::Article) -> Result<bool> {
        let conn = self.conn.lock().unwrap();
        let tags_str = article.tags.join(",");

        let result = conn.execute(
            "INSERT OR IGNORE INTO articles 
             (id, title, link, summary, content, published, source, source_url, score, comments, tags, fetched_at) 
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
            params![
                article.id,
                article.title,
                article.link,
                article.summary,
                article.content,
                article.published,
                article.source,
                article.source_url,
                article.score,
                article.comments,
                tags_str,
                chrono::Utc::now().to_rfc3339()
            ],
        )?;

        Ok(result > 0)
    }

    pub fn save_articles(&self, articles: &[super::Article]) -> Result<usize> {
        let mut saved = 0;
        for article in articles {
            if self.save_article(article)? {
                saved += 1;
            }
        }
        Ok(saved)
    }

    pub fn get_articles(&self, filter: DbArticleFilter) -> Result<Vec<ArticleWithState>> {
        let conn = self.conn.lock().unwrap();

        let mut sql = String::from(
            "SELECT id, title, link, summary, content, published, source, source_url, score, comments, is_read, is_saved \
             FROM articles WHERE 1=1"
        );

        let mut source_param: Option<String> = None;

        if filter.unread_only {
            sql.push_str(" AND is_read = 0");
        }

        if filter.saved_only {
            sql.push_str(" AND is_saved = 1");
        }

        if let Some(ref source) = filter.source {
            sql.push_str(" AND source = ?1");
            source_param = Some(source.clone());
        }

        if let Some(min_score) = filter.min_score {
            sql.push_str(&format!(" AND score >= {}", min_score));
        }

        if let Some(hours) = filter.hours {
            let cutoff = chrono::Utc::now() - chrono::Duration::hours(hours);
            sql.push_str(&format!(" AND fetched_at > '{}'", cutoff.to_rfc3339()));
        }

        sql.push_str(" ORDER BY published DESC");

        if filter.limit > 0 {
            sql.push_str(&format!(" LIMIT {}", filter.limit));
        }

        let mut stmt = conn.prepare(&sql)?;

        let map_row = |row: &rusqlite::Row| {
            Ok(ArticleWithState {
                id: row.get(0)?,
                title: row.get(1)?,
                link: row.get(2)?,
                summary: row.get(3)?,
                content: row.get(4)?,
                published: row.get(5)?,
                source: row.get(6)?,
                source_url: row.get(7)?,
                score: row.get(8)?,
                comments: row.get(9)?,
                is_read: row.get::<_, i32>(10)? == 1,
                is_saved: row.get::<_, i32>(11)? == 1,
            })
        };

        let articles = if let Some(ref src) = source_param {
            stmt.query_map(params![src], map_row)?
                .collect::<Result<Vec<_>>>()?
        } else {
            stmt.query_map([], map_row)?
                .collect::<Result<Vec<_>>>()?
        };

        Ok(articles)
    }

    pub fn mark_read(&self, article_id: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE articles SET is_read = 1 WHERE id = ?1",
            params![article_id],
        )?;
        Ok(())
    }

    pub fn mark_unread(&self, article_id: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE articles SET is_read = 0 WHERE id = ?1",
            params![article_id],
        )?;
        Ok(())
    }

    pub fn mark_all_read(&self) -> Result<usize> {
        let conn = self.conn.lock().unwrap();
        let count = conn.execute("UPDATE articles SET is_read = 1 WHERE is_read = 0", [])?;
        Ok(count)
    }

    pub fn toggle_save(&self, article_id: &str) -> Result<bool> {
        let conn = self.conn.lock().unwrap();

        let current: i32 = conn.query_row(
            "SELECT is_saved FROM articles WHERE id = ?1",
            params![article_id],
            |row| row.get(0),
        )?;

        let new_value = if current == 1 { 0 } else { 1 };

        conn.execute(
            "UPDATE articles SET is_saved = ?1 WHERE id = ?2",
            params![new_value, article_id],
        )?;

        Ok(new_value == 1)
    }

    pub fn get_unread_count(&self) -> Result<usize> {
        let conn = self.conn.lock().unwrap();
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM articles WHERE is_read = 0",
            [],
            |row| row.get(0),
        )?;
        Ok(count as usize)
    }

    pub fn get_total_count(&self) -> Result<usize> {
        let conn = self.conn.lock().unwrap();
        let count: i64 = conn.query_row("SELECT COUNT(*) FROM articles", [], |row| row.get(0))?;
        Ok(count as usize)
    }

    pub fn update_article_summary(&self, id: &str, summary: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE articles SET summary = ?1 WHERE id = ?2",
            params![summary, id],
        )?;
        Ok(())
    }

    pub fn update_article_content(&self, id: &str, content: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE articles SET content = ?1 WHERE id = ?2",
            params![content, id],
        )?;
        Ok(())
    }

    pub fn delete_feed(&self, url: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute("DELETE FROM feeds WHERE url = ?1", params![url])?;
        Ok(())
    }

    pub fn toggle_feed_enabled(&self, url: &str) -> Result<bool> {
        let conn = self.conn.lock().unwrap();
        let current: i32 = conn.query_row(
            "SELECT enabled FROM feeds WHERE url = ?1",
            params![url],
            |row| row.get(0),
        )?;
        let new_value = if current == 1 { 0 } else { 1 };
        conn.execute(
            "UPDATE feeds SET enabled = ?1 WHERE url = ?2",
            params![new_value, url],
        )?;
        Ok(new_value == 1)
    }

    pub fn search_articles(&self, query: &str, limit: usize) -> Result<Vec<ArticleWithState>> {
        let conn = self.conn.lock().unwrap();
        let search = format!("%{}%", query);

        let mut stmt = conn.prepare(
            "SELECT id, title, link, summary, content, published, source, source_url, score, comments, is_read, is_saved \
             FROM articles \
             WHERE title LIKE ?1 OR summary LIKE ?1 OR source LIKE ?1 \
             ORDER BY published DESC \
             LIMIT ?2"
        )?;

        let articles = stmt
            .query_map(params![search, limit as i64], |row| {
                Ok(ArticleWithState {
                    id: row.get(0)?,
                    title: row.get(1)?,
                    link: row.get(2)?,
                    summary: row.get(3)?,
                    content: row.get(4)?,
                    published: row.get(5)?,
                    source: row.get(6)?,
                    source_url: row.get(7)?,
                    score: row.get(8)?,
                    comments: row.get(9)?,
                    is_read: row.get::<_, i32>(10)? == 1,
                    is_saved: row.get::<_, i32>(11)? == 1,
                })
            })?
            .collect::<Result<Vec<_>>>()?;

        Ok(articles)
    }

    pub fn get_article_by_id(&self, id: &str) -> Result<Option<ArticleWithState>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, title, link, summary, content, published, source, source_url, score, comments, is_read, is_saved \
             FROM articles WHERE id = ?1"
        )?;

        let result = stmt.query_row(params![id], |row| {
            Ok(ArticleWithState {
                id: row.get(0)?,
                title: row.get(1)?,
                link: row.get(2)?,
                summary: row.get(3)?,
                content: row.get(4)?,
                published: row.get(5)?,
                source: row.get(6)?,
                source_url: row.get(7)?,
                score: row.get(8)?,
                comments: row.get(9)?,
                is_read: row.get::<_, i32>(10)? == 1,
                is_saved: row.get::<_, i32>(11)? == 1,
            })
        });

        match result {
            Ok(article) => Ok(Some(article)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(e),
        }
    }

    pub fn clear_old_articles(&self, days: i64) -> Result<usize> {
        let conn = self.conn.lock().unwrap();
        let cutoff = chrono::Utc::now() - chrono::Duration::days(days);

        let count = conn.execute(
            "DELETE FROM articles WHERE fetched_at < ?1 AND is_saved = 0 AND is_read = 1",
            params![cutoff.to_rfc3339()],
        )?;

        Ok(count)
    }
}

#[derive(Debug, Clone)]
pub struct Feed {
    pub id: i64,
    pub name: String,
    pub url: String,
    pub enabled: bool,
    pub tags: Vec<String>,
    pub last_fetched: Option<String>,
}

#[derive(Debug, Clone)]
pub struct ArticleWithState {
    pub id: String,
    pub title: String,
    pub link: String,
    pub summary: Option<String>,
    pub content: Option<String>,
    pub published: Option<String>,
    pub source: String,
    pub source_url: String,
    pub score: Option<i32>,
    pub comments: Option<i32>,
    pub is_read: bool,
    pub is_saved: bool,
}

#[derive(Debug, Clone, Default)]
pub struct DbArticleFilter {
    pub unread_only: bool,
    pub saved_only: bool,
    pub source: Option<String>,
    pub min_score: Option<i32>,
    pub hours: Option<i64>,
    pub limit: usize,
}
