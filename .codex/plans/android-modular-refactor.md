# Task: Android Modular Refactor

## Acceptance Criteria
- [ ] Create a new branch for the refactor work.
- [ ] Split oversized Android source files into focused Kotlin files without changing runtime behavior.
- [ ] Keep `MainActivity.kt` small and focused on Android activity startup.
- [ ] Preserve current package/API contracts, reader behavior, feed behavior, settings dialogs, and rich text rendering.
- [ ] Add a session log for the refactor.
- [ ] Run backend tests that cover shared API behavior.
- [ ] Build the Android release APK with Docker.
- [ ] Commit the refactor on the new branch.

## Tasks
1. Inventory Android file sizes and current declarations - `app/src/main/java/com/rssai/**` - complexity: S
2. Split root app and activity startup - `MainActivity.kt`, `RssAiApp.kt` - complexity: M
3. Split UI shell and shared design primitives - `RssChrome.kt`, `SharedUi.kt` - complexity: M
4. Split feature screens - `FeedsScreen.kt`, `ArticlesScreen.kt`, `ReaderScreen.kt` - complexity: M
5. Split rich article rendering helpers - `RichArticleText.kt` - complexity: M
6. Split dialogs and legacy adaptive layout - `FeedDialogs.kt`, `SettingsDialog.kt`, `AdaptiveReaderLayout.kt` - complexity: M
7. Verify no large Android source file remains and build release APK - Android source tree - complexity: M
8. Commit the refactor - git branch `codex/android-modular-refactor` - complexity: S

## Dependencies
- Tasks 2-6 depend on declaration inventory from task 1.
- Task 7 depends on all split files compiling.
- Task 8 depends on successful verification.

## Risks
- Mechanical split can leave missing imports or visibility issues between Kotlin files.
- Moving private top-level functions requires package-level visibility without behavior changes.
- Large Compose file split must preserve state ownership in `RssAiApp`.
