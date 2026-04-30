# rss-ai

A powerful **modular RSS feed aggregator** supercharged with AI. Fetch, filter, search, and summarize content from any RSS feed using advanced fuzzy matching, semantic vector search, and AI-powered summarization.

![Rust](https://img.shields.io/badge/Rust-1.70+-orange.svg)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

## Why rss-ai?

In a world overflowing with content, staying informed without drowning in noise is hard. rss-ai combines the simplicity of RSS with the power of AI to deliver exactly what matters to you.

- **AI Summaries** - Get concise, intelligent summaries of dozens of articles in seconds
- **Smart Filtering** - Filter by score, keywords, sources, and time - find exactly what you want
- **Fuzzy Search** - Never miss relevant content, even with typos
- **Semantic Search** - Go beyond keywords with vector embeddings powered by Ollama
- **Persistent Library** - Track what you've read, save articles for later, built-in SQLite storage
- **Full Content Extraction** - Fetch complete article content, even from protected sites

## Quick Start

### Installation

```bash
git clone https://github.com/yourusername/rss-ai.git
cd rss-ai
cargo build --release
```

### Setup

1. **Get an AI API key** (for summarization). rss-ai works with **any OpenAI-compatible provider**:
   - [MiniMax](https://platform.minimaxi.com) - Fast & affordable
   - [OpenAI](https://platform.openai.com) - GPT models
   - [Anthropic](https://www.anthropic.com) - Claude models (via proxy)
   - [Ollama](https://ollama.ai) - Local models
   - [AnyMirror](https://anymirror.ai), [LiteLLM](https://litellm.ai), etc.

   Export your key:
   ```bash
   export OPENAI_API_KEY=your_key_here
   # or
   export MINIMAX_API_KEY=your_key_here
   ```

2. (Optional) Install Ollama for semantic search:
   ```bash
   ollama pull nomic-embed-text
   ```

3. Create a config file or use defaults:
   ```bash
   cp config.example.yaml config.yaml
   ```

### Basic Usage

```bash
# List available feeds
./target/release/rss-ai --list-feeds

# Fetch and display articles
./target/release/rss-ai

# Get AI summaries
./target/release/rss-ai --summarize

# Search with fuzzy matching
./target/release/rss-ai --search "artificial intelligence" --fuzzy
```

### RSS Subscription Import/Export

The Android/serverless backend stores RSS subscriptions in DynamoDB, but the helper script uses the backend API so you do not need direct DynamoDB access.

```bash
# Export subscriptions as JSON
python3 scripts/rss_subscriptions.py export --format json --output subscriptions.json

# Export subscriptions as OPML for other RSS readers
python3 scripts/rss_subscriptions.py export --format opml --output subscriptions.opml

# Import JSON or OPML subscriptions without changing anything
python3 scripts/rss_subscriptions.py import subscriptions.opml --dry-run

# Import and update existing feeds matched by URL
python3 scripts/rss_subscriptions.py import subscriptions.opml

# Make backend feeds exactly match the import file
python3 scripts/rss_subscriptions.py import subscriptions.opml --replace
```

By default it reads API configuration from `.env` and `aws/generated/rss-api.env`.

### Vue Web App on S3

The serverless backend can also power a static Vue web app hosted as an S3 website. The deploy script creates/updates the Terraform-managed website bucket, builds the Vue app, writes a generated runtime config, and syncs the compiled assets to S3.

```bash
# Uses the existing backend API URL from aws/generated/rss-api.env or Terraform output
aws/scripts/deploy_web_app.sh
```

Defaults:

```bash
WEB_DOMAIN_NAME=rss.bitslovers.com
WEB_EMBED_API_TOKEN=0
WEB_THEME=warm
```

`WEB_EMBED_API_TOKEN` defaults to `0` so the public static site receives the API URL but not the API token. Enter the token once in the web app settings; it is stored in that browser's local storage. If you intentionally want a fully preconfigured personal static build, run with `WEB_EMBED_API_TOKEN=1`.

After deployment, create a DNS CNAME for `rss.bitslovers.com` pointing to the S3 website endpoint printed by the script. This direct S3 website hosting is HTTP-only; add CloudFront later if HTTPS is required for the custom domain.

## Features

### Filtering & Search

| Feature | Command | Description |
|---------|---------|-------------|
| Fuzzy search | `--search "query" --fuzzy` | Find articles even with typos |
| By score | `--min-score 100` | Filter by Hacker News points |
| Keywords include | `--include "AI,LLM"` | Must contain these words |
| Keywords exclude | `--exclude "crypto"` | Skip articles with these |
| Time range | `--hours 24` | Last 24 hours only |
| Sources | `--sources "Hacker News,TechCrunch"` | Filter by feed |

### AI Summarization

Works with **any OpenAI-compatible API**. Just set your provider's endpoint and key.

```bash
# Basic summary (uses default model)
./target/release/rss-ai --summarize

# Custom prompt
./target/release/rss-ai --summarize --prompt "Identify investment opportunities"

# Different model
./target/release/rss-ai --summarize --model "gpt-4o"
./target/release/rss-ai --summarize --model "claude-3-5-sonnet-20241022"
./target/release/rss-ai --summarize --model "MiniMax-M2.5-highspeed-Lightning"

# Use a custom API endpoint
export OPENAI_API_BASE="https://api.anthropic.com/v1"
./target/release/rss-ai --summarize
```

### Semantic Search (Vector)

```bash
# Generate embeddings for stored articles
./target/release/rss-ai --embed

# Search by meaning, not just keywords
./target/release/rss-ai --semantic-search "machine learning trends"
```

### Database & Organization

```bash
# Sync new articles (deduplication)
./target/release/rss-ai --sync

# View unread articles
./target/release/rss-ai --unread

# Save articles for later
./target/release/rss-ai --save <article-id>

# View saved articles
./target/release/rss-ai --saved

# Mark all as read
./target/release/rss-ai --mark-all-read

# Statistics
./target/release/rss-ai --stats
```

### Full Content Extraction

```bash
# Fetch full article content (AI-powered)
./target/release/rss-ai --fetch-full <article-id>

# Fetch from URL directly
./target/release/rss-ai --fetch-full "https://example.com/article"

# Bypass Cloudflare protection
./target/release/rss-ai --fetch-full <id> --force-browser
```

### Output Formats

```bash
# JSON (default)
./target/release/rss-ai --format json

# Markdown
./target/release/rss-ai --format markdown --pretty

# HTML
./target/release/rss-ai --format html --output digest.html

# CSV
./target/release/rss-ai --format csv
```

## Configuration

Create `config.yaml` to customize feeds and defaults:

```yaml
feeds:
  - name: "Hacker News Best"
    url: "https://hnrss.org/best"
    enabled: true
    tags: ["tech", "ai"]
    limit: 20
  
  - name: "TechCrunch"
    url: "https://techcrunch.com/feed/"
    enabled: true
    tags: ["tech", "startup"]
    limit: 10

filters:
  min_score: 50
  keywords_include: ["AI", "GPT"]
  keywords_exclude: ["crypto", "NFT"]
  time_range_hours: 24

ai:
  # Any OpenAI-compatible model
  model: "MiniMax-M2.5-highspeed"
  # Or: gpt-4o, claude-3-5-sonnet-20241022, etc.
  api_base: "https://api.minimax.chat/v1"  # Optional custom endpoint
  temperature: 0.7
  max_tokens: 2048
```

## Environment Variables

| Variable | Description |
|----------|-------------|
| `OPENAI_API_KEY` | API key for OpenAI-compatible providers |
| `OPENAI_API_BASE` | Custom API endpoint (default: MiniMax) |
| `MINIMAX_API_KEY` | Alias for OPENAI_API_KEY (MiniMax default) |
| `OLLAMA_URL` | Ollama server (default: http://localhost:11434) |

## Use Cases

### Daily News Digest

```bash
./target/release/rss-ai \
  --summarize \
  --hours 24 \
  --include "AI,technology" \
  --format markdown \
  --output today.md
```

### Research Mode

```bash
./target/release/rss-ai \
  --semantic-search "large language models" \
  --fuzzy \
  --unread
```

### Content Curation

```bash
# Fetch and filter
./target/release/rss-ai \
  --feeds "https://hnrss.org/best" \
  --feeds "https://techcrunch.com/feed/" \
  --min-score 100 \
  --include "startup,funding" \
  --summarize
```

## Contributing

Contributions are welcome! Here's how you can help:

1. **Fork** the repository
2. **Create** a feature branch (`git checkout -b feature/amazing`)
3. **Commit** your changes (`git commit -m 'Add amazing feature'`)
4. **Push** to the branch (`git push origin feature/amazing`)
5. **Open** a Pull Request

### Ideas for Contribution

- Web UI / TUI interface
- Newsletter generation
- RSS feed generation from filtered results
- More output formats (Notion, Obsidian, etc.)
- Integration with task managers (Todoist, Things, etc.)

## Architecture

```
rss-ai/
├── src/
│   ├── main.rs          # CLI entry point
│   ├── lib.rs           # Core library
│   ├── fetcher.rs       # RSS feed fetching
│   ├── fetcher_html.rs  # Full article extraction
│   ├── filter.rs        # Article filtering
│   ├── search.rs        # Fuzzy & semantic search
│   ├── summarizer.rs    # AI summarization
│   ├── vector.rs        # Vector embeddings (Ollama)
│   ├── database.rs      # SQLite storage
│   └── output.rs        # Formatters (JSON/MD/HTML/CSV)
├── config.example.yaml
└── Cargo.toml
```

## License

MIT License - see [LICENSE](LICENSE) for details.

## Acknowledgments

- [feed-rs](https://github.com/feed-rs/feed-rs) - RSS/Atom parsing
- [OpenAI](https://openai.com) - API standard (used by MiniMax, Anthropic, Ollama, and many more)
- [Ollama](https://ollama.ai) - Local embeddings
- [USearch](https://github.com/unum-cloud/usearch) - Vector similarity search

---

Built with Rust for speed and reliability. Feed your brain, not the algorithm.
