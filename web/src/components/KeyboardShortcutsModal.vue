<script setup lang="ts">
defineProps<{ open: boolean }>();
defineEmits<{ close: [] }>();

const shortcuts = [
  { keys: ['J', '↓'], action: 'Next article' },
  { keys: ['K', '↑'], action: 'Previous article' },
  { keys: ['M'], action: 'Mark active article read' },
  { keys: ['F'], action: 'Full screen or focus reader' },
  { keys: ['V'], action: 'Toggle flow' },
  { keys: ['[', 'Esc', 'Alt', '←'], action: 'Back' },
];
</script>

<template>
  <div v-if="open" class="modal-backdrop" @click.self="$emit('close')">
    <section class="settings-modal shortcuts-modal" role="dialog" aria-modal="true" aria-labelledby="keyboard-shortcuts-title">
      <header>
        <div>
          <p class="eyebrow">Keyboard</p>
          <h2 id="keyboard-shortcuts-title">Shortcuts</h2>
        </div>
        <button class="icon-button" aria-label="Close shortcuts" @click="$emit('close')">×</button>
      </header>

      <div class="shortcut-list">
        <div v-for="shortcut in shortcuts" :key="shortcut.action" class="shortcut-row">
          <span>{{ shortcut.action }}</span>
          <span class="shortcut-keys">
            <kbd v-for="key in shortcut.keys" :key="`${shortcut.action}-${key}`">{{ key }}</kbd>
          </span>
        </div>
      </div>
    </section>
  </div>
</template>
