import type { RuntimeConfig } from './types';

const CONFIG_STORAGE_KEY = 'rss-ai-web-config';

export async function loadRuntimeConfig(): Promise<RuntimeConfig> {
  const local = readStoredConfig();
  const runtime = await fetchRuntimeConfig();
  return {
    apiBaseUrl: local.apiBaseUrl || runtime.apiBaseUrl || '',
    apiToken: local.apiToken || runtime.apiToken || '',
    defaultTheme: local.defaultTheme || runtime.defaultTheme || 'warm',
  };
}

export function saveRuntimeConfig(config: RuntimeConfig): void {
  localStorage.setItem(CONFIG_STORAGE_KEY, JSON.stringify(config));
}

export function readStoredConfig(): RuntimeConfig {
  try {
    const raw = localStorage.getItem(CONFIG_STORAGE_KEY);
    if (!raw) return emptyConfig();
    const parsed = JSON.parse(raw) as Partial<RuntimeConfig>;
    return {
      apiBaseUrl: parsed.apiBaseUrl || '',
      apiToken: parsed.apiToken || '',
      defaultTheme: parsed.defaultTheme || 'warm',
    };
  } catch {
    return emptyConfig();
  }
}

async function fetchRuntimeConfig(): Promise<RuntimeConfig> {
  try {
    const response = await fetch('/config.json', { cache: 'no-store' });
    if (!response.ok) return emptyConfig();
    const parsed = (await response.json()) as Partial<RuntimeConfig>;
    return {
      apiBaseUrl: parsed.apiBaseUrl || '',
      apiToken: parsed.apiToken || '',
      defaultTheme: parsed.defaultTheme || 'warm',
    };
  } catch {
    return emptyConfig();
  }
}

function emptyConfig(): RuntimeConfig {
  return { apiBaseUrl: '', apiToken: '', defaultTheme: 'warm' };
}
