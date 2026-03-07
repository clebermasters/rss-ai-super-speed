# RSS AI Skill for kiro-bot

Fetch, filter, search, and summarize RSS feeds with AI.

## Quick Start

```
fetch RSS news about AI
summarize the latest tech news
get hacker news top stories
```

## Usage Examples

### Basic RSS Fetch
```
fetch RSS feeds from https://hnrss.org/best
get latest tech news
```

### With AI Summary
```
summarize the latest AI news
get RSS news and summarize with MiniMax
fetch https://hnrss.org/best and summarize
```

### With Filters
```
fetch RSS with min score 100
get news about GPT but exclude crypto
fetch from hacker news with keyword AI
```

### Search
```
search RSS for "machine learning"
fuzzy search for "AI" in tech feeds
```

### Database (tracks read/unread)
```
show my unread articles
what articles have I saved
mark article as read
save this article for later
sync my RSS feeds
show RSS statistics
```

### Full Article Content
```
fetch full article (use article ID)
open this article in browser
```

### Output Formats
```
fetch RSS and output as markdown
get news as HTML report
export as CSV
```

## Commands

| Command | Description |
|---------|-------------|
| `fetch RSS` | Fetch articles from RSS feeds |
| `summarize` | Generate AI summary with MiniMax |
| `search` | Search articles with fuzzy matching |
| `--feeds URL` | Specify RSS feed URLs |
| `--min-score N` | Filter by minimum score |
| `--include keyword` | Keywords to include |
| `--exclude keyword` | Keywords to exclude |
| `--format json\|markdown\|html\|csv` | Output format |
| `--quiet` | Show only titles |
| `--unread` | Show unread articles from DB |
| `--saved` | Show saved articles from DB |
| `--mark-read <id>` | Mark article as read |
| `--save <id>` | Save article for later |
| `--sync` | Fetch new articles (deduplication) |
| `--stats` | Show article statistics |
| `--fetch-full <id>` | Fetch full article content (AI) |
| `--open <id>` | Open article in browser |

## Environment Variables

| Variable | Description |
|----------|-------------|
| `MINIMAX_API_KEY` | API key for AI summarization |

## Example Prompts

- "fetch the latest hacker news and summarize"
- "get AI news from the last 24 hours"
- "search RSS for GPT with min score 50"
- "fetch techcrunch and output as markdown"
- "summarize venturebeat AI articles"
- "sync my RSS feeds"
- "show my unread articles"
- "save this article" (use ID from previous output)
- "mark article as read" (use ID from previous output)
- "what have I saved for later"
- "fetch full article" (use ID from unread list)
- "open this article" (use ID from unread list)

## Build

```bash
cd ~/.opencode/skills/rss-ai
cargo build --release
```
