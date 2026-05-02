# Task: RSS Conditional Refresh Cache

## Acceptance Criteria
- [x] Feed refresh stores RSS `ETag` and `Last-Modified` validators per subscription.
- [x] Feed refresh sends `If-None-Match` and `If-Modified-Since` when validators are available.
- [x] `304 Not Modified` responses skip parsing and DynamoDB article writes for that feed.
- [x] Feed polling uses bounded parallelism instead of one-feed-at-a-time network calls.
- [x] Refresh API returns clearer counters while preserving old `fetched` and `saved` fields for client compatibility.
- [x] Feed metadata records last fetch status, validator values, and useful fetch counters.
- [x] Unit tests cover conditional headers, 304 handling, validator persistence, and clearer refresh counters.
- [x] Backend tests pass.

## Tasks
1. Extend RSS fetcher result model for validators, unchanged feeds, and bounded parallel execution - `aws/lambda/rss_api/rss_fetcher.py` - complexity: M
2. Persist feed validator metadata and per-refresh counters - `aws/lambda/rss_api/storage.py`, `aws/lambda/rss_api/app.py` - complexity: M
3. Preserve API compatibility while adding `entriesChecked`, `newArticlesSaved`, `feedsUnchanged`, and related counters - `aws/lambda/rss_api/app.py` - complexity: S
4. Add focused backend tests for conditional request behavior and refresh result semantics - `aws/tests/` - complexity: M
5. Run verification and update this plan with results - complexity: S

## Dependencies
- Conditional HTTP caching depends on RSS servers honoring `ETag` or `Last-Modified`; feeds that do not support validators will still be fetched normally.
- Bounded parallelism must keep Lambda runtime and external site load reasonable.

## Risks
- Some feeds may return weak or malformed validators; store them as plain strings and only send non-empty values.
- Parallel fetches can expose flaky feeds faster; individual feed failures must remain isolated.

## Verification
- `python -m pytest aws/tests/test_rss_refresh_cache.py -q` -> 3 passed.
- `python -m pytest aws/tests -q` -> 69 passed, 3 skipped.
- `npm run build` in `web/` -> passed.
- `./build-android.sh release` -> passed.
- `env -u AWS_PROFILE aws/scripts/deploy_backend.sh` -> deployed API Lambda/browser Lambda and smoke tests passed.
- `env -u AWS_PROFILE aws/scripts/deploy_web_app.sh` -> built and uploaded the web app.
- Production refresh smoke test:
  - First call after deployment: 34 feeds checked, 21 unchanged, 194 entries checked, 1 new article saved, 9.33s.
  - Second call: 34 feeds checked, 24 unchanged, 144 entries checked, 0 new articles saved, 7.82s.

## Notes
- The first refresh after deploying new code can still inspect many feeds because validators must be learned and stored.
- Feeds that do not return or honor `ETag`/`Last-Modified` continue to fetch normally; they are visible as changed `ok` feeds in the response counters.
