<script setup lang="ts">
defineProps<{ query: string; loading: boolean; configured: boolean }>();
defineEmits<{
  'update:query': [value: string];
  refresh: [];
  settings: [];
}>();
</script>

<template>
  <header class="topbar float-in">
    <div>
      <p class="eyebrow">Personal intelligence stream</p>
      <h1>RSS AI Reader</h1>
    </div>
    <div class="topbar-actions">
      <label class="searchbox" aria-label="Search articles">
        <span>⌕</span>
        <input :value="query" placeholder="Search articles, summaries, sources" @input="$emit('update:query', ($event.target as HTMLInputElement).value)" />
      </label>
      <button class="icon-button" :disabled="loading || !configured" @click="$emit('refresh')" title="Refresh feeds">
        <span :class="{ spin: loading }">↻</span>
      </button>
      <button class="icon-button" @click="$emit('settings')" title="Settings">⚙</button>
    </div>
  </header>
</template>
