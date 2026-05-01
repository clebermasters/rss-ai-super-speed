<script setup lang="ts">
import { computed, ref, watch } from 'vue';

const props = defineProps<{
  tags: Array<{ tag: string; feedCount: number; articleCount: number; unreadCount: number }>;
  selectedTags: string[];
}>();
const emit = defineEmits<{ select: [tags: string[]] }>();

const tagQuery = ref('');
const expanded = ref(false);

const selectedSet = computed(() => new Set(props.selectedTags));
const selected = computed(() => props.tags.filter((tag) => selectedSet.value.has(tag.tag)));
const matchingTags = computed(() => {
  const query = cleanTag(tagQuery.value);
  return props.tags
    .filter((tag) => !query || tag.tag.includes(query))
    .slice(0, 8);
});

function cleanTag(value: string): string {
  return value.trim().replace(/^#/, '').toLowerCase();
}

function chooseTag(tag: string): void {
  if (!tag) {
    emit('select', []);
  } else {
    const next = new Set(props.selectedTags);
    if (next.has(tag)) {
      next.delete(tag);
    } else {
      next.add(tag);
    }
    emit('select', [...next]);
  }
  tagQuery.value = '';
  expanded.value = false;
}

function submitTag(): void {
  const query = cleanTag(tagQuery.value);
  if (!query) {
    chooseTag('');
    return;
  }
  const exact = props.tags.find((tag) => tag.tag === query);
  const first = matchingTags.value[0];
  chooseTag(exact?.tag || first?.tag || '');
}

watch(() => props.selectedTags, () => {
  tagQuery.value = '';
});
</script>

<template>
  <section v-if="tags.length" class="tag-filter-bar compact-tag-filter float-in">
    <span class="tag-filter-label">Filter tags</span>
    <div class="tag-combobox">
      <button class="tag-filter-all" :class="{ active: !selectedTags.length }" @click="chooseTag('')">All tags</button>
      <span v-for="tag in selected" :key="tag.tag" class="selected-filter-chip" :title="`${tag.articleCount} articles · ${tag.feedCount} feeds`">
        #{{ tag.tag }}
        <small v-if="tag.unreadCount">{{ tag.unreadCount }}</small>
        <button :aria-label="`Remove ${tag.tag} tag filter`" @click="chooseTag(tag.tag)">×</button>
      </span>
      <label class="tag-typeahead" aria-label="Type to filter tags">
        <span>⌕</span>
        <input
          v-model="tagQuery"
          :placeholder="selectedTags.length ? 'Add another tag...' : 'Type tags to filter...'"
          @blur="expanded = false"
          @focus="expanded = true"
          @keydown.enter.prevent="submitTag"
          @keydown.escape="expanded = false"
        />
      </label>
      <div v-if="expanded && matchingTags.length" class="tag-suggestions">
        <button
          v-for="tag in matchingTags"
          :key="tag.tag"
          type="button"
          :class="{ active: selectedSet.has(tag.tag) }"
          @mousedown.prevent="chooseTag(tag.tag)"
        >
          <span>#{{ tag.tag }}</span>
          <small>{{ tag.unreadCount || tag.articleCount }}</small>
        </button>
      </div>
    </div>
  </section>
</template>
