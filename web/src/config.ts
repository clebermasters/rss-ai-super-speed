import type { RuntimeConfig } from './types';

const CONFIG_STORAGE_KEY = 'rss-ai-web-config';

export async function loadRuntimeConfig(): Promise<RuntimeConfig> {
  const local = readStoredConfig();
  const runtime = await fetchRuntimeConfig();
  return {
    apiBaseUrl: local.apiBaseUrl || runtime.apiBaseUrl || '',
    apiToken: local.apiToken || runtime.apiToken || '',
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
    };
  } catch {
    return emptyConfig();
  }
}

async function fetchRuntimeConfig(): Promise<RuntimeConfig> {
  const configPaths = import.meta.env.DEV ? ['/config.local.json', '/config.json'] : ['/config.json'];
  for (const path of configPaths) {
    try {
      const response = await fetch(path, { cache: 'no-store' });
      if (!response.ok) continue;
      const parsed = (await response.json()) as Partial<RuntimeConfig>;
      return {
        apiBaseUrl: parsed.apiBaseUrl || '',
        apiToken: parsed.apiToken || '',
      };
    } catch {
      continue;
    }
  }
  return emptyConfig();
}

function emptyConfig(): RuntimeConfig {
  return { apiBaseUrl: '', apiToken: '' };
}
