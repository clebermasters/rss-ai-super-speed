use clap::{Parser, Subcommand, ValueEnum};
use rss_ai::{
    get_default_config, AiConfig, Article, ArticleFilter, ArticleSearch, Config,
    FeedFetcher, FilterConfig, OutputConfig, SearchConfig, Database,
    summarizer::AiSummarizer, output::OutputFormatter, database::DbArticleFilter,
    VectorStore, ArticleFetcher,
};

#[derive(Parser, Debug)]
#[command(name = "rss-ai")]
#[command(about = "Modular RSS AI Aggregator with filters, fuzzy search, and AI summarization", long_about = None)]
struct Args {
    #[command(subcommand)]
    command: Option<Commands>,
    /// RSS feed URLs (can be specified multiple times)
    #[arg(long, value_name = "URL")]
    feeds: Vec<String>,

    /// Config file (YAML)
    #[arg(short, long)]
    config: Option<String>,

    /// Number of articles to fetch per feed
    #[arg(short = 'n', long, default_value = "10")]
    limit: usize,

    /// Search query (fuzzy search)
    #[arg(short, long)]
    search: Option<String>,

    /// Use fuzzy search (default: exact)
    #[arg(long)]
    fuzzy: bool,

    /// Filter by minimum score
    #[arg(long)]
    min_score: Option<i32>,

    /// Filter by keywords (include)
    #[arg(long = "include", value_delimiter = ',')]
    keywords_include: Vec<String>,

    /// Filter by keywords (exclude)
    #[arg(long = "exclude", value_delimiter = ',')]
    keywords_exclude: Vec<String>,

    /// Filter by sources
    #[arg(long, value_delimiter = ',')]
    sources: Vec<String>,

    /// Filter by time range (hours)
    #[arg(long)]
    hours: Option<i64>,

    /// Enable AI summarization
    #[arg(long)]
    summarize: bool,

    /// AI model to use
    #[arg(long, default_value = "MiniMax-M2.5-highspeed")]
    model: String,

    /// Custom AI prompt
    #[arg(long)]
    prompt: Option<String>,

    /// Output format
    #[arg(short, long, value_enum, default_value = "json")]
    format: OutputFormatArg,

    /// Output file (default: stdout)
    #[arg(short, long)]
    output: Option<String>,

    /// Pretty print JSON/Markdown
    #[arg(short, long)]
    pretty: bool,

    /// Maximum results to return
    #[arg(long, default_value = "50")]
    max_results: usize,

    /// Show only titles (quiet mode)
    #[arg(short, long)]
    quiet: bool,

    /// List default feeds and exit
    #[arg(long)]
    list_feeds: bool,

    /// Database file path (default: ~/.rss-ai/rss-ai.db)
    #[arg(long)]
    db: Option<String>,

    /// Show only unread articles from database
    #[arg(long)]
    unread: bool,

    /// Show only saved articles from database
    #[arg(long)]
    saved: bool,

    /// Mark article as read (provide article ID)
    #[arg(long)]
    mark_read: Option<String>,

    /// Mark article as unread (provide article ID)
    #[arg(long)]
    mark_unread: Option<String>,

    /// Save article for later (provide article ID)
    #[arg(long)]
    save: Option<String>,

    /// Unsave article (provide article ID)
    #[arg(long)]
    unsave: Option<String>,

    /// Mark all articles as read
    #[arg(long)]
    mark_all_read: bool,

    /// Sync mode: only fetch new articles (deduplication)
    #[arg(long)]
    sync: bool,

    /// Clear old read articles (days, default: 30)
    #[arg(long)]
    cleanup: Option<i64>,

    /// Show statistics
    #[arg(long)]
    stats: bool,

    /// Semantic vector search (uses Ollama for embeddings)
    #[arg(long)]
    semantic_search: Option<String>,

    /// Generate embeddings for articles (uses Ollama, stores in vector DB)
    #[arg(long)]
    embed: bool,

    /// Rebuild vector index from stored embeddings
    #[arg(long)]
    rebuild_index: bool,

    /// Ollama URL (default: http://localhost:11434)
    #[arg(long)]
    ollama_url: Option<String>,

    /// Fetch full article content (provide article ID or URL)
    #[arg(long, value_name = "ID or URL")]
    fetch_full: Option<String>,

    /// Open article URL in browser
    #[arg(long, value_name = "ID or URL")]
    open: Option<String>,

    /// Use browser automation when content is blocked (Cloudflare, etc)
    #[arg(long)]
    force_browser: bool,

    /// Test content fetch strategies against a URL and report which worked
    #[arg(long, value_name = "URL")]
    test_fetch: Option<String>,
}

#[derive(Subcommand, Debug)]
enum Commands {
    /// Launch the interactive TUI reader
    #[cfg(feature = "tui")]
    Tui {
        /// Database file path (default: ~/.rss-ai/rss-ai.db)
        #[arg(long)]
        db: Option<String>,
    },
}

#[derive(Debug, Clone, ValueEnum)]
enum OutputFormatArg {
    Json,
    Markdown,
    Html,
    Csv,
}

impl From<OutputFormatArg> for rss_ai::OutputFormat {
    fn from(arg: OutputFormatArg) -> Self {
        match arg {
            OutputFormatArg::Json => rss_ai::OutputFormat::Json,
            OutputFormatArg::Markdown => rss_ai::OutputFormat::Markdown,
            OutputFormatArg::Html => rss_ai::OutputFormat::Html,
            OutputFormatArg::Csv => rss_ai::OutputFormat::Csv,
        }
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Load .env file (searches cwd, exe dir, ~/.rss-ai/)
    rss_ai::load_env();

    let args = Args::parse();

    // Handle subcommands first
    #[cfg(feature = "tui")]
    if let Some(Commands::Tui { db }) = args.command {
        let db_path = db.unwrap_or_else(|| {
            let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
            format!("{}/.rss-ai/rss-ai.db", home)
        });
        if let Some(parent) = std::path::Path::new(&db_path).parent() {
            std::fs::create_dir_all(parent).ok();
        }
        return rss_ai::tui::run(&db_path).await.map_err(|e| e.to_string().into());
    }

    // Load config
    let mut config = if let Some(config_path) = &args.config {
        Config::from_file(config_path).unwrap_or_else(|_| get_default_config())
    } else {
        get_default_config()
    };

    // Override config with CLI args
    config.search.fuzzy = args.fuzzy;
    config.search.max_results = args.max_results;

    // List feeds and exit
    if args.list_feeds {
        println!("Default feeds:");
        for feed in &config.feeds {
            println!("  - {} ({})", feed.name, feed.url);
        }
        return Ok(());
    }

    // Initialize database
    let db_path = args.db.unwrap_or_else(|| {
        let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
        format!("{}/.rss-ai/rss-ai.db", home)
    });
    
    // Ensure directory exists
    if let Some(parent) = std::path::Path::new(&db_path).parent() {
        std::fs::create_dir_all(parent).ok();
    }
    
    let db = Database::new(&db_path)?;

    // Initialize vector store
    let vector_store = match VectorStore::new(&db_path) {
        Ok(vs) => Some(vs),
        Err(e) => {
            eprintln!("Warning: Could not initialize vector store: {}", e);
            None
        }
    };

    // Handle vector operations
    if args.rebuild_index {
        if let Some(ref vs) = vector_store {
            vs.clear()?;
            println!("Vector index cleared.");
        }
        return Ok(());
    }

    if args.embed {
        if let Some(ref vs) = vector_store {
            println!("Generating embeddings with Ollama (nomic-embed-text)...", );

            let filter = DbArticleFilter {
                unread_only: false,
                saved_only: false,
                source: None,
                min_score: None,
                hours: None,
                limit: 1000,
            };

            let db_articles = db.get_articles(filter)?;
            println!("Generating embeddings for {} articles...", db_articles.len());

            for article in &db_articles {
                let text = format!("{} {} {}", article.title, article.source, article.summary.as_deref().unwrap_or(""));
                
                match vs.generate_embedding(&text).await {
                    Ok(embedding) => {
                        if let Err(e) = vs.save_embedding(&article.id, &embedding) {
                            eprintln!("Error saving embedding for {}: {}", article.id, e);
                        }
                    }
                    Err(e) => {
                        eprintln!("Error generating embedding for {}: {}", article.id, e);
                    }
                }
            }

            println!("Embeddings generated and stored.");
        }
        return Ok(());
    }

    if let Some(ref query) = args.semantic_search {
        if let Some(ref vs) = vector_store {
            println!("Searching for: {}\n", query);

            let embedding = vs.generate_embedding(query).await.map_err(|e| format!("{}", e))?;
            let results = vs.search(&embedding, args.max_results);

            for (article_id, similarity) in results {
                if let Ok(Some(article)) = db.get_article_by_id(&article_id) {
                    println!("[similarity: {:.2}] {}", similarity, article.title);
                    println!("   {}", article.link);
                    println!();
                }
            }
        }
        return Ok(());
    }

    // Handle database operations
    if args.stats {
        let total = db.get_total_count()?;
        let unread = db.get_unread_count()?;
        println!("Total articles: {}", total);
        println!("Unread articles: {}", unread);
        return Ok(());
    }

    if let Some(id) = &args.mark_read {
        db.mark_read(id)?;
        println!("Marked article {} as read", id);
        return Ok(());
    }

    if let Some(id) = &args.mark_unread {
        db.mark_unread(id)?;
        println!("Marked article {} as unread", id);
        return Ok(());
    }

    if let Some(id) = &args.save {
        let saved = db.toggle_save(id)?;
        println!("Article {} is now {}", id, if saved { "saved" } else { "unsaved" });
        return Ok(());
    }

    if let Some(id) = &args.unsave {
        // First get current state, then toggle
        if let Ok(Some(_article)) = db.get_article_by_id(id) {
            db.toggle_save(id)?;
            println!("Article {} is now unsaved", id);
        } else {
            println!("Article {} not found", id);
        }
        return Ok(());
    }

    if args.mark_all_read {
        let count = db.mark_all_read()?;
        println!("Marked {} articles as read", count);
        return Ok(());
    }

    if let Some(days) = args.cleanup {
        let count = db.clear_old_articles(days)?;
        println!("Cleared {} old articles", count);
        return Ok(());
    }

    // Handle test-fetch: probe all bypass strategies and report results
    if let Some(url) = &args.test_fetch {
        println!("Testing content fetch for: {}\n", url);
        let fetcher = ArticleFetcher::new();

        // Strategy 1: Direct HTTP
        print!("[1/3] Direct HTTP ... ");
        match fetcher.fetch_article(url).await {
            Ok(content) if !content.trim().is_empty() => {
                println!("✓ OK ({} chars)", content.len());
                println!("    Preview: {}", &content[..content.len().min(120)].replace('\n', " "));
            }
            Ok(_) => println!("✗ Empty response"),
            Err(e) => println!("✗ {}", e),
        }

        // Strategy 2: agent-browser
        print!("[2/3] agent-browser (Cloudflare bypass) ... ");
        match rss_ai::ArticleFetcher::fetch_via_browser_test(url).await {
            Ok(content) => {
                println!("✓ OK ({} chars)", content.len());
                println!("    Preview: {}", &content[..content.len().min(120)].replace('\n', " "));
            }
            Err(e) => println!("✗ {}", e),
        }

        // Strategy 3: Wayback Machine
        print!("[3/3] Wayback Machine ... ");
        match rss_ai::ArticleFetcher::fetch_via_wayback_test(url).await {
            Ok(content) => {
                println!("✓ OK ({} chars)", content.len());
                println!("    Preview: {}", &content[..content.len().min(120)].replace('\n', " "));
            }
            Err(e) => println!("✗ {}", e),
        }

        // Show what the combined fallback chain actually produces
        println!("\n--- Combined result (fetch_with_fallbacks) ---");
        match fetcher.fetch_with_fallbacks(url).await {
            Ok(raw_content) => {
                println!("✓ SUCCESS ({} chars)\n", raw_content.len());
                let preview: String = raw_content.chars().take(500).collect();
                println!("{}", preview);
                if raw_content.len() > 500 { println!("\n[... {} more chars]", raw_content.len() - 500); }

                // AI formatting pass
                let api_key = std::env::var("MINIMAX_API_KEY").unwrap_or_default();
                if !api_key.is_empty() {
                    println!("\n--- AI-formatted content (MINIMAX_API_KEY detected) ---");
                    let ai_config = rss_ai::AiConfig {
                        enabled: true,
                        model: std::env::var("AI_MODEL")
                            .unwrap_or_else(|_| "MiniMax-M2.5-highspeed".to_string()),
                        temperature: 0.3,
                        max_tokens: 4096,
                        custom_prompt: None,
                    };
                    let summarizer = rss_ai::summarizer::AiSummarizer::new(ai_config);
                    let formatted = summarizer.format_article_content(&raw_content).await;
                    println!("{}", formatted);
                } else {
                    println!("\nTip: set MINIMAX_API_KEY to enable AI content formatting.");
                }
            }
            Err(e) => println!("✗ All strategies failed: {}", e),
        }
        println!("\nTip: use --fetch-full <url> to fetch and display full content.");
        return Ok(());
    }

    // Handle fetch-full (get full article content)
    if let Some(id_or_url) = &args.fetch_full {
        let (url, article_id) = if id_or_url.starts_with("http") {
            (id_or_url.clone(), None)
        } else {
            // It's an article ID, look up the URL
            match db.get_article_by_id(id_or_url) {
                Ok(Some(article)) => (article.link, Some(id_or_url.clone())),
                Ok(None) => {
                    println!("Article {} not found", id_or_url);
                    return Ok(());
                }
                Err(e) => {
                    println!("Error: {}", e);
                    return Ok(());
                }
            }
        };

        println!("Fetching article content with Rust (soup + MiniMax)...\n");

        let api_key = std::env::var("MINIMAX_API_KEY").unwrap_or_default();

        if api_key.is_empty() {
            println!("Error: MINIMAX_API_KEY not set");
            return Ok(());
        }

        let fetcher = ArticleFetcher::new();
        match fetcher.fetch_and_summarize(&url, &api_key).await {
            Ok(article) => {
                println!("{}", article);
                
                // Auto-mark as read if we have an article ID
                if let Some(ref id) = article_id {
                    if let Err(e) = db.mark_read(id) {
                        eprintln!("Warning: Could not mark article as read: {}", e);
                    } else {
                        println!("\n[Marked as read]");
                    }
                }
            }
            Err(e) => {
                let error_msg = e.to_string();
                
                // Check if blocked and force_browser is enabled
                if error_msg.contains("BLOCKED") && args.force_browser {
                    println!("Content blocked. Using browser automation...\n");
                    
                    // Use agent-browser to get content
                    let output = std::process::Command::new("agent-browser")
                        .args(["open", &url])
                        .output();
                    
                    if output.is_err() || !output.as_ref().map(|o| o.status.success()).unwrap_or(false) {
                        eprintln!("Error: Could not open browser");
                        return Ok(());
                    }
                    
                    // Get page text
                    let text_output = std::process::Command::new("agent-browser")
                        .args(["get", "text", "body"])
                        .output();
                    
                    if let Ok(out) = text_output {
                        if out.status.success() {
                            let content = String::from_utf8_lossy(&out.stdout);
                            
                            // Now use MiniMax to extract
                            let api_key = std::env::var("MINIMAX_API_KEY").unwrap_or_else(|_| String::new());
                            if api_key.is_empty() {
                                // Just print raw content
                                println!("# Article Content\n\n**Source:** {}\n\n---\n\n{}", url, content);
                            } else {
                                // Use MiniMax to clean
                                let fetcher = rss_ai::ArticleFetcher::new();
                                match fetcher.fetch_and_summarize(&url, &api_key).await {
                                    Ok(article) => {
                                        println!("{}", article);
                                        
                                        if let Some(ref id) = article_id {
                                            db.mark_read(id).ok();
                                            println!("\n[Marked as read]");
                                        }
                                    }
                                    Err(_) => {
                                        println!("# Article Content\n\n**Source:** {}\n\n---\n\n{}", url, content);
                                    }
                                }
                            }
                            
                            // Close browser
                            std::process::Command::new("agent-browser")
                                .arg("close")
                                .spawn()
                                .ok();
                            
                            return Ok(());
                        }
                    }
                    
                    // Try screenshot instead
                    std::process::Command::new("agent-browser")
                        .arg("close")
                        .spawn()
                        .ok();
                    
                    eprintln!("Could not extract content from browser");
                    return Ok(());
                }
                
                if error_msg.contains("BLOCKED") {
                    println!("⚠️  Content blocked by Cloudflare or similar protection.");
                    println!("   The site requires browser verification.");
                    println!("\n   To auto-fetch using browser automation, run with:");
                    println!("   rss-ai --fetch-full <id> --force-browser");
                    println!("\n   Or open directly in your browser:");
                    println!("   rss-ai --open {}", url);
                } else {
                    eprintln!("Error fetching article: {}", e);
                }
            }
        }
        return Ok(());
    }

    // Handle open (open URL in browser)
    if let Some(id_or_url) = &args.open {
        let url = if id_or_url.starts_with("http") {
            id_or_url.clone()
        } else {
            // It's an article ID, look up the URL
            match db.get_article_by_id(id_or_url) {
                Ok(Some(article)) => article.link,
                Ok(None) => {
                    println!("Article {} not found", id_or_url);
                    return Ok(());
                }
                Err(e) => {
                    println!("Error: {}", e);
                    return Ok(());
                }
            }
        };

        println!("Opening: {}", url);

        #[cfg(target_os = "linux")]
        {
            std::process::Command::new("xdg-open")
                .arg(&url)
                .spawn()
                .ok();
        }

        #[cfg(target_os = "macos")]
        {
            std::process::Command::new("open")
                .arg(&url)
                .spawn()
                .ok();
        }

        #[cfg(target_os = "windows")]
        {
            std::process::Command::new("cmd")
                .args(["/c", "start", "", &url])
                .spawn()
                .ok();
        }

        println!("Opened in browser.");
        return Ok(());
    }

    // Show from database if unread or saved flags are set
    if args.unread || args.saved {
        let filter = DbArticleFilter {
            unread_only: args.unread,
            saved_only: args.saved,
            source: None,
            min_score: None,
            hours: None,
            limit: args.max_results,
        };
        
        let articles = db.get_articles(filter)?;
        println!("Found {} articles\n", articles.len());
        
        for (i, article) in articles.iter().enumerate() {
            let status = if article.is_read { "[read]" } else { "[unread]" };
            let saved = if article.is_saved { " ★" } else { "" };
            println!("{}. {} {}{}", i + 1, status, article.title, saved);
            println!("   Source: {}", article.source);
            if let Some(score) = article.score {
                println!("   Score: {}", score);
            }
            println!("   {}", article.link);
            println!("   ID: {}", article.id);
            println!();
        }
        return Ok(());
    }

    // Get feeds to fetch
    let feeds_to_fetch: Vec<(&str, &str, usize)> = if args.feeds.is_empty() {
        config
            .feeds
            .iter()
            .filter(|f| f.enabled)
            .map(|f| {
                let limit = f.limit.unwrap_or(args.limit);
                (f.name.as_str(), f.url.as_str(), limit)
            })
            .collect()
    } else {
        args.feeds
            .iter()
            .map(|url| ("Custom", url.as_str(), args.limit))
            .collect()
    };

    if feeds_to_fetch.is_empty() {
        eprintln!("Error: No feeds to fetch. Use --feeds or configure in config file.");
        std::process::exit(1);
    }

    // Fetch articles
    println!("Fetching {} feeds...", feeds_to_fetch.len());
    let fetcher = FeedFetcher::new();
    let mut articles: Vec<Article> = fetcher.fetch_multiple(&feeds_to_fetch).await?;

    // Sync mode: save new articles to database and exit
    if args.sync {
        let saved = db.save_articles(&articles)?;
        println!("Saved {} new articles to database", saved);
        
        // Also sync feeds to database
        for feed in &feeds_to_fetch {
            db.add_feed(feed.0, feed.1, &[]).ok();
        }
        return Ok(());
    }

    // Save all fetched articles to database (dedup)
    let saved = db.save_articles(&articles)?;
    println!("Fetched {} articles ({} new in DB)\n", articles.len(), saved);

    // Apply filters
    let filter_config = FilterConfig {
        min_score: args.min_score.or(config.filters.min_score),
        keywords_include: if args.keywords_include.is_empty() {
            config.filters.keywords_include.clone()
        } else {
            args.keywords_include.clone()
        },
        keywords_exclude: if args.keywords_exclude.is_empty() {
            config.filters.keywords_exclude.clone()
        } else {
            args.keywords_exclude.clone()
        },
        time_range_hours: args.hours.or(config.filters.time_range_hours),
        sources: if args.sources.is_empty() {
            config.filters.sources.clone()
        } else {
            args.sources.clone()
        },
    };

    let filter = ArticleFilter::new(filter_config);
    articles = filter.filter(articles);

    // Apply search
    if let Some(query) = &args.search {
        let search_config = SearchConfig {
            fuzzy: args.fuzzy,
            case_sensitive: false,
            max_results: args.max_results,
        };
        let search = ArticleSearch::new(search_config);
        articles = search.search(articles, query);
    }

    // Quiet mode - just show titles
    if args.quiet {
        for (i, article) in articles.iter().enumerate() {
            println!("{}. {}", i + 1, article.title);
            if let Some(score) = article.score {
                println!("   Score: {}", score);
            }
            println!("   {}", article.link);
            println!();
        }
        return Ok(());
    }

    // AI summarization
    let summary = if args.summarize {
        let ai_config = AiConfig {
            enabled: true,
            model: args.model,
            temperature: 0.7,
            max_tokens: 2048,
            custom_prompt: args.prompt.clone(),
        };
        let summarizer = AiSummarizer::new(ai_config);
        match summarizer.summarize(&articles).await {
            Ok(s) => {
                println!("=== AI Summary ===\n{}\n", s);
                Some(s)
            }
            Err(e) => {
                println!("Error summarizing: {}", e);
                None
            }
        }
    } else {
        None
    };

    // Output
    let output_config = OutputConfig {
        format: args.format.into(),
        pretty: args.pretty,
        include_summary: true,
    };

    let formatter = OutputFormatter::new(output_config);

    if let Some(output_path) = &args.output {
        formatter.to_file(&articles, summary.as_deref(), output_path)?;
        println!("Output written to: {}", output_path);
    } else {
        let output = formatter.format(&articles, summary.as_deref());
        println!("{}", output);
    }

    Ok(())
}
