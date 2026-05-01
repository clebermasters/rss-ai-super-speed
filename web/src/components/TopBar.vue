<script setup lang="ts">
defineProps<{ query: string; loading: boolean; configured: boolean; flowActive: boolean; newCount: number }>();
defineEmits<{
  'update:query': [value: string];
  flow: [];
  refresh: [];
  settings: [];
}>();
</script>

<template>
  <header class="topbar float-in">
    <div>
      <p class="eyebrow">Precision reader</p>
      <h1>Inbox</h1>
    </div>
    <div class="topbar-actions">
      <label class="searchbox" aria-label="Search articles">
        <span>⌕</span>
        <input id="article-search" name="article-search" :value="query" placeholder="Search articles, summaries, sources" @input="$emit('update:query', ($event.target as HTMLInputElement).value)" />
      </label>
      <button class="mode-button" :class="{ active: flowActive }" :disabled="!configured" @click="$emit('flow')">
        {{ flowActive ? 'Board' : 'Flow' }}
      </button>
      <span v-if="newCount > 0" class="new-count-pill">{{ newCount }} new</span>
      <button class="icon-button" :disabled="loading || !configured" @click="$emit('refresh')" title="Refresh feeds">
        <span :class="{ spin: loading }">↻</span>
      </button>
      <button class="icon-button" @click="$emit('settings')" title="Settings">⚙</button>
    </div>
  </header>
</template>
