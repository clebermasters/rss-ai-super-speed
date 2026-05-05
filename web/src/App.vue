<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import ArticleList from './components/ArticleList.vue';
import ConfigurationPage from './components/ConfigurationPage.vue';
import FeedRail from './components/FeedRail.vue';
import HighlightsPage from './components/HighlightsPage.vue';
import InfiniteArticleFlow from './components/InfiniteArticleFlow.vue';
import KeyboardShortcutsModal from './components/KeyboardShortcutsModal.vue';
import ReaderPane from './components/ReaderPane.vue';
import RsvpReader from './components/RsvpReader.vue';
import StatusToast from './components/StatusToast.vue';
import TagEditorModal from './components/TagEditorModal.vue';
import TagFilterBar from './components/TagFilterBar.vue';
import TopBar from './components/TopBar.vue';
import { useRssReader } from './composables/useRssReader';
import type { Article, Feed } from './types';
import { appRouteToHash, parseAppRoute, type AppRouteState, type AppView } from './urlState';

const reader = useRssReader();
const feedRailCollapsed = ref(false);
const articleListCollapsed = ref(false);
const readerFullscreen = ref(false);
const flowMode = ref(false);
const activeFlowArticleId = ref('');
const highlightsMode = ref(false);
const shortcutsOpen = ref(false);
const rsvpOpen = ref(false);
const rsvpMode = ref<'word-runner' | 'spritz'>('word-runner');
const tagEditor = ref<
  | { kind: 'feed'; subject: string; tags: string[]; feed: Feed }
  | { kind: 'article'; subject: string; tags: string[]; article: Article }
  | null
>(null);
const pendingRouteArticleId = ref('');
const routeArticleIdOverride = ref('');
let applyingRoute = false;
let lastHandledHash = '';

function toggleFlowMode(): void {
  routeArticleIdOverride.value = '';
  if (!flowMode.value) {
    activeFlowArticleId.value = reader.selectedArticle.value?.articleId || reader.articles.value[0]?.articleId || '';
  }
  flowMode.value = !flowMode.value;
  highlightsMode.value = false;
  reader.showSettings.value = false;
  if (flowMode.value) {
    reader.setFeed('');
    reader.filter.value = 'all';
    readerFullscreen.value = false;
  }
  pushAppRoute();
}

function toggleHighlightsMode(): void {
  routeArticleIdOverride.value = '';
  highlightsMode.value = !highlightsMode.value;
  flowMode.value = false;
  reader.showSettings.value = false;
  readerFullscreen.value = false;
  pushAppRoute();
}

function selectFeed(feedId: string): void {
  reader.setFeed(feedId);
  flowMode.value = feedId === '';
  highlightsMode.value = false;
  if (flowMode.value) reader.filter.value = 'all';
  if (flowMode.value) ensureActiveFlowArticle();
  pushAppRoute();
}

function selectTags(tags: string[]): void {
  reader.setTags(tags);
  pushAppRoute();
}

function openSettings(): void {
  reader.showSettings.value = true;
  pushAppRoute();
}

function closeSettings(): void {
  reader.showSettings.value = false;
  pushAppRoute();
}

function openHighlightedArticle(articleId: string): void {
  highlightsMode.value = false;
  flowMode.value = false;
  reader.showSettings.value = false;
  routeArticleIdOverride.value = articleId;
  pushAppRoute();
  void reader.selectArticleById(articleId).finally(() => {
    if (routeArticleIdOverride.value === articleId) routeArticleIdOverride.value = '';
    replaceAppRoute();
  });
}

function openFlowArticle(article: Article): void {
  routeArticleIdOverride.value = '';
  setActiveFlowArticle(article);
  replaceAppRoute();
  flowMode.value = false;
  reader.showSettings.value = false;
  void reader.selectArticle(article);
  pushAppRoute();
}

function focusFlowArticle(article: Article): void {
  routeArticleIdOverride.value = '';
  setActiveFlowArticle(article);
  replaceAppRoute();
  flowMode.value = false;
  reader.showSettings.value = false;
  readerFullscreen.value = true;
  void reader.selectArticle(article);
  pushAppRoute();
}

function selectArticle(article: Article): void {
  routeArticleIdOverride.value = '';
  flowMode.value = false;
  highlightsMode.value = false;
  reader.showSettings.value = false;
  void reader.selectArticle(article);
  pushAppRoute();
}

function toggleReaderFullscreen(): void {
  if (!reader.selectedArticle.value) return;
  readerFullscreen.value = !readerFullscreen.value;
  pushAppRoute();
}

function openRsvp(mode: 'word-runner' | 'spritz'): void {
  rsvpMode.value = mode;
  rsvpOpen.value = true;
}

function openFlowRsvp(article: Article, mode: 'word-runner' | 'spritz'): void {
  setActiveFlowArticle(article);
  replaceAppRoute();
  void reader.selectArticle(article).then(() => openRsvp(mode));
}

function listenFlowArticle(article: Article, target: 'content' | 'summary'): void {
  setActiveFlowArticle(article);
  replaceAppRoute();
  void reader.selectArticle(article).then(() => reader.playSpeech(target));
}

function editFeedTags(feed: Feed): void {
  tagEditor.value = { kind: 'feed', subject: feed.name, tags: feed.tags || [], feed };
}

function editSelectedArticleTags(): void {
  const article = reader.selectedArticle.value;
  if (!article) return;
  tagEditor.value = { kind: 'article', subject: article.title, tags: article.tags || [], article };
}

function saveTags(tags: string[]): void {
  const current = tagEditor.value;
  if (!current) return;
  tagEditor.value = null;
  if (current.kind === 'feed') {
    void reader.updateFeedTags(current.feed, tags);
  } else {
    void reader.updateArticleTags(current.article, tags);
  }
}

function ensureActiveFlowArticle(): Article | null {
  const articles = reader.articles.value;
  const current = articles.find((article) => article.articleId === activeFlowArticleId.value);
  if (current) return current;
  const next = reader.selectedArticle.value && articles.find((article) => article.articleId === reader.selectedArticle.value?.articleId)
    ? reader.selectedArticle.value
    : articles[0] || null;
  activeFlowArticleId.value = next?.articleId || '';
  return next;
}

function setActiveFlowArticle(article: Article): void {
  activeFlowArticleId.value = article.articleId;
}

function moveArticle(offset: number): void {
  const articles = reader.articles.value;
  if (!articles.length) return;
  const currentId = flowMode.value ? activeFlowArticleId.value : reader.selectedArticle.value?.articleId || '';
  const currentIndex = articles.findIndex((article) => article.articleId === currentId);
  const fallbackIndex = offset > 0 ? -1 : articles.length;
  const nextIndex = Math.min(Math.max((currentIndex >= 0 ? currentIndex : fallbackIndex) + offset, 0), articles.length - 1);
  const nextArticle = articles[nextIndex];
  if (!nextArticle) return;
  if (flowMode.value) {
    activeFlowArticleId.value = nextArticle.articleId;
    replaceAppRoute();
    return;
  }
  selectArticle(nextArticle);
}

function markActiveArticleRead(): void {
  const article = flowMode.value ? ensureActiveFlowArticle() : reader.selectedArticle.value;
  if (!article) return;
  void reader.markArticleRead(article);
}

function toggleKeyboardFocusMode(): void {
  if (flowMode.value) {
    const article = ensureActiveFlowArticle();
    if (article) focusFlowArticle(article);
    return;
  }
  toggleReaderFullscreen();
}

function goBackShortcut(): void {
  if (shortcutsOpen.value) {
    shortcutsOpen.value = false;
    return;
  }
  if (rsvpOpen.value) {
    rsvpOpen.value = false;
    return;
  }
  if (tagEditor.value) {
    tagEditor.value = null;
    return;
  }
  if (window.history.length > 1) {
    window.history.back();
    return;
  }
  reader.showSettings.value = false;
  highlightsMode.value = false;
  flowMode.value = false;
  readerFullscreen.value = false;
  replaceAppRoute();
}

function currentView(): AppView {
  if (reader.showSettings.value) return 'settings';
  if (highlightsMode.value) return 'highlights';
  if (flowMode.value) return 'flow';
  return 'board';
}

function buildRouteState(): AppRouteState {
  const view = currentView();
  return {
    articleId: view === 'flow' ? activeFlowArticleId.value : routeArticleIdOverride.value || reader.selectedArticle.value?.articleId || '',
    feedId: reader.selectedFeedId.value,
    filter: reader.filter.value,
    query: reader.query.value,
    readerFullscreen: view === 'board' && readerFullscreen.value,
    tags: reader.selectedTags.value,
    view,
  };
}

function pushAppRoute(): void {
  commitAppRoute('push');
}

function replaceAppRoute(): void {
  commitAppRoute('replace');
}

function commitAppRoute(mode: 'push' | 'replace'): void {
  if (applyingRoute) return;
  const hash = appRouteToHash(buildRouteState());
  if (window.location.hash === hash) return;
  const url = `${window.location.pathname}${window.location.search}${hash}`;
  window.history[mode === 'push' ? 'pushState' : 'replaceState']({ rssAi: true }, '', url);
  lastHandledHash = hash;
}

function applyRouteState(route: AppRouteState): void {
  applyingRoute = true;
  routeArticleIdOverride.value = '';
  reader.setFeed(route.feedId);
  reader.setTags(route.tags);
  reader.filter.value = route.filter;
  reader.query.value = route.query;
  readerFullscreen.value = route.view === 'board' && route.readerFullscreen;
  reader.showSettings.value = route.view === 'settings';
  highlightsMode.value = route.view === 'highlights';
  flowMode.value = route.view === 'flow';
  activeFlowArticleId.value = route.view === 'flow' ? route.articleId : '';
  pendingRouteArticleId.value = route.view === 'board' ? route.articleId : '';
  void nextTick(() => {
    applyingRoute = false;
    if (flowMode.value) ensureActiveFlowArticle();
    void openPendingRouteArticle();
  });
}

async function openPendingRouteArticle(): Promise<void> {
  if (!reader.initialized.value || !pendingRouteArticleId.value) return;
  const articleId = pendingRouteArticleId.value;
  pendingRouteArticleId.value = '';
  reader.showSettings.value = false;
  highlightsMode.value = false;
  flowMode.value = false;
  await reader.selectArticleById(articleId);
  replaceAppRoute();
}

function applyCurrentRouteFromLocation(): void {
  const hash = window.location.hash || '#/board';
  if (hash === lastHandledHash) return;
  lastHandledHash = hash;
  applyRouteState(parseAppRoute(hash));
}

function handlePopState(): void {
  applyCurrentRouteFromLocation();
}

function handleKeyboardShortcut(event: KeyboardEvent): void {
  if (event.defaultPrevented) return;
  if (shouldIgnoreShortcutTarget(event.target)) return;
  if (event.altKey && event.key === 'ArrowLeft') {
    event.preventDefault();
    goBackShortcut();
    return;
  }
  if (event.metaKey || event.ctrlKey || event.altKey) return;
  const key = event.key.toLowerCase();
  if (shortcutsOpen.value && key !== 'escape' && key !== '[') return;
  if (key === 'j' || key === 'arrowdown') {
    event.preventDefault();
    moveArticle(1);
  } else if (key === 'k' || key === 'arrowup') {
    event.preventDefault();
    moveArticle(-1);
  } else if (key === 'm') {
    event.preventDefault();
    markActiveArticleRead();
  } else if (key === 'f') {
    event.preventDefault();
    toggleKeyboardFocusMode();
  } else if (key === 'v') {
    event.preventDefault();
    toggleFlowMode();
  } else if (key === '[' || key === 'escape') {
    event.preventDefault();
    goBackShortcut();
  }
}

function shouldIgnoreShortcutTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || Boolean(target.closest('input, textarea, select, [contenteditable], [role="textbox"]'));
}

lastHandledHash = window.location.hash || '#/board';
applyRouteState(parseAppRoute(lastHandledHash));

watch(
  [
    flowMode,
    highlightsMode,
    activeFlowArticleId,
    routeArticleIdOverride,
    reader.showSettings,
    reader.selectedArticle,
    reader.selectedFeedId,
    reader.selectedTags,
    reader.filter,
    reader.query,
    readerFullscreen,
  ],
  () => replaceAppRoute(),
  { flush: 'post' },
);

watch(reader.initialized, () => void openPendingRouteArticle());
watch(reader.articles, () => {
  if (flowMode.value) ensureActiveFlowArticle();
  void openPendingRouteArticle();
});

onMounted(() => {
  window.addEventListener('popstate', handlePopState);
  window.addEventListener('hashchange', handlePopState);
  window.addEventListener('keydown', handleKeyboardShortcut);
  replaceAppRoute();
  void openPendingRouteArticle();
});

onBeforeUnmount(() => {
  window.removeEventListener('popstate', handlePopState);
  window.removeEventListener('hashchange', handlePopState);
  window.removeEventListener('keydown', handleKeyboardShortcut);
});
</script>

<template>
  <main class="app-shell">
    <div class="aurora one" />
    <div class="aurora two" />
    <TopBar
      v-model:query="reader.query.value"
      :configured="reader.configured.value"
      :flow-active="flowMode"
      :highlight-count="reader.highlights.value.length"
      :highlights-active="highlightsMode"
      :loading="reader.refreshing.value"
      :new-count="reader.brandNewCount.value"
      @flow="toggleFlowMode"
      @highlights="toggleHighlightsMode"
      @refresh="reader.refreshFeeds"
      @shortcuts="shortcutsOpen = true"
      @settings="openSettings"
    />

    <ConfigurationPage
      v-if="reader.showSettings.value"
      :available-tags="reader.availableTags.value.map((tag) => tag.tag)"
      :config="reader.config.value"
      :configured="reader.configured.value"
      :settings="reader.activeSettings.value"
      @close="closeSettings"
      @save="reader.saveConfig"
    />

    <TagFilterBar
      v-else
      :selected-tags="reader.selectedTags.value"
      :tags="reader.availableTags.value"
      @select="selectTags"
    />

    <InfiniteArticleFlow
      v-if="!reader.showSettings.value && flowMode"
      :active-article-id="activeFlowArticleId"
      :articles="reader.articles.value"
      :brand-new-article-ids="reader.brandNewArticleIds.value"
      :loading="reader.loading.value"
      :selected-tag="reader.selectedTags.value.join(', ')"
      @focus="focusFlowArticle"
      @listen="listenFlowArticle"
      @open="openFlowArticle"
      @rsvp="openFlowRsvp"
    />

    <HighlightsPage
      v-else-if="!reader.showSettings.value && highlightsMode"
      :highlights="reader.highlights.value"
      @delete="reader.deleteHighlight"
      @open="openHighlightedArticle"
    />

    <section
      v-else-if="!reader.showSettings.value"
      class="dashboard"
      :class="{ 'feed-collapsed': feedRailCollapsed, 'list-collapsed': articleListCollapsed, 'reader-fullscreen': readerFullscreen }"
    >
      <FeedRail
        :brand-new-count="reader.brandNewCount.value"
        :collapsed="feedRailCollapsed"
        :feeds="reader.feeds.value"
        :selected-feed-id="reader.selectedFeedId.value"
        :total-unread="reader.totalUnread.value"
        @edit-tags="editFeedTags"
        @select="selectFeed"
        @toggle="feedRailCollapsed = !feedRailCollapsed"
      />
      <ArticleList
        v-model:filter="reader.filter.value"
        :articles="reader.articles.value"
        :brand-new-article-ids="reader.brandNewArticleIds.value"
        :collapsed="articleListCollapsed"
        :loading="reader.loading.value"
        :selected-article-id="reader.selectedArticle.value?.articleId || ''"
        @select="selectArticle"
        @toggle="articleListCollapsed = !articleListCollapsed"
      />
      <ReaderPane
        :article="reader.selectedArticle.value"
        :audio-label="reader.audioLabel.value"
        :audio-url="reader.audioUrl.value"
        :busy-action="reader.busyAction.value"
        :feed-name="reader.selectedFeed.value?.name || 'All Articles'"
        :fullscreen="readerFullscreen"
        :highlights="reader.articleHighlights.value"
        :is-brand-new="Boolean(reader.selectedArticle.value && reader.brandNewArticleIds.value.has(reader.selectedArticle.value.articleId))"
        :settings="reader.activeSettings.value"
        :speech-cache-status="reader.speechCacheStatus.value"
        :speech-input-chars="reader.speechInputChars.value"
        :speech-segment-count="reader.speechSegmentCount.value"
        :speech-segment-index="reader.speechSegmentIndex.value"
        :speech-segment-percent="reader.speechSegmentPercent.value"
        :speech-source-chars="reader.speechSourceChars.value"
        :speech-target="reader.speechTarget.value"
        @audio-ended="reader.handleSpeechEnded"
        @edit-tags="editSelectedArticleTags"
        @fetch-content="reader.fetchContent"
        @format-content="reader.formatContent"
        @delete-highlight="reader.deleteHighlight"
        @play-speech="reader.playSpeech"
        @regenerate-speech="(target) => reader.playSpeech(target, { forceRefresh: true })"
        @rsvp="openRsvp"
        @save-highlight="reader.saveHighlight"
        @set-speech-segment-index="reader.setSpeechSegmentIndex"
        @set-speech-segment-percent="reader.setSpeechSegmentPercent"
        @stop-speech="reader.stopSpeech"
        @summarize="reader.summarize"
        @toggle-fullscreen="toggleReaderFullscreen"
        @toggle-save="reader.toggleSaved"
      />
    </section>

    <RsvpReader
      :article="reader.selectedArticle.value"
      :mode="rsvpMode"
      :open="rsvpOpen"
      @close="rsvpOpen = false"
    />

    <KeyboardShortcutsModal :open="shortcutsOpen" @close="shortcutsOpen = false" />

    <TagEditorModal
      :open="Boolean(tagEditor)"
      :subject="tagEditor?.subject || ''"
      :tags="tagEditor?.tags || []"
      @close="tagEditor = null"
      @save="saveTags"
    />

    <StatusToast :notice="reader.notice.value" @dismiss="reader.dismissNotice" />
  </main>
</template>
