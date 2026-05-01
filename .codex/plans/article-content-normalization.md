# Article Content Normalization Plan

## Goal
Ensure article bodies never display raw Markdown fragments, broken HTML attributes, decorative image links, or author/date boilerplate as visible reader content.

## Scope
- Backend RSS API article content fetch and AI formatting pipeline.
- Browser-fetch Lambda Markdown conversion.
- Vue web reader Markdown/HTML rendering fallback for existing stored content.
- Unit tests covering the exact malformed NVIDIA-style artifacts.

## Features Covered
- Direct HTTP article extraction cleanup.
- Browser automation article extraction cleanup.
- Wayback article extraction cleanup.
- Existing-content AI formatting cleanup.
- Saved-content cleanup before DynamoDB chunking.
- Web rich text rendering cleanup for older already-saved articles.
- Decorative image/media noise removal.
- Broken anchor/HTML attribute fragment repair.
- Author byline/date boilerplate removal from article body.
- Status marker preservation for fetch strategy notes.

## Verification
- Python unit tests for sanitizer and fetch/format integration.
- TypeScript/Vue production build.
- Manual local web validation can use the existing Vite instance at `http://localhost:5174/`.
