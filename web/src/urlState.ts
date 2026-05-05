import type { ArticleFilter } from './types';

export type AppView = 'board' | 'flow' | 'highlights' | 'settings';

export interface AppRouteState {
  articleId: string;
  feedId: string;
  filter: ArticleFilter;
  query: string;
  readerFullscreen: boolean;
  tags: string[];
  view: AppView;
}

export const DEFAULT_ROUTE_STATE: AppRouteState = {
  articleId: '',
  feedId: '',
  filter: 'all',
  query: '',
  readerFullscreen: false,
  tags: [],
  view: 'board',
};

export function parseAppRoute(hash = window.location.hash): AppRouteState {
  const raw = hash.replace(/^#/, '') || '/board';
  const [pathPart, queryPart = ''] = raw.split('?');
  const segments = pathPart
    .split('/')
    .map((part) => part.trim())
    .filter(Boolean);
  const params = new URLSearchParams(queryPart);
  const first = segments[0] || 'board';
  const state: AppRouteState = { ...DEFAULT_ROUTE_STATE };

  if (first === 'article') {
    state.view = 'board';
    state.articleId = decodeRouteValue(segments[1] || params.get('id') || params.get('article') || '');
  } else if (first === 'flow' || first === 'highlights' || first === 'settings' || first === 'board') {
    state.view = first;
    state.articleId = params.get('article') || '';
  } else {
    state.view = 'board';
    state.articleId = params.get('article') || '';
  }

  state.feedId = params.get('feed') || '';
  state.filter = parseFilter(params.get('filter'));
  state.query = params.get('q') || '';
  state.readerFullscreen = params.get('reader') === 'full';
  state.tags = parseTags(params.get('tags') || '');
  return state;
}

export function appRouteToHash(state: AppRouteState): string {
  const params = new URLSearchParams();
  const tags = normalizeRouteTags(state.tags);
  const view = state.view || 'board';
  let path = `/${view}`;

  if (state.feedId) params.set('feed', state.feedId);
  if (state.filter && state.filter !== 'all') params.set('filter', state.filter);
  if (state.query.trim()) params.set('q', state.query.trim());
  if (tags.length) params.set('tags', tags.join(','));

  if (view === 'board' && state.articleId) {
    path = `/article/${encodeURIComponent(state.articleId)}`;
  } else if (state.articleId) {
    params.set('article', state.articleId);
  }

  if (view === 'board' && state.readerFullscreen) params.set('reader', 'full');

  const query = params.toString();
  return `#${path}${query ? `?${query}` : ''}`;
}

function parseFilter(value: string | null): ArticleFilter {
  return value === 'unread' || value === 'saved' ? value : 'all';
}

function parseTags(value: string): string[] {
  return normalizeRouteTags(value.split(','));
}

function normalizeRouteTags(tags: string[]): string[] {
  const seen = new Set<string>();
  const normalized: string[] = [];
  for (const tag of tags) {
    const clean = tag.trim().replace(/^#/, '').toLowerCase().replace(/\s+/g, ' ');
    if (!clean || seen.has(clean)) continue;
    seen.add(clean);
    normalized.push(clean);
  }
  return normalized;
}

function decodeRouteValue(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}
