# Task: Tags And Android Flow Mode

## Acceptance Criteria
- [x] Feeds/subscriptions can carry normalized tags and tag edits propagate to existing articles from that feed.
- [x] Articles can carry tags and be filtered by one or more tags through the backend API.
- [x] Web can view/filter by tags, edit subscription tags, and edit article tags.
- [x] Web Flow mode respects the selected tag and makes the active tag visible.
- [x] Android can view/filter by tags, edit article tags, and keep subscription tag editing.
- [x] Android has a Flow screen with lazy/infinite-style article scrolling and tag filtering.
- [x] Existing RSS fetch, reader, saved, audio, and AI content flows keep working.

## Tasks
1. Backend tag normalization/filtering - `aws/lambda/rss_api/storage.py`, `aws/lambda/rss_api/app.py` - complexity: M
2. Backend tag tests - `aws/tests/` - complexity: S
3. Web API/types/composable tag state - `web/src/api.ts`, `web/src/types.ts`, `web/src/composables/useRssReader.ts` - complexity: M
4. Web tag UI/editor and flow context - `web/src/App.vue`, `web/src/components/*`, `web/src/styles.css` - complexity: M
5. Android API/model tag support - `app/src/main/java/com/rssai/data/*` - complexity: S
6. Android tag filters and Flow screen - `app/src/main/java/com/rssai/*` - complexity: L
7. Verification - backend pytest, Web build, Android build - complexity: M

## Dependencies
- Web and Android tag filtering depend on backend `tag`/`tags` query support.
- Android Flow mode depends on article tags being present in API responses.
- Article tag editing depends on existing `PATCH /v1/articles/{articleId}`.

## Risks
- The current article API is limit-based, not cursor-based. Android Flow will use Compose `LazyColumn` virtualization over the loaded API result rather than true server cursor pagination in this pass.
- Existing historical articles may have empty tags. Propagating feed tag changes handles feed-owned articles, but manually imported articles without `sourceFeedId` may need manual article tags.
- Android screens are already split but import-heavy. Keep new code in separate files and avoid growing existing files more than needed.

## Verification
- [x] `pytest -q aws/tests`
- [x] `npm --prefix web run build`
- [x] `./build-android.sh debug`
- [x] `git diff --check`
