<script setup lang="ts">
import { computed } from 'vue';
import { plainDate } from '../render';
import type { ArticleHighlight } from '../types';

const props = defineProps<{ highlights: ArticleHighlight[] }>();
defineEmits<{
  delete: [highlight: ArticleHighlight];
  open: [articleId: string];
}>();

const groupedHighlights = computed(() => {
  const groups = new Map<string, { articleId: string; title: string; source: string; link: string; items: ArticleHighlight[] }>();
  for (const highlight of props.highlights) {
    const current = groups.get(highlight.articleId) || {
      articleId: highlight.articleId,
      title: highlight.articleTitle || 'Untitled article',
      source: highlight.articleSource || 'Unknown source',
      link: highlight.articleLink || '',
      items: [],
    };
    current.items.push(highlight);
    groups.set(highlight.articleId, current);
  }
  return [...groups.values()].map((group) => ({
    ...group,
    items: group.items.sort((left, right) => Number(right.createdAt || 0) - Number(left.createdAt || 0)),
  }));
});
</script>

<template>
  <section class="highlights-page float-in">
    <header class="highlights-hero">
      <div>
        <p class="eyebrow">Review shelf</p>
        <h2>Highlights</h2>
      </div>
      <strong>{{ highlights.length }} saved text{{ highlights.length === 1 ? '' : 's' }}</strong>
    </header>

    <div v-if="!highlights.length" class="empty-card highlight-empty">
      <h3>No highlights yet</h3>
      <p>Select text inside an article and use Save highlight. It will be available here and on Android.</p>
    </div>

    <article v-for="group in groupedHighlights" :key="group.articleId" class="highlight-group">
      <header>
        <div>
          <small>{{ group.source }}</small>
          <h3>{{ group.title }}</h3>
        </div>
        <button class="ghost-button" @click="$emit('open', group.articleId)">Open article</button>
      </header>
      <blockquote v-for="highlight in group.items" :key="highlight.highlightId" class="highlight-review-item">
        <p>{{ highlight.text }}</p>
        <footer>
          <span>{{ plainDate(new Date(Number(highlight.createdAt || 0)).toISOString()) }}</span>
          <button @click="$emit('delete', highlight)">Remove</button>
        </footer>
      </blockquote>
    </article>
  </section>
</template>
