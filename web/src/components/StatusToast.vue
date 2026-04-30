<script setup lang="ts">
defineProps<{ notice: { kind: 'info' | 'success' | 'error'; message: string } | null }>();
defineEmits<{ dismiss: [] }>();
</script>

<template>
  <aside v-if="notice" class="status-toast" :class="notice.kind">
    <span>{{ notice.kind === 'error' ? '!' : notice.kind === 'success' ? '✓' : 'i' }}</span>
    <p>{{ notice.message }}</p>
    <button @click="$emit('dismiss')">×</button>
  </aside>
</template>
