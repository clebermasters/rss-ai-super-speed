<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { isFreshlyPublished, publishedAgo } from '../freshness';
import { plainDate, richTextToHtml } from '../render';
import type { Article } from '../types';

const props = defineProps<{ articles: Article[]; brandNewArticleIds: Set<string>; loading: boolean; selectedTag: string }>();
defineEmits<{
  focus: [article: Article];
  listen: [article: Article, target: 'content' | 'summary'];
  open: [article: Article];
  rsvp: [article: Article, mode: 'word-runner' | 'spritz'];
}>();

const visibleCount = ref(12);
const sentinel = ref<HTMLElement | null>(null);
let observer: IntersectionObserver | null = null;

const visibleArticles = computed(() => props.articles.slice(0, visibleCount.value));
const hasMore = computed(() => visibleCount.value < props.articles.length);

function showMore(): void {
  visibleCount.value = Math.min(visibleCount.value + 10, props.articles.length);
}

function articleBody(article: Article): string {
  const html = richTextToHtml(article.content || article.contentPreview || article.summary || 'No preview is available yet. Open the article to fetch full content.');
  return removeDuplicateOpeningHeading(html, article.title);
}

function removeDuplicateOpeningHeading(html: string, title: string): string {
  const titleKey = normalizeHeadingText(title);
  if (!titleKey) return html;
  return html.replace(/^\s*<h[1-3][^>]*>([\s\S]*?)<\/h[1-3]>\s*/i, (match, heading: string) => {
    const headingKey = normalizeHeadingText(heading.replace(/<[^>]+>/g, ''));
    if (!headingKey) return match;
    return titleKey === headingKey || titleKey.includes(headingKey) || headingKey.includes(titleKey) ? '' : match;
  });
}

function normalizeHeadingText(value: string): string {
  return value
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

watch(
  () => props.articles,
  () => {
    visibleCount.value = 12;
  },
);

onMounted(() => {
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) showMore();
    },
    { rootMargin: '700px 0px' },
  );
  if (sentinel.value) observer.observe(sentinel.value);
});

onBeforeUnmount(() => observer?.disconnect());
</script>

<template>
  <section class="flow-page float-in">
    <header class="flow-hero">
      <div>
        <p class="eyebrow">{{ selectedTag ? `#${selectedTag} Flow` : 'All Articles Flow' }}</p>
        <h2>Scroll the river</h2>
        <p>Articles render in generous reading cards as you move down the page. Tag filters apply before the river starts.</p>
      </div>
      <strong>{{ articles.length }} loaded</strong>
    </header>

    <div v-if="loading" class="flow-loading">
      <div v-for="item in 5" :key="item" class="skeleton-card" />
    </div>

    <article v-for="(article, index) in visibleArticles" v-else :key="article.articleId" class="flow-article" :class="{ unread: !article.isRead, 'brand-new': brandNewArticleIds.has(article.articleId), fresh: isFreshlyPublished(article.publishedAt) }">
      <div class="flow-index">{{ index + 1 }}</div>
      <div class="flow-content">
        <p class="eyebrow">{{ article.source }}</p>
        <h3>{{ article.title }}</h3>
        <div class="article-facts">
          <span v-if="brandNewArticleIds.has(article.articleId)" class="new-badge">New since last load</span>
          <span v-else-if="isFreshlyPublished(article.publishedAt)" class="fresh-badge">Published recently</span>
          <span>{{ publishedAgo(article.publishedAt) }}</span>
          <span>Published {{ plainDate(article.publishedAt) }}</span>
          <span v-if="article.score">{{ article.score }} pts</span>
          <span v-if="article.isSaved">Saved</span>
          <span v-if="article.contentAiFormatted">AI formatted</span>
        </div>
        <div v-if="article.tags?.length" class="reader-tags compact-tags">
          <span v-for="tag in article.tags.slice(0, 6)" :key="tag" class="tag-chip passive">#{{ tag }}</span>
        </div>
        <div class="rich-text compact flow-copy" v-html="articleBody(article)" />
        <footer>
          <a class="flow-action neutral" :href="article.link" target="_blank" rel="noreferrer">Source ↗</a>
          <button class="flow-action read" @click="$emit('rsvp', article, 'word-runner')">Word Runner</button>
          <button class="flow-action audio" @click="$emit('listen', article, 'content')">Listen</button>
          <button class="flow-action audio secondary" :disabled="!article.summary" @click="$emit('listen', article, 'summary')">Summary</button>
          <button class="flow-action neutral" @click="$emit('open', article)">Side reader</button>
          <button class="flow-action focus" @click="$emit('focus', article)">Focus</button>
        </footer>
      </div>
    </article>

    <button v-if="hasMore && !loading" class="load-more-button" @click="showMore">Load more stories</button>
    <div ref="sentinel" class="flow-sentinel" aria-hidden="true" />
  </section>
</template>
