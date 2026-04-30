import type { Article, ArticlesResponse, BootstrapResponse, FeedsResponse, FetchContentResponse, RefreshResponse, Settings, SpeechOptions } from './types';

export class RssApiError extends Error {
  constructor(message: string, public status = 0) {
    super(message);
  }
}

export class RssApiClient {
  constructor(private readonly baseUrl: string, private readonly token: string) {}

  bootstrap(): Promise<BootstrapResponse> {
    return this.request('GET', '/v1/bootstrap');
  }

  feeds(): Promise<FeedsResponse> {
    return this.request('GET', '/v1/feeds');
  }

  articles(options: { query?: string; source?: string; saved?: boolean; unread?: boolean; limit?: number } = {}): Promise<ArticlesResponse> {
    const params = new URLSearchParams();
    params.set('limit', String(options.limit || 100));
    if (options.query) params.set('query', options.query);
    if (options.source) params.set('source', options.source);
    if (options.saved) params.set('saved', 'true');
    if (options.unread) params.set('unread', 'true');
    return this.request('GET', `/v1/articles?${params.toString()}`);
  }

  article(articleId: string): Promise<Article> {
    return this.request('GET', `/v1/articles/${encodeURIComponent(articleId)}`);
  }

  refresh(): Promise<RefreshResponse> {
    return this.request('POST', '/v1/sync/refresh', {});
  }

  refreshFeed(feedId: string): Promise<RefreshResponse> {
    return this.request('POST', `/v1/feeds/${encodeURIComponent(feedId)}/refresh`, {});
  }

  markRead(articleId: string): Promise<Article> {
    return this.request('POST', `/v1/articles/${encodeURIComponent(articleId)}/mark-read`, {});
  }

  markUnread(articleId: string): Promise<Article> {
    return this.request('POST', `/v1/articles/${encodeURIComponent(articleId)}/mark-unread`, {});
  }

  toggleSave(articleId: string): Promise<Article> {
    return this.request('POST', `/v1/articles/${encodeURIComponent(articleId)}/toggle-save`, {});
  }

  fetchContent(articleId: string, formatWithAi: boolean): Promise<FetchContentResponse> {
    return this.request('POST', `/v1/articles/${encodeURIComponent(articleId)}/fetch-content`, { formatWithAi, markRead: true });
  }

  formatContent(articleId: string): Promise<FetchContentResponse> {
    return this.request('POST', `/v1/articles/${encodeURIComponent(articleId)}/format-content`, { formatWithAi: true, markRead: true });
  }

  contentJob(jobId: string): Promise<FetchContentResponse> {
    return this.request('GET', `/v1/content-jobs/${encodeURIComponent(jobId)}`);
  }

  summarize(articleId: string): Promise<{ articleId: string; summary: string }> {
    return this.request('POST', `/v1/articles/${encodeURIComponent(articleId)}/summarize`, {});
  }

  settings(): Promise<Settings> {
    return this.request('GET', '/v1/settings');
  }

  updateSettings(settings: Settings): Promise<Settings> {
    return this.request('PUT', '/v1/settings', settings);
  }

  async speech(articleId: string, options: SpeechOptions): Promise<Blob> {
    const response = await fetch(this.url(`/v1/articles/${encodeURIComponent(articleId)}/tts`), {
      method: 'POST',
      headers: this.headers('audio/mpeg', true),
      body: JSON.stringify({
        target: options.target,
        segmentPercent: options.segmentPercent || (options.target === 'content' ? 30 : 100),
        segmentIndex: options.segmentIndex || 0,
        forceRefresh: Boolean(options.forceRefresh),
      }),
    });
    if (!response.ok) throw new RssApiError(await safeError(response), response.status);
    return response.blob();
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const response = await fetch(this.url(path), {
      method,
      headers: this.headers('application/json'),
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) throw new RssApiError(await safeError(response), response.status);
    return response.json() as Promise<T>;
  }

  private url(path: string): string {
    if (!this.baseUrl) throw new RssApiError('Backend API URL is not configured.');
    return `${this.baseUrl.replace(/\/$/, '')}${path}`;
  }

  private headers(accept: string, hasJsonBody = false): HeadersInit {
    const headers: Record<string, string> = { accept };
    if (this.token) headers['x-rss-ai-token'] = this.token;
    if (accept === 'application/json' || hasJsonBody) headers['content-type'] = 'application/json';
    return headers;
  }
}

async function safeError(response: Response): Promise<string> {
  const text = await response.text().catch(() => '');
  if (!text) return `HTTP ${response.status}`;
  try {
    const parsed = JSON.parse(text) as { error?: string };
    return parsed.error || `HTTP ${response.status}`;
  } catch {
    return text.slice(0, 240);
  }
}
