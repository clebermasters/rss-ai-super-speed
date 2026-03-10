#!/usr/bin/env python3
"""Fetch full article content using AI-powered extraction (MiniMax API)."""

import sys
import argparse
import os


def fetch_page_content(url: str) -> str:
    """Fetch raw page content using requests."""
    try:
        import requests
        from bs4 import BeautifulSoup

        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        }

        response = requests.get(url, headers=headers, timeout=30)
        soup = BeautifulSoup(response.text, "html.parser")

        # Remove scripts and styles
        for script in soup(["script", "style"]):
            script.decompose()

        # Try to get article content
        article = soup.find("article")
        if article:
            text = article.get_text(separator="\n", strip=True)
        else:
            main = soup.find("main") or soup.find("div", class_="content")
            if main:
                text = main.get_text(separator="\n", strip=True)
            else:
                text = soup.get_text(separator="\n", strip=True)

        # Clean up
        lines = [line.strip() for line in text.split("\n") if line.strip()]
        return "\n".join(lines[:200])

    except Exception as e:
        return f"Error fetching page: {e}"


def extract_with_ai(content: str, url: str) -> dict:
    """Use MiniMax to extract article from content."""
    try:
        import requests
        import json
        import re

        api_key = os.environ.get("MINIMAX_API_KEY")
        if not api_key:
            return {"title": "Error", "url": url, "content": "MINIMAX_API_KEY not set"}

        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }

        prompt = f"""Extract the main article title and content from this web page content. 

Page content:
{content[:5000]}

Return as JSON with:
- "title": The article headline/title
- "content": The main article paragraphs (not navigation, ads, etc)"""

        payload = {
            "model": "MiniMax-M2.5-highspeed",
            "messages": [
                {
                    "role": "system",
                    "content": "You extract article content from web pages. Return valid JSON only.",
                },
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.3,
            "max_tokens": 8000,
        }

        response = requests.post(
            "https://api.minimax.io/v1/text/chatcompletion_v2",
            headers=headers,
            json=payload,
            timeout=60,
        )

        if response.status_code != 200:
            return {
                "title": "Error",
                "url": url,
                "content": f"API error: {response.status_code}",
            }

        result = response.json()
        content = result.get("choices", [{}])[0].get("message", {}).get("content", "")

        try:
            data = json.loads(content)
        except:
            json_match = re.search(r"\{[^{}]*\}", content, re.DOTALL)
            if json_match:
                try:
                    data = json.loads(json_match.group())
                except:
                    data = {"title": "Extracted", "content": content}
            else:
                data = {"title": "Extracted", "content": content}

        return {
            "title": data.get("title", "Extracted Article"),
            "url": url,
            "content": data.get("content", content),
        }

    except Exception as e:
        return {"title": "Error", "url": url, "content": f"Error: {e}"}


def fetch_article_ai(url: str) -> dict:
    """Fetch article: first get content, then use AI to extract."""
    # Step 1: Fetch raw content
    content = fetch_page_content(url)

    # Step 2: Use AI to extract structured content
    return extract_with_ai(content, url)


def to_markdown(article: dict) -> str:
    lines = [
        f"# {article['title']}",
        "",
        f"**Source:** {article['url']}",
        "",
        "---",
        "",
        article["content"],
    ]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Fetch full article content using AI")
    parser.add_argument("url", help="Article URL")
    parser.add_argument("--plain", "-p", action="store_true", help="Plain text output")
    args = parser.parse_args()

    print("Fetching with AI extraction (MiniMax-M2.5-highspeed)...\n")

    article = fetch_article_ai(args.url)

    if args.plain:
        print(article["content"])
    else:
        print(to_markdown(article))


if __name__ == "__main__":
    main()
