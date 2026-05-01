# Task: Cross-Device Article Highlights

## Acceptance Criteria
- [x] Backend stores article highlights in DynamoDB so Web and Android share the same highlights.
- [x] API supports listing all highlights, listing highlights for one article, creating a highlight, and deleting a highlight.
- [x] Creating a highlight saves the parent article automatically.
- [x] Web can select article text, save a highlight, view article highlights inside the reader, and open a Highlights review area.
- [x] Android uses backend highlights instead of local-only SharedPreferences for new highlight saves and deletes.
- [x] Android has an area/page to review highlighted article text across articles.
- [x] Highlight records include article id, title, source, link, selected text, and created timestamp.
- [x] Existing article reading, save article, audio, formatting, and tag flows continue to build.

## Tasks
1. Add backend highlight storage helpers and API routes - `aws/lambda/rss_api/storage.py`, `aws/lambda/rss_api/app.py` - complexity: M
2. Add Web highlight API/types/state - `web/src/api.ts`, `web/src/types.ts`, `web/src/composables/useRssReader.ts` - complexity: M
3. Add Web selection save and highlights review UI - `web/src/App.vue`, `web/src/components/ReaderPane.vue`, new Web component, `web/src/styles.css` - complexity: L
4. Add Android highlight API models/client integration - `app/src/main/java/com/rssai/data/*.kt` - complexity: M
5. Replace Android local highlight storage path and add highlights review area - `app/src/main/java/com/rssai/*.kt` - complexity: L
6. Verify backend unit tests and Web/Android builds where available - test/build commands - complexity: M

## Dependencies
- Web and Android UI work depends on backend API shape.
- Android review area depends on app navigation state.

## Risks
- Android selection callbacks are currently synchronous; backend saves must be launched from app-level coroutine state.
- Full article text rendering is HTML-backed in both clients, so visual highlight styling of selected ranges is out of scope for this first pass; saved highlight review is in scope.
- Existing local Android highlights may not automatically migrate unless we add a migration path later.
