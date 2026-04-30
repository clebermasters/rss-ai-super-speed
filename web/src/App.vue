<script setup lang="ts">
import ArticleList from './components/ArticleList.vue';
import FeedRail from './components/FeedRail.vue';
import ReaderPane from './components/ReaderPane.vue';
import SettingsModal from './components/SettingsModal.vue';
import StatusToast from './components/StatusToast.vue';
import TopBar from './components/TopBar.vue';
import { useRssReader } from './composables/useRssReader';

const reader = useRssReader();
</script>

<template>
  <main class="app-shell">
    <div class="aurora one" />
    <div class="aurora two" />
    <TopBar
      v-model:query="reader.query.value"
      :configured="reader.configured.value"
      :loading="reader.refreshing.value"
      @refresh="reader.refreshFeeds"
      @settings="reader.showSettings.value = true"
    />

    <section class="dashboard">
      <FeedRail
        :feeds="reader.feeds.value"
        :selected-feed-id="reader.selectedFeedId.value"
        :total-unread="reader.totalUnread.value"
        @select="reader.setFeed"
      />
      <ArticleList
        v-model:filter="reader.filter.value"
        :articles="reader.articles.value"
        :loading="reader.loading.value"
        :selected-article-id="reader.selectedArticle.value?.articleId || ''"
        @select="reader.selectArticle"
      />
      <ReaderPane
        :article="reader.selectedArticle.value"
        :audio-label="reader.audioLabel.value"
        :audio-url="reader.audioUrl.value"
        :busy-action="reader.busyAction.value"
        :feed-name="reader.selectedFeed.value?.name || 'All Articles'"
        :settings="reader.activeSettings.value"
        @fetch-content="reader.fetchContent"
        @format-content="reader.formatContent"
        @play-speech="reader.playSpeech"
        @summarize="reader.summarize"
        @toggle-save="reader.toggleSaved"
      />
    </section>

    <SettingsModal
      :config="reader.config.value"
      :open="reader.showSettings.value"
      :settings="reader.activeSettings.value"
      @close="reader.showSettings.value = false"
      @save="reader.saveConfig"
    />

    <StatusToast :notice="reader.notice.value" @dismiss="reader.dismissNotice" />
  </main>
</template>
