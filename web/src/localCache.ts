import type { Article, ArticleHighlight, Feed, Settings } from './types';

const DB_NAME = 'rss-ai-offline-cache';
const DB_VERSION = 1;
const STORE_NAME = 'snapshots';
const CACHE_KEY = 'default';

export interface OfflineCacheSnapshot {
  articles: Article[];
  cachedAt: number;
  cursor: number;
  feeds: Feed[];
  highlights: ArticleHighlight[];
  settings: Settings | null;
}

export function emptyCacheSnapshot(): OfflineCacheSnapshot {
  return {
    articles: [],
    cachedAt: 0,
    cursor: 0,
    feeds: [],
    highlights: [],
    settings: null,
  };
}

export async function loadOfflineCache(): Promise<OfflineCacheSnapshot | null> {
  try {
    const db = await openCacheDb();
    return await requestToPromise<OfflineCacheSnapshot | null>(db.transaction(STORE_NAME, 'readonly').objectStore(STORE_NAME).get(CACHE_KEY));
  } catch {
    return null;
  }
}

export async function saveOfflineCache(snapshot: OfflineCacheSnapshot, retentionDays: number): Promise<void> {
  try {
    const db = await openCacheDb();
    const pruned = pruneSnapshot({ ...snapshot, cachedAt: Date.now() }, retentionDays);
    await requestToPromise(db.transaction(STORE_NAME, 'readwrite').objectStore(STORE_NAME).put(pruned, CACHE_KEY));
  } catch {
    // Cache failures should never break reading.
  }
}

export function mergeArticles(existing: Article[], incoming: Article[]): Article[] {
  const byId = new Map<string, Article>();
  for (const article of existing) byId.set(article.articleId, article);
  for (const article of incoming) {
    const current = byId.get(article.articleId);
    byId.set(article.articleId, current ? mergeArticle(current, article) : article);
  }
  return [...byId.values()].sort(compareArticleRecency);
}

export function mergeHighlights(existing: ArticleHighlight[], incoming: ArticleHighlight[]): ArticleHighlight[] {
  const byId = new Map<string, ArticleHighlight>();
  for (const highlight of existing) byId.set(highlight.highlightId, highlight);
  for (const highlight of incoming) byId.set(highlight.highlightId, { ...byId.get(highlight.highlightId), ...highlight });
  return [...byId.values()].sort((left, right) => Number(right.createdAt || 0) - Number(left.createdAt || 0));
}

export function pruneSnapshot(snapshot: OfflineCacheSnapshot, retentionDays: number): OfflineCacheSnapshot {
  const cutoff = Date.now() - Math.max(1, Math.min(365, Math.trunc(retentionDays || 30))) * 24 * 60 * 60 * 1000;
  const articles = snapshot.articles.filter((article) => articleTimeMs(article) >= cutoff || article.isSaved);
  const articleIds = new Set(articles.map((article) => article.articleId));
  return {
    ...snapshot,
    articles,
    highlights: snapshot.highlights.filter((highlight) => articleIds.has(highlight.articleId)),
  };
}

function openCacheDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      request.result.createObjectStore(STORE_NAME);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function requestToPromise<T = unknown>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function compareArticleRecency(left: Article, right: Article): number {
  return articleTimeMs(right) - articleTimeMs(left);
}

function mergeArticle(current: Article, next: Article): Article {
  return {
    ...current,
    ...next,
    summary: next.summary ?? current.summary,
    content: next.content ?? current.content,
    contentPreview: next.contentPreview ?? current.contentPreview,
    sourceFeedId: next.sourceFeedId ?? current.sourceFeedId,
    tags: next.tags?.length ? next.tags : current.tags,
    score: next.score ?? current.score,
    comments: next.comments ?? current.comments,
    contentAiFormatted: next.contentAiFormatted || current.contentAiFormatted,
    contentAiFormattedAt: next.contentAiFormattedAt ?? current.contentAiFormattedAt,
    contentExpiresAt: next.contentExpiresAt ?? current.contentExpiresAt,
    contentFetchedAt: next.contentFetchedAt ?? current.contentFetchedAt,
    fetchedAt: next.fetchedAt ?? current.fetchedAt,
    summaryGeneratedAt: next.summaryGeneratedAt ?? current.summaryGeneratedAt,
    updatedAt: next.updatedAt ?? current.updatedAt,
  };
}

function articleTimeMs(article: Article): number {
  const published = Date.parse(article.publishedAt || '');
  if (Number.isFinite(published)) return published;
  return Number(article.updatedAt || article.fetchedAt || 0);
}
