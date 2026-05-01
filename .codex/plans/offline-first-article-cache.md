# Task: Offline-First Article Cache

## Acceptance Criteria
- [x] Android stores feeds, settings, articles, highlights, and sync cursor locally for offline navigation.
- [x] Web stores feeds, settings, articles, highlights, and sync cursor locally where browser storage is available.
- [x] Cached data renders before network calls so app startup and navigation are fast.
- [x] Cache retention is configurable from settings, defaulting to 30 days.
- [x] Opening the app checks for new articles automatically when `refreshOnOpen` is enabled.
- [x] Refresh/open sync appends or updates only changed articles where possible using incremental sync.
- [x] Offline mode continues to show cached feeds/articles/content/highlights if backend is unreachable.
- [x] Existing fetch, format, summarize, save/read, tag, highlight, audio, and Flow behavior continues to build.

## Tasks
1. [x] Add backend/client setting `localArticleCacheDays` - backend defaults, Web/Android types/settings UI - complexity: M
2. [x] Add Web IndexedDB cache module and cache-first reader state - `web/src/localCache.ts`, `useRssReader.ts`, API types - complexity: L
3. [x] Add Android file-backed cache module and cache-first app state - new Kotlin cache file, `RssAiApp.kt`, API models/client - complexity: L
4. [x] Wire refresh-on-open to incremental sync instead of full list reload where possible - Web and Android clients - complexity: M
5. [x] Verify backend tests, Web build, Android release build - complexity: M

## Dependencies
- Local cache retention depends on settings being available with sane defaults before the backend responds.
- Incremental sync depends on `/v1/sync/pull?since=...` returning changed articles and a cursor.

## Risks
- Browser IndexedDB can be cleared by the browser; offline support should degrade gracefully.
- Android cache files can grow if content is very large; pruning by publish/update age limits growth.
- Existing local-only audio cache remains separate from article metadata/content cache.

## Verification
- `python -m pytest aws/tests -q` -> 66 passed, 3 skipped.
- `npm run build` in `web/` -> passed.
- `./build-android.sh release` -> passed, generated `rss-ai-release.apk`.
