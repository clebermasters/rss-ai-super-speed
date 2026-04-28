#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$SCRIPT_DIR/docker/android-native"
ENV_FILE="$SCRIPT_DIR/.env"
GENERATED_ENV_FILE="$SCRIPT_DIR/aws/generated/rss-api.env"

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

ENV_RSS_API_BASE_URL="${RSS_API_BASE_URL:-}"
ENV_RSS_API_TOKEN="${RSS_API_TOKEN:-}"
ENV_DEFAULT_LLM_PROVIDER="${DEFAULT_LLM_PROVIDER:-}"
ENV_DEFAULT_AI_MODEL="${DEFAULT_AI_MODEL:-}"
ENV_DEFAULT_CODEX_MODEL="${DEFAULT_CODEX_MODEL:-}"
ENV_DEFAULT_BROWSER_BYPASS_ENABLED="${DEFAULT_BROWSER_BYPASS_ENABLED:-}"

RSS_API_BASE_URL="${ENV_RSS_API_BASE_URL:-}"
RSS_API_TOKEN="${ENV_RSS_API_TOKEN:-}"
DEFAULT_LLM_PROVIDER="${ENV_DEFAULT_LLM_PROVIDER:-openai_compatible}"
DEFAULT_AI_MODEL="${ENV_DEFAULT_AI_MODEL:-gpt-5.4}"
DEFAULT_CODEX_MODEL="${ENV_DEFAULT_CODEX_MODEL:-gpt-5.4}"
DEFAULT_BROWSER_BYPASS_ENABLED="${ENV_DEFAULT_BROWSER_BYPASS_ENABLED:-true}"
LOADED_ENV_FILES=()

load_env_file() {
  local file="$1"
  if [ ! -f "$file" ]; then
    return 0
  fi
  LOADED_ENV_FILES+=("${file#$SCRIPT_DIR/}")
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
  done < "$file"
}

load_env_file "$ENV_FILE"
load_env_file "$GENERATED_ENV_FILE"

if [ -n "$ENV_RSS_API_BASE_URL" ]; then RSS_API_BASE_URL="$ENV_RSS_API_BASE_URL"; fi
if [ -n "$ENV_RSS_API_TOKEN" ]; then RSS_API_TOKEN="$ENV_RSS_API_TOKEN"; fi
if [ -n "$ENV_DEFAULT_LLM_PROVIDER" ]; then DEFAULT_LLM_PROVIDER="$ENV_DEFAULT_LLM_PROVIDER"; fi
if [ -n "$ENV_DEFAULT_AI_MODEL" ]; then DEFAULT_AI_MODEL="$ENV_DEFAULT_AI_MODEL"; fi
if [ -n "$ENV_DEFAULT_CODEX_MODEL" ]; then DEFAULT_CODEX_MODEL="$ENV_DEFAULT_CODEX_MODEL"; fi
if [ -n "$ENV_DEFAULT_BROWSER_BYPASS_ENABLED" ]; then DEFAULT_BROWSER_BYPASS_ENABLED="$ENV_DEFAULT_BROWSER_BYPASS_ENABLED"; fi

if [ "${#LOADED_ENV_FILES[@]}" -gt 0 ]; then
  echo "Loaded Android build configuration from: ${LOADED_ENV_FILES[*]}"
fi

if [ -z "$RSS_API_BASE_URL" ] || [ -z "$RSS_API_TOKEN" ]; then
  echo "WARNING: RSS_API_BASE_URL or RSS_API_TOKEN is empty; the app will open Settings for manual configuration."
fi

if [ ! -f "$SCRIPT_DIR/app/debug.keystore" ]; then
  if command -v keytool >/dev/null 2>&1; then
    mkdir -p "$SCRIPT_DIR/app"
    keytool -genkeypair -v \
      -keystore "$SCRIPT_DIR/app/debug.keystore" \
      -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass android -keypass android \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null
    echo "Created reusable local debug keystore at app/debug.keystore"
  else
    echo "WARNING: keytool not found; Docker will create an ephemeral debug keystore and adb reinstall may require uninstalling first."
  fi
fi

if ! docker image inspect rss-ai-android-base:latest >/dev/null 2>&1; then
  docker build \
    -t rss-ai-android-base:latest \
    -f "$SCRIPT_DIR/docker/android-base/Dockerfile" \
    "$SCRIPT_DIR" \
    --progress=plain
fi

CONFIG_SECRET_FILE="$(mktemp)"
chmod 600 "$CONFIG_SECRET_FILE"
cat > "$CONFIG_SECRET_FILE" <<EOF
RSS_API_BASE_URL=$RSS_API_BASE_URL
RSS_API_TOKEN=$RSS_API_TOKEN
DEFAULT_LLM_PROVIDER=$DEFAULT_LLM_PROVIDER
DEFAULT_AI_MODEL=$DEFAULT_AI_MODEL
DEFAULT_CODEX_MODEL=$DEFAULT_CODEX_MODEL
DEFAULT_BROWSER_BYPASS_ENABLED=$DEFAULT_BROWSER_BYPASS_ENABLED
EOF
trap 'rm -f "$CONFIG_SECRET_FILE"' EXIT

DOCKER_BUILDKIT=1 docker build \
  -t rss-ai-android-builder:latest \
  -f "$DOCKER_DIR/Dockerfile" \
  "$SCRIPT_DIR" \
  --progress=plain \
  --secret id=rss_android_config,src="$CONFIG_SECRET_FILE" \
  --build-arg BUILD_TYPE="$BUILD_TYPE"

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
