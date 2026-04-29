# Task: Android Add RSS Subscription

## Acceptance Criteria
- [x] Android Feeds screen exposes an obvious action to add a new RSS feed.
- [x] User can enter feed name, RSS URL, enabled state, article limit, and optional tags.
- [x] The app validates that RSS URL is non-empty and HTTP/HTTPS before sending.
- [x] Android calls the existing backend `POST /v1/feeds` route and handles API errors.
- [x] After creation, Android refreshes the new feed or reloads app data so the new subscription appears.
- [x] API token/base URL remain loaded from existing config paths and are not exposed in logs.
- [x] Build and tests pass.

## Tasks
1. Add Android feed-create request/client methods - `app/src/main/java/com/rssai/data/Models.kt`, `app/src/main/java/com/rssai/data/RssApiClient.kt` - complexity: S
2. Add Feeds screen action and Add Feed dialog - `app/src/main/java/com/rssai/MainActivity.kt` - complexity: M
3. Wire create/refresh/reload state flow - `app/src/main/java/com/rssai/MainActivity.kt` - complexity: M
4. Verify Android build and backend tests - Gradle/Docker, `aws/tests` - complexity: M

## Dependencies
- Task 2 depends on Task 1.
- Task 3 depends on Task 1 and Task 2.

## Risks
- Some sites provide HTML pages instead of RSS URLs; the app can only validate URL shape locally, while backend refresh reports parse failures.
- Refreshing immediately after create may fail for a bad feed URL; the feed should still be saved so the user can adjust later when edit support is added.
