<script setup lang="ts">
import type { Article } from '../types';
import { plainDate } from '../render';

defineProps<{ articles: Article[]; selectedArticleId: string; loading: boolean; filter: 'all' | 'unread' | 'saved' }>();
defineEmits<{
  select: [article: Article];
  'update:filter': [filter: 'all' | 'unread' | 'saved'];
}>();
</script>

<template>
  <section class="article-list float-in delay-2">
    <div class="section-title">
      <div>
        <p class="eyebrow">Now reading</p>
        <h2>{{ articles.length }} stories</h2>
      </div>
      <div class="segmented">
        <button :class="{ active: filter === 'all' }" @click="$emit('update:filter', 'all')">All</button>
        <button :class="{ active: filter === 'unread' }" @click="$emit('update:filter', 'unread')">Unread</button>
        <button :class="{ active: filter === 'saved' }" @click="$emit('update:filter', 'saved')">Saved</button>
      </div>
    </div>
    <div v-if="loading" class="skeleton-stack">
      <div v-for="item in 6" :key="item" class="skeleton-card" />
    </div>
    <button v-for="article in articles" v-else :key="article.articleId" class="article-card" :class="{ active: selectedArticleId === article.articleId, unread: !article.isRead }" @click="$emit('select', article)">
      <span class="dot" />
      <span class="article-main">
        <strong>{{ article.title }}</strong>
        <small>{{ article.source }} · {{ plainDate(article.publishedAt) }}</small>
      </span>
      <span class="article-meta">
        <b v-if="article.score">{{ article.score }}</b>
        <span v-if="article.isSaved">★</span>
      </span>
    </button>
    <div v-if="!loading && articles.length === 0" class="empty-card">
      <strong>No articles here yet.</strong>
      <span>Try another feed, clear search, or refresh.</span>
    </div>
  </section>
</template>
