from __future__ import annotations

import csv
import io
import json
from html import escape
from typing import Any


def articles_to_markdown(articles: list[dict[str, Any]]) -> str:
    sections = []
    for article in articles:
        parts = [
            f"## {article.get('title', 'Untitled')}",
            f"Source: {article.get('source', 'Unknown')}",
            f"Link: {article.get('link', '')}",
        ]
        if article.get("summary"):
            parts.extend(["", str(article["summary"])])
        sections.append("\n".join(parts))
    return "\n\n".join(sections).strip()


def articles_to_html(articles: list[dict[str, Any]]) -> str:
    sections = []
    for article in articles:
        sections.append(
            "<article>"
            f"<h2>{escape(str(article.get('title', 'Untitled')))}</h2>"
            f"<p><strong>Source:</strong> {escape(str(article.get('source', 'Unknown')))}</p>"
            f"<p><a href=\"{escape(str(article.get('link', '')))}\">Open original</a></p>"
            f"<p>{escape(str(article.get('summary') or ''))}</p>"
            "</article>"
        )
    return "<!doctype html><html><body>" + "\n".join(sections) + "</body></html>"


def articles_to_csv(articles: list[dict[str, Any]]) -> str:
    output = io.StringIO()
    writer = csv.DictWriter(output, fieldnames=["articleId", "title", "source", "publishedAt", "link", "isRead", "isSaved"])
    writer.writeheader()
    for article in articles:
        writer.writerow({key: article.get(key) for key in writer.fieldnames})
    return output.getvalue()


def format_articles(articles: list[dict[str, Any]], format_name: str) -> tuple[str, str]:
    format_name = (format_name or "json").lower()
    if format_name == "markdown":
        return articles_to_markdown(articles), "text/markdown; charset=utf-8"
    if format_name == "html":
        return articles_to_html(articles), "text/html; charset=utf-8"
    if format_name == "csv":
        return articles_to_csv(articles), "text/csv; charset=utf-8"
    return json.dumps({"articles": articles}, default=str), "application/json"
