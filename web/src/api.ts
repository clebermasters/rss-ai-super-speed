import type { Article, ArticlesResponse, BootstrapResponse, FeedsResponse, FetchContentResponse, RefreshResponse, Settings, SpeechAudio, SpeechJobResponse, SpeechOptions } from './types';

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

  async speech(articleId: string, options: SpeechOptions): Promise<SpeechAudio> {
    return this.requestSpeech(articleId, options, true);
  }

  private async requestSpeech(articleId: string, options: SpeechOptions, allowAsync: boolean): Promise<SpeechAudio> {
    const response = await fetch(this.url(`/v1/articles/${encodeURIComponent(articleId)}/tts`), {
      method: 'POST',
      headers: this.headers('audio/mpeg', true),
      body: JSON.stringify({
        target: options.target,
        segmentPercent: options.segmentPercent || (options.target === 'content' ? 30 : 100),
        segmentIndex: options.segmentIndex || 0,
        forceRefresh: Boolean(options.forceRefresh),
        async: allowAsync,
      }),
    });
    if (response.status === 202) {
      const job = (await response.json()) as SpeechJobResponse;
      await this.waitForSpeechJob(job.jobId);
      return this.requestSpeech(articleId, { ...options, forceRefresh: false }, false);
    }
    if (!response.ok) throw new RssApiError(await safeError(response), response.status);
    const contentType = response.headers.get('content-type') || 'audio/mpeg';
    if (!contentType.toLowerCase().startsWith('audio/')) {
      throw new RssApiError(`Expected audio but received ${contentType}`, response.status);
    }
    const buffer = await response.arrayBuffer();
    if (buffer.byteLength < 512) {
      throw new RssApiError(`Generated audio is unexpectedly small (${buffer.byteLength} bytes). Regenerate this segment.`, response.status);
    }
    return {
      blob: new Blob([buffer], { type: contentType }),
      contentType,
      cacheKey: response.headers.get('x-rss-ai-cache-key'),
      cacheStatus: response.headers.get('x-rss-ai-cache') || 'miss',
      segmentIndex: intHeader(response.headers, 'x-rss-ai-segment-index', options.segmentIndex || 0),
      segmentCount: intHeader(response.headers, 'x-rss-ai-segment-count', 1),
      segmentPercent: intHeader(response.headers, 'x-rss-ai-segment-percent', options.segmentPercent || 100),
      inputChars: intHeader(response.headers, 'x-rss-ai-input-chars', 0),
      sourceChars: intHeader(response.headers, 'x-rss-ai-source-chars', 0),
    };
  }

  private speechJob(jobId: string): Promise<SpeechJobResponse> {
    return this.request('GET', `/v1/audio-jobs/${encodeURIComponent(jobId)}`);
  }

  private async waitForSpeechJob(jobId: string): Promise<SpeechJobResponse> {
    const startedAt = Date.now();
    let delayMs = 1500;
    while (Date.now() - startedAt < 140000) {
      await sleep(delayMs);
      const job = await this.speechJob(jobId);
      if (job.status === 'completed') return job;
      if (job.status === 'failed') {
        const detail = job.message || job.errors?.[0] || 'Audio generation failed.';
        throw new RssApiError(detail, 502);
      }
      delayMs = Math.min(3500, delayMs + 500);
    }
    throw new RssApiError('Audio generation is still running. Try Listen again in a moment; the cached result should be ready shortly.', 202);
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

function intHeader(headers: Headers, name: string, fallback: number): number {
  const value = Number.parseInt(headers.get(name) || '', 10);
  return Number.isFinite(value) ? value : fallback;
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

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}
