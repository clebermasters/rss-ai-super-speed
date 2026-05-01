<script setup lang="ts">
defineProps<{
  tags: Array<{ tag: string; feedCount: number; articleCount: number; unreadCount: number }>;
  selectedTag: string;
}>();
defineEmits<{ select: [tag: string] }>();
</script>

<template>
  <section v-if="tags.length" class="tag-filter-bar float-in">
    <span class="tag-filter-label">Filter tags</span>
    <button class="tag-chip" :class="{ active: !selectedTag }" @click="$emit('select', '')">All tags</button>
    <button
      v-for="tag in tags"
      :key="tag.tag"
      class="tag-chip"
      :class="{ active: selectedTag === tag.tag }"
      :title="`${tag.articleCount} articles · ${tag.feedCount} feeds`"
      @click="$emit('select', selectedTag === tag.tag ? '' : tag.tag)"
    >
      #{{ tag.tag }}
      <small v-if="tag.unreadCount">{{ tag.unreadCount }}</small>
    </button>
  </section>
</template>
