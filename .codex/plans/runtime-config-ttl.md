# Task: Runtime Configuration Page And DynamoDB Content TTL

## Acceptance Criteria
- [ ] Article full-content cache chunks stored in DynamoDB receive an `expiresAt` TTL attribute.
- [ ] Cache TTL is controlled by a runtime backend setting, not by redeploying Lambda or Terraform.
- [ ] The article metadata records `contentExpiresAt` so API and scheduled prefetch know when content cache is stale.
- [ ] Changing runtime settings through the app immediately updates backend behavior through `PUT /v1/settings`.
- [ ] Web app has a dedicated configuration page that exposes all currently supported runtime settings.
- [ ] Android settings include the new content cache TTL setting and continue saving settings through the API.
- [ ] Tests/build checks cover the backend TTL behavior and app type changes.

## Tasks
1. Add backend content cache TTL setting and storage behavior - `aws/lambda/rss_api/storage.py`, `aws/lambda/rss_api/app.py` - complexity M.
2. Add/adjust backend tests for TTL on content chunks and expired content cache detection - `aws/tests/*` - complexity M.
3. Extend shared Web settings type/defaults and create a dedicated configuration page - `web/src/types.ts`, `web/src/composables/useRssReader.ts`, `web/src/components/*`, `web/src/App.vue`, `web/src/styles.css` - complexity L.
4. Extend Android settings model/dialog with content cache TTL - `app/src/main/java/com/rssai/data/Models.kt`, `app/src/main/java/com/rssai/SettingsDialog.kt` - complexity S.
5. Run backend unit tests plus frontend/Android compile checks where available - complexity M.

## Dependencies
- Backend setting must exist before Web and Android can expose it.
- Web and Android models must stay compatible with backend settings JSON.

## Risks
- DynamoDB TTL deletion is eventual, so API must also treat `contentExpiresAt` as stale before AWS physically deletes chunks.
- Existing content chunks without `expiresAt` are not automatically expired until settings are saved or content is re-saved.
- Exposing all settings creates a dense UI; grouping and compact controls are needed.
