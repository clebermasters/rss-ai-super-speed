import type { Article } from './types';

const KNOWN_ARTICLE_IDS_KEY = 'rss-ai-web-known-article-ids';
const HAS_ARTICLE_BASELINE_KEY = 'rss-ai-web-has-article-baseline';
const MAX_KNOWN_ARTICLE_IDS = 2500;
const FRESH_HOURS = 24;

export function detectBrandNewArticleIds(articles: Article[]): Set<string> {
  const loadedIds = articles.map((article) => article.articleId).filter(Boolean);
  if (!loadedIds.length) return new Set();

  const hasBaseline = localStorage.getItem(HAS_ARTICLE_BASELINE_KEY) === 'true';
  const knownIds = readKnownArticleIds();
  const brandNewIds = hasBaseline ? loadedIds.filter((id) => !knownIds.has(id)) : [];

  const mergedIds = [...loadedIds, ...[...knownIds].filter((id) => !loadedIds.includes(id))].slice(0, MAX_KNOWN_ARTICLE_IDS);
  localStorage.setItem(HAS_ARTICLE_BASELINE_KEY, 'true');
  localStorage.setItem(KNOWN_ARTICLE_IDS_KEY, JSON.stringify(mergedIds));

  return new Set(brandNewIds);
}

export function isFreshlyPublished(value?: string | null): boolean {
  const ageMs = articleAgeMs(value);
  return ageMs !== null && ageMs >= 0 && ageMs <= FRESH_HOURS * 60 * 60 * 1000;
}

export function publishedAgo(value?: string | null): string {
  const ageMs = articleAgeMs(value);
  if (ageMs === null) return 'undated';
  if (ageMs < 60 * 1000) return 'just now';

  const minutes = Math.floor(ageMs / 60000);
  if (minutes < 60) return `${minutes}m ago`;

  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours}h ago`;

  const days = Math.floor(hours / 24);
  if (days < 31) return `${days}d ago`;

  const months = Math.floor(days / 30);
  if (months < 12) return `${months}mo ago`;

  return `${Math.floor(months / 12)}y ago`;
}

function articleAgeMs(value?: string | null): number | null {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return Date.now() - date.getTime();
}

function readKnownArticleIds(): Set<string> {
  try {
    const parsed = JSON.parse(localStorage.getItem(KNOWN_ARTICLE_IDS_KEY) || '[]') as unknown;
    return new Set(Array.isArray(parsed) ? parsed.filter((value): value is string => typeof value === 'string') : []);
  } catch {
    return new Set();
  }
}
