import type { Article, Settings, SpeechAudio, SpeechOptions } from './types';

const DB_NAME = 'rss-ai-web-speech-cache';
const STORE_NAME = 'speech';
const DB_VERSION = 1;

interface StoredSpeechAudio {
  key: string;
  blob: Blob;
  contentType: string;
  cacheKey?: string | null;
  cacheStatus: string;
  segmentIndex: number;
  segmentCount: number;
  segmentPercent: number;
  inputChars: number;
  sourceChars: number;
  createdAt: number;
}

export function speechCacheKey(article: Article, options: SpeechOptions, settings: Settings): string {
  return [
    article.articleId,
    options.target,
    options.segmentPercent || 100,
    options.segmentIndex || 0,
    contentVersion(article, options.target),
    settings.ttsModel || '',
    settings.ttsVoice || '',
  ].map((part) => encodeURIComponent(String(part))).join('|');
}

function contentVersion(article: Article, target: SpeechOptions['target']): string {
  if (target === 'summary') {
    return `summary:${article.summaryGeneratedAt || article.updatedAt || article.fetchedAt || 0}:${hashText(article.summary || '')}`;
  }
  return [
    'content',
    article.contentAiFormattedAt || article.contentFetchedAt || article.updatedAt || article.fetchedAt || 0,
    article.contentAiFormatted ? 'formatted' : 'raw',
    hashText(article.content || article.contentPreview || article.summary || ''),
  ].join(':');
}

function hashText(text: string): string {
  let hash = 2166136261;
  for (let index = 0; index < text.length; index += 1) {
    hash ^= text.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(16);
}

export async function loadCachedSpeech(key: string): Promise<SpeechAudio | null> {
  const db = await openDb().catch(() => null);
  if (!db) return null;
  const stored = await transaction<StoredSpeechAudio | undefined>(db, 'readonly', (store) => store.get(key));
  if (!stored?.blob || stored.blob.size < 512) return null;
  return {
    blob: stored.blob,
    contentType: stored.contentType,
    cacheKey: stored.cacheKey,
    cacheStatus: 'device',
    segmentIndex: stored.segmentIndex,
    segmentCount: stored.segmentCount,
    segmentPercent: stored.segmentPercent,
    inputChars: stored.inputChars,
    sourceChars: stored.sourceChars,
    fromDeviceCache: true,
  };
}

export async function saveCachedSpeech(key: string, audio: SpeechAudio): Promise<void> {
  const db = await openDb().catch(() => null);
  if (!db) return;
  const stored: StoredSpeechAudio = {
    key,
    blob: audio.blob,
    contentType: audio.contentType,
    cacheKey: audio.cacheKey,
    cacheStatus: audio.cacheStatus,
    segmentIndex: audio.segmentIndex,
    segmentCount: audio.segmentCount,
    segmentPercent: audio.segmentPercent,
    inputChars: audio.inputChars,
    sourceChars: audio.sourceChars,
    createdAt: Date.now(),
  };
  await transaction(db, 'readwrite', (store) => store.put(stored));
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME, { keyPath: 'key' });
      }
    };
  });
}

function transaction<T>(db: IDBDatabase, mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, mode);
    const request = action(tx.objectStore(STORE_NAME));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    tx.onerror = () => reject(tx.error);
  });
}
