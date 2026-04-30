import { computed, onMounted, ref, watch } from 'vue';
import { RssApiClient, RssApiError } from '../api';
import { loadRuntimeConfig, saveRuntimeConfig } from '../config';
import type { Article, ArticleFilter, Feed, RuntimeConfig, Settings, SpeechTarget } from '../types';

type Notice = { kind: 'info' | 'success' | 'error'; message: string };

const INITIAL_CONFIG: RuntimeConfig = { apiBaseUrl: '', apiToken: '', defaultTheme: 'warm' };

export function defaultSettings(): Settings {
  return {
    llmProvider: 'openai_compatible',
    aiModel: 'gpt-5.4',
    aiApiBase: 'https://api.openai.com/v1',
    codexModel: 'gpt-5.4',
    codexReasoningEffort: 'medium',
    embeddingModel: 'text-embedding-3-small',
    ttsModel: 'gpt-4o-mini-tts-2025-12-15',
    ttsVoice: 'marin',
    ttsInstructions: 'Read clearly with natural pacing for a personal news briefing.',
    aiContentFormattingEnabled: false,
    browserBypassEnabled: true,
    browserBypassMode: 'on_blocked',
  };
}

export function useRssReader() {
  const config = ref<RuntimeConfig>(INITIAL_CONFIG);
  const settings = ref<Settings | null>(null);
  const feeds = ref<Feed[]>([]);
  const articles = ref<Article[]>([]);
  const selectedFeedId = ref('');
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
  let searchTimer: ReturnType<typeof setTimeout> | undefined;

  const configured = computed(() => Boolean(config.value.apiBaseUrl && config.value.apiToken));
  const totalUnread = computed(() => feeds.value.reduce((sum, feed) => sum + Number(feed.unreadCount || 0), 0));
  const selectedFeed = computed(() => feeds.value.find((feed) => feed.feedId === selectedFeedId.value) || null);
  const activeSettings = computed(() => settings.value || defaultSettings());

  function client(): RssApiClient {
    return new RssApiClient(config.value.apiBaseUrl, config.value.apiToken);
  }

  async function initialize(): Promise<void> {
    config.value = await loadRuntimeConfig();
    if (!configured.value) {
      showSettings.value = true;
      notice.value = { kind: 'info', message: 'Add the backend API URL and token to start reading.' };
      return;
    }
    await bootstrap();
  }

  async function bootstrap(): Promise<void> {
    loading.value = true;
    try {
      const data = await client().bootstrap();
      settings.value = { ...defaultSettings(), ...data.settings };
      feeds.value = data.feeds || [];
      await loadArticles({ preserveSelection: true });
    } catch (error) {
      handleError(error, 'Unable to load backend data.');
      showSettings.value = true;
    } finally {
      loading.value = false;
    }
  }

  async function loadArticles(options: { preserveSelection?: boolean } = {}): Promise<void> {
    if (!configured.value) return;
    loading.value = true;
    try {
      const data = await client().articles({
        query: query.value.trim() || undefined,
        source: selectedFeedId.value || undefined,
        unread: filter.value === 'unread',
        saved: filter.value === 'saved',
        limit: 200,
      });
      articles.value = data.articles || [];
      const currentId = selectedArticle.value?.articleId;
      const next = options.preserveSelection
        ? articles.value.find((article) => article.articleId === currentId) || articles.value[0] || null
        : articles.value[0] || null;
      if (next && next.articleId !== currentId) {
        await selectArticle(next);
      } else if (!next) {
        selectedArticle.value = null;
      }
    } catch (error) {
      handleError(error, 'Unable to load articles.');
    } finally {
      loading.value = false;
    }
  }

  async function selectArticle(article: Article): Promise<void> {
    selectedArticle.value = article;
    busyAction.value = 'Opening article';
    try {
      const full = await client().article(article.articleId);
      replaceArticle(full);
      selectedArticle.value = full;
      if (!full.isRead) {
        const read = await client().markRead(full.articleId);
        replaceArticle(read);
        selectedArticle.value = { ...full, ...read, content: full.content, contentPreview: full.contentPreview };
        refreshFeedCounts();
      }
    } catch (error) {
      handleError(error, 'Unable to open article.');
    } finally {
      busyAction.value = '';
    }
  }

  async function refreshFeeds(): Promise<void> {
    if (!configured.value) return;
    refreshing.value = true;
    try {
      const result = selectedFeedId.value ? await client().refreshFeed(selectedFeedId.value) : await client().refresh();
      notice.value = { kind: 'success', message: `Refresh complete: ${result.saved} new, ${result.fetched} fetched.` };
      await bootstrap();
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
    } catch (error) {
      handleError(error, 'Unable to update saved state.');
    }
  }

  async function playSpeech(target: SpeechTarget): Promise<void> {
    const article = selectedArticle.value;
    if (!article) return;
    busyAction.value = target === 'summary' ? 'Creating summary audio' : 'Creating article audio';
    try {
      if (audioUrl.value) URL.revokeObjectURL(audioUrl.value);
      const blob = await client().speech(article.articleId, { target, segmentPercent: target === 'content' ? 30 : 100 });
      audioUrl.value = URL.createObjectURL(blob);
      audioLabel.value = target === 'summary' ? 'AI summary audio' : 'Article audio';
      notice.value = { kind: 'success', message: 'Audio is ready.' };
    } catch (error) {
      handleError(error, 'Unable to create audio.');
    } finally {
      busyAction.value = '';
    }
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
    notice.value = { kind: 'success', message: result.message || 'Article content updated.' };
  }

  async function pollContentJob(jobId: string): Promise<{ status: string; article?: Article | null; message?: string | null; errors?: string[] }> {
    for (let attempt = 0; attempt < 70; attempt += 1) {
      await delay(attempt < 2 ? 900 : 1500);
      const result = await client().contentJob(jobId);
      if (result.status === 'completed') return result;
      if (result.status === 'failed') {
        throw new Error(result.errors?.join('; ') || result.message || 'Content job failed.');
      }
      notice.value = { kind: 'info', message: result.message || `Content job ${result.status}...` };
    }
    throw new Error('Content job timed out in the browser. It may still finish in the backend.');
  }

  function replaceArticle(article: Article): void {
    articles.value = articles.value.map((item) => (item.articleId === article.articleId ? { ...item, ...article } : item));
  }

  function refreshFeedCounts(): void {
    const sourceId = selectedArticle.value?.sourceFeedId;
    feeds.value = feeds.value.map((feed) => {
      if (!sourceId || feed.feedId !== sourceId || feed.unreadCount <= 0) return feed;
      return { ...feed, unreadCount: feed.unreadCount - 1 };
    });
  }

  function handleError(error: unknown, fallback: string): void {
    const message = error instanceof RssApiError || error instanceof Error ? error.message : fallback;
    notice.value = { kind: 'error', message: message || fallback };
  }

  function scheduleLoadArticles(): void {
    if (searchTimer) clearTimeout(searchTimer);
    searchTimer = setTimeout(() => void loadArticles({ preserveSelection: false }), 280);
  }

  watch(query, scheduleLoadArticles);
  watch([selectedFeedId, filter], () => void loadArticles({ preserveSelection: false }));
  onMounted(() => void initialize());

  return {
    activeSettings,
    articles,
    audioLabel,
    audioUrl,
    bootstrap,
    busyAction,
    config,
    configured,
    dismissNotice,
    feeds,
    fetchContent,
    filter,
    formatContent,
    loading,
    notice,
    playSpeech,
    query,
    refreshFeeds,
    refreshing,
    saveConfig,
    selectArticle,
    selectedArticle,
    selectedFeed,
    selectedFeedId,
    setFeed,
    settings,
    showSettings,
    summarize,
    toggleSaved,
    totalUnread,
  };
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}
