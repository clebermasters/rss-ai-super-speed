use crate::{Article, SearchConfig};
use fuzzy_matcher::skim::SkimMatcherV2;
use fuzzy_matcher::FuzzyMatcher;

pub struct ArticleSearch {
    config: SearchConfig,
    matcher: SkimMatcherV2,
}

impl ArticleSearch {
    pub fn new(config: SearchConfig) -> Self {
        Self {
            config,
            matcher: SkimMatcherV2::default(),
        }
    }

    pub fn search(&self, articles: Vec<Article>, query: &str) -> Vec<Article> {
        if query.is_empty() {
            return articles;
        }

        if self.config.fuzzy {
            self.fuzzy_search(articles, query)
        } else {
            self.exact_search(articles, query)
        }
    }

    fn fuzzy_search(&self, articles: Vec<Article>, query: &str) -> Vec<Article> {
        let mut scored: Vec<(i64, Article)> = articles
            .into_iter()
            .filter_map(|article| {
                let text = article.text_for_search();
                self.matcher
                    .fuzzy_match(&text, query)
                    .map(|score| (score, article))
            })
            .collect();

        // Sort by score (highest first)
        scored.sort_by(|a, b| b.0.cmp(&a.0));

        // Limit results
        scored
            .into_iter()
            .take(self.config.max_results)
            .map(|(_, a)| a)
            .collect()
    }

    fn exact_search(&self, articles: Vec<Article>, query: &str) -> Vec<Article> {
        let query_lower = if self.config.case_sensitive {
            query.to_string()
        } else {
            query.to_lowercase()
        };

        articles
            .into_iter()
            .filter(|article| {
                let text = if self.config.case_sensitive {
                    article.text_for_search()
                } else {
                    article.text_for_search().to_lowercase()
                };
                text.contains(&query_lower)
            })
            .take(self.config.max_results)
            .collect()
    }

    pub fn search_with_scores(&self, articles: Vec<Article>, query: &str) -> Vec<(i64, Article)> {
        if query.is_empty() {
            return articles.into_iter().map(|a| (0, a)).collect();
        }

        if self.config.fuzzy {
            let mut scored: Vec<(i64, Article)> = articles
                .into_iter()
                .filter_map(|article| {
                    let text = article.text_for_search();
                    self.matcher
                        .fuzzy_match(&text, query)
                        .map(|score| (score, article))
                })
                .collect();

            scored.sort_by(|a, b| b.0.cmp(&a.0));
            scored
        } else {
            let query_lower = if self.config.case_sensitive {
                query.to_string()
            } else {
                query.to_lowercase()
            };

            articles
                .into_iter()
                .filter(|article| {
                    let text = if self.config.case_sensitive {
                        article.text_for_search()
                    } else {
                        article.text_for_search().to_lowercase()
                    };
                    text.contains(&query_lower)
                })
                .map(|a| {
                    // For exact search, give high score to matches
                    let score = if a.title.to_lowercase().contains(&query_lower) {
                        1000
                    } else {
                        100
                    };
                    (score, a)
                })
                .collect()
        }
    }

    pub fn rank_by_relevance(&self, articles: Vec<Article>, query: &str) -> Vec<Article> {
        if query.is_empty() {
            return articles;
        }

        let query_lower = query.to_lowercase();

        let mut scored: Vec<(i32, Article)> = articles
            .into_iter()
            .map(|article| {
                let text = article.text_for_search().to_lowercase();
                let mut score = 0i32;

                // Title matches are worth more
                if text.contains(&query_lower) {
                    score += 10;
                }
                if article.title.to_lowercase().contains(&query_lower) {
                    score += 100;
                }

                // Boost by HN score if available
                if let Some(s) = article.score {
                    score += (s / 10) as i32;
                }

                (score, article)
            })
            .collect();

        scored.sort_by(|a, b| b.0.cmp(&a.0));

        scored
            .into_iter()
            .take(self.config.max_results)
            .map(|(_, a)| a)
            .collect()
    }
}

impl Default for ArticleSearch {
    fn default() -> Self {
        Self::new(SearchConfig::default())
    }
}

pub fn search_articles(articles: Vec<Article>, config: &SearchConfig, query: &str) -> Vec<Article> {
    ArticleSearch::new(config.clone()).search(articles, query)
}
