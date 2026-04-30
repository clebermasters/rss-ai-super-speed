<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import type { Article } from '../types';

const props = defineProps<{ article: Article | null; mode: 'word-runner' | 'spritz'; open: boolean }>();
defineEmits<{ close: [] }>();

const index = ref(0);
const playing = ref(false);
const wpm = ref(360);
let timer: number | undefined;

const words = computed(() => tokenize(articleText(props.article)));
const currentWord = computed(() => words.value[index.value] || '');
const progress = computed(() => (words.value.length ? Math.round(((index.value + 1) / words.value.length) * 100) : 0));
const modeTitle = computed(() => (props.mode === 'spritz' ? 'Spritz Reader' : 'Word Runner'));
const highlightedWord = computed(() => splitAtRecognitionPoint(currentWord.value));

function toggle(): void {
  playing.value = !playing.value;
}

function rewind(): void {
  index.value = Math.max(0, index.value - 15);
}

function forward(): void {
  index.value = Math.min(words.value.length - 1, index.value + 15);
}

function close(): void {
  playing.value = false;
}

function tick(): void {
  if (!playing.value || !words.value.length) return;
  if (index.value >= words.value.length - 1) {
    playing.value = false;
    return;
  }
  index.value += 1;
}

function schedule(): void {
  window.clearTimeout(timer);
  if (!playing.value || !props.open) return;
  const word = currentWord.value;
  const punctuationPause = /[.!?]$/.test(word) ? 1.8 : /[,;:]$/.test(word) ? 1.35 : 1;
  const delay = Math.max(70, (60_000 / wpm.value) * punctuationPause);
  timer = window.setTimeout(() => {
    tick();
    schedule();
  }, delay);
}

watch([playing, wpm, currentWord, () => props.open], schedule);
watch(
  () => props.article?.articleId,
  () => {
    index.value = 0;
    playing.value = false;
  },
);
watch(
  () => props.open,
  (open) => {
    if (!open) close();
  },
);

onBeforeUnmount(() => window.clearTimeout(timer));

function articleText(article: Article | null): string {
  if (!article) return '';
  return [article.title, article.content, article.contentPreview, article.summary].filter(Boolean).join('\n\n');
}

function tokenize(text: string): string[] {
  return normalizeText(text)
    .split(/\s+/)
    .map((word) => word.trim())
    .filter(Boolean);
}

function normalizeText(text: string): string {
  const withoutHtml = text
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ');
  return withoutHtml
    .replace(/!\[[^\]]*]\([^)]+\)/g, ' ')
    .replace(/\[([^\]]+)]\([^)]+\)/g, '$1')
    .replace(/[`*_#>~-]+/g, ' ')
    .replace(/https?:\/\/\S+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function splitAtRecognitionPoint(word: string): { before: string; focus: string; after: string } {
  if (!word) return { before: '', focus: '', after: '' };
  const cleanLength = word.replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, '').length || word.length;
  const focusIndex = Math.min(word.length - 1, Math.max(0, Math.floor(cleanLength * 0.35)));
  return {
    before: word.slice(0, focusIndex),
    focus: word.slice(focusIndex, focusIndex + 1),
    after: word.slice(focusIndex + 1),
  };
}
</script>

<template>
  <div v-if="open" class="rsvp-backdrop" @click.self="$emit('close')">
    <section class="rsvp-panel">
      <header>
        <div>
          <p class="eyebrow">{{ modeTitle }}</p>
          <h2>{{ article?.title || 'Reader' }}</h2>
        </div>
        <button class="icon-button" @click="$emit('close')">×</button>
      </header>

      <div class="rsvp-stage" :class="mode">
        <div class="rsvp-guide" />
        <div class="rsvp-word">
          <span>{{ highlightedWord.before }}</span>
          <b>{{ highlightedWord.focus }}</b>
          <span>{{ highlightedWord.after }}</span>
        </div>
      </div>

      <div class="rsvp-progress">
        <span>{{ index + 1 }} / {{ words.length || 1 }}</span>
        <div><i :style="{ width: `${progress}%` }" /></div>
        <span>{{ progress }}%</span>
      </div>

      <div class="rsvp-controls">
        <button @click="rewind">−15</button>
        <button class="primary-button" @click="toggle">{{ playing ? 'Pause' : 'Play' }}</button>
        <button @click="forward">+15</button>
        <label>
          <span>{{ wpm }} WPM</span>
          <input v-model.number="wpm" type="range" min="180" max="720" step="20" />
        </label>
      </div>

      <p class="rsvp-hint">
        {{ mode === 'spritz' ? 'Spritz-style mode fixes your eye on the highlighted recognition letter.' : 'Word Runner streams one word at a time with punctuation-aware pauses.' }}
      </p>
    </section>
  </div>
</template>
