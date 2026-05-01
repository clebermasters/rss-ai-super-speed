# Task: Startup Refresh Performance Fix

## Acceptance Criteria
- [x] Web app no longer awaits `POST /v1/sync/refresh` during initial page load.
- [x] Android app no longer awaits full RSS refresh before first article list render.
- [x] Both clients render cached articles first, then perform fast `/v1/sync/pull` only once a cursor exists.
- [x] Fresh cache loads use lightweight `/v1/articles?limit=...` instead of `sync/pull?since=0`.
- [x] `refreshOnOpen` still checks for new feed items, but in a throttled background path that cannot block startup.
- [x] Manual refresh still performs full RSS refresh and updates the local cache.
- [x] Android launch is verified with ADB after building/installing.
- [x] Web build and backend tests pass.

## Tasks
1. Add throttled background refresh to Web reader state - `web/src/composables/useRssReader.ts` - complexity: M
2. Add throttled background refresh to Android state - `app/src/main/java/com/rssai/RssAiApp.kt` - complexity: M
3. Update settings wording so refresh-on-open no longer implies blocking startup - Web and Android settings UI - complexity: S
4. Verify builds/tests and test Android launch through ADB - complexity: M
5. Avoid large first-load `sync/pull?since=0` payloads by using lightweight article-list fallback until a cursor exists - Web and Android cache sync - complexity: S

## Verification
- `python -m pytest aws/tests -q` -> 66 passed, 3 skipped.
- `npm run build` in `web/` -> passed.
- `./build-android.sh release` -> passed and generated `rss-ai-release.apk`.
- `adb install -r rss-ai-release.apk` -> succeeded.
- `adb shell am start -W -n com.rssai/.MainActivity` -> cold launch status ok in 263 ms, no AndroidRuntime/FATAL log entries.
- Deployed web bundle to S3 static site; hosted HTML references `assets/index-BuCdO1Id.js`.
- Measured deployed API fast paths: `/v1/articles?limit=50` ~0.61s and incremental `/v1/sync/pull` with a recent cursor ~0.94s / 7.9 KB.

## Dependencies
- Fast startup depends on cached/local state and `/v1/sync/pull` staying cheap.
- Full feed refresh remains the only way to discover RSS items before scheduled backend refresh runs.

## Risks
- Background refresh can still take ~30s server-side, so it must be throttled per device/browser.
- If the user clears local cache, the first article load still needs the lighter `/v1/articles` fallback.
