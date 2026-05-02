import { computed, onMounted, ref, watch } from 'vue';
import { RssApiClient, RssApiError } from '../api';
import { loadRuntimeConfig, saveRuntimeConfig } from '../config';
import { detectBrandNewArticleIds } from '../freshness';
import { emptyCacheSnapshot, loadOfflineCache, mergeArticles, mergeHighlights, saveOfflineCache } from '../localCache';
import { loadCachedSpeech, saveCachedSpeech, speechCacheKey } from '../speechCache';
import type { Article, ArticleFilter, ArticleHighlight, Feed, RuntimeConfig, Settings, SpeechAudio, SpeechTarget } from '../types';

type Notice = { kind: 'info' | 'success' | 'error'; message: string };

const INITIAL_CONFIG: RuntimeConfig = { apiBaseUrl: '', apiToken: '' };
const DEFAULT_SEGMENT_PERCENT = 30;
const CONTENT_JOB_TIMEOUT_MS = 360_000;
const OPEN_REFRESH_MIN_INTERVAL_MS = 15 * 60 * 1000;
const OPEN_REFRESH_STORAGE_KEY = 'rss-ai-web:last-open-refresh';

export function defaultSettings(): Settings {
  return {
    llmProvider: 'openai_compatible',
    aiModel: 'gpt-5.4',
    aiApiBase: 'https://api.openai.com/v1',
    codexModel: 'gpt-5.4',
    codexReasoningEffort: 'medium',
    codexClientVersion: '0.118.0',
    embeddingProvider: 'openai_compatible',
    embeddingModel: 'text-embedding-3-small',
    ttsApiBase: 'https://api.openai.com/v1',
    ttsModel: 'gpt-4o-mini-tts-2025-12-15',
    ttsVoice: 'marin',
    ttsInstructions: 'Read clearly with natural pacing for a personal news briefing.',
    ttsMaxInputChars: 6000,
    ttsResponseFormat: 'mp3',
    ttsSegmentPercent: 100,
    autoSummarize: false,
    autoFetchContent: false,
    aiContentFormattingEnabled: false,
    aiContentFormattingMinWords: 120,
    aiContentFormattingChunkChars: 8500,
    aiContentFormattingMaxChunks: 8,
    aiContentFormattingMaxTokens: 6000,
    aiContentFormattingTemperature: 0.1,
    prefetchDistance: 3,
    browserBypassEnabled: true,
    browserBypassMode: 'on_blocked',
    refreshOnOpen: true,
    scheduledRefreshEnabled: false,
    scheduledRefreshRate: 'rate(6 hours)',
    scheduledAiPrefetchEnabled: false,
    scheduledAiPrefetchTags: [],
    scheduledAiPrefetchLimit: 5,
    scheduledAiPrefetchMaxAgeHours: 24,
    scheduledAiPrefetchRetryMinutes: 60,
    scheduledAiPrefetchSummaries: true,
    scheduledAiPrefetchContent: true,
    defaultArticleLimit: 50,
    cleanupReadAfterDays: 30,
    articleContentCacheTtlDays: 30,
    localArticleCacheDays: 30,
    semanticSearchEnabled: false,
    exportDefaultFormat: 'markdown',
  };
}

export function useRssReader() {
  const config = ref<RuntimeConfig>(INITIAL_CONFIG);
  const settings = ref<Settings | null>(null);
  const feeds = ref<Feed[]>([]);
  const allArticles = ref<Article[]>([]);
  const articles = ref<Article[]>([]);
  const highlights = ref<ArticleHighlight[]>([]);
  const articleHighlights = ref<ArticleHighlight[]>([]);
  const cacheCursor = ref(0);
  const brandNewArticleIds = ref<Set<string>>(new Set());
  const selectedFeedId = ref('');
  const selectedTags = ref<string[]>([]);
  const selectedArticle = ref<Article | null>(null);
  const query = ref('');
  const filter = ref<ArticleFilter>('all');
  const loading = ref(false);
  const refreshing = ref(false);
  const busyAction = ref('');
  const notice = ref<Notice | null>(null);
  const showSettings = ref(false);
  const audioUrl = ref('');
  const audioLabel = ref('');
  const speechSegmentPercent = ref(DEFAULT_SEGMENT_PERCENT);
  const speechSegmentIndex = ref(0);
  const speechSegmentCount = ref(1);
  const speechCacheStatus = ref('');
  const speechInputChars = ref(0);
  const speechSourceChars = ref(0);
  const speechTarget = ref<SpeechTarget | null>(null);
  let searchTimer: ReturnType<typeof setTimeout> | undefined;
  let backgroundRefreshInFlight = false;

  const configured = computed(() => Boolean(config.value.apiBaseUrl && config.value.apiToken));
  const totalUnread = computed(() => feeds.value.reduce((sum, feed) => sum + Number(feed.unreadCount || 0), 0));
  const brandNewCount = computed(() => brandNewArticleIds.value.size);
  const selectedFeed = computed(() => feeds.value.find((feed) => feed.feedId === selectedFeedId.value) || null);
  const selectedTag = computed(() => selectedTags.value[0] || '');
  const activeSettings = computed(() => settings.value || defaultSettings());
  const availableTags = computed(() => {
    const counts = new Map<string, { tag: string; feedCount: number; articleCount: number; unreadCount: number }>();
    for (const feed of feeds.value) {
      for (const tag of normalizeTags(feed.tags)) {
        const current = counts.get(tag) || { tag, feedCount: 0, articleCount: 0, unreadCount: 0 };
        current.feedCount += 1;
        counts.set(tag, current);
      }
    }
    for (const article of allArticles.value.length ? allArticles.value : articles.value) {
      for (const tag of normalizeTags(article.tags)) {
        const current = counts.get(tag) || { tag, feedCount: 0, articleCount: 0, unreadCount: 0 };
        current.articleCount += 1;
        if (!article.isRead) current.unreadCount += 1;
        counts.set(tag, current);
      }
    }
    return [...counts.values()].sort((a, b) => b.articleCount - a.articleCount || a.tag.localeCompare(b.tag));
  });

  function client(): RssApiClient {
    return new RssApiClient(config.value.apiBaseUrl, config.value.apiToken);
  }

  async function initialize(): Promise<void> {
    config.value = await loadRuntimeConfig();
    if (!configured.value) {
      await hydrateFromCache();
      showSettings.value = true;
      notice.value = { kind: 'info', message: 'Add the backend API URL and token to start reading.' };
      return;
    }
    await hydrateFromCache();
    await bootstrap();
  }

  async function bootstrap(): Promise<void> {
    loading.value = true;
    try {
      const data = await client().bootstrap();
      settings.value = { ...defaultSettings(), ...data.settings };
      feeds.value = data.feeds || [];
      await syncIncremental();
      await loadHighlights({ persist: false });
      await persistCache();
      applyArticleFilters({ preserveSelection: true });
    } catch (error) {
      handleError(error, 'Unable to load backend data.');
      if (!allArticles.value.length && !feeds.value.length) showSettings.value = true;
    } finally {
      loading.value = false;
      scheduleOpenRefresh();
    }
  }

  async function loadArticles(options: { preserveSelection?: boolean; network?: boolean } = {}): Promise<void> {
    applyArticleFilters({ preserveSelection: options.preserveSelection });
    if (options.network === false) return;
    if (!configured.value) return;
    loading.value = true;
    try {
      const limit = boundedNumber(activeSettings.value.defaultArticleLimit, 50, 1, 1000);
      const baseOptions = {
        query: query.value.trim() || undefined,
        source: selectedFeedId.value || undefined,
        unread: filter.value === 'unread',
        saved: filter.value === 'saved',
        limit,
      };
      const activeTags = selectedTags.value;
      let loadedArticles: Article[] = [];
      if (activeTags.length <= 1) {
        const data = await client().articles({ ...baseOptions, tag: activeTags[0] || undefined });
        loadedArticles = data.articles || [];
      } else {
        const responses = await Promise.all(activeTags.map((tag) => client().articles({ ...baseOptions, tag })));
        const merged = new Map<string, Article>();
        for (const response of responses) {
          for (const article of response.articles || []) merged.set(article.articleId, article);
        }
        loadedArticles = [...merged.values()].sort(compareArticlesByRecency).slice(0, limit);
      }
      allArticles.value = mergeArticles(allArticles.value, loadedArticles);
      await persistCache();
      applyArticleFilters({ preserveSelection: options.preserveSelection });
    } catch (error) {
      if (!articles.value.length) handleError(error, 'Unable to load articles.');
    } finally {
      loading.value = false;
    }
  }

  async function selectArticle(article: Article): Promise<void> {
    selectedArticle.value = article;
    articleHighlights.value = highlights.value.filter((highlight) => highlight.articleId === article.articleId);
    loadSpeechPrefs(article.articleId);
    busyAction.value = 'Opening article';
    try {
      const full = await client().article(article.articleId);
      replaceArticle(full);
      selectedArticle.value = full;
      await loadArticleHighlights(full.articleId);
      await persistCache();
      loadSpeechPrefs(full.articleId);
      if (!full.isRead) {
        const read = await client().markRead(full.articleId);
        replaceArticle(read);
        selectedArticle.value = { ...full, ...read, content: full.content, contentPreview: full.contentPreview };
        refreshFeedCounts();
        await persistCache();
      }
    } catch (error) {
      if (!article.content && !article.contentPreview && !article.summary) handleError(error, 'Unable to open article.');
    } finally {
      busyAction.value = '';
    }
  }

  async function selectArticleById(articleId: string): Promise<void> {
    const existing = articles.value.find((article) => article.articleId === articleId) || allArticles.value.find((article) => article.articleId === articleId);
    if (existing) {
      await selectArticle(existing);
      return;
    }
    busyAction.value = 'Opening article';
    try {
      const full = await client().article(articleId);
      replaceArticle(full);
      selectedArticle.value = full;
      await loadArticleHighlights(full.articleId);
      await persistCache();
      loadSpeechPrefs(full.articleId);
    } catch (error) {
      handleError(error, 'Unable to open highlighted article.');
    } finally {
      busyAction.value = '';
    }
  }

  async function refreshFeeds(): Promise<void> {
    if (!configured.value) return;
    refreshing.value = true;
    try {
      const result = selectedFeedId.value ? await client().refreshFeed(selectedFeedId.value) : await client().refresh();
      notice.value = { kind: 'success', message: refreshMessage('Refresh complete', result) };
      markOpenRefreshAttempt();
      await syncIncremental();
      const data = await client().bootstrap();
      settings.value = { ...defaultSettings(), ...data.settings };
      feeds.value = data.feeds || feeds.value;
      await loadHighlights({ persist: false });
      await persistCache();
      applyArticleFilters({ preserveSelection: true });
    } catch (error) {
      handleError(error, 'Refresh failed.');
    } finally {
      refreshing.value = false;
    }
  }

  async function fetchContent(): Promise<void> {
    const article = selectedArticle.value;
    if (!article) return;
    await runContentAction('Fetching full article', async () => {
      const result = await client().fetchContent(article.articleId, Boolean(activeSettings.value.aiContentFormattingEnabled));
      await settleContentResult(result);
    });
  }

  async function formatContent(): Promise<void> {
    const article = selectedArticle.value;
    if (!article) return;
    await runContentAction('Formatting for mobile reading', async () => {
      const result = await client().formatContent(article.articleId);
      await settleContentResult(result);
    });
  }

  async function summarize(): Promise<void> {
    const article = selectedArticle.value;
    if (!article) return;
    busyAction.value = 'Generating AI summary';
    try {
      const result = await client().summarize(article.articleId);
      const updated = { ...article, summary: result.summary };
      replaceArticle(updated);
      selectedArticle.value = updated;
      await persistCache();
      notice.value = { kind: 'success', message: 'AI summary updated.' };
    } catch (error) {
      handleError(error, 'Summary failed.');
    } finally {
      busyAction.value = '';
    }
  }

  async function toggleSaved(): Promise<void> {
    const article = selectedArticle.value;
    if (!article) return;
    try {
      const updated = await client().toggleSave(article.articleId);
      replaceArticle({ ...article, ...updated });
      selectedArticle.value = { ...article, ...updated };
      await persistCache();
    } catch (error) {
      handleError(error, 'Unable to update saved state.');
    }
  }

  async function loadHighlights(options: { persist?: boolean } = {}): Promise<void> {
    if (!configured.value) return;
    try {
      const data = await client().highlights();
      highlights.value = data.highlights || [];
      if (selectedArticle.value) {
        articleHighlights.value = highlights.value.filter((highlight) => highlight.articleId === selectedArticle.value?.articleId);
      }
      if (options.persist !== false) await persistCache();
    } catch (error) {
      handleError(error, 'Unable to load highlights.');
    }
  }

  async function loadArticleHighlights(articleId: string): Promise<void> {
    if (!configured.value || !articleId) {
      articleHighlights.value = [];
      return;
    }
    try {
      const data = await client().articleHighlights(articleId);
      articleHighlights.value = data.highlights || [];
      highlights.value = mergeHighlights(highlights.value, articleHighlights.value);
      await persistCache();
    } catch (error) {
      articleHighlights.value = highlights.value.filter((highlight) => highlight.articleId === articleId);
      handleError(error, 'Unable to load article highlights.');
    }
  }

  async function saveHighlight(text: string): Promise<void> {
    const article = selectedArticle.value;
    const normalized = text.trim().replace(/\s+/g, ' ');
    if (!article || !normalized) return;
    busyAction.value = 'Saving highlight';
    try {
      const created = await client().createHighlight(article.articleId, normalized);
      articleHighlights.value = upsertHighlight(articleHighlights.value, created);
      highlights.value = upsertHighlight(highlights.value, created);
      replaceArticle({ ...article, isSaved: true });
      await persistCache();
      notice.value = { kind: 'success', message: 'Highlight saved for review.' };
    } catch (error) {
      handleError(error, 'Unable to save highlight.');
    } finally {
      busyAction.value = '';
    }
  }

  async function deleteHighlight(highlight: ArticleHighlight): Promise<void> {
    busyAction.value = 'Deleting highlight';
    try {
      await client().deleteHighlight(highlight.articleId, highlight.highlightId);
      highlights.value = highlights.value.filter((item) => item.highlightId !== highlight.highlightId);
      articleHighlights.value = articleHighlights.value.filter((item) => item.highlightId !== highlight.highlightId);
      await persistCache();
      notice.value = { kind: 'success', message: 'Highlight removed.' };
    } catch (error) {
      handleError(error, 'Unable to delete highlight.');
    } finally {
      busyAction.value = '';
    }
  }

  async function playSpeech(target: SpeechTarget, options: { forceRefresh?: boolean } = {}): Promise<void> {
    const article = selectedArticle.value;
    if (!article) return;
    busyAction.value = target === 'summary' ? 'Creating summary audio' : 'Creating article audio';
    try {
      const request = {
        target,
        segmentPercent: target === 'content' ? speechSegmentPercent.value : 100,
        segmentIndex: target === 'content' ? speechSegmentIndex.value : 0,
        forceRefresh: Boolean(options.forceRefresh),
      };
      const key = speechCacheKey(article, request, activeSettings.value);
      let audio: SpeechAudio | null = null;
      if (!request.forceRefresh) {
        audio = await loadCachedSpeech(key);
      }
      if (!audio) {
        audio = await client().speech(article.articleId, request);
        await saveCachedSpeech(key, audio);
      }
      setAudio(article, target, audio);
    } catch (error) {
      handleError(error, 'Unable to create audio.');
    } finally {
      busyAction.value = '';
    }
  }

  function setAudio(article: Article, target: SpeechTarget, audio: SpeechAudio): void {
    if (audioUrl.value) URL.revokeObjectURL(audioUrl.value);
    audioUrl.value = URL.createObjectURL(audio.blob);
    audioLabel.value = target === 'summary' ? 'AI summary audio' : `Article audio part ${audio.segmentIndex + 1} of ${Math.max(audio.segmentCount, 1)}`;
    speechTarget.value = target;
    speechCacheStatus.value = audio.cacheStatus || (audio.fromDeviceCache ? 'device' : 'miss');
    speechInputChars.value = audio.inputChars;
    speechSourceChars.value = audio.sourceChars;
    if (target === 'content') {
      speechSegmentIndex.value = audio.segmentIndex;
      speechSegmentCount.value = Math.max(audio.segmentCount, 1);
      speechSegmentPercent.value = audio.segmentPercent || speechSegmentPercent.value;
      saveSpeechPrefs(article.articleId);
    }
    const cacheLabel = speechCacheStatus.value ? ` · ${speechCacheStatus.value} cache` : '';
    notice.value = { kind: 'success', message: `Audio ready${cacheLabel}. Use the player controls to play or pause.` };
  }

  function stopSpeech(): void {
    if (audioUrl.value) URL.revokeObjectURL(audioUrl.value);
    audioUrl.value = '';
    audioLabel.value = '';
    speechTarget.value = null;
  }

  function setSpeechSegmentPercent(percent: number): void {
    speechSegmentPercent.value = percent;
    speechSegmentIndex.value = loadStoredInt(`segment:${selectedArticle.value?.articleId}:${percent}`, 0);
    speechSegmentCount.value = loadStoredInt(`count:${selectedArticle.value?.articleId}:${percent}`, 1);
    if (selectedArticle.value) saveSpeechPrefs(selectedArticle.value.articleId);
  }

  function setSpeechSegmentIndex(index: number): void {
    speechSegmentIndex.value = Math.min(Math.max(index, 0), Math.max(speechSegmentCount.value - 1, 0));
    if (selectedArticle.value) saveSpeechPrefs(selectedArticle.value.articleId);
  }

  function handleSpeechEnded(): void {
    if (speechTarget.value !== 'content' || !selectedArticle.value) return;
    const nextSegment = Math.min(speechSegmentIndex.value + 1, Math.max(speechSegmentCount.value - 1, 0));
    speechSegmentIndex.value = nextSegment;
    saveSpeechPrefs(selectedArticle.value.articleId);
  }

  async function saveConfig(nextConfig: RuntimeConfig, nextSettings: Settings): Promise<void> {
    config.value = nextConfig;
    saveRuntimeConfig(nextConfig);
    showSettings.value = false;
    if (!configured.value) {
      notice.value = { kind: 'info', message: 'Configuration saved locally. Add URL and token when ready.' };
      return;
    }
    loading.value = true;
    try {
      settings.value = await client().updateSettings(nextSettings);
      notice.value = { kind: 'success', message: 'Settings saved.' };
      await persistCache();
      await bootstrap();
    } catch (error) {
      handleError(error, 'Settings were saved locally, but backend update failed.');
    } finally {
      loading.value = false;
    }
  }

  function setFeed(feedId: string): void {
    selectedFeedId.value = feedId;
  }

  function setTag(tag: string): void {
    setTags(tag ? [tag] : []);
  }

  function setTags(tags: string[]): void {
    selectedTags.value = normalizeTags(tags);
  }

  async function updateFeedTags(feed: Feed, tags: string[]): Promise<void> {
    const normalized = normalizeTags(tags);
    busyAction.value = 'Updating feed tags';
    try {
      const updated = await client().updateFeed(feed.feedId, {
        name: feed.name,
        url: feed.url,
        enabled: feed.enabled,
        tags: normalized,
        limit: feed.limit || 20,
      });
      feeds.value = feeds.value.map((item) => (item.feedId === updated.feedId ? { ...item, ...updated } : item));
      allArticles.value = allArticles.value.map((article) => (article.sourceFeedId === updated.feedId ? { ...article, tags: updated.tags } : article));
      articles.value = articles.value.map((article) => (article.sourceFeedId === updated.feedId ? { ...article, tags: updated.tags } : article));
      if (selectedArticle.value?.sourceFeedId === updated.feedId) {
        selectedArticle.value = { ...selectedArticle.value, tags: updated.tags };
      }
      notice.value = { kind: 'success', message: `Updated tags for ${updated.name}.` };
      await persistCache();
      await loadArticles({ preserveSelection: true });
    } catch (error) {
      handleError(error, 'Unable to update feed tags.');
    } finally {
      busyAction.value = '';
    }
  }

  async function updateArticleTags(article: Article, tags: string[]): Promise<void> {
    const normalized = normalizeTags(tags);
    busyAction.value = 'Updating article tags';
    try {
      const updated = await client().updateArticle(article.articleId, { tags: normalized });
      replaceArticle({ ...article, ...updated });
      await persistCache();
      notice.value = { kind: 'success', message: 'Article tags updated.' };
    } catch (error) {
      handleError(error, 'Unable to update article tags.');
    } finally {
      busyAction.value = '';
    }
  }

  function dismissNotice(): void {
    notice.value = null;
  }

  async function runContentAction(label: string, action: () => Promise<void>): Promise<void> {
    busyAction.value = label;
    try {
      await action();
    } catch (error) {
      handleError(error, `${label} failed.`);
    } finally {
      busyAction.value = '';
    }
  }

  async function settleContentResult(result: { jobId?: string | null; status: string; article?: Article | null; message?: string | null; errors?: string[] }): Promise<void> {
    if (result.jobId && result.status !== 'completed') {
      notice.value = { kind: 'info', message: result.message || 'Content job queued.' };
      result = await pollContentJob(result.jobId);
    }
    if (result.article) {
      replaceArticle(result.article);
      selectedArticle.value = result.article;
    } else if (selectedArticle.value) {
      const refreshed = await client().article(selectedArticle.value.articleId);
      replaceArticle(refreshed);
      selectedArticle.value = refreshed;
    }
    await persistCache();
    notice.value = { kind: 'success', message: result.message || 'Article content updated.' };
  }

  async function pollContentJob(jobId: string): Promise<{ status: string; article?: Article | null; message?: string | null; errors?: string[] }> {
    const startedAt = Date.now();
    let attempt = 0;
    while (Date.now() - startedAt < CONTENT_JOB_TIMEOUT_MS) {
      await delay(attempt < 2 ? 900 : 2000);
      attempt += 1;
      const result = await client().contentJob(jobId);
      if (result.status === 'completed') return result;
      if (result.status === 'failed') {
        throw new Error(result.errors?.join('; ') || result.message || 'Content job failed.');
      }
      notice.value = { kind: 'info', message: result.message || `Content job ${result.status}...` };
    }
    throw new Error('Content job is taking longer than expected. Leave this article open and try Fetch Full again in a moment; the backend may still finish and cache the result.');
  }

  function replaceArticle(article: Article): void {
    allArticles.value = mergeArticles(allArticles.value, [article]);
    articles.value = articles.value.map((item) => (item.articleId === article.articleId ? { ...item, ...article } : item));
    if (selectedArticle.value?.articleId === article.articleId) {
      selectedArticle.value = { ...selectedArticle.value, ...article };
    }
  }

  function refreshFeedCounts(): void {
    const sourceId = selectedArticle.value?.sourceFeedId;
    feeds.value = feeds.value.map((feed) => {
      if (!sourceId || feed.feedId !== sourceId || feed.unreadCount <= 0) return feed;
      return { ...feed, unreadCount: feed.unreadCount - 1 };
    });
  }

  async function hydrateFromCache(): Promise<void> {
    const snapshot = (await loadOfflineCache()) || emptyCacheSnapshot();
    settings.value = { ...defaultSettings(), ...(snapshot.settings || {}) };
    feeds.value = snapshot.feeds || [];
    allArticles.value = snapshot.articles || [];
    highlights.value = snapshot.highlights || [];
    cacheCursor.value = Number(snapshot.cursor || 0);
    applyArticleFilters({ preserveSelection: true });
  }

  async function syncIncremental(): Promise<void> {
    if (!configured.value) return;
    if (!cacheCursor.value && !allArticles.value.length) {
      const initial = await client().articles({ limit: boundedNumber(activeSettings.value.defaultArticleLimit, 50, 1, 500) });
      allArticles.value = mergeArticles(allArticles.value, initial.articles || []);
      cacheCursor.value = Number(initial.cursor || Date.now());
      return;
    }
    const pulled = await client().syncPull(cacheCursor.value);
    allArticles.value = mergeArticles(allArticles.value, pulled.articles || []);
    for (const deletedId of pulled.deletions || []) {
      allArticles.value = allArticles.value.filter((article) => article.articleId !== deletedId);
    }
    cacheCursor.value = Number(pulled.cursor || cacheCursor.value || Date.now());
    if (!allArticles.value.length) {
      const fallback = await client().articles({ limit: boundedNumber(activeSettings.value.defaultArticleLimit, 50, 1, 500) });
      allArticles.value = mergeArticles(allArticles.value, fallback.articles || []);
      cacheCursor.value = Number(fallback.cursor || Date.now());
    }
  }

  function scheduleOpenRefresh(): void {
    if (!configured.value || !activeSettings.value.refreshOnOpen) return;
    if (backgroundRefreshInFlight || !openRefreshDue()) return;
    backgroundRefreshInFlight = true;
    markOpenRefreshAttempt();
    window.setTimeout(() => {
      void runOpenRefreshInBackground();
    }, 0);
  }

  async function runOpenRefreshInBackground(): Promise<void> {
    refreshing.value = true;
    try {
      const result = await client().refresh();
      await syncIncremental();
      const data = await client().bootstrap();
      settings.value = { ...defaultSettings(), ...data.settings };
      feeds.value = data.feeds || feeds.value;
      await loadHighlights({ persist: false });
      await persistCache();
      applyArticleFilters({ preserveSelection: true });
      notice.value = { kind: 'success', message: refreshMessage('Background refresh complete', result) };
    } catch (error) {
      if (!articles.value.length) handleError(error, 'Background refresh failed.');
    } finally {
      refreshing.value = false;
      backgroundRefreshInFlight = false;
    }
  }

  function openRefreshDue(): boolean {
    const last = Number(localStorage.getItem(OPEN_REFRESH_STORAGE_KEY) || 0);
    return !Number.isFinite(last) || Date.now() - last >= OPEN_REFRESH_MIN_INTERVAL_MS;
  }

  function markOpenRefreshAttempt(): void {
    localStorage.setItem(OPEN_REFRESH_STORAGE_KEY, String(Date.now()));
  }

  function refreshMessage(prefix: string, result: { saved: number; fetched: number; newArticlesSaved?: number; entriesChecked?: number; feedsUnchanged?: number }): string {
    const newCount = result.newArticlesSaved ?? result.saved;
    const checked = result.entriesChecked ?? result.fetched;
    const unchanged = result.feedsUnchanged ? `, ${result.feedsUnchanged} unchanged feeds` : '';
    return `${prefix}: ${newCount} new, ${checked} entries checked${unchanged}.`;
  }

  async function persistCache(): Promise<void> {
    await saveOfflineCache(
      {
        articles: allArticles.value,
        cachedAt: Date.now(),
        cursor: cacheCursor.value || Date.now(),
        feeds: feeds.value,
        highlights: highlights.value,
        settings: activeSettings.value,
      },
      activeSettings.value.localArticleCacheDays || 30,
    );
  }

  function applyArticleFilters(options: { preserveSelection?: boolean } = {}): void {
    const limit = boundedNumber(activeSettings.value.defaultArticleLimit, 50, 1, 1000);
    const q = query.value.trim().toLowerCase();
    const activeTags = selectedTags.value;
    let nextArticles = allArticles.value.filter((article) => {
      if (selectedFeedId.value && article.sourceFeedId !== selectedFeedId.value) return false;
      if (filter.value === 'unread' && article.isRead) return false;
      if (filter.value === 'saved' && !article.isSaved) return false;
      if (activeTags.length && !activeTags.some((tag) => normalizeTags(article.tags).includes(tag))) return false;
      if (q) {
        const haystack = [article.title, article.source, article.summary, article.contentPreview, article.content, ...(article.tags || [])].join(' ').toLowerCase();
        if (!haystack.includes(q)) return false;
      }
      return true;
    });
    nextArticles = nextArticles.sort(compareArticlesByRecency).slice(0, limit);
    articles.value = nextArticles;
    brandNewArticleIds.value = detectBrandNewArticleIds(articles.value);
    const currentId = selectedArticle.value?.articleId;
    const next = options.preserveSelection
      ? articles.value.find((article) => article.articleId === currentId) || articles.value[0] || null
      : articles.value[0] || null;
    if (next && next.articleId !== currentId) {
      selectedArticle.value = next;
      articleHighlights.value = highlights.value.filter((highlight) => highlight.articleId === next.articleId);
      loadSpeechPrefs(next.articleId);
    } else if (!next) {
      selectedArticle.value = null;
      articleHighlights.value = [];
    }
  }

  function handleError(error: unknown, fallback: string): void {
    const message = error instanceof RssApiError || error instanceof Error ? error.message : fallback;
    notice.value = { kind: 'error', message: message || fallback };
  }

  function loadSpeechPrefs(articleId: string): void {
    speechSegmentPercent.value = loadStoredInt(`percent:${articleId}`, DEFAULT_SEGMENT_PERCENT);
    speechSegmentIndex.value = loadStoredInt(`segment:${articleId}:${speechSegmentPercent.value}`, 0);
    speechSegmentCount.value = loadStoredInt(`count:${articleId}:${speechSegmentPercent.value}`, 1);
  }

  function saveSpeechPrefs(articleId: string): void {
    localStorage.setItem(`rss-ai-web-speech:percent:${articleId}`, String(speechSegmentPercent.value));
    localStorage.setItem(`rss-ai-web-speech:segment:${articleId}:${speechSegmentPercent.value}`, String(speechSegmentIndex.value));
    localStorage.setItem(`rss-ai-web-speech:count:${articleId}:${speechSegmentPercent.value}`, String(speechSegmentCount.value));
  }

  function scheduleLoadArticles(): void {
    if (searchTimer) clearTimeout(searchTimer);
    searchTimer = setTimeout(() => void loadArticles({ preserveSelection: false, network: false }), 280);
  }

  watch(query, scheduleLoadArticles);
  watch([selectedFeedId, selectedTags, filter], () => void loadArticles({ preserveSelection: false, network: false }));
  onMounted(() => void initialize());

  return {
    activeSettings,
    articles,
    articleHighlights,
    audioLabel,
    audioUrl,
    availableTags,
    bootstrap,
    brandNewArticleIds,
    brandNewCount,
    busyAction,
    config,
    configured,
    dismissNotice,
    feeds,
    fetchContent,
    filter,
    formatContent,
    deleteHighlight,
    highlights,
    loading,
    notice,
    playSpeech,
    query,
    refreshFeeds,
    refreshing,
    saveConfig,
    saveHighlight,
    selectArticle,
    selectArticleById,
    selectedArticle,
    selectedFeed,
    selectedFeedId,
    selectedTags,
    setFeed,
    selectedTag,
    setTag,
    setTags,
    setSpeechSegmentIndex,
    setSpeechSegmentPercent,
    settings,
    showSettings,
    speechCacheStatus,
    speechInputChars,
    speechSegmentCount,
    speechSegmentIndex,
    speechSegmentPercent,
    speechSourceChars,
    speechTarget,
    stopSpeech,
    summarize,
    handleSpeechEnded,
    toggleSaved,
    totalUnread,
    updateArticleTags,
    updateFeedTags,
  };
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function compareArticlesByRecency(left: Article, right: Article): number {
  return articleTimestamp(right) - articleTimestamp(left);
}

function articleTimestamp(article: Article): number {
  const published = Date.parse(article.publishedAt || '');
  if (Number.isFinite(published)) return published;
  return Number(article.updatedAt || article.fetchedAt || 0);
}

function loadStoredInt(key: string, fallback: number): number {
  const value = Number.parseInt(localStorage.getItem(`rss-ai-web-speech:${key}`) || '', 10);
  return Number.isFinite(value) ? value : fallback;
}

function normalizeTags(value: string | string[] | null | undefined): string[] {
  const raw = Array.isArray(value) ? value : String(value || '').split(',');
  const seen = new Set<string>();
  const normalized: string[] = [];
  for (const tag of raw) {
    const clean = String(tag).trim().replace(/^#/, '').toLowerCase().replace(/\s+/g, ' ');
    if (!clean || seen.has(clean)) continue;
    seen.add(clean);
    normalized.push(clean);
  }
  return normalized;
}

function boundedNumber(value: unknown, fallback: number, minimum: number, maximum: number): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.max(minimum, Math.min(maximum, Math.trunc(parsed)));
}

function upsertHighlight(items: ArticleHighlight[], highlight: ArticleHighlight): ArticleHighlight[] {
  const rest = items.filter((item) => item.highlightId !== highlight.highlightId);
  return [highlight, ...rest].sort((left, right) => Number(right.createdAt || 0) - Number(left.createdAt || 0));
}
