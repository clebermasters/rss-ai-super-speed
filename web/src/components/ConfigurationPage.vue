<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { defaultSettings } from '../composables/useRssReader';
import type { RuntimeConfig, Settings } from '../types';

const props = defineProps<{
  availableTags: string[];
  config: RuntimeConfig;
  configured: boolean;
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

const visibleSettingsCount = computed(() => Object.keys(localSettings).length);

function resetForm(): void {
  Object.assign(localConfig, { apiBaseUrl: '', apiToken: '' }, props.config);
  Object.assign(localSettings, defaultSettings(), props.settings);
}

function save(): void {
  emit('save', { ...localConfig }, normalizeSettings(localSettings));
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

function normalizeSettings(settings: Settings): Settings {
  return {
    ...settings,
    scheduledAiPrefetchTags: normalizeTags(settings.scheduledAiPrefetchTags),
    scheduledAiPrefetchLimit: boundedInt(settings.scheduledAiPrefetchLimit, 5, 1, 25),
    scheduledAiPrefetchMaxAgeHours: boundedInt(settings.scheduledAiPrefetchMaxAgeHours, 24, 1, 168),
    scheduledAiPrefetchRetryMinutes: boundedInt(settings.scheduledAiPrefetchRetryMinutes, 60, 5, 1440),
    defaultArticleLimit: boundedInt(settings.defaultArticleLimit, 50, 1, 1000),
    cleanupReadAfterDays: boundedInt(settings.cleanupReadAfterDays, 30, 1, 3650),
    articleContentCacheTtlDays: boundedInt(settings.articleContentCacheTtlDays, 30, 1, 365),
    aiContentFormattingMinWords: boundedInt(settings.aiContentFormattingMinWords, 120, 1, 5000),
    aiContentFormattingChunkChars: boundedInt(settings.aiContentFormattingChunkChars, 8500, 1000, 30000),
    aiContentFormattingMaxChunks: boundedInt(settings.aiContentFormattingMaxChunks, 8, 1, 20),
    aiContentFormattingMaxTokens: boundedInt(settings.aiContentFormattingMaxTokens, 6000, 512, 32000),
    aiContentFormattingTemperature: boundedFloat(settings.aiContentFormattingTemperature, 0.1, 0, 2),
    prefetchDistance: boundedInt(settings.prefetchDistance, 3, 0, 25),
    ttsMaxInputChars: boundedInt(settings.ttsMaxInputChars, 6000, 500, 16000),
    ttsSegmentPercent: boundedInt(settings.ttsSegmentPercent, 100, 5, 100),
  };
}

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

function boundedInt(value: unknown, fallback: number, minimum: number, maximum: number): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.max(minimum, Math.min(maximum, Math.trunc(parsed)));
}

function boundedFloat(value: unknown, fallback: number, minimum: number, maximum: number): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.max(minimum, Math.min(maximum, parsed));
}

watch(() => props.config, resetForm, { deep: true, immediate: true });
watch(() => props.settings, resetForm, { deep: true });
</script>

<template>
  <section class="settings-page float-in">
    <header class="settings-page-hero">
      <div>
        <p class="eyebrow">Runtime control room</p>
        <h2>Configuration</h2>
        <p>
          These values are saved through the backend settings API and are read by Lambda at runtime.
          No redeploy is needed for normal provider, cache, prefetch, and reader behavior changes.
        </p>
      </div>
      <div class="settings-page-actions">
        <span class="settings-count-pill">{{ visibleSettingsCount }} settings visible</span>
        <button class="ghost-button" :disabled="!configured" @click="$emit('close')">Back to reader</button>
        <button class="primary-button" @click="save">Save all</button>
      </div>
    </header>

    <div class="config-grid">
      <section class="config-card wide">
        <div class="config-card-title">
          <span>01</span>
          <div>
            <h3>Connection</h3>
            <p>Stored locally in this browser. The API token is not written to the static site config.</p>
          </div>
        </div>
        <div class="config-fields two">
          <label>
            <span>Backend API URL</span>
            <input v-model.trim="localConfig.apiBaseUrl" placeholder="https://api.example.com" />
          </label>
          <label>
            <span>API Token</span>
            <input v-model.trim="localConfig.apiToken" type="password" placeholder="Stored only in this browser" />
          </label>
          <label>
            <span>Default article limit</span>
            <input v-model.number="localSettings.defaultArticleLimit" min="1" max="1000" type="number" />
          </label>
        </div>
      </section>

      <section class="config-card">
        <div class="config-card-title">
          <span>02</span>
          <div>
            <h3>LLM Providers</h3>
            <p>Backend-only provider settings. Android and Web only select the provider/model.</p>
          </div>
        </div>
        <div class="config-fields">
          <label>
            <span>LLM provider</span>
            <select v-model="localSettings.llmProvider">
              <option value="openai_compatible">OpenAI-compatible</option>
              <option value="codex_subscription">Codex subscription</option>
            </select>
          </label>
          <label>
            <span>OpenAI-compatible API base</span>
            <input v-model.trim="localSettings.aiApiBase" />
          </label>
          <label>
            <span>OpenAI-compatible model</span>
            <input v-model.trim="localSettings.aiModel" />
          </label>
          <label>
            <span>Codex model</span>
            <input v-model.trim="localSettings.codexModel" />
          </label>
          <label>
            <span>Codex reasoning effort</span>
            <select v-model="localSettings.codexReasoningEffort">
              <option value="low">low</option>
              <option value="medium">medium</option>
              <option value="high">high</option>
              <option value="xhigh">xhigh</option>
            </select>
          </label>
          <label>
            <span>Codex client version</span>
            <input v-model.trim="localSettings.codexClientVersion" />
          </label>
          <label>
            <span>Embedding provider</span>
            <select v-model="localSettings.embeddingProvider">
              <option value="openai_compatible">OpenAI-compatible</option>
            </select>
          </label>
          <label>
            <span>Embedding model</span>
            <input v-model.trim="localSettings.embeddingModel" />
          </label>
        </div>
      </section>

      <section class="config-card">
        <div class="config-card-title">
          <span>03</span>
          <div>
            <h3>Audio Reader</h3>
            <p>TTS generation, segmentation, and cache fingerprints use these values immediately.</p>
          </div>
        </div>
        <div class="config-fields">
          <label>
            <span>TTS API base</span>
            <input v-model.trim="localSettings.ttsApiBase" />
          </label>
          <label>
            <span>TTS model</span>
            <input v-model.trim="localSettings.ttsModel" />
          </label>
          <label>
            <span>TTS voice</span>
            <input v-model.trim="localSettings.ttsVoice" />
          </label>
          <label>
            <span>TTS response format</span>
            <select v-model="localSettings.ttsResponseFormat">
              <option value="mp3">mp3</option>
              <option value="opus">opus</option>
              <option value="aac">aac</option>
              <option value="flac">flac</option>
              <option value="wav">wav</option>
              <option value="pcm">pcm</option>
            </select>
          </label>
          <label>
            <span>TTS max input chars</span>
            <input v-model.number="localSettings.ttsMaxInputChars" min="500" max="16000" type="number" />
          </label>
          <label>
            <span>Default TTS segment percent</span>
            <input v-model.number="localSettings.ttsSegmentPercent" min="5" max="100" type="number" />
          </label>
          <label class="full-field">
            <span>TTS reading style</span>
            <textarea v-model.trim="localSettings.ttsInstructions" rows="4" />
          </label>
        </div>
      </section>

      <section class="config-card wide">
        <div class="config-card-title">
          <span>04</span>
          <div>
            <h3>Content Cache And Reader Behavior</h3>
            <p>Article full-content cache now writes DynamoDB TTL on content chunks using this runtime value.</p>
          </div>
        </div>
        <div class="switch-grid">
          <label class="switch-row compact">
            <input v-model="localSettings.refreshOnOpen" type="checkbox" />
            <span><strong>Refresh on open</strong><small>App may refresh/bootstrap on startup.</small></span>
          </label>
          <label class="switch-row compact">
            <input v-model="localSettings.autoFetchContent" type="checkbox" />
            <span><strong>Auto fetch content</strong><small>Reader can fetch full content automatically.</small></span>
          </label>
          <label class="switch-row compact">
            <input v-model="localSettings.autoSummarize" type="checkbox" />
            <span><strong>Auto summarize</strong><small>Enable summary generation where supported.</small></span>
          </label>
          <label class="switch-row compact">
            <input v-model="localSettings.semanticSearchEnabled" type="checkbox" />
            <span><strong>Semantic search</strong><small>Use embeddings when semantic search endpoints are invoked.</small></span>
          </label>
        </div>
        <div class="config-fields three">
          <label>
            <span>DynamoDB article content TTL days</span>
            <input v-model.number="localSettings.articleContentCacheTtlDays" min="1" max="365" type="number" />
          </label>
          <label>
            <span>Cleanup read articles after days</span>
            <input v-model.number="localSettings.cleanupReadAfterDays" min="1" max="3650" type="number" />
          </label>
          <label>
            <span>Reader prefetch distance</span>
            <input v-model.number="localSettings.prefetchDistance" min="0" max="25" type="number" />
          </label>
          <label>
            <span>Export default format</span>
            <select v-model="localSettings.exportDefaultFormat">
              <option value="markdown">markdown</option>
              <option value="json">json</option>
              <option value="html">html</option>
            </select>
          </label>
          <label>
            <span>Browser bypass mode</span>
            <select v-model="localSettings.browserBypassMode">
              <option value="on_blocked">Only when blocked</option>
              <option value="always">Always for full content</option>
              <option value="disabled">Disabled</option>
            </select>
          </label>
          <label class="inline-check">
            <input v-model="localSettings.browserBypassEnabled" type="checkbox" />
            <span>Enable browser bot-bypass Lambda</span>
          </label>
        </div>
      </section>

      <section class="config-card wide">
        <div class="config-card-title">
          <span>05</span>
          <div>
            <h3>AI Mobile Formatting</h3>
            <p>Controls the non-summary reformatting pass for comfortable mobile reading.</p>
          </div>
        </div>
        <div class="switch-grid single">
          <label class="switch-row compact">
            <input v-model="localSettings.aiContentFormattingEnabled" type="checkbox" />
            <span><strong>AI readability formatting</strong><small>Format full article text without summarizing.</small></span>
          </label>
        </div>
        <div class="config-fields five">
          <label>
            <span>Minimum words</span>
            <input v-model.number="localSettings.aiContentFormattingMinWords" min="1" max="5000" type="number" />
          </label>
          <label>
            <span>Chunk chars</span>
            <input v-model.number="localSettings.aiContentFormattingChunkChars" min="1000" max="30000" type="number" />
          </label>
          <label>
            <span>Max chunks</span>
            <input v-model.number="localSettings.aiContentFormattingMaxChunks" min="1" max="20" type="number" />
          </label>
          <label>
            <span>Max tokens</span>
            <input v-model.number="localSettings.aiContentFormattingMaxTokens" min="512" max="32000" type="number" />
          </label>
          <label>
            <span>Temperature</span>
            <input v-model.number="localSettings.aiContentFormattingTemperature" min="0" max="2" step="0.1" type="number" />
          </label>
        </div>
      </section>

      <section class="config-card wide">
        <div class="config-card-title">
          <span>06</span>
          <div>
            <h3>Scheduled Automation</h3>
            <p>EventBridge runs Lambda on the configured infrastructure cadence. These values decide what the Lambda does at runtime.</p>
          </div>
        </div>
        <div class="switch-grid">
          <label class="switch-row compact">
            <input v-model="localSettings.scheduledRefreshEnabled" type="checkbox" />
            <span><strong>Scheduled feed refresh</strong><small>Refresh feeds without AI prefetch when AI prefetch is disabled.</small></span>
          </label>
          <label class="switch-row compact">
            <input v-model="localSettings.scheduledAiPrefetchEnabled" type="checkbox" />
            <span><strong>Scheduled AI prefetch cache</strong><small>Refresh matching feeds and queue content/summary cache jobs.</small></span>
          </label>
        </div>
        <div class="config-fields four">
          <label>
            <span>Scheduled refresh rate label</span>
            <input v-model.trim="localSettings.scheduledRefreshRate" placeholder="rate(6 hours)" />
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
          <label class="full-field">
            <span>Prefetch tags</span>
            <input v-model="prefetchTagsText" placeholder="ai, research, openai" />
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
    </div>

    <footer class="settings-page-footer">
      <p>
        Infrastructure-only values such as the real EventBridge cadence, Lambda memory, and S3 lifecycle days still require Terraform.
        Runtime values on this page are saved to DynamoDB settings and read by the API Lambda on each operation.
      </p>
      <div>
        <button class="ghost-button" :disabled="!configured" @click="$emit('close')">Cancel</button>
        <button class="primary-button" @click="save">Save configuration</button>
      </div>
    </footer>
  </section>
</template>
