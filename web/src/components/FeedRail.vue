<script setup lang="ts">
import type { Feed } from '../types';

defineProps<{ brandNewCount: number; collapsed: boolean; feeds: Feed[]; selectedFeedId: string; totalUnread: number }>();
defineEmits<{ editTags: [feed: Feed]; select: [feedId: string]; toggle: [] }>();
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
    <button class="feed-card all" :class="{ active: selectedFeedId === '' }" :title="`All Articles · ${totalUnread} unread`" @click="$emit('select', '')">
      <span class="feed-glyph">✦</span>
      <span class="feed-text">
        <strong>All Articles</strong>
        <small>{{ totalUnread }} unread<span v-if="brandNewCount > 0"> · {{ brandNewCount }} new</span></small>
      </span>
    </button>
    <div
      v-for="feed in feeds"
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
  </aside>
</template>
