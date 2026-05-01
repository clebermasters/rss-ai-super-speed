<script setup lang="ts">
import { ref } from 'vue';
import ArticleList from './components/ArticleList.vue';
import ConfigurationPage from './components/ConfigurationPage.vue';
import FeedRail from './components/FeedRail.vue';
import InfiniteArticleFlow from './components/InfiniteArticleFlow.vue';
import ReaderPane from './components/ReaderPane.vue';
import RsvpReader from './components/RsvpReader.vue';
import StatusToast from './components/StatusToast.vue';
import TagEditorModal from './components/TagEditorModal.vue';
import TagFilterBar from './components/TagFilterBar.vue';
import TopBar from './components/TopBar.vue';
import { useRssReader } from './composables/useRssReader';
import type { Article, Feed } from './types';

const reader = useRssReader();
const feedRailCollapsed = ref(false);
const articleListCollapsed = ref(false);
const readerFullscreen = ref(false);
const flowMode = ref(false);
const rsvpOpen = ref(false);
const rsvpMode = ref<'word-runner' | 'spritz'>('word-runner');
const tagEditor = ref<
  | { kind: 'feed'; subject: string; tags: string[]; feed: Feed }
  | { kind: 'article'; subject: string; tags: string[]; article: Article }
  | null
>(null);

function toggleFlowMode(): void {
  flowMode.value = !flowMode.value;
  if (flowMode.value) {
    reader.setFeed('');
    reader.filter.value = 'all';
    readerFullscreen.value = false;
  }
}

function selectFeed(feedId: string): void {
  reader.setFeed(feedId);
  flowMode.value = feedId === '';
  if (flowMode.value) reader.filter.value = 'all';
}

function openFlowArticle(article: Article): void {
  flowMode.value = false;
  void reader.selectArticle(article);
}

function focusFlowArticle(article: Article): void {
  flowMode.value = false;
  readerFullscreen.value = true;
  void reader.selectArticle(article);
}

function openRsvp(mode: 'word-runner' | 'spritz'): void {
  rsvpMode.value = mode;
  rsvpOpen.value = true;
}

function openFlowRsvp(article: Article, mode: 'word-runner' | 'spritz'): void {
  void reader.selectArticle(article).then(() => openRsvp(mode));
}

function listenFlowArticle(article: Article, target: 'content' | 'summary'): void {
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
</script>

<template>
  <main class="app-shell">
    <div class="aurora one" />
    <div class="aurora two" />
    <TopBar
      v-model:query="reader.query.value"
      :configured="reader.configured.value"
      :flow-active="flowMode"
      :loading="reader.refreshing.value"
      :new-count="reader.brandNewCount.value"
      @flow="toggleFlowMode"
      @refresh="reader.refreshFeeds"
      @settings="reader.showSettings.value = true"
    />

    <ConfigurationPage
      v-if="reader.showSettings.value"
      :available-tags="reader.availableTags.value.map((tag) => tag.tag)"
      :config="reader.config.value"
      :configured="reader.configured.value"
      :settings="reader.activeSettings.value"
      @close="reader.showSettings.value = false"
      @save="reader.saveConfig"
    />

    <TagFilterBar
      v-else
      :selected-tag="reader.selectedTag.value"
      :tags="reader.availableTags.value"
      @select="reader.setTag"
    />

    <InfiniteArticleFlow
      v-if="!reader.showSettings.value && flowMode"
      :articles="reader.articles.value"
      :brand-new-article-ids="reader.brandNewArticleIds.value"
      :loading="reader.loading.value"
      :selected-tag="reader.selectedTag.value"
      @focus="focusFlowArticle"
      @listen="listenFlowArticle"
      @open="openFlowArticle"
      @rsvp="openFlowRsvp"
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
        @select="reader.selectArticle"
        @toggle="articleListCollapsed = !articleListCollapsed"
      />
      <ReaderPane
        :article="reader.selectedArticle.value"
        :audio-label="reader.audioLabel.value"
        :audio-url="reader.audioUrl.value"
        :busy-action="reader.busyAction.value"
        :feed-name="reader.selectedFeed.value?.name || 'All Articles'"
        :fullscreen="readerFullscreen"
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
        @play-speech="reader.playSpeech"
        @regenerate-speech="(target) => reader.playSpeech(target, { forceRefresh: true })"
        @rsvp="openRsvp"
        @set-speech-segment-index="reader.setSpeechSegmentIndex"
        @set-speech-segment-percent="reader.setSpeechSegmentPercent"
        @stop-speech="reader.stopSpeech"
        @summarize="reader.summarize"
        @toggle-fullscreen="readerFullscreen = !readerFullscreen"
        @toggle-save="reader.toggleSaved"
      />
    </section>

    <RsvpReader
      :article="reader.selectedArticle.value"
      :mode="rsvpMode"
      :open="rsvpOpen"
      @close="rsvpOpen = false"
    />

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
