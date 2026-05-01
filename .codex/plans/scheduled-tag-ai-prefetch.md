# Task: Scheduled Tag-Based AI Prefetch

## Acceptance Criteria
- [x] Settings expose an enable/disable control for scheduled AI prefetch.
- [x] Settings allow choosing the tags whose subscriptions should be refreshed and pre-cached.
- [x] Terraform creates an EventBridge rule that invokes the API Lambda every 5 minutes.
- [x] Scheduled invocations are cheap when the feature is disabled or no tags are configured.
- [x] Scheduled runs refresh only enabled subscriptions matching the configured tags.
- [x] Matching new/recent articles are automatically queued for full-content fetch and AI mobile formatting without marking them read.
- [x] Matching articles can also get AI summaries cached before the user opens them.
- [x] Duplicate prefetch jobs are throttled so the 5-minute scheduler does not requeue the same article constantly.
- [x] Web settings and Android settings can edit the scheduled prefetch fields.
- [x] Unit tests cover disabled scheduler, tag-filtered refresh, queueing, and duplicate throttling.
- [x] Terraform and app builds/tests pass.

## Tasks
1. Add settings defaults and scheduled prefetch helpers - `aws/lambda/rss_api/storage.py`, `aws/lambda/rss_api/app.py` - complexity M.
2. Add content-job summary generation support for prefetch jobs - `aws/lambda/rss_api/app.py` - complexity M.
3. Add scheduler unit tests - `aws/tests` - complexity M.
4. Enable 5-minute EventBridge default in Terraform - `aws/terraform/variables.tf`, `aws/terraform/eventbridge.tf` - complexity S.
5. Add Web settings controls for scheduled prefetch tags - `web/src/types.ts`, `web/src/components/SettingsModal.vue`, `web/src/App.vue`, `web/src/composables/useRssReader.ts`, `web/src/styles.css` - complexity M.
6. Add Android settings controls for scheduled prefetch tags - `app/src/main/java/com/rssai/data/Models.kt`, `app/src/main/java/com/rssai/SettingsDialog.kt`, `app/src/main/java/com/rssai/RssAiApp.kt` - complexity M.
7. Verify backend tests, web build, Android release build, Terraform validation, and deploy backend/web if safe - complexity L.

## Dependencies
- Scheduler logic depends on settings and existing article tag filtering.
- Content prefetch depends on existing async content jobs.
- Terraform deploy depends on successful Lambda package build.

## Risks
- Five-minute EventBridge cadence can create costs if no backend guard exists; mitigate by exiting unless enabled and tags are configured.
- Full browser fetch and AI formatting can be slow; mitigate by queueing async jobs and limiting articles per run.
- Existing feed summaries may not be equivalent to AI summaries; mitigate by storing `summaryGeneratedAt` and generating AI summary through content jobs.
