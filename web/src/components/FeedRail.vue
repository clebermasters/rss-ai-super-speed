<script setup lang="ts">
import { computed, ref } from 'vue';
import type { Feed } from '../types';

const props = defineProps<{ brandNewCount: number; collapsed: boolean; feeds: Feed[]; selectedFeedId: string; totalUnread: number }>();
const emit = defineEmits<{ editTags: [feed: Feed]; select: [feedId: string]; toggle: [] }>();

const feedQuery = ref('');
const normalizedFeedQuery = computed(() => normalizeSearch(feedQuery.value));
const filteringFeeds = computed(() => Boolean(normalizedFeedQuery.value));
const visibleFeeds = computed(() => {
  const query = normalizedFeedQuery.value;
  if (!query) return props.feeds;
  return props.feeds.filter((feed) => searchableFeedText(feed).includes(query));
});

function normalizeSearch(value: string): string {
  return value.trim().toLowerCase().replace(/^#/, '').replace(/\s+/g, ' ');
}

function feedInitials(feed: Feed): string {
  return feed.name
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part[0])
    .join('')
    .slice(0, 4)
    .toLowerCase();
}

function searchableFeedText(feed: Feed): string {
  return normalizeSearch(`${feed.name} ${feed.url} ${(feed.tags || []).join(' ')} ${feedInitials(feed)} ${feed.name.slice(0, 2)}`);
}

function selectOnlyMatch(): void {
  if (visibleFeeds.value.length === 1) {
    emit('select', visibleFeeds.value[0].feedId);
  }
}
</script>

<template>
  <aside class="feed-rail float-in delay-1" :class="{ collapsed }">
    <div class="rail-tools">
      <span v-if="!collapsed" class="rail-brand">
        <strong>Krebs RSS</strong>
        <small>Personal intelligence stream</small>
      </span>
      <button class="collapse-button" @click="$emit('toggle')">{{ collapsed ? '→' : '←' }}</button>
    </div>
    <label v-if="!collapsed" class="feed-filter" aria-label="Filter subscriptions">
      <span>⌕</span>
      <input v-model="feedQuery" placeholder="Filter sources..." type="search" @keydown.enter="selectOnlyMatch" />
      <small v-if="filteringFeeds">{{ visibleFeeds.length }}</small>
      <button v-if="filteringFeeds" type="button" aria-label="Clear source filter" @click="feedQuery = ''">×</button>
    </label>
    <button v-if="!filteringFeeds" class="feed-card all" :class="{ active: selectedFeedId === '' }" :title="`All Articles · ${totalUnread} unread`" @click="$emit('select', '')">
      <span class="feed-glyph">✦</span>
      <span class="feed-text">
        <strong>All Articles</strong>
        <small>{{ totalUnread }} unread<span v-if="brandNewCount > 0"> · {{ brandNewCount }} new</span></small>
      </span>
    </button>
    <div
      v-for="feed in visibleFeeds"
      :key="feed.feedId"
      class="feed-card"
      :class="{ active: selectedFeedId === feed.feedId }"
      :title="`${feed.name} · ${feed.unreadCount} unread`"
      role="button"
      tabindex="0"
      @click="$emit('select', feed.feedId)"
      @keydown.enter="$emit('select', feed.feedId)"
    >
      <span class="feed-glyph">{{ feed.name.slice(0, 2).toUpperCase() }}</span>
      <span class="feed-text">
        <strong>{{ feed.name }}</strong>
        <small>{{ feed.unreadCount }} unread · {{ feed.articleCount }} total</small>
        <span v-if="feed.tags?.length" class="inline-tags">
          <b v-for="tag in feed.tags.slice(0, 3)" :key="tag">#{{ tag }}</b>
        </span>
      </span>
      <button v-if="!collapsed" class="mini-tag-button" @click.stop="$emit('editTags', feed)">Tags</button>
    </div>
    <p v-if="!collapsed && filteringFeeds && !visibleFeeds.length" class="feed-filter-empty">No matching sources.</p>
  </aside>
</template>
