<script setup lang="ts">
import { computed } from 'vue';
import { plainDate, richTextToHtml } from '../render';
import type { Article, Settings, SpeechTarget } from '../types';

const props = defineProps<{
  article: Article | null;
  audioLabel: string;
  audioUrl: string;
  busyAction: string;
  feedName: string;
  settings: Settings;
}>();

defineEmits<{
  fetchContent: [];
  formatContent: [];
  playSpeech: [target: SpeechTarget];
  summarize: [];
  toggleSave: [];
}>();

const busy = computed(() => Boolean(props.busyAction));
const summaryHtml = computed(() => richTextToHtml(props.article?.summary || ''));
const contentHtml = computed(() => richTextToHtml(props.article?.content || props.article?.contentPreview || ''));
const hasContent = computed(() => Boolean(props.article?.content || props.article?.contentPreview));
const sourceHost = computed(() => {
  if (!props.article?.link) return '';
  try {
    return new URL(props.article.link).hostname.replace(/^www\./, '');
  } catch {
    return props.article.link;
  }
});
</script>

<template>
  <article class="reader-pane float-in delay-3">
    <div v-if="!article" class="reader-empty">
      <span class="empty-orb">⌁</span>
      <h2>Select an article</h2>
      <p>Your full reading surface will appear here with AI summaries, formatting, audio, and source links.</p>
    </div>

    <template v-else>
      <header class="reader-header">
        <div>
          <p class="eyebrow">{{ feedName }}</p>
          <h2>{{ article.title }}</h2>
        </div>
        <button class="save-button" :class="{ saved: article.isSaved }" @click="$emit('toggleSave')">
          {{ article.isSaved ? 'Saved ★' : 'Save ☆' }}
        </button>
      </header>

      <div class="article-facts">
        <span>{{ article.source }}</span>
        <span>{{ plainDate(article.publishedAt) }}</span>
        <span v-if="article.score">{{ article.score }} pts</span>
        <span v-if="article.contentAiFormatted">AI formatted</span>
      </div>

      <a class="source-link" :href="article.link" target="_blank" rel="noreferrer">
        <span>↗</span>
        {{ sourceHost }}
      </a>

      <section v-if="summaryHtml" class="summary-card">
        <div class="card-title">
          <span>✦</span>
          <strong>AI Summary</strong>
        </div>
        <div class="rich-text compact" v-html="summaryHtml" />
      </section>

      <div class="reader-toolbar">
        <button :disabled="busy" @click="$emit('fetchContent')">{{ busyAction === 'Fetching full article' ? 'Fetching...' : 'Fetch Full' }}</button>
        <button :disabled="busy || !hasContent" @click="$emit('formatContent')">{{ busyAction === 'Formatting for mobile reading' ? 'Formatting...' : 'Format' }}</button>
        <button :disabled="busy" @click="$emit('summarize')">Summarize</button>
        <button :disabled="busy || !hasContent" @click="$emit('playSpeech', 'content')">Listen</button>
        <button :disabled="busy || !article.summary" @click="$emit('playSpeech', 'summary')">Summary Audio</button>
      </div>

      <p v-if="settings.aiContentFormattingEnabled" class="format-hint">
        Mobile AI formatting is enabled. Fetch Full will queue formatting automatically when needed.
      </p>

      <section v-if="audioUrl" class="audio-card">
        <strong>{{ audioLabel }}</strong>
        <audio :src="audioUrl" controls autoplay />
      </section>

      <section class="content-card">
        <div class="card-title">
          <span>☷</span>
          <strong>Article Content</strong>
        </div>
        <div v-if="contentHtml" class="rich-text" v-html="contentHtml" />
        <div v-else class="reader-empty small">
          <h3>No full content yet</h3>
          <p>Use Fetch Full to extract the complete article. If AI formatting is enabled, it will also make the text easier to read on mobile.</p>
        </div>
      </section>
    </template>
  </article>
</template>
