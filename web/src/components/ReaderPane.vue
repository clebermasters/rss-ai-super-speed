<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { isFreshlyPublished, publishedAgo } from '../freshness';
import { plainDate, richTextToHtml } from '../render';
import type { Article, Settings, SpeechTarget } from '../types';

const props = defineProps<{
  article: Article | null;
  audioLabel: string;
  audioUrl: string;
  busyAction: string;
  feedName: string;
  fullscreen: boolean;
  isBrandNew: boolean;
  settings: Settings;
  speechCacheStatus: string;
  speechInputChars: number;
  speechSegmentCount: number;
  speechSegmentIndex: number;
  speechSegmentPercent: number;
  speechSourceChars: number;
  speechTarget: SpeechTarget | null;
}>();

defineEmits<{
  audioEnded: [];
  editTags: [];
  fetchContent: [];
  formatContent: [];
  playSpeech: [target: SpeechTarget];
  regenerateSpeech: [target: SpeechTarget];
  rsvp: [mode: 'word-runner' | 'spritz'];
  setSpeechSegmentIndex: [index: number];
  setSpeechSegmentPercent: [percent: number];
  stopSpeech: [];
  summarize: [];
  toggleFullscreen: [];
  toggleSave: [];
}>();

const audioElement = ref<HTMLAudioElement | null>(null);
const audioPlaybackError = ref('');
const summaryExpanded = ref(true);
const audioReaderExpanded = ref(false);
const busy = computed(() => Boolean(props.busyAction));
const summaryHtml = computed(() => richTextToHtml(props.article?.summary || ''));
const contentHtml = computed(() => richTextToHtml(props.article?.content || props.article?.contentPreview || ''));
const hasContent = computed(() => Boolean(props.article?.content || props.article?.contentPreview));
const canReadText = computed(() => Boolean(props.article?.content || props.article?.contentPreview || props.article?.summary || props.article?.title));
const canListenContent = computed(() => Boolean(props.article?.content || props.article?.contentPreview || props.article?.summary));
const audioSegmentText = computed(() => (props.speechTarget === 'summary' ? '1 / 1' : `${props.speechSegmentIndex + 1} / ${props.speechSegmentCount}`));
const sourceHost = computed(() => {
  if (!props.article?.link) return '';
  try {
    return new URL(props.article.link).hostname.replace(/^www\./, '');
  } catch {
    return props.article.link;
  }
});

watch(
  () => props.audioUrl,
  async (url) => {
    audioPlaybackError.value = '';
    if (!url) return;
    await nextTick();
    try {
      await audioElement.value?.play();
    } catch {
      audioPlaybackError.value = 'Browser autoplay was blocked. Press play in the audio controls.';
    }
  },
);

watch(
  () => props.article?.articleId,
  () => {
    summaryExpanded.value = true;
    audioReaderExpanded.value = false;
  },
);
</script>

<template>
  <article class="reader-pane float-in delay-3" :class="{ fullscreen }">
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
        <div class="reader-header-actions">
          <button class="save-button" @click="$emit('toggleFullscreen')">
            {{ fullscreen ? 'Exit Focus' : 'Full Screen' }}
          </button>
          <button class="save-button" :class="{ saved: article.isSaved }" @click="$emit('toggleSave')">
            {{ article.isSaved ? 'Saved ★' : 'Save ☆' }}
          </button>
        </div>
      </header>

      <div class="article-facts">
        <span v-if="isBrandNew" class="new-badge">New since last load</span>
        <span v-else-if="isFreshlyPublished(article.publishedAt)" class="fresh-badge">Published recently</span>
        <span>{{ article.source }}</span>
        <span>{{ publishedAgo(article.publishedAt) }}</span>
        <span>Published {{ plainDate(article.publishedAt) }}</span>
        <span v-if="article.score">{{ article.score }} pts</span>
        <span v-if="article.contentAiFormatted">AI formatted</span>
      </div>

      <div class="reader-tags">
        <button v-for="tag in article.tags || []" :key="tag" class="tag-chip passive">#{{ tag }}</button>
        <button class="mini-tag-button" @click="$emit('editTags')">{{ article.tags?.length ? 'Edit tags' : 'Add tags' }}</button>
      </div>

      <a class="source-link" :href="article.link" target="_blank" rel="noreferrer">
        <span>↗</span>
        {{ sourceHost }}
      </a>

      <div class="reader-primary-actions">
        <button :disabled="!canReadText" @click="$emit('rsvp', 'word-runner')">
          <span>Word Runner</span>
        </button>
        <button :disabled="busy || !canListenContent" @click="$emit('playSpeech', 'content')">
          <span>{{ busyAction === 'Creating article audio' ? 'Creating...' : 'Listen Article' }}</span>
        </button>
        <button :disabled="busy || !article.summary" @click="$emit('playSpeech', 'summary')">
          <span>{{ busyAction === 'Creating summary audio' ? 'Creating...' : 'Listen Summary' }}</span>
        </button>
      </div>

      <section class="speech-card collapsible-card" :class="{ collapsed: !audioReaderExpanded }">
        <button class="collapsible-card-header" @click="audioReaderExpanded = !audioReaderExpanded">
          <span>
            <strong>Audio reader</strong>
            <small>Segments, cache, and regeneration controls.</small>
          </span>
          <b>{{ audioReaderExpanded ? 'Hide' : 'Show' }}</b>
        </button>
        <div v-if="audioReaderExpanded" class="collapsible-card-body">
          <div class="speech-segments">
            <button :class="{ active: speechSegmentPercent === 20 }" @click="$emit('setSpeechSegmentPercent', 20)">20%</button>
            <button :class="{ active: speechSegmentPercent === 30 }" @click="$emit('setSpeechSegmentPercent', 30)">30%</button>
            <button :class="{ active: speechSegmentPercent === 100 }" @click="$emit('setSpeechSegmentPercent', 100)">All</button>
          </div>
          <div class="speech-row">
            <span>{{ speechSegmentPercent === 100 ? 'Article: all content' : `Article part ${speechSegmentIndex + 1} of ${speechSegmentCount}` }}</span>
            <button :disabled="speechSegmentIndex <= 0 || busy" @click="$emit('setSpeechSegmentIndex', speechSegmentIndex - 1)">Prev</button>
            <button :disabled="speechSegmentIndex >= speechSegmentCount - 1 || busy" @click="$emit('setSpeechSegmentIndex', speechSegmentIndex + 1)">Next</button>
          </div>
          <div class="speech-row">
            <button :disabled="busy || !canListenContent" @click="$emit('playSpeech', 'content')">{{ busyAction === 'Creating article audio' ? 'Creating article...' : 'Read Article Part' }}</button>
            <button :disabled="busy || !article.summary" @click="$emit('playSpeech', 'summary')">{{ busyAction === 'Creating summary audio' ? 'Creating summary...' : 'Read Summary' }}</button>
            <button :disabled="busy || !canListenContent" @click="$emit('regenerateSpeech', 'content')">Regenerate Part</button>
          </div>
          <p v-if="speechCacheStatus || speechInputChars" class="speech-meta">
            {{ speechCacheStatus ? `${speechCacheStatus} cache` : 'cache status pending' }}
            <span v-if="speechInputChars"> · {{ speechInputChars }} chars sent</span>
            <span v-if="speechSourceChars"> · {{ speechSourceChars }} readable chars total</span>
          </p>
        </div>
      </section>

      <section v-if="audioUrl" class="audio-card">
        <div>
          <strong>{{ audioLabel }}</strong>
          <small v-if="speechTarget">Target: {{ speechTarget }} · segment {{ audioSegmentText }}</small>
        </div>
        <audio ref="audioElement" :src="audioUrl" controls autoplay @ended="$emit('audioEnded')" />
        <div class="speech-row">
          <button @click="audioElement?.play()">Play</button>
          <button @click="audioElement?.pause()">Pause</button>
          <button @click="audioElement && (audioElement.currentTime = 0)">Restart</button>
          <button @click="$emit('stopSpeech')">Stop</button>
        </div>
        <p v-if="audioPlaybackError" class="speech-error">{{ audioPlaybackError }}</p>
      </section>

      <section v-if="summaryHtml" class="summary-card collapsible-card" :class="{ collapsed: !summaryExpanded }">
        <button class="collapsible-card-header summary-toggle" @click="summaryExpanded = !summaryExpanded">
          <span class="card-title">
            <span>✦</span>
            <strong>AI Summary</strong>
          </span>
          <b>{{ summaryExpanded ? 'Hide' : 'Show' }}</b>
        </button>
        <div v-if="summaryExpanded" class="collapsible-card-body">
          <div class="rich-text compact" v-html="summaryHtml" />
        </div>
      </section>

      <div class="reader-toolbar">
        <button :disabled="busy" @click="$emit('fetchContent')">{{ busyAction === 'Fetching full article' ? 'Fetching...' : 'Fetch Full' }}</button>
        <button :disabled="busy || !hasContent" @click="$emit('formatContent')">{{ busyAction === 'Formatting for mobile reading' ? 'Formatting...' : 'Format' }}</button>
        <button :disabled="busy" @click="$emit('summarize')">Summarize</button>
      </div>

      <p v-if="settings.aiContentFormattingEnabled" class="format-hint">
        AI formatting enabled.
      </p>

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
