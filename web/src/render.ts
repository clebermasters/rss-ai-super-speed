import DOMPurify from 'dompurify';
import { marked } from 'marked';

export function richTextToHtml(input = ''): string {
  const text = normalizeReaderInput(input);
  if (!text) return '';
  if (looksLikeHtml(text)) return sanitizeHtml(text);
  return sanitizeHtml(marked.parse(text, { async: false, breaks: false, gfm: true }) as string);
}

export function normalizeReaderInput(input = ''): string {
  let text = input.trim().replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  if (!text) return '';
  text = text.replace(/<a\b(?=[^>]*\bhref\s*=)([^>]*)>([\s\S]*?)<\/a>/gi, (_match, attrs: string, label: string) => {
    const href = extractHref(attrs);
    return markdownLinkOrText(stripTags(label), href);
  });
  text = text.replace(
    /\bhref\s*=\s*(?:["']?)([^"'\s>]+)(?:["']?)(?:\s+[\w:-]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))*\s*>\s*([^\n<]+)/gi,
    (_match, href: string, label: string) => markdownLinkOrText(label, href),
  );
  text = text.replace(/!\[[^\]\n]*]\([^)]+\)/g, '');
  text = normalizeMarkdownLinks(text);
  text = stripOrphanAttributes(text);

  const output: string[] = [];
  let meaningfulIndex = 0;
  for (const rawLine of text.split('\n')) {
    const line = cleanReaderLine(rawLine);
    if (!line) {
      if (output.length && output[output.length - 1] !== '') output.push('');
      continue;
    }
    if (isStatusMarker(line)) {
      output.push(line);
      continue;
    }
    if (isNoiseLine(line, meaningfulIndex)) continue;
    output.push(line);
    meaningfulIndex += 1;
  }
  return output.join('\n').replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
}

export function plainDate(value?: string | null): string {
  if (!value) return 'undated';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date);
}

function looksLikeHtml(value: string): boolean {
  return /<\/?(p|br|h[1-6]|ul|ol|li|strong|b|em|i|a|blockquote|pre|code)\b/i.test(value);
}

function sanitizeHtml(value: string): string {
  const clean = DOMPurify.sanitize(value, {
    ALLOWED_TAGS: [
      'a',
      'blockquote',
      'br',
      'code',
      'em',
      'h1',
      'h2',
      'h3',
      'h4',
      'h5',
      'h6',
      'hr',
      'li',
      'ol',
      'p',
      'pre',
      'strong',
      'table',
      'tbody',
      'td',
      'th',
      'thead',
      'tr',
      'ul',
    ],
    ALLOWED_ATTR: ['href', 'title', 'target', 'rel'],
    ALLOW_DATA_ATTR: false,
  });
  return clean.replace(/<a href=/g, '<a target="_blank" rel="noreferrer" href=');
}

function markdownishToHtml(value: string): string {
  const lines = value.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
  const html: string[] = [];
  const paragraph: string[] = [];
  let listOpen = false;

  const flushParagraph = () => {
    if (!paragraph.length) return;
    html.push(`<p>${inline(paragraph.join(' ').trim())}</p>`);
    paragraph.length = 0;
  };
  const closeList = () => {
    if (!listOpen) return;
    html.push('</ul>');
    listOpen = false;
  };

  for (const raw of lines) {
    const line = raw.trim();
    if (!line) {
      flushParagraph();
      closeList();
      continue;
    }
    const heading = /^(#{1,6})\s+(.+)$/.exec(line);
    if (heading) {
      flushParagraph();
      closeList();
      const level = Math.min(3, Math.max(2, heading[1].length));
      html.push(`<h${level}>${inline(heading[2])}</h${level}>`);
      continue;
    }
    const bullet = /^[-*+•]\s+(.+)$/.exec(line);
    if (bullet) {
      flushParagraph();
      if (!listOpen) {
        html.push('<ul>');
        listOpen = true;
      }
      html.push(`<li>${inline(bullet[1])}</li>`);
      continue;
    }
    paragraph.push(line);
  }
  flushParagraph();
  closeList();
  return html.join('\n');
}

function inline(value: string): string {
  let html = escapeHtml(value);
  html = html.replace(/!\[([^\]]*)]\(([^)\s]+)(?:\s+["'][^)]*["'])?\)/g, (_m, label: string, href: string) => link(label || 'Open image', href));
  html = html.replace(/(?<!!)\[([^\]]+)]\(([^)\s]+)(?:\s+["'][^)]*["'])?\)/g, (_m, label: string, href: string) => link(label, href));
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
  html = html.replace(/\bhttps?:\/\/[^\s<>"')\]]+/g, (href: string) => link(href.replace(/^https?:\/\//, ''), href));
  return html;
}

function link(label: string, href: string): string {
  const safe = cleanHref(href);
  return `<a href="${escapeAttribute(safe)}" target="_blank" rel="noreferrer">${escapeHtml(label)}</a>`;
}

function normalizeMarkdownLinks(value: string): string {
  return value.replace(/(?<!!)\[([^\]\n]{1,240})]\(([^)\n]+)\)/g, (_match, label: string, href: string) => markdownLinkOrText(label, href));
}

function markdownLinkOrText(rawLabel: string, rawHref: string): string {
  const label = stripTags(rawLabel).replace(/\s+/g, ' ').trim();
  const href = cleanHref(rawHref);
  if (!label) return '';
  if (!href || href === '#' || isDecorativeLabel(label) || looksLikeMediaUrl(href)) return label;
  return `[${label}](${href})`;
}

function extractHref(attrs: string): string {
  const match = /\bhref\s*=\s*(?:(")(.*?)"|(')(.*?)'|([^\s>]+))/i.exec(attrs);
  return match?.[2] || match?.[4] || match?.[5] || '';
}

function cleanHref(value: string): string {
  let href = value
    .trim()
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"');
  href = stripOrphanAttributes(href);
  href = href.split(/\s+(?=["'])/)[0] || href;
  href = href.split(/["']\s+(?:target|rel|class|style|width|height)=/i)[0] || href;
  href = href.trim().replace(/^["'<]+|[>"')\].,;]+$/g, '');
  if (href.startsWith('//')) href = `https:${href}`;
  if (/^[a-z0-9.-]+\.[a-z]{2,}(?:\/|$)/i.test(href)) href = `https://${href}`;
  return href.startsWith('http://') || href.startsWith('https://') ? href : '#';
}

function cleanReaderLine(value: string): string {
  return stripOrphanAttributes(value)
    .replace(/\s*\/?>\s*/g, ' ')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

function stripOrphanAttributes(value: string): string {
  return value.replace(
    /\s+(?:target|rel|class|style|width|height|loading|decoding|srcset|sizes|aria-[\w-]+|data-[\w-]+)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi,
    '',
  );
}

function stripTags(value: string): string {
  return value.replace(/<[^>]+>/g, '').trim();
}

function isStatusMarker(value: string): boolean {
  return /^\*\[[^\]]+]\*$/.test(value.trim());
}

function isNoiseLine(value: string, meaningfulIndex: number): boolean {
  const lower = value.toLowerCase();
  if (
    ['tags:', 'tagged:', 'categories:', 'category:', 'share this', 'share on', 'related posts', 'recommended reading', 'subscribe', 'newsletter', 'advertisement'].some((prefix) =>
      lower.startsWith(prefix),
    )
  ) {
    return true;
  }
  if (meaningfulIndex < 16 && looksLikeAuthorByline(value)) return true;
  if (meaningfulIndex < 16 && /^\*?\s*(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z.]*\s+\d{1,2},\s+\d{4}\s*\*?$/i.test(value)) return true;
  if (looksLikeMediaLine(value)) return true;
  if (/\b(?:target|rel|href)=/i.test(value)) return linkNoiseRatio(value) > 0.35;
  return false;
}

function looksLikeAuthorByline(value: string): boolean {
  const lower = value.toLowerCase();
  if (!lower.startsWith('by ')) return false;
  if (lower.includes('posts by') || lower.includes('/author/') || lower.includes('author/')) return true;
  const withoutLinks = value.replace(/\[([^\]]+)]\([^)]+\)/g, '$1');
  return /^By\s+[A-Z][A-Za-z .'-]{1,80}$/.test(withoutLinks) && withoutLinks.split(/\s+/).length <= 8;
}

function looksLikeMediaLine(value: string): boolean {
  const lower = value.toLowerCase();
  if (['decorative image', 'featured image', 'hero image', 'thumbnail', 'image credit', 'photo credit', 'getty images', 'shutterstock', 'stock photo', 'alt text'].some((word) => lower.includes(word))) {
    return true;
  }
  if (!looksLikeMediaUrl(value)) return false;
  const words = value.match(/[A-Za-z]{3,}/g) || [];
  return words.length <= 8 || linkNoiseRatio(value) > 0.45;
}

function looksLikeMediaUrl(value: string): boolean {
  return /\.(png|jpe?g|gif|webp|svg|avif)(?:[?#][^\s)]*)?/i.test(value);
}

function isDecorativeLabel(value: string): boolean {
  const lower = value.toLowerCase().trim();
  return !lower || ['image', 'photo', 'open image'].includes(lower) || ['decorative image', 'featured image', 'hero image', 'thumbnail', 'image credit', 'photo credit', 'alt text'].some((word) => lower.includes(word));
}

function linkNoiseRatio(value: string): number {
  const matches = value.match(/https?:\/\/\S+|[a-z0-9.-]+\.[a-z]{2,}\/\S+|\w+=/gi) || [];
  const noisyChars = matches.reduce((total, match) => total + match.length, 0);
  return noisyChars / Math.max(1, value.length);
}

function escapeHtml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function escapeAttribute(value: string): string {
  return escapeHtml(value).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
