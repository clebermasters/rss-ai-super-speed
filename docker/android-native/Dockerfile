# syntax=docker/dockerfile:1.7
ARG BUILD_TYPE=debug
FROM rss-ai-android-base:latest AS builder

ARG BUILD_TYPE=debug
ARG RSS_API_BASE_URL=""
ARG DEFAULT_LLM_PROVIDER="openai_compatible"
ARG DEFAULT_AI_MODEL="gpt-5.4"
ARG DEFAULT_CODEX_MODEL="gpt-5.4"
ARG DEFAULT_BROWSER_BYPASS_ENABLED="true"

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
    --mount=type=secret,id=rss_api_token \
    set -eux; \
    RSS_API_TOKEN_VALUE="$(cat /run/secrets/rss_api_token 2>/dev/null || true)"; \
    task="assembleDebug"; \
    if [ "$BUILD_TYPE" = "release" ]; then task="assembleRelease"; fi; \
    gradle ":app:${task}" --no-daemon \
      -PRSS_API_BASE_URL="$RSS_API_BASE_URL" \
      -PRSS_API_TOKEN="$RSS_API_TOKEN_VALUE" \
      -PDEFAULT_LLM_PROVIDER="$DEFAULT_LLM_PROVIDER" \
      -PDEFAULT_AI_MODEL="$DEFAULT_AI_MODEL" \
      -PDEFAULT_CODEX_MODEL="$DEFAULT_CODEX_MODEL" \
      -PDEFAULT_BROWSER_BYPASS_ENABLED="$DEFAULT_BROWSER_BYPASS_ENABLED"

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
