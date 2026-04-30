<script setup lang="ts">
import type { Article } from '../types';
import { isFreshlyPublished, publishedAgo } from '../freshness';
import { plainDate } from '../render';

defineProps<{
  articles: Article[];
  brandNewArticleIds: Set<string>;
  collapsed: boolean;
  selectedArticleId: string;
  loading: boolean;
  filter: 'all' | 'unread' | 'saved';
}>();
defineEmits<{
  select: [article: Article];
  toggle: [];
  'update:filter': [filter: 'all' | 'unread' | 'saved'];
}>();
</script>

<template>
  <section class="article-list float-in delay-2" :class="{ collapsed }">
    <button v-if="collapsed" class="article-expand-button" @click="$emit('toggle')">
      <span>Stories</span>
      <strong>{{ articles.length }}</strong>
    </button>
    <div class="section-title">
      <div>
        <p class="eyebrow">Now reading</p>
        <h2>{{ articles.length }} stories</h2>
      </div>
      <button class="collapse-button" @click="$emit('toggle')">→</button>
      <div class="segmented">
        <button :class="{ active: filter === 'all' }" @click="$emit('update:filter', 'all')">All</button>
        <button :class="{ active: filter === 'unread' }" @click="$emit('update:filter', 'unread')">Unread</button>
        <button :class="{ active: filter === 'saved' }" @click="$emit('update:filter', 'saved')">Saved</button>
      </div>
    </div>
    <div v-if="loading" class="skeleton-stack">
      <div v-for="item in 6" :key="item" class="skeleton-card" />
    </div>
    <div v-else class="article-scroll">
      <button v-for="article in articles" :key="article.articleId" class="article-card" :class="{ active: selectedArticleId === article.articleId, unread: !article.isRead, 'brand-new': brandNewArticleIds.has(article.articleId), fresh: isFreshlyPublished(article.publishedAt) }" @click="$emit('select', article)">
        <span class="dot" />
        <span class="article-main">
          <span class="article-badges">
            <b v-if="brandNewArticleIds.has(article.articleId)" class="new-badge">New</b>
            <b v-else-if="isFreshlyPublished(article.publishedAt)" class="fresh-badge">Fresh</b>
            <b class="published-badge">{{ publishedAgo(article.publishedAt) }}</b>
          </span>
          <strong>{{ article.title }}</strong>
          <small>{{ article.source }} · Published {{ plainDate(article.publishedAt) }}</small>
        </span>
        <span class="article-meta">
          <b v-if="article.score">{{ article.score }}</b>
          <span v-if="article.isSaved">★</span>
        </span>
      </button>
    </div>
    <div v-if="!loading && articles.length === 0" class="empty-card">
      <strong>No articles here yet.</strong>
      <span>Try another feed, clear search, or refresh.</span>
    </div>
  </section>
</template>
