# Task: Fix Fetch Full Timeout

## Acceptance Criteria
- [x] Fetch Full no longer times out in the web browser before the backend job has a fair chance to finish.
- [x] API Lambda timeout budget covers direct fetch, browser fallback, Wayback fallback, and optional AI formatting.
- [x] Synchronous API-to-browser Lambda invocation does not hit the boto3 default read timeout before the browser Lambda timeout.
- [x] Stale content jobs report a backend timeout/failure instead of staying `running` forever.
- [x] Deployed E2E smoke test exercises Fetch Full and polls the job to completion.
- [x] Backend unit tests, deployed smoke tests, and web build pass.

## Tasks
1. Align backend/browser timeout budgets - `aws/terraform/lambda_api.tf`, `aws/terraform/lambda_browser.tf`, `aws/lambda/rss_api/content_fetcher.py` - M
2. Extend web polling and improve timeout message - `web/src/composables/useRssReader.ts` - S
3. Add stale job handling - `aws/lambda/rss_api/app.py`, `aws/tests/test_app_fetch_jobs.py` - M
4. Add deployed Fetch Full E2E coverage - `aws/tests/test_rss_api_e2e.py` - M
5. Verify and deploy backend/web - scripts/tests - M

## Dependencies
- Task 4 depends on Tasks 1-3 so the deployed E2E test validates the corrected behavior.

## Risks
- AI formatting can legitimately take multiple minutes on long articles; the fix must avoid false browser timeouts while still failing stale backend jobs clearly.
- Browser Lambda remains reserved concurrency 1, so queued browser jobs can add latency under repeated manual clicks.
