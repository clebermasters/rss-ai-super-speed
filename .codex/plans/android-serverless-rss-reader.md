# Task: Android + Serverless RSS AI Reader

Created: 2026-04-28
Repo: `/home/cleber_rodrigues/softwares/AI/rss-ai`
Reference Android build: `/home/cleber_rodrigues/softwares/AI/android-whisper`
Mode: autopilot planning

## Goal

Design a personal Android RSS reader with an AWS serverless backend that preserves every feature currently available in the `rss-ai` TUI and CLI-adjacent modules, while keeping operating cost extremely low.

The target platform is:

- Android app built with Docker, following the same pattern as `android-whisper`.
- AWS API Gateway HTTP API plus Lambda for backend APIs.
- DynamoDB for backend persistence.
- Terraform for all AWS infrastructure, with S3 remote state supplied through local backend config/environment.
- Separate browser-fetch Lambda container for bot-detection bypass.
- Private encrypted S3 bucket for Codex auth JSON and oversized browser-fetch payloads.
- Optional EventBridge Scheduler for periodic refreshes.
- No always-on servers, no RDS, no OpenSearch, no NAT Gateway, no provisioned capacity by default.

## Acceptance Criteria

- [ ] A markdown implementation plan exists in `.codex/plans/`.
- [ ] The plan lists every current TUI feature and related RSS feature that must be preserved.
- [ ] The plan maps each feature to Android UI, backend API, data model, and verification work.
- [ ] The plan uses API Gateway, Lambda, and DynamoDB as the baseline backend.
- [ ] The plan uses Terraform only for infrastructure.
- [ ] The plan includes a Docker APK build strategy modeled after `android-whisper`.
- [ ] The plan includes Codex subscription as a first-class LLM provider.
- [ ] The plan includes Lambda-hosted browser automation for bot-detection bypass.
- [ ] The plan prioritizes the cheapest practical serverless design for a single personal user.
- [ ] The plan identifies feature gaps and implementation risks before coding starts.

## Assumptions

- This is a personal, single-user application. There is no account signup, multi-tenant authorization, sharing model, or organization administration.
- The Android app is side-loaded or personally distributed, not initially published through Google Play.
- The backend is deployed to one AWS region, probably `us-east-1` unless changed at deploy time.
- Terraform state uses a caller-provided S3 bucket via `TERRAFORM_STATE_BUCKET`, key `rss-ai/terraform.tfstate` by default, a caller-provided or default region, and encryption enabled.
- DynamoDB is the backend source of truth. Android may still use Room as an offline/local cache.
- AI features support OpenAI-compatible providers and Codex subscription. Provider keys and Codex OAuth tokens live only in backend-managed storage, not hard-coded into the APK.
- Existing Rust code remains available as a reference implementation, but the mobile/backend implementation can be Kotlin plus Python unless there is a clear reason to reuse Rust.
- Semantic search must be preserved as a feature, but the cheap serverless version cannot rely on local Ollama inside Lambda. It should be optional and on-demand.
- Browser automation for Cloudflare-protected pages is part of the baseline full-content flow, isolated in a separate x86_64 Playwright/Chromium Lambda container that runs only after direct fetch fails or detects bot protection.

## Cost Position

Use the cheapest serverless primitives that have no idle server cost:

- API Gateway HTTP API, not REST API, because the app only needs simple HTTPS routes.
- Lambda on ARM64 where dependencies allow it.
- DynamoDB on-demand billing for personal low/variable usage.
- EventBridge Scheduler only for low-frequency refreshes.
- No VPC attachment for Lambda, because NAT Gateway would dominate cost.
- No API Gateway cache, ElastiCache, RDS, OpenSearch, ECS, Fargate, or EC2.
- No Secrets Manager by default, because it has a standing monthly secret cost. Use sensitive Terraform variables, encrypted Lambda environment variables, and encrypted private S3 objects for the personal baseline.
- No KMS key by default. Use SSE-S3 for the private app bucket to avoid KMS standing/key-request cost.
- The only intentional non-DynamoDB storage additions are Terraform state S3, private encrypted S3 objects, and ECR image storage for the browser bypass Lambda.
- CloudWatch log retention capped to 7 or 14 days.
- AI calls only on explicit summarize/full-content actions or user-enabled auto modes.

Official cost references checked on 2026-04-28:

- AWS Lambda pricing: https://aws.amazon.com/lambda/pricing/
- Amazon API Gateway pricing: https://aws.amazon.com/api-gateway/pricing/
- Amazon DynamoDB pricing: https://aws.amazon.com/dynamodb/pricing/
- Amazon EventBridge pricing: https://aws.amazon.com/eventbridge/pricing/

## Current Feature Inventory

This inventory is the parity target.

### TUI Layout And Interaction

- Responsive layout:
  - 3 panels on wide terminals: Feeds, Articles, Reader.
  - 2 panels on medium terminals: Articles, Reader.
  - 1 focused panel on narrow terminals.
- Focus management:
  - Feeds panel.
  - Articles panel.
  - Reader panel.
  - Panel cycling.
- Status bar:
  - Mode display.
  - Loading spinner.
  - Success/error messages.
  - Contextual action hints.
- Help overlay:
  - Navigation help.
  - Reader scroll help.
  - Article action help.
  - Global shortcut help.
- Input overlays:
  - Search input.
  - Add feed URL input.
- Mouse wheel support in focused panel.
- Terminal size guard in TUI. Android equivalent should be responsive phone/tablet layouts.

### Feed Features

- Default feed bootstrap:
  - Hacker News.
  - TechCrunch.
  - VentureBeat.
  - Config example also includes OpenAI Blog.
- Persisted feeds.
- Feed list with:
  - All Articles aggregate row.
  - Feed enabled indicator.
  - Per-feed unread count.
  - Per-feed total count.
- Add feed by URL.
- Auto-name feed from URL when adding from TUI.
- Delete feed.
- Refresh all enabled feeds.
- TUI status bar advertises feed enable/disable with `e`, and database has `toggle_feed_enabled`, but the TUI key handler does not currently wire it. Android should implement this intended feature explicitly.
- CLI source filtering exists and should be exposed in Android filter controls.

### Article List Features

- Latest articles from persistent database.
- Default TUI article limit of 500.
- Sorted newest first.
- Unread indicator.
- Saved indicator.
- Source name.
- Relative published date.
- Score display when available.
- HN score extraction.
- HN comments extraction.
- Select article.
- Open article in reader.
- Opening article in reader marks it read.
- Move next.
- Move previous.
- Jump top.
- Jump bottom.
- Next unread.
- Previous unread.
- Hide article from current visible list.
- Refresh from article panel.
- Reader panel can be scrolled while article list remains focused.

### Reader Features

- Title rendering.
- Metadata row:
  - Source.
  - Published date.
  - Score.
  - Saved status.
- Link display.
- External browser open.
- AI summary section when present.
- Full content section when present.
- Placeholder when full content is missing.
- Markdown rendering:
  - Headings.
  - Lists.
  - Numbered lists.
  - Blockquotes.
  - Horizontal rules.
  - Code fences.
  - Indented code blocks.
  - Fetch status markers.
  - Plain paragraph wrapping.
- HTML-to-text fallback for content that still contains HTML.
- Reader scrolling:
  - Line up/down.
  - Page up/down.
  - 10-line up/down.
  - Jump to top.

### Article State Features

- Mark article read.
- Mark article unread.
- Toggle saved.
- Toggle unsaved.
- Mark all read.
- Show unread only.
- Show saved only.
- Article hidden state in the TUI is session-local. Android should support session hide and optional persistent archive/hide.
- Auto-mark read after full content fetch by article ID in CLI.
- Deduplication by article link hash.

### Search And Filter Features

- TUI live local search across:
  - Title.
  - Source.
  - Summary.
- CLI exact search.
- CLI fuzzy search.
- Case-insensitive search by default.
- Max results.
- Minimum score filter.
- Include keyword filter.
- Exclude keyword filter.
- Source filter.
- Time range filter in hours.
- Semantic search by meaning.
- Rebuild semantic index.
- Generate embeddings for stored articles.

### RSS Fetch And Sync Features

- Fetch single feed.
- Fetch multiple feeds.
- Per-feed article limit.
- Enabled feeds only.
- Sort by published date.
- Save fetched articles to persistent database.
- Sync mode saves only new articles and exits.
- Add feeds to database during sync.
- Graceful warning when one feed fails.
- Initial TUI auto-refresh when no articles exist.
- Manual refresh.
- Background refresh action.
- Loading state during refresh.
- New article count after refresh.

### Full Content Extraction Features

- Fetch full article content by article ID.
- Fetch full article content by URL.
- Direct HTTP content extraction.
- Browser automation fallback in CLI when blocked.
- Lambda-hosted browser automation fallback for Android/backend full-content fetches.
- Wayback Machine fallback in fetcher.
- Test fetch strategies for URL.
- AI clean/format full article content.
- Detect Cloudflare/blocking and explain fallback.
- Force browser flag.
- Store fetched content in database.
- In TUI, fetch content for current article.
- In TUI, prefetch current article content if missing.
- In TUI, silently prefetch content for article 3 positions ahead.
- In TUI, avoid duplicate in-flight prefetches.

### AI Features

- Summarize fetched article list.
- Summarize one selected article in TUI.
- Custom prompt for summarization.
- Configurable model.
- Configurable temperature.
- Configurable max tokens.
- OpenAI-compatible API base.
- Codex subscription provider using ChatGPT-authenticated Codex OAuth credentials.
- Codex model listing through `/models`.
- Codex responses through `/responses` SSE parsing.
- Codex token refresh and persistence.
- MiniMax key alias.
- AI formatting of full content.
- Auto-summarize current article in TUI when `MINIMAX_API_KEY` exists and summary is missing.
- Store generated summary in database.
- Loading/error status for AI actions.

### Output And Export Features

- JSON output.
- Markdown output.
- HTML output.
- CSV output.
- Pretty printing.
- Output to file.
- Quiet mode titles-only output.
- Include AI summary in formatted output.
- Android equivalent:
  - Share/export selected article.
  - Share/export filtered article list.
  - Export JSON, Markdown, HTML, CSV from backend or local cache.

### Database And Maintenance Features

- Feeds table.
- Articles table.
- Article read state.
- Article saved state.
- Article summary storage.
- Article full content storage.
- Feed last fetched timestamp.
- Total article count.
- Unread article count.
- Cleanup old read articles.
- Search database articles.
- Get article by ID.
- Toggle feed enabled.
- Vector embeddings stored with articles in current SQLite implementation.

### Config And Environment Features

- YAML config in CLI:
  - Feeds.
  - Filters.
  - Search.
  - AI.
  - Output.
- `.env` search order:
  - Current directory.
  - Executable directory.
  - `~/.rss-ai/.env`.
- Environment variables:
  - `OPENAI_API_KEY`.
  - `OPENAI_API_BASE`.
  - `MINIMAX_API_KEY`.
  - `OLLAMA_URL`.
  - `AI_MODEL` in TUI path.
  - `OPENAI_CODEX_CLIENT_VERSION` for backend Codex subscription compatibility.
  - `OPENAI_CODEX_MODEL` default for Codex subscription usage.
- Android equivalent:
  - Settings screen for API base URL, token, LLM provider, model, Codex reasoning effort, AI feature toggles, browser bypass controls, refresh behavior, and cache cleanup.
  - Build-time defaults from `.env`, with runtime override.

## Target Architecture

### High-Level Flow

```text
Android app
  |
  | HTTPS + x-rss-ai-token
  v
API Gateway HTTP API
  |
  v
Lambda rss-api
  |
  +-- DynamoDB rss-ai-personal table
  +-- Private encrypted S3 bucket
  +-- External RSS feeds
  +-- OpenAI-compatible AI provider
  +-- Codex subscription provider
  +-- Wayback/archive fetch
  +-- Lambda rss-browser-fetcher (Playwright/Chromium, invoked only for blocked content)

Optional:
EventBridge Scheduler -> Lambda rss-refresh -> DynamoDB
```

Terraform manages all AWS infrastructure:

```text
terraform {
  backend "s3" {}
}
```

### Android App

Use Kotlin and Jetpack Compose.

Recommended modules/packages:

```text
app/src/main/java/com/rssai/
  MainActivity.kt
  core/config/
  core/network/
  core/sync/
  data/local/
  data/remote/
  data/repository/
  domain/model/
  domain/usecase/
  ui/articles/
  ui/reader/
  ui/feeds/
  ui/search/
  ui/settings/
  ui/export/
```

Android responsibilities:

- Render the TUI-equivalent reader experience.
- Cache articles, feeds, settings, and state locally with Room.
- Sync changes with backend.
- Run manual refresh and optional WorkManager background sync.
- Keep UI responsive when backend is offline.
- Open article URLs using Android intents.
- Share/export article content using Android share sheet.
- Store API base URL and token in DataStore. Consider EncryptedSharedPreferences if available and dependency cost is acceptable.

### Backend

Use one lightweight core API Lambda for normal application traffic and one isolated browser-fetch Lambda container for bot-detection bypass.

Recommended backend layout:

```text
aws/
  terraform/
    backend.tf
    providers.tf
    variables.tf
    main.tf
    outputs.tf
    api_gateway.tf
    dynamodb.tf
    lambda_api.tf
    lambda_browser.tf
    s3.tf
    ecr.tf
    eventbridge.tf
    iam.tf
  lambda/
    rss_api/
      app.py
      ai_client.py
      codex_provider.py
      content_fetcher.py
      storage.py
      requirements.txt
    browser_fetcher/
      Dockerfile
      app.py
      requirements.txt
  scripts/
    deploy_rss_api.sh
    run_rss_api_e2e.sh
  tests/
    test_rss_api_e2e.py
```

Backend responsibilities:

- Authenticate requests with a single shared token.
- Manage feeds.
- Fetch RSS feeds.
- Deduplicate articles.
- Store article metadata, state, summaries, and content.
- Serve article lists and details.
- Run search/filter queries for cloud-backed results.
- Run AI summarization and AI content formatting.
- Support both `openai_compatible` and `codex_subscription` LLM providers.
- Store Codex OAuth auth JSON in encrypted S3 and persist refreshed tokens.
- Invoke the browser-fetch Lambda only when direct content extraction fails or detects bot protection.
- Generate exports.
- Run maintenance operations.

### DynamoDB Data Model

Use one table to keep the system simple.

Table: `rss-ai-personal`

Primary key:

- `pk` string.
- `sk` string.

GSI1 for newest article listing:

- `gsi1pk` string.
- `gsi1sk` string.

Item types:

```text
Settings:
  pk = USER#default
  sk = SETTINGS#app

Feed:
  pk = USER#default
  sk = FEED#<feedId>

Article:
  pk = USER#default
  sk = ARTICLE#<articleId>
  gsi1pk = USER#default#ARTICLES
  gsi1sk = <publishedEpochPadded>#<articleId>

Content chunk:
  pk = USER#default
  sk = CONTENT#<articleId>#<chunkIndexPadded>

Embedding:
  pk = USER#default
  sk = EMBED#<articleId>
```

Article attributes:

```text
articleId
title
link
canonicalLink
summary
feedSummary
contentPreview
contentChunkCount
publishedAt
publishedEpoch
source
sourceUrl
sourceFeedId
score
comments
tags
isRead
isSaved
isHidden
fetchedAt
updatedAt
contentFetchedAt
summaryGeneratedAt
embeddingGeneratedAt
dedupeKey
```

Feed attributes:

```text
feedId
name
url
enabled
tags
limit
lastFetchedAt
lastFetchStatus
lastFetchError
articleCount
unreadCount
createdAt
updatedAt
```

Settings attributes:

```text
aiModel
aiApiBase
llmProvider
codexModel
codexReasoningEffort
codexClientVersion
autoSummarize
autoFetchContent
prefetchDistance
browserBypassEnabled
browserBypassMode
refreshOnOpen
scheduledRefreshEnabled
scheduledRefreshRate
defaultArticleLimit
cleanupReadAfterDays
semanticSearchEnabled
exportDefaultFormat
```

Content chunking is required because DynamoDB items are limited in size. Store full article content in chunks when content is large. Store a preview on the article item for list/detail speed.

### Authentication

Personal baseline:

- Generate a random token during deploy.
- Store it as a sensitive Terraform variable or generated deployment value.
- Put it into Lambda environment as `RSS_AI_API_TOKEN`.
- Require header `x-rss-ai-token` on every non-OPTIONS request.
- Write generated values to `aws/generated/rss-api.env`.
- Optionally merge these values into repo `.env` for Android build defaults.
- Store Codex OAuth payload from `~/.codex/auth.json` as an encrypted S3 object at `codex/auth.json`.

Do not use Cognito for the initial personal version.

### API Contract

All JSON APIs use:

```text
Header: x-rss-ai-token: <token>
Content-Type: application/json
```

Core:

```text
GET    /v1/health
GET    /v1/bootstrap
GET    /v1/settings
PUT    /v1/settings
```

Feeds:

```text
GET    /v1/feeds
POST   /v1/feeds
GET    /v1/feeds/{feedId}
PUT    /v1/feeds/{feedId}
DELETE /v1/feeds/{feedId}
POST   /v1/feeds/{feedId}/toggle-enabled
POST   /v1/feeds/{feedId}/refresh
```

Refresh/sync:

```text
POST   /v1/sync/refresh
GET    /v1/sync/pull?since=<cursor>
POST   /v1/sync/push
```

Articles:

```text
GET    /v1/articles?limit=&cursor=&unread=&saved=&source=&hours=&minScore=&include=&exclude=&query=&fuzzy=
GET    /v1/articles/{articleId}
PATCH  /v1/articles/{articleId}
POST   /v1/articles/{articleId}/mark-read
POST   /v1/articles/{articleId}/mark-unread
POST   /v1/articles/{articleId}/toggle-save
POST   /v1/articles/{articleId}/hide
POST   /v1/articles/mark-all-read
DELETE /v1/articles/cleanup?days=<days>
```

Content and AI:

```text
POST   /v1/articles/{articleId}/fetch-content
POST   /v1/articles/{articleId}/summarize
POST   /v1/articles/summarize
POST   /v1/articles/{articleId}/embedding
POST   /v1/search/semantic
DELETE /v1/search/semantic-index
GET    /v1/llm/providers
GET    /v1/llm/models?provider=openai_compatible|codex_subscription
GET    /v1/llm/codex-auth
PUT    /v1/llm/codex-auth
DELETE /v1/llm/codex-auth
```

Stats/export:

```text
GET    /v1/stats
GET    /v1/export?format=json|markdown|html|csv&scope=all|unread|saved|filtered
GET    /v1/articles/{articleId}/export?format=json|markdown|html|csv
```

### Android Local Data Model

Use Room for offline cache:

```text
FeedEntity
ArticleEntity
ArticleContentChunkEntity
SettingsEntity
SyncStateEntity
PendingMutationEntity
```

The backend remains authoritative. Local mutations are queued and pushed when online.

## Docker APK Build Plan

Follow `android-whisper` patterns with project-specific names.

Files:

```text
build-android.sh
docker/android-base/Dockerfile
docker/android-native/Dockerfile
settings.gradle.kts
build.gradle.kts
app/build.gradle.kts
app/proguard-rules.pro
```

Build script behavior:

- Accept `debug` or `release`.
- Accept `--install`.
- Auto-detect Docker socket.
- Start Docker if possible.
- Build/reuse Android base image.
- Read `.env`.
- Pass build args:
  - `RSS_API_BASE_URL`.
  - `RSS_API_TOKEN`.
  - `DEFAULT_LLM_PROVIDER`.
  - `DEFAULT_AI_MODEL`.
  - `DEFAULT_CODEX_MODEL`.
  - `DEFAULT_CODEX_REASONING_EFFORT`.
  - `DEFAULT_BROWSER_BYPASS_ENABLED`.
  - `DEFAULT_AUTO_SYNC_ENABLED`.
  - `DEFAULT_REFRESH_ON_OPEN`.
- Generate `BuildConfig.kt` in Docker.
- Build APK with Gradle cache mount.
- Copy APK to repo root as:
  - `rss-ai-debug.apk`.
  - `rss-ai-release.apk`.
- Optionally install with `adb install -r`.

Example commands:

```bash
./build-android.sh debug
./build-android.sh release
./build-android.sh release --install
```

Base Docker image:

- Ubuntu 22.04.
- JDK 21.
- Android SDK 35.
- Gradle 8.11.1 or current compatible Gradle wrapper.
- Android build tools 35.0.0.

Android dependencies:

- Jetpack Compose Material 3.
- AndroidX Activity Compose.
- Lifecycle ViewModel Compose.
- Kotlinx Serialization JSON.
- OkHttp.
- Room.
- DataStore Preferences.
- WorkManager.
- CommonMark for markdown rendering or Compose markdown renderer.
- Jsoup for local HTML cleanup if needed.

## Feature Implementation Plan

### Phase 0: Repo And Project Foundation

1. Create Android Gradle project - `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/...` - complexity M.
2. Add Docker build system modeled after `android-whisper` - `build-android.sh`, `docker/android-base/Dockerfile`, `docker/android-native/Dockerfile` - complexity M.
3. Add backend folder structure - `aws/terraform`, `aws/lambda/rss_api`, `aws/lambda/browser_fetcher`, `aws/scripts`, `aws/tests` - complexity S.
4. Add shared API schema notes - `docs/api.md` or generated docs in plan follow-up - complexity S.
5. Add `.env.example` for Android/backend deploy values - `.env.example` - complexity S.

Dependencies:

- Docker build depends on Android Gradle scaffold.
- Backend deploy script depends on Terraform modules, API Lambda package, and browser Lambda container image.

### Phase 1: Terraform AWS Infrastructure

1. Create partial S3 backend config and pass bucket/key/region/encryption through deploy-time backend config, keeping account-specific state bucket names out of the repo - `aws/terraform/backend.tf`, `aws/scripts/deploy_backend.sh`, `aws/scripts/deploy_rss_api.sh` - complexity S.
2. Create provider, variables, locals, and outputs - `aws/terraform/providers.tf`, `variables.tf`, `outputs.tf` - complexity S.
3. Create DynamoDB table with pk/sk and GSI1 - `aws/terraform/dynamodb.tf` - complexity M.
4. Create private encrypted app bucket for Codex auth JSON and oversized browser results, with SSE-S3 and public access blocked - `aws/terraform/s3.tf` - complexity M.
5. Create ECR repository for `rss-browser-fetcher` image - `aws/terraform/ecr.tf` - complexity M.
6. Create core API Lambda as Python 3.12 ARM64 ZIP - `aws/terraform/lambda_api.tf` - complexity M.
7. Create browser-fetch Lambda as x86_64 container image with timeout 90s, memory 2048MB, no VPC, reserved concurrency 1 - `aws/terraform/lambda_browser.tf` - complexity M.
8. Create IAM roles/policies with least-privilege DynamoDB, S3, Lambda invoke, ECR, and CloudWatch access - `aws/terraform/iam.tf` - complexity M.
9. Create API Gateway HTTP API proxy integration and CORS - `aws/terraform/api_gateway.tf` - complexity M.
10. Create CloudWatch log groups with 7 or 14 day retention - `aws/terraform/lambda_api.tf`, `lambda_browser.tf` - complexity S.
11. Create optional EventBridge Scheduler resource, disabled by default through Terraform variable - `aws/terraform/eventbridge.tf` - complexity M.
12. Add deploy script flow: `terraform init`, ensure/build/push browser ECR image, package API Lambda zip, `terraform apply`, write generated env file - `aws/scripts/deploy_rss_api.sh` - complexity L.

Dependencies:

- Lambda code must expose handlers before Terraform apply and E2E tests can be verified.
- Browser Lambda image must be pushed before Terraform can deploy the image-based function.

### Phase 2: Backend Core API

1. Implement request routing and JSON responses - `aws/lambda/rss_api/app.py` - complexity M.
2. Implement token authentication and OPTIONS handling - `aws/lambda/rss_api/app.py` - complexity S.
3. Implement `/v1/health` - `aws/lambda/rss_api/app.py` - complexity S.
4. Implement DynamoDB helper layer - `aws/lambda/rss_api/storage.py` or `app.py` initially - complexity M.
5. Implement settings get/update - `aws/lambda/rss_api/app.py` - complexity S.
6. Implement default bootstrap response with default feeds and recommended settings - `aws/lambda/rss_api/app.py` - complexity S.
7. Add unit tests for auth, routing, and response formats - `aws/tests` - complexity M.

Dependencies:

- Storage helper depends on DynamoDB key design.

### Phase 3: Feed Management

1. Implement list feeds - `GET /v1/feeds` - complexity S.
2. Implement add feed by URL with auto-name fallback - `POST /v1/feeds` - complexity M.
3. Implement edit feed name, tags, limit, enabled - `PUT /v1/feeds/{feedId}` - complexity M.
4. Implement delete feed - `DELETE /v1/feeds/{feedId}` - complexity S.
5. Implement toggle enabled - `POST /v1/feeds/{feedId}/toggle-enabled` - complexity S.
6. Maintain feed article and unread counters after refresh/state updates - storage layer - complexity M.
7. Android Feeds screen:
  - All Articles row.
  - Feed rows.
  - Enabled indicator.
  - Unread/total counts.
  - Add feed dialog.
  - Delete confirmation.
  - Enable/disable switch.
  - Pull-to-refresh per feed.
  - Files: `ui/feeds`, `data/repository/FeedRepository.kt` - complexity L.

Dependencies:

- Android feed UI depends on backend feed APIs and local Room entities.

### Phase 4: RSS Fetch, Sync, And Deduplication

1. Implement RSS parser and feed fetcher - `aws/lambda/rss_api/rss_fetcher.py` - complexity M.
2. Parse title, link, summary, content snippet, published/updated dates - `rss_fetcher.py` - complexity M.
3. Extract Hacker News points and comments from summary - `rss_fetcher.py` - complexity S.
4. Canonicalize links and generate stable article IDs - `rss_fetcher.py` - complexity M.
5. Save only new articles by dedupe key - storage layer - complexity M.
6. Implement refresh all enabled feeds - `POST /v1/sync/refresh` - complexity M.
7. Implement refresh one feed - `POST /v1/feeds/{feedId}/refresh` - complexity S.
8. Gracefully return per-feed failures without failing whole refresh - backend - complexity M.
9. Implement pull sync with cursor - `GET /v1/sync/pull` - complexity M.
10. Implement push sync for local read/save/hide mutations - `POST /v1/sync/push` - complexity M.
11. Android repository sync:
  - Refresh on first launch if empty.
  - Manual refresh.
  - Optional refresh on app open.
  - WorkManager optional background refresh.
  - Pending mutation queue.
  - Files: `core/sync`, `data/repository`, `data/local` - complexity L.

Dependencies:

- Article APIs depend on article storage.
- Android offline mutation queue depends on Room setup.

### Phase 5: Article List Parity

1. Implement article list query with cursor and limit - `GET /v1/articles` - complexity M.
2. Implement unread filter - `GET /v1/articles?unread=true` - complexity S.
3. Implement saved filter - `GET /v1/articles?saved=true` - complexity S.
4. Implement source filter - `GET /v1/articles?source=<feedId>` - complexity S.
5. Implement hours filter - `GET /v1/articles?hours=24` - complexity S.
6. Implement min score filter - `GET /v1/articles?minScore=100` - complexity S.
7. Implement include/exclude keyword filters - backend query/filter layer - complexity M.
8. Implement query search parameter - backend query/filter layer - complexity M.
9. Implement article detail - `GET /v1/articles/{articleId}` - complexity S.
10. Implement mark read/unread - article routes - complexity S.
11. Implement toggle save/unsave - article routes - complexity S.
12. Implement hide - article route and local UI state - complexity S.
13. Implement mark all read - `POST /v1/articles/mark-all-read` - complexity M.
14. Android article list:
  - Unread dot.
  - Saved icon.
  - Source.
  - Relative date.
  - Score.
  - Newest first.
  - List paging.
  - Empty state.
  - Refresh state.
  - Files: `ui/articles`, `domain/model`, `data/repository` - complexity L.
15. Android navigation actions:
  - Next/previous article through touch UI.
  - Next/previous unread buttons or overflow actions.
  - Jump top/bottom via fast scroll or menu.
  - Session hide.
  - Files: `ui/articles` - complexity M.

Dependencies:

- Backend article list depends on RSS sync.
- Android article list depends on local Room cache and repositories.

### Phase 6: Reader Parity

1. Implement article detail response with summary, content preview, and content chunks - `GET /v1/articles/{articleId}` - complexity M.
2. Implement content chunk fetch in backend - storage layer - complexity M.
3. Android Reader screen:
  - Title.
  - Source.
  - Published date.
  - Score.
  - Saved status.
  - Link.
  - Summary section.
  - Full content section.
  - Missing content placeholder.
  - Files: `ui/reader` - complexity L.
4. Android markdown renderer:
  - Headings.
  - Bullets.
  - Numbered lists.
  - Blockquotes.
  - Rules.
  - Code blocks.
  - Paragraph wrapping.
  - Files: `ui/reader/MarkdownContent.kt` - complexity M.
5. Android open in browser intent - `ui/reader`, `core/launcher` - complexity S.
6. Android mark read when opening reader - repository/use case - complexity S.
7. Android reader scroll and fast scroll behavior - `ui/reader` - complexity M.
8. Android tablet layout:
  - 3-pane equivalent when width allows.
  - 2-pane equivalent for medium width.
  - Single-pane navigation for phones.
  - Files: `ui/AppScaffold.kt` - complexity L.

Dependencies:

- Full reader content depends on content fetch/chunking.

### Phase 7: Full Content Fetch

1. Implement direct HTTP fetch with timeouts and safe user agent - `aws/lambda/rss_api/content_fetcher.py` - complexity M.
2. Implement HTML cleanup/readability extraction - `content_fetcher.py` - complexity M.
3. Implement blocked-site detection using the existing TUI patterns: Cloudflare, DDoS-Guard, Incapsula, captcha, security check, access denied, JS-required pages, and trivial JS redirect content - `content_fetcher.py` - complexity M.
4. Implement browser Lambda invocation after direct fetch fails or returns blocked/trivial content - `content_fetcher.py`, `aws/lambda/browser_fetcher/app.py` - complexity L.
5. Implement Playwright/Chromium browser fetch with `--disable-blink-features=AutomationControlled`, no sandbox, realistic user agent, viewport, language headers, bounded waits, and guaranteed cleanup - `aws/lambda/browser_fetcher/app.py` - complexity L.
6. Return browser markdown inline when small; write large results to `browser-results/<requestId>.json` in private S3 and return the object key - `aws/lambda/browser_fetcher/app.py` - complexity M.
7. Implement Wayback/archive fallback after browser Lambda failure - `content_fetcher.py` - complexity M.
8. Preserve status markers such as `*[Fetched via browser automation]*` and `*[Retrieved from Wayback Machine]*` - `content_fetcher.py`, browser Lambda - complexity S.
9. Implement `POST /v1/articles/{articleId}/fetch-content` - backend route - complexity M.
10. Store full content in chunks and update article metadata - storage layer - complexity M.
11. Implement AI formatting with the selected LLM provider when user requests it - backend route/service - complexity M.
12. Android fetch content action - `ui/reader`, repository - complexity M.
13. Android automatic content prefetch:
  - Current article if missing and enabled.
  - Article `prefetchDistance` ahead, default 3.
  - Avoid duplicate in-flight requests.
  - Files: `core/sync/PrefetchCoordinator.kt` - complexity M.
14. Android blocked content fallback:
  - Show message.
  - Open original URL in browser.
  - Surface whether direct, browser Lambda, or Wayback strategy failed.
  - Files: `ui/reader` - complexity M.

Dependencies:

- AI formatting depends on AI provider service.
- Prefetch depends on reader/list selection state.

### Phase 8: AI Summarization

1. Implement provider registry with `openai_compatible` and `codex_subscription` - `aws/lambda/rss_api/ai_client.py` - complexity M.
2. Implement OpenAI-compatible client supporting `OPENAI_API_KEY`, `MINIMAX_API_KEY`, `OPENAI_API_BASE`, `AI_MODEL`, model, temperature, and max tokens - `ai_client.py` - complexity M.
3. Implement Codex subscription provider based on `/home/cleber_rodrigues/project/cloud-conquer-question-engine/providers/openai_codex_client.py` - `aws/lambda/rss_api/codex_provider.py` - complexity L.
4. Store Codex auth payload from `~/.codex/auth.json` in encrypted S3 object `codex/auth.json`; never store full auth JSON in Lambda env - storage layer and deploy script - complexity M.
5. Implement Codex OAuth token refresh via `https://auth.openai.com/oauth/token`, persist refreshed tokens back to S3, and return provider-specific auth errors if refresh fails - `codex_provider.py` - complexity M.
6. Implement Codex `/models` support with `client_version` from Terraform/deploy env, default `0.118.0`; never run `codex --version` in Lambda - `codex_provider.py`, deploy script - complexity M.
7. Implement Codex `/responses` support with SSE parsing, retries for 429/5xx, 401 refresh/retry, reasoning effort, and Chat Completions-shaped normalized output - `codex_provider.py` - complexity L.
8. Implement LLM management routes: providers, models, get/put/delete Codex auth - backend routes - complexity M.
9. Implement summarize selected article - `POST /v1/articles/{articleId}/summarize` - complexity M.
10. Implement summarize filtered/list articles - `POST /v1/articles/summarize` - complexity M.
11. Support custom prompt, provider, model, temperature/max tokens or Codex reasoning effort from request/settings/env precedence - API payload/settings - complexity M.
12. Store summary on article item - storage layer - complexity S.
13. Android summarize action from article row and reader - `ui/articles`, `ui/reader` - complexity M.
14. Android LLM provider/model settings, Codex auth status display, and provider-specific error UI - `ui/settings` - complexity M.
15. Android AI loading/error state - UI state models - complexity S.
16. Android optional auto-summary for current article when enabled - prefetch coordinator - complexity M.

Dependencies:

- AI endpoints depend on provider key configuration or Codex auth S3 configuration.
- Codex subscription must fail explicitly when auth is missing or invalid; it must not silently fall back to OpenAI-compatible mode.

### Phase 9: Search, Filter, Fuzzy, And Semantic Search

1. Android live local search across title, source, summary, and content preview - `ui/search`, repository - complexity M.
2. Backend exact query search - article route filter - complexity M.
3. Backend fuzzy search - implement similarity scoring for title/source/summary - complexity M.
4. Android filter screen/sheet:
  - Unread.
  - Saved.
  - Source.
  - Hours.
  - Min score.
  - Include keywords.
  - Exclude keywords.
  - Max results.
  - Files: `ui/search` - complexity L.
5. Persist filter presets in DataStore/Room - settings/local - complexity M.
6. Semantic search baseline:
  - Disabled by default.
  - Generate embedding only on demand for recent/saved articles.
  - Store packed embedding in `EMBED#<articleId>` item.
  - Search by scanning recent embeddings and cosine-ranking in Lambda.
  - Files: `ai_client.py`, `storage.py`, backend routes - complexity L.
7. Rebuild semantic index endpoint - `DELETE /v1/search/semantic-index` and regeneration flow - complexity M.
8. Android semantic search UI:
  - Explicit "semantic" toggle.
  - Explain provider cost before first use.
  - Files: `ui/search` - complexity M.

Dependencies:

- Semantic search depends on embedding provider support.
- Local search depends on Room cache.

### Phase 10: Export And Share

1. Implement output formatters on backend:
  - JSON.
  - Markdown.
  - HTML.
  - CSV.
  - Files: `aws/lambda/rss_api/formatters.py` - complexity M.
2. Implement export selected article - `GET /v1/articles/{articleId}/export` - complexity S.
3. Implement export article list by scope/filter - `GET /v1/export` - complexity M.
4. Enforce response size safeguards and return pagination guidance if export is too large - backend - complexity M.
5. Android share selected article - `ui/export` - complexity S.
6. Android export filtered list - `ui/export` - complexity M.
7. Android local export fallback from Room cache when offline - `ui/export`, repository - complexity M.
8. Preserve quiet titles-only equivalent as a lightweight "Share titles only" option - `ui/export` - complexity S.

Dependencies:

- Export depends on article list/detail format models.

### Phase 11: Stats, Cleanup, And Maintenance

1. Implement stats endpoint:
  - Total articles.
  - Unread articles.
  - Saved articles.
  - Feed count.
  - Last refresh.
  - Files: backend route/storage - complexity S.
2. Implement cleanup old read articles - `DELETE /v1/articles/cleanup?days=` - complexity M.
3. Use DynamoDB TTL for optional hidden/deleted/transient data where appropriate - `aws/terraform`, storage layer - complexity M.
4. Android stats/settings screen - `ui/settings` - complexity M.
5. Android cleanup action with confirmation - `ui/settings` - complexity S.
6. Android cache cleanup for local Room content chunks - local repository - complexity M.

Dependencies:

- Cleanup depends on article state and content chunk storage.

### Phase 12: Settings And Config Parity

1. Backend settings model equivalent to YAML config sections:
  - Feeds.
  - Filters.
  - Search.
  - AI.
  - Output.
  - Files: storage/settings routes - complexity M.
2. Android settings UI:
  - API base URL.
  - API token.
  - LLM provider: `openai_compatible` or `codex_subscription`.
  - Default refresh behavior.
  - Default article limit.
  - AI model.
  - AI API base display.
  - Codex model.
  - Codex reasoning effort.
  - Codex auth configured/not configured state.
  - Auto-summary toggle.
  - Auto-content-fetch toggle.
  - Prefetch distance.
  - Browser bypass enabled.
  - Browser bypass mode.
  - Semantic search toggle.
  - Export default format.
  - Cleanup days.
  - Files: `ui/settings` - complexity L.
3. Build-time defaults through generated `BuildConfig.kt` - Gradle/Docker - complexity M.
4. Runtime override with DataStore - `core/config`, `data/local` - complexity M.
5. Import/export settings JSON - `ui/settings`, backend optional - complexity M.

Dependencies:

- Settings UI depends on DataStore and backend settings API.

### Phase 13: Scheduled Refresh

1. Add Terraform variable for scheduled refresh enablement - `aws/terraform/variables.tf` - complexity S.
2. Add Terraform variable for refresh rate expression - `aws/terraform/variables.tf` - complexity S.
3. Add EventBridge Scheduler or EventBridge Rule to invoke refresh Lambda - `aws/terraform/eventbridge.tf` - complexity M.
4. Ensure refresh Lambda can run without API token because invocation is internal IAM - backend handler - complexity S.
5. Android setting to enable/disable scheduled refresh by changing Terraform-managed backend resources is not practical directly. Instead document deploy-time setting and support app-side WorkManager refresh - docs/settings - complexity S.
6. Android WorkManager background refresh for device-side scheduling - `core/sync` - complexity M.

Dependencies:

- Backend scheduled refresh depends on RSS refresh implementation.

### Phase 14: Testing And Verification

1. Backend unit tests:
  - Auth.
  - Routing.
  - RSS parser.
  - HN metrics.
  - Link hashing/dedupe.
  - Filters.
  - Formatters.
  - Codex provider model listing, SSE response parsing, 401 refresh, retryable 429/5xx, and S3 token persistence.
  - Content fetch fallback order: direct success, direct blocked then browser success, browser failure then Wayback success, all strategies fail.
  - Files: `aws/tests` - complexity M.
2. Terraform verification:
  - `terraform fmt -check`.
  - `terraform validate`.
  - `terraform plan` against the S3 backend.
  - Required outputs: API URL, API token or token parameter reference, DynamoDB table, private S3 bucket, browser Lambda name, ECR repo URL.
  - Files: `aws/terraform` - complexity M.
3. Backend local integration tests against DynamoDB Local or mocked boto3/S3/Lambda - `aws/tests` - complexity M.
4. Deployed API E2E test modeled after `android-whisper/aws/tests/test_notes_api_e2e.py` - `aws/tests/test_rss_api_e2e.py` - complexity M.
5. Deployed LLM smoke tests:
  - `GET /v1/llm/codex-auth`.
  - `GET /v1/llm/models?provider=codex_subscription` when auth is configured.
  - Summarization with selected provider.
  - complexity M.
6. Deployed browser-bypass smoke test:
  - `POST /v1/articles/{articleId}/fetch-content` against a mocked or controlled blocked-page fixture when possible.
  - Manual real-site validation documented separately.
  - complexity M.
7. Android unit tests:
  - Repository sync.
  - Local filters.
  - Search.
  - Markdown rendering model.
  - LLM provider settings.
  - Files: `app/src/test` - complexity M.
8. Android instrumentation smoke tests:
  - Launch app.
  - Load article list.
  - Open reader.
  - Toggle saved/read.
  - Select `openai_compatible` or `codex_subscription` in settings.
  - Files: `app/src/androidTest` - complexity M.
9. Docker build verification:
  - `./build-android.sh debug`.
  - `./build-android.sh release`.
  - APK copied to expected path.
  - complexity S.
10. Manual acceptance checklist:
  - Add feed.
  - Refresh feed.
  - Search.
  - Filter unread/saved.
  - Open reader.
  - Mark read/unread.
  - Save/unsave.
  - Fetch content.
  - Summarize.
  - Export.
  - Cleanup.
  - complexity S.

Dependencies:

- E2E tests depend on Terraform-applied infrastructure and generated `.env`.

## Android Screen Plan

### Main Screen

Phone:

- Top app bar with refresh, search, settings.
- Feed chips or feed drawer.
- Article list.
- Tap article opens reader route.
- Bottom actions for unread/saved filters.

Tablet/foldable:

- Navigation rail or permanent feed pane.
- Article list pane.
- Reader pane.
- This is the closest Android equivalent to the TUI 3-panel layout.

### Feeds Screen

- All Articles row.
- Feed rows with enabled state and unread/total counts.
- Add feed floating action button.
- Swipe or menu delete.
- Toggle enabled.
- Refresh feed.

### Article List

- Unread dot.
- Saved icon.
- Title.
- Source.
- Relative date.
- Score if present.
- Pull to refresh.
- Overflow menu:
  - Mark read/unread.
  - Save/unsave.
  - Hide.
  - Fetch content.
  - Summarize.
  - Open in browser.

### Reader

- Title and metadata.
- Link/open button.
- AI summary card.
- Full content markdown.
- Missing content action row:
  - Fetch full content.
  - Summarize.
  - Open browser.
- Save/read actions.
- Share/export actions.

### Search And Filters

- Search bar.
- Exact/fuzzy toggle.
- Semantic toggle.
- Filter sheet:
  - Source.
  - Time range.
  - Score.
  - Include keywords.
  - Exclude keywords.
  - Unread.
  - Saved.

### Settings

- Backend connection.
- Refresh behavior.
- AI behavior.
- Semantic search behavior.
- Export defaults.
- Cache cleanup.
- Backend stats.

## Backend Implementation Notes

### RSS Parsing

Python options:

- `feedparser` for RSS/Atom parsing.
- `httpx` or `urllib` for HTTP. Prefer `httpx` if dependency size remains acceptable.
- Keep timeouts short.
- Fetch feeds sequentially first for simplicity. Add bounded concurrency later only if refresh becomes slow.

### Content Extraction

Baseline:

- Fetch HTML directly.
- Remove scripts/styles/nav.
- Extract article-like text with BeautifulSoup/readability approach.
- Convert basic HTML to markdown-ish text.
- Detect blocked/trivial pages using the TUI patterns before accepting direct output.
- Invoke the separate Playwright/Chromium browser Lambda when direct fetch fails or detects bot protection.
- Try Wayback fallback when browser Lambda fails or returns no usable content.
- Browser Lambda returns inline markdown for small outputs and writes large outputs to private S3 under `browser-results/<requestId>.json`.
- Store content chunks.

### AI Calls

Use backend-side AI calls to avoid exposing provider keys in the APK.

Supported providers:

- `openai_compatible`: OpenAI-compatible chat/completions API using backend environment variables and settings.
- `codex_subscription`: ChatGPT-authenticated Codex backend API using OAuth JSON stored in encrypted S3.

Codex subscription details:

- Use `https://chatgpt.com/backend-api/codex` by default.
- Refresh OAuth tokens through `https://auth.openai.com/oauth/token`.
- Store refreshed tokens back to the encrypted S3 auth object.
- List models through `/models?client_version=<version>`.
- Generate text through `/responses`, parse SSE events, and normalize to the internal completion shape.
- Do not run `codex --version` in Lambda. The deploy script may detect the local version and pass it to Terraform; otherwise default to `0.118.0`.
- Missing or invalid Codex auth is a provider-specific error, not a silent fallback.

Config priority:

1. Request payload override.
2. Backend settings item.
3. Lambda environment variables.
4. Defaults.

### Semantic Search

Cheap baseline:

- Do not deploy a vector database.
- Do not run Ollama in Lambda.
- Generate embeddings only when the user explicitly asks or enables semantic search.
- Store embeddings in DynamoDB.
- For a personal app, scan recent embeddings and rank in Lambda.
- Cap semantic index size by settings, for example newest 1000 articles and all saved articles.

## Risks And Mitigations

- Risk: DynamoDB item size limit can break full-content storage.
  - Mitigation: Store full content as chunk items and keep only preview metadata on article item.
- Risk: RSS fetch plus content extraction may exceed Lambda timeout.
  - Mitigation: Separate feed refresh from full-content fetch; set bounded per-feed timeouts; refresh only enabled feeds; make content fetch on demand.
- Risk: Browser automation in Lambda increases package size, cold starts, and cost.
  - Mitigation: Isolate it in a separate x86_64 container Lambda with reserved concurrency 1 and invoke it only after direct fetch fails or detects bot protection.
- Risk: Codex subscription OAuth tokens expire or refresh fails.
  - Mitigation: Store auth JSON in encrypted S3, persist refreshed tokens, expose auth status route, and return explicit provider auth errors.
- Risk: API token in APK can be extracted.
  - Mitigation: Personal app accepts this baseline risk; allow runtime token entry; rotate token through deploy script; keep AI provider keys and Codex OAuth tokens only in backend.
- Risk: Fuzzy and semantic search over DynamoDB can become inefficient if article count grows.
  - Mitigation: Personal scope allows scanning recent cached records; add local Room search; cap cloud search window; add GSI only if measured need appears.
- Risk: AI costs can exceed AWS costs.
  - Mitigation: AI disabled by default except explicit actions; cache summaries/content formatting; show semantic-search cost warning.
- Risk: Android background work is not guaranteed by OS battery policies.
  - Mitigation: Manual refresh remains primary; WorkManager is best effort; EventBridge scheduled refresh is optional.
- Risk: Existing TUI feed selection does not actually filter article list.
  - Mitigation: Android should implement the intended behavior because CLI source filtering exists and feed-pane filtering is expected.

## Suggested Milestones

1. Backend foundation and deploy.
2. Android Docker scaffold and empty Compose app.
3. Feed list plus refresh into DynamoDB.
4. Article list and reader with read/save state.
5. Search/filter parity.
6. Full content fetch and prefetch.
7. AI summarize and AI content formatting.
8. Export, stats, cleanup, settings.
9. Semantic search.
10. Polish, tests, and release APK.

## Done Definition

- `./build-android.sh release` produces `rss-ai-release.apk`.
- `aws/scripts/deploy_rss_api.sh` initializes Terraform, builds/pushes the browser Lambda image, packages the core API Lambda, applies Terraform, and writes generated env values.
- Terraform deploys API Gateway, core API Lambda, browser-fetch Lambda, DynamoDB, ECR, private encrypted S3, EventBridge Scheduler, IAM, and log retention.
- A fresh install can configure API URL/token, bootstrap default feeds, refresh feeds, and show articles.
- LLM settings support `openai_compatible` and `codex_subscription`, with Codex auth status and model listing.
- Full-content fetch attempts direct HTTP, browser Lambda bypass, then Wayback fallback.
- Every feature listed in "Current Feature Inventory" has an Android UI path or explicit backend/API path.
- Manual acceptance checklist in Phase 14 passes.
- Deployed API E2E tests pass.
- Android unit tests and debug build pass.
