#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WEB_DIR="$REPO_ROOT/web"
GENERATED_ENV="$REPO_ROOT/aws/generated/rss-api.env"
APP_ENV_FILE="$REPO_ROOT/.env"
LOCAL_CONFIG="$WEB_DIR/public/config.local.json"

WEB_HOST="${WEB_HOST:-0.0.0.0}"
WEB_PORT="${WEB_PORT:-5173}"
WEB_THEME="${WEB_THEME:-warm}"
WEB_LOCAL_INCLUDE_TOKEN="${WEB_LOCAL_INCLUDE_TOKEN:-1}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

existing_env_value() {
  local key="$1"
  local file="$2"
  if [ ! -f "$file" ]; then
    return 0
  fi
  awk -F= -v wanted="$key" '
    $1 == wanted {
      value = substr($0, index($0, "=") + 1)
    }
    END {
      if (value != "") {
        print value
      }
    }
  ' "$file"
}

require_command node
require_command npm

RSS_API_BASE_URL="${RSS_API_BASE_URL:-$(existing_env_value RSS_API_BASE_URL "$GENERATED_ENV")}"
RSS_API_BASE_URL="${RSS_API_BASE_URL:-$(existing_env_value RSS_API_BASE_URL "$APP_ENV_FILE")}"
RSS_API_TOKEN="${RSS_API_TOKEN:-$(existing_env_value RSS_API_TOKEN "$GENERATED_ENV")}"
RSS_API_TOKEN="${RSS_API_TOKEN:-$(existing_env_value RSS_API_TOKEN "$APP_ENV_FILE")}"

CONFIG_TOKEN=""
if [ "$WEB_LOCAL_INCLUDE_TOKEN" = "1" ]; then
  CONFIG_TOKEN="$RSS_API_TOKEN"
fi

mkdir -p "$WEB_DIR/public"
node - "$LOCAL_CONFIG" "$RSS_API_BASE_URL" "$CONFIG_TOKEN" "$WEB_THEME" <<'NODE'
const fs = require('node:fs');
const [file, apiBaseUrl, apiToken, defaultTheme] = process.argv.slice(2);
fs.writeFileSync(file, `${JSON.stringify({ apiBaseUrl, apiToken, defaultTheme }, null, 2)}\n`, { mode: 0o600 });
NODE
chmod 600 "$LOCAL_CONFIG"

if [ ! -d "$WEB_DIR/node_modules" ]; then
  echo "Installing web dependencies..."
  (
    cd "$WEB_DIR"
    if [ -f package-lock.json ]; then
      npm ci
    else
      npm install
    fi
  )
fi

cat <<EOF
Starting RSS AI web app locally.

URL: http://localhost:${WEB_PORT}
Host binding: ${WEB_HOST}:${WEB_PORT}
Local config: ${LOCAL_CONFIG}
Backend URL configured: $(test -n "$RSS_API_BASE_URL" && echo yes || echo no)
API token configured locally: $(test -n "$CONFIG_TOKEN" && echo yes || echo no)

Set WEB_LOCAL_INCLUDE_TOKEN=0 to start without writing the API token to local config.
EOF

(
  cd "$WEB_DIR"
  ./node_modules/.bin/vite --host "$WEB_HOST" --port "$WEB_PORT"
)
