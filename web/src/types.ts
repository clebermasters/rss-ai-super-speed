export interface RuntimeConfig {
  apiBaseUrl: string;
  apiToken: string;
  defaultTheme?: string;
}

export type ArticleFilter = 'all' | 'unread' | 'saved';

export type SpeechTarget = 'content' | 'summary';

export interface Feed {
  feedId: string;
  name: string;
  url: string;
  enabled: boolean;
  tags: string[];
  limit: number;
  articleCount: number;
  unreadCount: number;
}

export interface Article {
  articleId: string;
  title: string;
  link: string;
  summary?: string | null;
  content?: string | null;
  contentPreview?: string | null;
  publishedAt?: string | null;
  source: string;
  sourceFeedId?: string | null;
  tags: string[];
  score?: number | null;
  comments?: number | null;
  isRead: boolean;
  isSaved: boolean;
  contentAiFormatted: boolean;
  contentAiFormattedAt?: number | null;
  contentExpiresAt?: number | null;
  contentFetchedAt?: number | null;
  fetchedAt?: number | null;
  summaryGeneratedAt?: number | null;
  updatedAt?: number | null;
}

export interface FeedUpdateRequest {
  name?: string | null;
  url: string;
  enabled: boolean;
  tags: string[];
  limit: number;
}

export interface Settings {
  llmProvider: string;
  aiModel: string;
  aiApiBase: string;
  codexModel: string;
  codexReasoningEffort: string;
  codexClientVersion: string;
  embeddingProvider: string;
  embeddingModel: string;
  ttsApiBase: string;
  ttsModel: string;
  ttsVoice: string;
  ttsInstructions: string;
  ttsMaxInputChars: number;
  ttsResponseFormat: string;
  ttsSegmentPercent: number;
  autoSummarize: boolean;
  autoFetchContent: boolean;
  aiContentFormattingEnabled: boolean;
  aiContentFormattingMinWords: number;
  aiContentFormattingChunkChars: number;
  aiContentFormattingMaxChunks: number;
  aiContentFormattingMaxTokens: number;
  aiContentFormattingTemperature: number;
  prefetchDistance: number;
  browserBypassEnabled: boolean;
  browserBypassMode: string;
  refreshOnOpen: boolean;
  scheduledRefreshEnabled: boolean;
  scheduledRefreshRate: string;
  scheduledAiPrefetchEnabled: boolean;
  scheduledAiPrefetchTags: string[];
  scheduledAiPrefetchLimit: number;
  scheduledAiPrefetchMaxAgeHours: number;
  scheduledAiPrefetchRetryMinutes: number;
  scheduledAiPrefetchSummaries: boolean;
  scheduledAiPrefetchContent: boolean;
  defaultArticleLimit: number;
  cleanupReadAfterDays: number;
  articleContentCacheTtlDays: number;
  semanticSearchEnabled: boolean;
  exportDefaultFormat: string;
}

export interface FetchContentResponse {
  articleId: string;
  jobId?: string | null;
  status: string;
  strategy?: string | null;
  content?: string;
  article?: Article | null;
  formattingRequested: boolean;
  contentFormattingAttempted: boolean;
  contentAiFormatted: boolean;
  contentFormattingError?: string | null;
  errors: string[];
  message?: string | null;
}

export interface SpeechOptions {
  target: SpeechTarget;
  segmentPercent?: number;
  segmentIndex?: number;
  forceRefresh?: boolean;
}

export interface SpeechAudio {
  blob: Blob;
  contentType: string;
  cacheKey?: string | null;
  cacheStatus: string;
  segmentIndex: number;
  segmentCount: number;
  segmentPercent: number;
  inputChars: number;
  sourceChars: number;
  fromDeviceCache?: boolean;
}

export interface SpeechJobResponse {
  jobId: string;
  articleId: string;
  target: SpeechTarget;
  status: 'queued' | 'running' | 'completed' | 'failed' | string;
  message?: string | null;
  cacheKey?: string | null;
  segmentIndex: number;
  segmentCount: number;
  segmentPercent: number;
  inputChars: number;
  sourceChars: number;
  errors?: string[];
}

export interface BootstrapResponse {
  feeds: Feed[];
  settings: Settings;
}

export interface ArticlesResponse {
  articles: Article[];
  cursor: number;
}

export interface FeedsResponse {
  feeds: Feed[];
}

export interface TagsResponse {
  tags: Array<{ tag: string; feedCount: number; articleCount: number; unreadCount: number }>;
}

export interface RefreshResponse {
  fetched: number;
  saved: number;
  errors?: Array<{ name?: string; url?: string; error?: string }>;
}
