<script setup lang="ts">
import type { Feed } from '../types';

defineProps<{ feeds: Feed[]; selectedFeedId: string; totalUnread: number }>();
defineEmits<{ select: [feedId: string] }>();
</script>

<template>
  <aside class="feed-rail float-in delay-1">
    <button class="feed-card all" :class="{ active: selectedFeedId === '' }" @click="$emit('select', '')">
      <span class="feed-glyph">✦</span>
      <span>
        <strong>All Articles</strong>
        <small>{{ totalUnread }} unread</small>
      </span>
    </button>
    <button v-for="feed in feeds" :key="feed.feedId" class="feed-card" :class="{ active: selectedFeedId === feed.feedId }" @click="$emit('select', feed.feedId)">
      <span class="feed-glyph">{{ feed.name.slice(0, 2).toUpperCase() }}</span>
      <span>
        <strong>{{ feed.name }}</strong>
        <small>{{ feed.unreadCount }} unread · {{ feed.articleCount }} total</small>
      </span>
    </button>
  </aside>
</template>
