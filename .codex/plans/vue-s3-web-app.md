# Task: Vue S3 Web App

## Acceptance Criteria
- [x] Vue web app reuses the existing RSS AI backend API and data model.
- [x] Web UI supports core reader flows: feed list, article list, reader, search, refresh, save/read toggles, fetch full content, AI format, summarize, and TTS download/playback where backend supports it.
- [x] UI is modern, responsive, and visually polished with purposeful animation.
- [x] Static hosting is provisioned by Terraform using S3 Website hosting.
- [x] Final website bucket/domain target is `rss.bitslovers.com`.
- [x] Deploy script checks preconditions, builds Vue, applies Terraform, uploads to S3, and writes runtime config.
- [x] Backend API token is not committed and is not embedded in the public site unless explicitly opted in.
- [x] Verification covers build, Terraform format/validate, and deploy script syntax.

## Tasks
1. Scaffold Vue/Vite app under `web/` - files: `web/package.json`, `web/src/*`, `web/index.html` - complexity M.
2. Build backend API client and runtime config loader - files: `web/src/api.ts`, `web/src/config.ts` - complexity M.
3. Implement responsive RSS reader UI - files: `web/src/App.vue`, `web/src/styles.css` - complexity L.
4. Add Terraform S3 static website resources and outputs - files: `aws/terraform/web_static.tf`, `aws/terraform/variables.tf`, `aws/terraform/outputs.tf` - complexity M.
5. Add web deploy script with preflight/build/sync - files: `aws/scripts/deploy_web_app.sh`, `.env.example` - complexity M.
6. Document usage and DNS steps - files: `README.md` - complexity S.
7. Verify with npm build, Terraform checks, shell syntax, and secret scan - complexity S.

## Dependencies
- Task 2 depends on Task 1.
- Task 3 depends on Task 2.
- Task 5 depends on Tasks 1 and 4.
- Task 7 depends on all implementation tasks.

## Risks
- Public S3 website hosting is HTTP-only for custom domains; HTTPS requires CloudFront/ACM later.
- Embedding `RSS_API_TOKEN` in static assets exposes the personal backend; default deploy should avoid doing this.
- DNS may be external to Terraform; script should output the S3 website endpoint and documented CNAME target.

## Verification
- `npm run build` passed in `web/`.
- `terraform fmt -check` passed in `aws/terraform/`.
- `terraform validate` passed in `aws/terraform/`.
- `bash -n aws/scripts/deploy_web_app.sh` passed.
- `aws/scripts/deploy_web_app.sh` created the S3 website resources and uploaded the Vue build.
- Public `config.json` sanity check confirmed it has an API URL and no embedded API token by default.
