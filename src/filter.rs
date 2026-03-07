use crate::{Article, FilterConfig};

pub struct ArticleFilter {
    config: FilterConfig,
}

impl ArticleFilter {
    pub fn new(config: FilterConfig) -> Self {
        Self { config }
    }

    pub fn filter(&self, articles: Vec<Article>) -> Vec<Article> {
        articles
            .into_iter()
            .filter(|article| self.matches(article))
            .collect()
    }

    pub fn matches(&self, article: &Article) -> bool {
        // Check minimum score
        if let Some(min_score) = self.config.min_score {
            if let Some(score) = article.score {
                if score < min_score {
                    return false;
                }
            }
        }

        // Check keywords to include (must match at least one if specified)
        if !self.config.keywords_include.is_empty() {
            let text = article.text_for_search().to_lowercase();
            let matches = self
                .config
                .keywords_include
                .iter()
                .any(|kw| text.contains(&kw.to_lowercase()));
            if !matches {
                return false;
            }
        }

        // Check keywords to exclude
        if !self.config.keywords_exclude.is_empty() {
            let text = article.text_for_search().to_lowercase();
            let matches = self
                .config
                .keywords_exclude
                .iter()
                .any(|kw| text.contains(&kw.to_lowercase()));
            if matches {
                return false;
            }
        }

        // Check time range
        if let Some(hours) = self.config.time_range_hours {
            if let Some(published) = &article.published {
                if let Ok(dt) = chrono::DateTime::parse_from_rfc3339(published) {
                    let now = chrono::Utc::now();
                    let duration = now.signed_duration_since(dt);
                    if duration.num_hours() > hours {
                        return false;
                    }
                }
            }
        }

        // Check specific sources
        if !self.config.sources.is_empty() {
            if !self
                .config
                .sources
                .iter()
                .any(|s| article.source.to_lowercase().contains(&s.to_lowercase()))
            {
                return false;
            }
        }

        true
    }

    pub fn filter_by_score(&self, articles: Vec<Article>, min_score: i32) -> Vec<Article> {
        articles
            .into_iter()
            .filter(|a| a.score.unwrap_or(0) >= min_score)
            .collect()
    }

    pub fn filter_by_source(&self, articles: Vec<Article>, sources: &[String]) -> Vec<Article> {
        if sources.is_empty() {
            return articles;
        }
        articles
            .into_iter()
            .filter(|a| sources.iter().any(|s| a.source.contains(s)))
            .collect()
    }

    pub fn filter_by_keywords(
        &self,
        articles: Vec<Article>,
        include: &[String],
        exclude: &[String],
    ) -> Vec<Article> {
        articles
            .into_iter()
            .filter(|a| {
                let text = a.text_for_search().to_lowercase();

                // Must match at least one include keyword (if specified)
                let matches_include = include.is_empty()
                    || include.iter().any(|kw| text.contains(&kw.to_lowercase()));

                // Must not match any exclude keyword
                let matches_exclude = exclude.is_empty()
                    || !exclude.iter().any(|kw| text.contains(&kw.to_lowercase()));

                matches_include && matches_exclude
            })
            .collect()
    }

    pub fn filter_by_time(&self, articles: Vec<Article>, hours: i64) -> Vec<Article> {
        articles
            .into_iter()
            .filter(|a| {
                if let Some(published) = &a.published {
                    if let Ok(dt) = chrono::DateTime::parse_from_rfc3339(published) {
                        let now = chrono::Utc::now();
                        return now.signed_duration_since(dt).num_hours() <= hours;
                    }
                }
                // Keep articles without published date
                true
            })
            .collect()
    }
}

impl Default for ArticleFilter {
    fn default() -> Self {
        Self::new(FilterConfig::default())
    }
}

pub fn filter_articles(articles: Vec<Article>, config: &FilterConfig) -> Vec<Article> {
    ArticleFilter::new(config.clone()).filter(articles)
}
