# syntax=docker/dockerfile:1.7
ARG BUILD_TYPE=debug
FROM rss-ai-android-base:latest AS builder

ARG BUILD_TYPE=debug

WORKDIR /app

COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app/build.gradle.kts ./app/build.gradle.kts
COPY app/proguard-rules.pro ./app/proguard-rules.pro

RUN mkdir -p app/src/main/java app/src/main/res/values \
    && printf '%s\n' '<?xml version="1.0" encoding="utf-8"?><manifest xmlns:android="http://schemas.android.com/apk/res/android"/>' > app/src/main/AndroidManifest.xml \
    && printf '%s\n' '<resources><string name="app_name">RSS AI</string></resources>' > app/src/main/res/values/strings.xml

RUN --mount=type=cache,target=/root/.gradle \
    gradle dependencies --no-daemon || true

COPY . ./

RUN mkdir -p /root/.android \
    && if [ ! -f /app/app/debug.keystore ]; then \
      keytool -genkeypair -v \
        -keystore /app/app/debug.keystore \
        -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Android Debug,O=Android,C=US"; \
    fi \
    && cp /app/app/debug.keystore /root/.android/debug.keystore

RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=secret,id=rss_android_config \
    set -eu; \
    config_file="/run/secrets/rss_android_config"; \
    read_config() { awk -F= -v wanted="$1" '$1 == wanted { value = substr($0, index($0, "=") + 1) } END { print value }' "$config_file" 2>/dev/null || true; }; \
    RSS_API_BASE_URL_VALUE="$(read_config RSS_API_BASE_URL)"; \
    RSS_API_TOKEN_VALUE="$(read_config RSS_API_TOKEN)"; \
    DEFAULT_LLM_PROVIDER_VALUE="$(read_config DEFAULT_LLM_PROVIDER)"; \
    DEFAULT_AI_MODEL_VALUE="$(read_config DEFAULT_AI_MODEL)"; \
    DEFAULT_CODEX_MODEL_VALUE="$(read_config DEFAULT_CODEX_MODEL)"; \
    DEFAULT_AI_CONTENT_FORMATTING_ENABLED_VALUE="$(read_config DEFAULT_AI_CONTENT_FORMATTING_ENABLED)"; \
    DEFAULT_BROWSER_BYPASS_ENABLED_VALUE="$(read_config DEFAULT_BROWSER_BYPASS_ENABLED)"; \
    DEFAULT_LLM_PROVIDER_VALUE="${DEFAULT_LLM_PROVIDER_VALUE:-openai_compatible}"; \
    DEFAULT_AI_MODEL_VALUE="${DEFAULT_AI_MODEL_VALUE:-gpt-5.4}"; \
    DEFAULT_CODEX_MODEL_VALUE="${DEFAULT_CODEX_MODEL_VALUE:-gpt-5.4}"; \
    DEFAULT_AI_CONTENT_FORMATTING_ENABLED_VALUE="${DEFAULT_AI_CONTENT_FORMATTING_ENABLED_VALUE:-false}"; \
    DEFAULT_BROWSER_BYPASS_ENABLED_VALUE="${DEFAULT_BROWSER_BYPASS_ENABLED_VALUE:-true}"; \
    task="assembleDebug"; \
    if [ "$BUILD_TYPE" = "release" ]; then task="assembleRelease"; fi; \
    gradle ":app:${task}" --no-daemon \
      -PRSS_API_BASE_URL="$RSS_API_BASE_URL_VALUE" \
      -PRSS_API_TOKEN="$RSS_API_TOKEN_VALUE" \
      -PDEFAULT_LLM_PROVIDER="$DEFAULT_LLM_PROVIDER_VALUE" \
      -PDEFAULT_AI_MODEL="$DEFAULT_AI_MODEL_VALUE" \
      -PDEFAULT_CODEX_MODEL="$DEFAULT_CODEX_MODEL_VALUE" \
      -PDEFAULT_AI_CONTENT_FORMATTING_ENABLED="$DEFAULT_AI_CONTENT_FORMATTING_ENABLED_VALUE" \
      -PDEFAULT_BROWSER_BYPASS_ENABLED="$DEFAULT_BROWSER_BYPASS_ENABLED_VALUE"

RUN set -eux; \
    apk_path=""; \
    for candidate in \
      "/app/app/build/outputs/apk/${BUILD_TYPE}/app-${BUILD_TYPE}.apk" \
      "/app/app/build/outputs/apk/${BUILD_TYPE}/app-${BUILD_TYPE}-unsigned.apk"; do \
      if [ -f "$candidate" ]; then apk_path="$candidate"; break; fi; \
    done; \
    if [ -z "$apk_path" ]; then \
      find /app/app/build/outputs -maxdepth 5 -type f -name "*.apk" -print; \
      exit 1; \
    fi; \
    cp "$apk_path" "/rss-ai-${BUILD_TYPE}.apk"

FROM alpine:latest AS final
ARG BUILD_TYPE=debug
COPY --from=builder /rss-ai-${BUILD_TYPE}.apk /
CMD ["sh", "-c", "echo 'RSS AI APK artifact ready'"]
