<script setup lang="ts">
import { reactive, watch } from 'vue';
import { defaultSettings } from '../composables/useRssReader';
import type { RuntimeConfig, Settings } from '../types';

const props = defineProps<{
  config: RuntimeConfig;
  open: boolean;
  settings: Settings;
}>();

const emit = defineEmits<{
  close: [];
  save: [config: RuntimeConfig, settings: Settings];
}>();

const localConfig = reactive<RuntimeConfig>({ apiBaseUrl: '', apiToken: '', defaultTheme: 'warm' });
const localSettings = reactive<Settings>(defaultSettings());

function resetForm(): void {
  Object.assign(localConfig, { apiBaseUrl: '', apiToken: '', defaultTheme: 'warm' }, props.config);
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
        <label>
          <span>Default Theme</span>
          <select v-model="localConfig.defaultTheme">
            <option value="warm">Warm editorial</option>
            <option value="dark">Midnight glass</option>
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
