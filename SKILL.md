# RSS AI Skill

Modular RSS feed aggregator with AI summarization, filters, fuzzy search, and multiple output formats.

Binary is installed and available in PATH: `rss-ai`

Set your MiniMax API key:
```bash
echo "MINIMAX_API_KEY=your_key" > .env
```

## Quick Start

```bash
# List default feeds
rss-ai --list-feeds

# Fetch and display JSON
rss-ai

# Fetch and summarize with AI
rss-ai --summarize

# Search with fuzzy matching
rss-ai --search "AI" --fuzzy

# Filter by score and keywords
rss-ai --min-score 100 --include "GPT,Claude" --exclude "crypto"
```

## CLI Options

| Flag | Description |
|------|-------------|
| `--feeds, -f` | RSS feed URLs (multiple) |
| `--config, -c` | Config file (YAML) |
| `--limit, -n` | Articles per feed (default: 10) |
| `--search, -s` | Search query |
| `--fuzzy` | Use fuzzy search |
| `--min-score` | Minimum score filter |
| `--include` | Keywords to include (comma-separated) |
| `--exclude` | Keywords to exclude (comma-separated) |
| `--sources` | Filter by sources (comma-separated) |
| `--hours` | Filter by time range (hours) |
| `--summarize, -S` | Enable AI summarization |
| `--model` | AI model (default: MiniMax-M2.5-highspeed) |
| `--prompt` | Custom AI prompt |
| `--format` | Output format: json, markdown, html, csv |
| `--output, -o` | Output file path |
| `--pretty` | Pretty print output |
| `--quiet, -q` | Show only titles |
| `--list-feeds` | List default feeds |
| `--db` | Database file path (default: ~/.rss-ai/rss-ai.db) |
| `--unread` | Show only unread articles from database |
| `--saved` | Show only saved articles from database |
| `--mark-read <id>` | Mark article as read |
| `--mark-unread <id>` | Mark article as unread |
| `--save <id>` | Save/unsave article |
| `--mark-all-read` | Mark all articles as read |
| `--sync` | Fetch new articles only (deduplication) |
| `--cleanup <days>` | Clear old read articles |
| `--stats` | Show statistics |
| `--semantic-search <query>` | Semantic vector search (requires Ollama) |
| `--embed` | Generate embeddings for articles (requires Ollama) |
| `--rebuild-index` | Clear vector index |
| `--fetch-full <id or url>` | Fetch full article content (AI-powered) |
| `--open <id or url>` | Open article in browser |
| `--force-browser` | Use browser automation when blocked (Cloudflare) |

## Examples

### Fetch multiple feeds
```bash
rss-ai \
  --feeds "https://hnrss.org/best" \
  --feeds "https://techcrunch.com/feed/" \
  --limit 20
```

### Search with filters
```bash
rss-ai \
  --search "machine learning" \
  --fuzzy \
  --min-score 50 \
  --hours 24 \
  --include "AI,LLM" \
  --exclude "crypto"
```

### AI Summary with custom prompt
```bash
rss-ai \
  --summarize \
  --model "MiniMax-M2.5-highspeed-Lightning" \
  --prompt "Summarize and identify investment opportunities" \
  --format markdown
```

### Output to file
```bash
rss-ai \
  --summarize \
  --output "daily_digest.html" \
  --format html
```

### Database Operations
```bash
# Sync new articles (deduplication)
rss-ai --sync

# Show unread articles
rss-ai --unread

# Show saved articles
rss-ai --saved

# Mark article as read (use ID from --unread output)
rss-ai --mark-read <article-id>

# Save article for later
rss-ai --save <article-id>

# Mark all as read
rss-ai --mark-all-read

# Show statistics
rss-ai --stats

# Cleanup old read articles (30 days)
rss-ai --cleanup 30

# Generate embeddings for semantic search (requires Ollama)
rss-ai --embed

# Semantic search
rss-ai --semantic-search "machine learning"

# Fetch full article content (AI-powered with MiniMax)
rss-ai --fetch-full <article-id>
rss-ai --fetch-full "https://techcrunch.com/..."

# Open article in browser
rss-ai --open <article-id>

# Use browser automation when blocked (Cloudflare)
rss-ai --fetch-full <article-id> --force-browser
```

## Config File

Create a `config.yaml` for persistent configuration:

```yaml
feeds:
  - name: "Hacker News"
    url: "https://hnrss.org/best"
    enabled: true
    tags: ["tech", "hackernews"]
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
  sources: []

search:
  fuzzy: true
  case_sensitive: false
  max_results: 50

ai:
  enabled: true
  model: "MiniMax-M2.5-highspeed"
  temperature: 0.7
  max_tokens: 2048
  custom_prompt: null

output:
  format: json
  pretty: true
  include_summary: true
```

## Features

### Filtering
- Minimum score (HN points)
- Keywords include/exclude
- Source filtering
- Time range filtering

### Search
- Fuzzy matching with scoring
- Exact matching
- Case-sensitive option

### AI Summarization
- MiniMax models (M2.5, Lightning)
- Custom prompts
- Configurable temperature/tokens

### Semantic Search (Vector)
- Uses Ollama with nomic-embed-text model
- Cosine similarity search
- Local embeddings stored in SQLite

### AI Content Extraction
- Full article extraction using MiniMax-M2.5-highspeed
- Fetches page + AI cleans/structures content
- Outputs clean markdown
- Auto-detects Cloudflare blocks
- Uses browser automation (agent-browser) when blocked
- Auto-marks as read after fetching

### Browser Integration
- Open articles directly in default browser
- `--force-browser` flag bypasses Cloudflare protection

### Output Formats
- JSON (default)
- Markdown
- HTML
- CSV

### Database (SQLite)
- Persistent article storage
- Read/unread tracking
- Save articles for later
- Deduplication with --sync
- Cleanup old read articles

## Environment Variables

| Variable | Description |
|----------|-------------|
| `MINIMAX_API_KEY` | MiniMax API key for AI summarization |
