#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$SCRIPT_DIR/docker/android-native"
ENV_FILE="$SCRIPT_DIR/.env"

BUILD_TYPE="release"
AUTO_INSTALL=false

for arg in "$@"; do
  case "$arg" in
    debug|release) BUILD_TYPE="$arg" ;;
    --install|-i) AUTO_INSTALL=true ;;
    --help|-h)
      echo "Usage: $0 [debug|release] [--install]"
      exit 0
      ;;
  esac
done

find_docker_socket() {
  local candidates=(
    "${DOCKER_HOST#unix://}"
    "/run/user/$(id -u)/docker.sock"
    "/var/run/docker.sock"
  )
  for sock in "${candidates[@]}"; do
    if [ -S "$sock" ] 2>/dev/null; then
      echo "$sock"
      return 0
    fi
  done
  return 1
}

if ! docker info >/dev/null 2>&1; then
  if sock="$(find_docker_socket)"; then
    export DOCKER_HOST="unix://$sock"
  fi
fi

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not reachable."
  exit 1
fi

RSS_API_BASE_URL=""
RSS_API_TOKEN=""
DEFAULT_LLM_PROVIDER="openai_compatible"
DEFAULT_AI_MODEL="gpt-5.4"
DEFAULT_CODEX_MODEL="gpt-5.4"
DEFAULT_BROWSER_BYPASS_ENABLED="true"

if [ -f "$ENV_FILE" ]; then
  while IFS='=' read -r key value || [ -n "$key" ]; do
    [[ -z "$key" || "$key" =~ ^# ]] && continue
    key="$(echo "$key" | xargs)"
    value="$(echo "$value" | xargs)"
    case "$key" in
      RSS_API_BASE_URL) RSS_API_BASE_URL="$value" ;;
      RSS_API_TOKEN) RSS_API_TOKEN="$value" ;;
      DEFAULT_LLM_PROVIDER) DEFAULT_LLM_PROVIDER="$value" ;;
      DEFAULT_AI_MODEL|AI_MODEL) DEFAULT_AI_MODEL="$value" ;;
      DEFAULT_CODEX_MODEL|OPENAI_CODEX_MODEL) DEFAULT_CODEX_MODEL="$value" ;;
      DEFAULT_BROWSER_BYPASS_ENABLED) DEFAULT_BROWSER_BYPASS_ENABLED="$value" ;;
    esac
  done < "$ENV_FILE"
fi

if ! docker image inspect rss-ai-android-base:latest >/dev/null 2>&1; then
  docker build \
    -t rss-ai-android-base:latest \
    -f "$SCRIPT_DIR/docker/android-base/Dockerfile" \
    "$SCRIPT_DIR" \
    --progress=plain
fi

TOKEN_SECRET_FILE="$(mktemp)"
chmod 600 "$TOKEN_SECRET_FILE"
printf '%s' "$RSS_API_TOKEN" > "$TOKEN_SECRET_FILE"
trap 'rm -f "$TOKEN_SECRET_FILE"' EXIT

DOCKER_BUILDKIT=1 docker build \
  -t rss-ai-android-builder:latest \
  -f "$DOCKER_DIR/Dockerfile" \
  "$SCRIPT_DIR" \
  --progress=plain \
  --secret id=rss_api_token,src="$TOKEN_SECRET_FILE" \
  --build-arg BUILD_TYPE="$BUILD_TYPE" \
  --build-arg RSS_API_BASE_URL="$RSS_API_BASE_URL" \
  --build-arg DEFAULT_LLM_PROVIDER="$DEFAULT_LLM_PROVIDER" \
  --build-arg DEFAULT_AI_MODEL="$DEFAULT_AI_MODEL" \
  --build-arg DEFAULT_CODEX_MODEL="$DEFAULT_CODEX_MODEL" \
  --build-arg DEFAULT_BROWSER_BYPASS_ENABLED="$DEFAULT_BROWSER_BYPASS_ENABLED"

container_id="$(docker create rss-ai-android-builder:latest)"
rm -f "$SCRIPT_DIR/rss-ai-debug.apk" "$SCRIPT_DIR/rss-ai-release.apk"
docker cp "$container_id:/rss-ai-${BUILD_TYPE}.apk" "$SCRIPT_DIR/rss-ai-${BUILD_TYPE}.apk"
docker rm "$container_id" >/dev/null

ls -lh "$SCRIPT_DIR/rss-ai-${BUILD_TYPE}.apk"

if [ "$AUTO_INSTALL" = true ]; then
  if command -v adb >/dev/null 2>&1; then
    adb install -r "$SCRIPT_DIR/rss-ai-${BUILD_TYPE}.apk"
  else
    echo "WARNING: adb not found; APK is at $SCRIPT_DIR/rss-ai-${BUILD_TYPE}.apk"
  fi
fi
