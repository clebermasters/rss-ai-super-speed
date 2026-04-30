#!/usr/bin/env python3
"""Import and export RSS AI subscriptions through the backend API.

The script intentionally uses the HTTP API instead of direct DynamoDB access so
it works from any machine that has the generated API URL/token.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.dom.minidom
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ENV_FILES = [REPO_ROOT / ".env", REPO_ROOT / "aws" / "generated" / "rss-api.env"]


class ApiError(RuntimeError):
    pass


class RssApiClient:
    def __init__(self, base_url: str, token: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token

    def request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        body = None
        headers = {
            "accept": "application/json",
            "x-rss-ai-token": self.token,
        }
        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
            headers["content-type"] = "application/json"
        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            raw_error = exc.read().decode("utf-8", errors="replace")
            raise ApiError(f"HTTP {exc.code}: {safe_error_message(raw_error)}") from exc
        except urllib.error.URLError as exc:
            raise ApiError(f"Connection failed: {safe_error_message(str(exc.reason))}") from exc
        if not raw:
            return {}
        return json.loads(raw)

    def list_feeds(self) -> list[dict[str, Any]]:
        return list(self.request("GET", "/v1/feeds").get("feeds") or [])

    def create_feed(self, feed: dict[str, Any]) -> dict[str, Any]:
        return self.request("POST", "/v1/feeds", feed)

    def update_feed(self, feed_id: str, feed: dict[str, Any]) -> dict[str, Any]:
        return self.request("PUT", f"/v1/feeds/{urllib.parse.quote(feed_id)}", feed)

    def delete_feed(self, feed_id: str) -> None:
        self.request("DELETE", f"/v1/feeds/{urllib.parse.quote(feed_id)}")

    def refresh(self) -> dict[str, Any]:
        return self.request("POST", "/v1/sync/refresh", {})


def main() -> int:
    args = parse_args()
    env = load_env(args.env_file)
    api_base_url = args.api_base_url or os.environ.get("RSS_API_BASE_URL") or env.get("RSS_API_BASE_URL")
    api_token = args.api_token or os.environ.get("RSS_API_TOKEN") or env.get("RSS_API_TOKEN")
    if not api_base_url or not api_token:
        raise SystemExit(
            "RSS_API_BASE_URL and RSS_API_TOKEN are required. "
            "Use --env-file, aws/generated/rss-api.env, .env, or shell env vars."
        )

    client = RssApiClient(api_base_url, api_token)
    if args.command == "export":
        return export_subscriptions(client, args)
    if args.command == "import":
        return import_subscriptions(client, args)
    if args.command == "list":
        feeds = client.list_feeds()
        for feed in sorted(feeds, key=lambda item: str(item.get("name", "")).lower()):
            status = "enabled" if feed.get("enabled", True) else "disabled"
            print(f"{feed.get('name', feed.get('url'))} | {status} | {feed.get('url')}")
        print(f"{len(feeds)} subscription(s)")
        return 0
    raise SystemExit(f"Unsupported command: {args.command}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Import/export RSS AI subscriptions.")
    parser.add_argument("--api-base-url", help="Backend API base URL. Defaults to env files or RSS_API_BASE_URL.")
    parser.add_argument("--api-token", help="Backend API token. Defaults to env files or RSS_API_TOKEN.")
    parser.add_argument(
        "--env-file",
        action="append",
        type=Path,
        help="Env file containing RSS_API_BASE_URL/RSS_API_TOKEN. May be repeated.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    export_parser = subparsers.add_parser("export", help="Export subscriptions.")
    export_parser.add_argument("-o", "--output", default="-", help="Output path, or '-' for stdout.")
    export_parser.add_argument("--format", choices=["json", "opml", "auto"], default="auto")
    export_parser.add_argument("--enabled-only", action="store_true", help="Export only enabled feeds.")

    import_parser = subparsers.add_parser("import", help="Import subscriptions from JSON or OPML.")
    import_parser.add_argument("input", type=Path, help="Input JSON/OPML file.")
    import_parser.add_argument("--format", choices=["json", "opml", "auto"], default="auto")
    import_parser.add_argument("--dry-run", action="store_true", help="Show changes without writing anything.")
    import_parser.add_argument("--replace", action="store_true", help="Delete existing feeds not present in the input.")
    import_parser.add_argument("--no-update", action="store_true", help="Only create missing feeds; do not update existing feeds.")
    import_parser.add_argument("--refresh", action="store_true", help="Refresh feeds after import.")
    import_parser.add_argument("--default-limit", type=int, default=20, help="Default article limit for OPML feeds.")

    subparsers.add_parser("list", help="List subscriptions from the backend.")
    return parser.parse_args()


def export_subscriptions(client: RssApiClient, args: argparse.Namespace) -> int:
    feeds = client.list_feeds()
    if args.enabled_only:
        feeds = [feed for feed in feeds if feed.get("enabled", True)]
    feeds = [subscription_payload(feed) for feed in feeds]
    feeds.sort(key=lambda item: str(item.get("name", "")).lower())
    output_format = resolve_export_format(args.format, args.output)
    if output_format == "opml":
        content = feeds_to_opml(feeds)
    else:
        content = json.dumps(
            {
                "exportedAt": now_iso(),
                "count": len(feeds),
                "feeds": feeds,
            },
            ensure_ascii=False,
            indent=2,
        ) + "\n"
    write_output(args.output, content)
    if args.output != "-":
        print(f"Exported {len(feeds)} subscription(s) to {args.output}")
    return 0


def import_subscriptions(client: RssApiClient, args: argparse.Namespace) -> int:
    imported = read_subscriptions(args.input, args.format, default_limit=args.default_limit)
    if args.replace and not imported:
        raise SystemExit("Refusing to replace subscriptions with an empty import file.")

    existing = client.list_feeds()
    existing_by_url = {normalize_url(feed.get("url", "")): feed for feed in existing if feed.get("url")}
    imported_by_url = {normalize_url(feed["url"]): feed for feed in imported}
    created = updated = deleted = unchanged = 0

    for feed in imported:
        normalized_url = normalize_url(feed["url"])
        current = existing_by_url.get(normalized_url)
        if current is None:
            created += 1
            print(f"CREATE {feed['name']} <{feed['url']}>")
            if not args.dry_run:
                client.create_feed(subscription_payload(feed))
            continue

        desired = subscription_payload(feed)
        current_payload = subscription_payload(current)
        if desired == current_payload or args.no_update:
            unchanged += 1
            print(f"SKIP   {feed['name']} <{feed['url']}>")
            continue

        updated += 1
        print(f"UPDATE {feed['name']} <{feed['url']}>")
        if not args.dry_run:
            client.update_feed(str(current["feedId"]), desired)

    if args.replace:
        for feed in existing:
            if normalize_url(feed.get("url", "")) in imported_by_url:
                continue
            deleted += 1
            print(f"DELETE {feed.get('name', feed.get('url'))} <{feed.get('url')}>")
            if not args.dry_run:
                client.delete_feed(str(feed["feedId"]))

    if args.refresh and not args.dry_run:
        client.refresh()
        print("Triggered feed refresh")

    mode = "Dry run" if args.dry_run else "Import"
    print(f"{mode} complete: created={created}, updated={updated}, deleted={deleted}, unchanged={unchanged}")
    return 0


def read_subscriptions(path: Path, requested_format: str, *, default_limit: int) -> list[dict[str, Any]]:
    content = sys.stdin.read() if str(path) == "-" else path.read_text(encoding="utf-8")
    input_format = resolve_input_format(requested_format, path, content)
    if input_format == "opml":
        return parse_opml(content, default_limit=default_limit)
    return parse_json_subscriptions(content, default_limit=default_limit)


def parse_json_subscriptions(content: str, *, default_limit: int = 20) -> list[dict[str, Any]]:
    data = json.loads(content)
    raw_feeds = data.get("feeds") if isinstance(data, dict) else data
    if not isinstance(raw_feeds, list):
        raise ValueError("JSON import must be a feed list or an object with a 'feeds' list.")
    return [subscription_payload(feed, default_limit=default_limit) for feed in raw_feeds]


def parse_opml(content: str, *, default_limit: int = 20) -> list[dict[str, Any]]:
    root = ET.fromstring(content)
    feeds = []
    for outline in root.iter("outline"):
        attrs = case_insensitive_attrs(outline.attrib)
        url = attrs.get("xmlurl")
        if not url:
            continue
        tags = split_tags(attrs.get("category") or attrs.get("categories") or attrs.get("tags") or "")
        feeds.append(
            subscription_payload(
                {
                    "name": attrs.get("title") or attrs.get("text") or url,
                    "url": url,
                    "enabled": parse_bool(attrs.get("rssaienabled") or attrs.get("enabled"), default=True),
                    "tags": tags,
                    "limit": parse_int(attrs.get("rssailimit") or attrs.get("limit"), default_limit),
                },
                default_limit=default_limit,
            )
        )
    return dedupe_feeds(feeds)


def feeds_to_opml(feeds: list[dict[str, Any]]) -> str:
    opml = ET.Element("opml", {"version": "2.0"})
    head = ET.SubElement(opml, "head")
    ET.SubElement(head, "title").text = "RSS AI subscriptions"
    ET.SubElement(head, "dateCreated").text = now_iso()
    body = ET.SubElement(opml, "body")
    for feed in feeds:
        attrs = {
            "type": "rss",
            "text": str(feed["name"]),
            "title": str(feed["name"]),
            "xmlUrl": str(feed["url"]),
            "rssAiEnabled": "true" if feed.get("enabled", True) else "false",
            "rssAiLimit": str(feed.get("limit", 20)),
        }
        tags = feed.get("tags") or []
        if tags:
            attrs["category"] = ",".join(map(str, tags))
        ET.SubElement(body, "outline", attrs)
    rough = ET.tostring(opml, encoding="utf-8")
    return xml.dom.minidom.parseString(rough).toprettyxml(indent="  ", encoding="utf-8").decode("utf-8")


def subscription_payload(feed: dict[str, Any], *, default_limit: int = 20) -> dict[str, Any]:
    url = str(feed.get("url") or feed.get("xmlUrl") or "").strip()
    if not url:
        raise ValueError(f"Feed is missing url: {feed!r}")
    return {
        "name": str(feed.get("name") or feed.get("title") or feed.get("text") or url).strip(),
        "url": url,
        "enabled": parse_bool(feed.get("enabled"), default=True),
        "tags": split_tags(feed.get("tags") or []),
        "limit": parse_int(feed.get("limit"), default_limit),
    }


def dedupe_feeds(feeds: list[dict[str, Any]]) -> list[dict[str, Any]]:
    deduped: dict[str, dict[str, Any]] = {}
    for feed in feeds:
        deduped[normalize_url(feed["url"])] = feed
    return list(deduped.values())


def load_env(paths: list[Path] | None) -> dict[str, str]:
    env: dict[str, str] = {}
    for path in paths or DEFAULT_ENV_FILES:
        if not path.exists():
            continue
        for raw_line in path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip().strip('"').strip("'")
    return env


def resolve_export_format(requested_format: str, output: str) -> str:
    if requested_format != "auto":
        return requested_format
    suffix = Path(output).suffix.lower()
    return "opml" if suffix in {".opml", ".xml"} else "json"


def resolve_input_format(requested_format: str, path: Path, content: str) -> str:
    if requested_format != "auto":
        return requested_format
    if path.suffix.lower() in {".opml", ".xml"}:
        return "opml"
    stripped = content.lstrip()
    return "opml" if stripped.startswith("<") else "json"


def write_output(output: str, content: str) -> None:
    if output == "-":
        print(content, end="")
        return
    path = Path(output)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def case_insensitive_attrs(attrs: dict[str, str]) -> dict[str, str]:
    return {key.lower(): value for key, value in attrs.items()}


def split_tags(value: Any) -> list[str]:
    if isinstance(value, list):
        candidates = value
    else:
        candidates = re.split(r"[,;]", str(value or ""))
    return [str(tag).strip() for tag in candidates if str(tag).strip()]


def parse_bool(value: Any, *, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() not in {"0", "false", "no", "off", "disabled"}


def parse_int(value: Any, default: int) -> int:
    try:
        return max(1, int(value))
    except (TypeError, ValueError):
        return default


def normalize_url(url: str) -> str:
    return str(url).strip().rstrip("/").lower()


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def safe_error_message(message: str) -> str:
    cleaned = (
        message.replace("\\n", " ")
        .replace("\\r", " ")
        .replace("\n", " ")
        .replace("\r", " ")
    )
    cleaned = cleaned.replace("\\\"", "\"")
    cleaned = RegexPatterns.AWS_ARN.sub("[aws-resource]", cleaned)
    cleaned = RegexPatterns.AWS_ACCOUNT.sub("[aws-account]", cleaned)
    cleaned = RegexPatterns.PRIVATE_BUCKET.sub("[private-bucket]", cleaned)
    return cleaned


class RegexPatterns:
    AWS_ARN = re.compile(r"arn:aws:[^\s\"'{}]+")
    AWS_ACCOUNT = re.compile(r"\b\d{12}\b")
    PRIVATE_BUCKET = re.compile(r"rss-ai-private-[A-Za-z0-9-]+")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ApiError, ValueError, ET.ParseError, json.JSONDecodeError, OSError) as exc:
        print(f"error: {safe_error_message(str(exc))}", file=sys.stderr)
        raise SystemExit(1)
