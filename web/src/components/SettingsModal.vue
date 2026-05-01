<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { defaultSettings } from '../composables/useRssReader';
import type { RuntimeConfig, Settings } from '../types';

const props = defineProps<{
  availableTags: string[];
  config: RuntimeConfig;
  open: boolean;
  settings: Settings;
}>();

const emit = defineEmits<{
  close: [];
  save: [config: RuntimeConfig, settings: Settings];
}>();

const localConfig = reactive<RuntimeConfig>({ apiBaseUrl: '', apiToken: '' });
const localSettings = reactive<Settings>(defaultSettings());
const prefetchTagsText = computed({
  get: () => normalizeTags(localSettings.scheduledAiPrefetchTags).join(', '),
  set: (value: string) => {
    localSettings.scheduledAiPrefetchTags = normalizeTags(value);
  },
});

function normalizeTags(value: string | string[]): string[] {
  const raw = Array.isArray(value) ? value : value.split(',');
  const seen = new Set<string>();
  const tags: string[] = [];
  for (const tag of raw) {
    const clean = String(tag).trim().replace(/^#/, '').toLowerCase().replace(/\s+/g, ' ');
    if (!clean || seen.has(clean)) continue;
    seen.add(clean);
    tags.push(clean);
  }
  return tags;
}

function togglePrefetchTag(tag: string): void {
  const clean = normalizeTags([tag])[0];
  if (!clean) return;
  const selected = new Set(normalizeTags(localSettings.scheduledAiPrefetchTags));
  if (selected.has(clean)) {
    selected.delete(clean);
  } else {
    selected.add(clean);
  }
  localSettings.scheduledAiPrefetchTags = [...selected].sort();
}

function resetForm(): void {
  Object.assign(localConfig, { apiBaseUrl: '', apiToken: '' }, props.config);
  Object.assign(localSettings, defaultSettings(), props.settings);
}

function save(): void {
  emit('save', { ...localConfig }, { ...localSettings });
}

watch(() => props.open, resetForm, { immediate: true });
watch(() => props.config, resetForm, { deep: true });
watch(() => props.settings, resetForm, { deep: true });
</script>

<template>
  <div v-if="open" class="modal-backdrop" @click.self="$emit('close')">
    <section class="settings-modal float-in">
      <header>
        <div>
          <p class="eyebrow">Local web configuration</p>
          <h2>Reader Settings</h2>
        </div>
        <button class="icon-button" @click="$emit('close')">×</button>
      </header>

      <div class="form-grid">
        <label>
          <span>Backend API URL</span>
          <input v-model.trim="localConfig.apiBaseUrl" placeholder="https://api.example.com" />
        </label>
        <label>
          <span>API Token</span>
          <input v-model.trim="localConfig.apiToken" type="password" placeholder="Stored only in this browser" />
        </label>
        <label>
          <span>LLM Provider</span>
          <select v-model="localSettings.llmProvider">
            <option value="openai_compatible">OpenAI-compatible</option>
            <option value="codex_subscription">Codex subscription</option>
          </select>
        </label>
        <label>
          <span>AI Model</span>
          <input v-model.trim="localSettings.aiModel" />
        </label>
        <label>
          <span>Codex Model</span>
          <input v-model.trim="localSettings.codexModel" />
        </label>
        <label>
          <span>TTS Voice</span>
          <input v-model.trim="localSettings.ttsVoice" />
        </label>
        <label>
          <span>Browser Bypass</span>
          <select v-model="localSettings.browserBypassMode">
            <option value="on_blocked">Only when blocked</option>
            <option value="always">Always for full content</option>
            <option value="disabled">Disabled</option>
          </select>
        </label>
      </div>

      <label class="switch-row">
        <input v-model="localSettings.aiContentFormattingEnabled" type="checkbox" />
        <span>
          <strong>AI mobile readability formatting</strong>
          <small>When enabled, full-content fetches can reformat articles without summarizing them.</small>
        </span>
      </label>
      <label class="switch-row">
        <input v-model="localSettings.browserBypassEnabled" type="checkbox" />
        <span>
          <strong>Browser automation bypass</strong>
          <small>Use the isolated Playwright Lambda when direct article extraction is blocked.</small>
        </span>
      </label>

      <section class="settings-section">
        <label class="switch-row">
          <input v-model="localSettings.scheduledAiPrefetchEnabled" type="checkbox" />
          <span>
            <strong>Scheduled AI prefetch cache</strong>
            <small>Every 5 minutes EventBridge asks Lambda to refresh matching feeds and cache AI summary, full content, and AI formatting before you open articles.</small>
          </span>
        </label>
        <div class="form-grid compact-grid">
          <label>
            <span>Prefetch tags</span>
            <input v-model="prefetchTagsText" placeholder="ai, research, openai" />
          </label>
          <label>
            <span>Articles per run</span>
            <input v-model.number="localSettings.scheduledAiPrefetchLimit" min="1" max="25" type="number" />
          </label>
          <label>
            <span>Max article age hours</span>
            <input v-model.number="localSettings.scheduledAiPrefetchMaxAgeHours" min="1" max="168" type="number" />
          </label>
          <label>
            <span>Retry after minutes</span>
            <input v-model.number="localSettings.scheduledAiPrefetchRetryMinutes" min="5" max="1440" type="number" />
          </label>
        </div>
        <div class="prefetch-options">
          <label>
            <input v-model="localSettings.scheduledAiPrefetchContent" type="checkbox" />
            <span>Fetch and AI-format content</span>
          </label>
          <label>
            <input v-model="localSettings.scheduledAiPrefetchSummaries" type="checkbox" />
            <span>Generate AI summaries</span>
          </label>
        </div>
        <div v-if="availableTags.length" class="prefetch-tag-cloud">
          <button
            v-for="tag in availableTags"
            :key="tag"
            type="button"
            :class="{ active: localSettings.scheduledAiPrefetchTags.includes(tag) }"
            @click="togglePrefetchTag(tag)"
          >
            #{{ tag }}
          </button>
        </div>
      </section>

      <p class="security-note">
        The static website deploy writes the API URL into config, but not the token unless you opt in. The token above is saved in this browser only.
      </p>

      <footer>
        <button class="ghost-button" @click="$emit('close')">Cancel</button>
        <button class="primary-button" @click="save">Save settings</button>
      </footer>
    </section>
  </div>
</template>
