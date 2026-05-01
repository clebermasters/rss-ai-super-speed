<script setup lang="ts">
import { ref, watch } from 'vue';

const props = defineProps<{ open: boolean; subject: string; tags: string[] }>();
const emit = defineEmits<{ close: []; save: [tags: string[]] }>();

const tagText = ref('');

watch(
  () => [props.open, props.tags] as const,
  () => {
    if (props.open) tagText.value = props.tags.join(', ');
  },
  { immediate: true },
);

function save(): void {
  emit(
    'save',
    tagText.value
      .split(',')
      .map((tag) => tag.trim().replace(/^#/, '').toLowerCase().replace(/\s+/g, ' '))
      .filter(Boolean)
      .filter((tag, index, tags) => tags.indexOf(tag) === index),
  );
}
</script>

<template>
  <div v-if="open" class="modal-backdrop" @click.self="$emit('close')">
    <section class="settings-modal tag-editor-modal" role="dialog" aria-modal="true" aria-labelledby="tag-editor-title">
      <header>
        <div>
          <p class="eyebrow">Tags</p>
          <h2 id="tag-editor-title">Organize {{ subject }}</h2>
        </div>
        <button class="icon-button" @click="$emit('close')">×</button>
      </header>
      <label class="tag-editor-field">
        <span>Comma-separated tags</span>
        <input v-model="tagText" placeholder="ai, research, papers" />
      </label>
      <p class="security-note">Tags are normalized to lowercase and used by Android Flow, Web Flow, and article filters.</p>
      <footer>
        <button class="ghost-button" @click="$emit('close')">Cancel</button>
        <button class="primary-button" @click="save">Save tags</button>
      </footer>
    </section>
  </div>
</template>
