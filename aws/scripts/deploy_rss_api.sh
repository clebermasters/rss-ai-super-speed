#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AWS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$AWS_DIR/.." && pwd)"
TF_DIR="$AWS_DIR/terraform"
API_LAMBDA_DIR="$AWS_DIR/lambda/rss_api"
BROWSER_LAMBDA_DIR="$AWS_DIR/lambda/browser_fetcher"
DIST_DIR="$AWS_DIR/dist"
GENERATED_DIR="$AWS_DIR/generated"
GENERATED_ENV="$GENERATED_DIR/rss-api.env"
APP_ENV_FILE="$REPO_ROOT/.env"

APP_NAME="${APP_NAME:-rss-ai}"
REGION="${AWS_REGION:-$(aws configure get region 2>/dev/null || true)}"
REGION="${REGION:-us-east-1}"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
ECR_REPO_NAME="${APP_NAME}-browser-fetcher"
ECR_REPO_URL="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${ECR_REPO_NAME}"
IMAGE_TAG="${IMAGE_TAG:-$(date +%Y%m%d%H%M%S)}"
BROWSER_IMAGE_URI="${ECR_REPO_URL}:${IMAGE_TAG}"
ZIP_PATH="$DIST_DIR/rss-api.zip"
PACKAGE_DIR="$DIST_DIR/rss-api-package"

mkdir -p "$DIST_DIR" "$GENERATED_DIR"

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

STATE_BUCKET="${TERRAFORM_STATE_BUCKET:-$(existing_env_value TERRAFORM_STATE_BUCKET "$APP_ENV_FILE")}"
STATE_BUCKET="${STATE_BUCKET:-krebs-terraform-states}"
STATE_KEY="${TERRAFORM_STATE_KEY:-$(existing_env_value TERRAFORM_STATE_KEY "$APP_ENV_FILE")}"
STATE_KEY="${STATE_KEY:-rss-ai/terraform.tfstate}"
STATE_REGION="${TERRAFORM_STATE_REGION:-$(existing_env_value TERRAFORM_STATE_REGION "$APP_ENV_FILE")}"
STATE_REGION="${STATE_REGION:-$REGION}"

terraform_init() {
  if [ -z "$STATE_BUCKET" ]; then
    cat >&2 <<EOF
TERRAFORM_STATE_BUCKET is required.
Set it in your shell or ignored .env file before deployment.
Example:
  TERRAFORM_STATE_BUCKET=your-private-state-bucket
  TERRAFORM_STATE_KEY=$STATE_KEY
  TERRAFORM_STATE_REGION=$STATE_REGION
EOF
    exit 1
  fi
  terraform init \
    -backend-config="bucket=$STATE_BUCKET" \
    -backend-config="key=$STATE_KEY" \
    -backend-config="region=$STATE_REGION" \
    -backend-config="encrypt=true"
}

EXISTING_API_TOKEN="$(existing_env_value RSS_API_TOKEN "$GENERATED_ENV")"
if [ -z "$EXISTING_API_TOKEN" ]; then
  EXISTING_API_TOKEN="$(existing_env_value RSS_API_TOKEN "$APP_ENV_FILE")"
fi

if [ "${ROTATE_API_TOKEN:-0}" = "1" ]; then
  API_TOKEN="${API_TOKEN:-$(openssl rand -hex 32)}"
elif [ -n "${API_TOKEN:-}" ]; then
  API_TOKEN="$API_TOKEN"
elif [ -n "$EXISTING_API_TOKEN" ]; then
  API_TOKEN="$EXISTING_API_TOKEN"
else
  API_TOKEN="$(openssl rand -hex 32)"
fi

CODEX_CLIENT_VERSION="${OPENAI_CODEX_CLIENT_VERSION:-}"
if [ -z "$CODEX_CLIENT_VERSION" ] && command -v codex >/dev/null 2>&1; then
  CODEX_CLIENT_VERSION="$(codex --version 2>/dev/null | awk '{print $2}' | head -n 1 || true)"
fi
CODEX_CLIENT_VERSION="${CODEX_CLIENT_VERSION:-0.118.0}"

OPENAI_API_KEY="${OPENAI_API_KEY:-$(existing_env_value OPENAI_API_KEY "$APP_ENV_FILE")}"
MINIMAX_API_KEY="${MINIMAX_API_KEY:-$(existing_env_value MINIMAX_API_KEY "$APP_ENV_FILE")}"
OPENAI_API_BASE="${OPENAI_API_BASE:-$(existing_env_value OPENAI_API_BASE "$APP_ENV_FILE")}"
OPENAI_API_BASE="${OPENAI_API_BASE:-https://api.openai.com/v1}"
AI_MODEL="${AI_MODEL:-$(existing_env_value AI_MODEL "$APP_ENV_FILE")}"
AI_MODEL="${AI_MODEL:-gpt-5.4}"
EMBEDDING_MODEL="${EMBEDDING_MODEL:-$(existing_env_value EMBEDDING_MODEL "$APP_ENV_FILE")}"
EMBEDDING_MODEL="${EMBEDDING_MODEL:-text-embedding-3-small}"
OPENAI_TTS_API_BASE="${OPENAI_TTS_API_BASE:-$(existing_env_value OPENAI_TTS_API_BASE "$APP_ENV_FILE")}"
OPENAI_TTS_API_BASE="${OPENAI_TTS_API_BASE:-https://api.openai.com/v1}"
OPENAI_TTS_MODEL="${OPENAI_TTS_MODEL:-$(existing_env_value OPENAI_TTS_MODEL "$APP_ENV_FILE")}"
OPENAI_TTS_MODEL="${OPENAI_TTS_MODEL:-gpt-4o-mini-tts-2025-12-15}"
OPENAI_TTS_VOICE="${OPENAI_TTS_VOICE:-$(existing_env_value OPENAI_TTS_VOICE "$APP_ENV_FILE")}"
OPENAI_TTS_VOICE="${OPENAI_TTS_VOICE:-marin}"
OPENAI_CODEX_MODEL="${OPENAI_CODEX_MODEL:-$(existing_env_value OPENAI_CODEX_MODEL "$APP_ENV_FILE")}"
OPENAI_CODEX_MODEL="${OPENAI_CODEX_MODEL:-gpt-5.4}"
DEFAULT_AI_CONTENT_FORMATTING_ENABLED="${DEFAULT_AI_CONTENT_FORMATTING_ENABLED:-$(existing_env_value DEFAULT_AI_CONTENT_FORMATTING_ENABLED "$APP_ENV_FILE")}"
DEFAULT_AI_CONTENT_FORMATTING_ENABLED="${DEFAULT_AI_CONTENT_FORMATTING_ENABLED:-false}"
S3_TTS_CACHE_EXPIRATION_DAYS="${S3_TTS_CACHE_EXPIRATION_DAYS:-$(existing_env_value S3_TTS_CACHE_EXPIRATION_DAYS "$APP_ENV_FILE")}"
S3_TTS_CACHE_EXPIRATION_DAYS="${S3_TTS_CACHE_EXPIRATION_DAYS:-30}"
S3_BROWSER_RESULTS_EXPIRATION_DAYS="${S3_BROWSER_RESULTS_EXPIRATION_DAYS:-$(existing_env_value S3_BROWSER_RESULTS_EXPIRATION_DAYS "$APP_ENV_FILE")}"
S3_BROWSER_RESULTS_EXPIRATION_DAYS="${S3_BROWSER_RESULTS_EXPIRATION_DAYS:-7}"
S3_CACHE_NONCURRENT_EXPIRATION_DAYS="${S3_CACHE_NONCURRENT_EXPIRATION_DAYS:-$(existing_env_value S3_CACHE_NONCURRENT_EXPIRATION_DAYS "$APP_ENV_FILE")}"
S3_CACHE_NONCURRENT_EXPIRATION_DAYS="${S3_CACHE_NONCURRENT_EXPIRATION_DAYS:-1}"

echo "Packaging API Lambda..."
rm -rf "$PACKAGE_DIR" "$ZIP_PATH"
mkdir -p "$PACKAGE_DIR"
python3 -m pip install --quiet --target "$PACKAGE_DIR" -r "$API_LAMBDA_DIR/requirements.txt"
cp "$API_LAMBDA_DIR"/*.py "$PACKAGE_DIR/"
(
  cd "$PACKAGE_DIR"
  zip -qr "$ZIP_PATH" .
)

echo "Initializing Terraform..."
(
  cd "$TF_DIR"
  terraform_init
  terraform apply \
    -target=aws_ecr_repository.browser_fetcher \
    -var "app_name=$APP_NAME" \
    -var "aws_region=$REGION" \
    -var "api_token=$API_TOKEN" \
    -var "api_lambda_zip_path=$ZIP_PATH" \
    -var "browser_image_uri=$BROWSER_IMAGE_URI" \
    -auto-approve
)

echo "Building browser-fetch Lambda image..."
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"
docker build -t "$BROWSER_IMAGE_URI" "$BROWSER_LAMBDA_DIR"
docker push "$BROWSER_IMAGE_URI"

echo "Applying Terraform..."
(
  cd "$TF_DIR"
  terraform apply \
    -var "app_name=$APP_NAME" \
    -var "aws_region=$REGION" \
    -var "api_token=$API_TOKEN" \
    -var "api_lambda_zip_path=$ZIP_PATH" \
    -var "browser_image_uri=$BROWSER_IMAGE_URI" \
    -var "openai_api_key=$OPENAI_API_KEY" \
    -var "minimax_api_key=$MINIMAX_API_KEY" \
    -var "openai_api_base=$OPENAI_API_BASE" \
    -var "ai_model=$AI_MODEL" \
    -var "embedding_model=$EMBEDDING_MODEL" \
    -var "openai_tts_api_base=$OPENAI_TTS_API_BASE" \
    -var "openai_tts_model=$OPENAI_TTS_MODEL" \
    -var "openai_tts_voice=$OPENAI_TTS_VOICE" \
    -var "codex_model=$OPENAI_CODEX_MODEL" \
    -var "codex_client_version=$CODEX_CLIENT_VERSION" \
    -var "s3_tts_cache_expiration_days=$S3_TTS_CACHE_EXPIRATION_DAYS" \
    -var "s3_browser_results_expiration_days=$S3_BROWSER_RESULTS_EXPIRATION_DAYS" \
    -var "s3_cache_noncurrent_expiration_days=$S3_CACHE_NONCURRENT_EXPIRATION_DAYS" \
    -auto-approve
)

API_BASE_URL="$(cd "$TF_DIR" && terraform output -raw api_base_url)"
PRIVATE_BUCKET="$(cd "$TF_DIR" && terraform output -raw private_bucket_name)"
CODEX_AUTH_KEY="$(cd "$TF_DIR" && terraform output -raw codex_auth_s3_key)"
BROWSER_LAMBDA="$(cd "$TF_DIR" && terraform output -raw browser_lambda_name)"

if [ -f "$HOME/.codex/auth.json" ]; then
  echo "Uploading Codex auth JSON to s3://${PRIVATE_BUCKET}/${CODEX_AUTH_KEY}..."
  aws s3 cp "$HOME/.codex/auth.json" "s3://${PRIVATE_BUCKET}/${CODEX_AUTH_KEY}" \
    --sse AES256 \
    --content-type application/json >/dev/null
fi

cat > "$GENERATED_ENV" <<EOF
TERRAFORM_STATE_BUCKET=$STATE_BUCKET
TERRAFORM_STATE_KEY=$STATE_KEY
TERRAFORM_STATE_REGION=$STATE_REGION
RSS_API_BASE_URL=$API_BASE_URL
RSS_API_TOKEN=$API_TOKEN
RSS_AWS_REGION=$REGION
RSS_PRIVATE_BUCKET=$PRIVATE_BUCKET
RSS_BROWSER_LAMBDA=$BROWSER_LAMBDA
RSS_BROWSER_IMAGE_URI=$BROWSER_IMAGE_URI
OPENAI_CODEX_CLIENT_VERSION=$CODEX_CLIENT_VERSION
OPENAI_CODEX_MODEL=$OPENAI_CODEX_MODEL
AI_MODEL=$AI_MODEL
EMBEDDING_MODEL=$EMBEDDING_MODEL
OPENAI_TTS_API_BASE=$OPENAI_TTS_API_BASE
OPENAI_TTS_MODEL=$OPENAI_TTS_MODEL
OPENAI_TTS_VOICE=$OPENAI_TTS_VOICE
DEFAULT_AI_CONTENT_FORMATTING_ENABLED=$DEFAULT_AI_CONTENT_FORMATTING_ENABLED
S3_TTS_CACHE_EXPIRATION_DAYS=$S3_TTS_CACHE_EXPIRATION_DAYS
S3_BROWSER_RESULTS_EXPIRATION_DAYS=$S3_BROWSER_RESULTS_EXPIRATION_DAYS
S3_CACHE_NONCURRENT_EXPIRATION_DAYS=$S3_CACHE_NONCURRENT_EXPIRATION_DAYS
EOF

if [ -f "$APP_ENV_FILE" ]; then
  grep -v '^RSS_' "$APP_ENV_FILE" | grep -v '^OPENAI_CODEX_' | grep -v '^OPENAI_TTS_' | grep -v '^AI_MODEL=' | grep -v '^EMBEDDING_MODEL=' | grep -v '^S3_TTS_CACHE_EXPIRATION_DAYS=' | grep -v '^S3_BROWSER_RESULTS_EXPIRATION_DAYS=' | grep -v '^S3_CACHE_NONCURRENT_EXPIRATION_DAYS=' > "$APP_ENV_FILE.tmp" || true
  cat "$GENERATED_ENV" >> "$APP_ENV_FILE.tmp"
  mv "$APP_ENV_FILE.tmp" "$APP_ENV_FILE"
else
  cp "$GENERATED_ENV" "$APP_ENV_FILE"
fi

cat <<EOF
Deployed RSS AI backend.

API: $API_BASE_URL
Region: $REGION
Private bucket: $PRIVATE_BUCKET
Browser Lambda: $BROWSER_LAMBDA
Env: $GENERATED_ENV
App env updated: $APP_ENV_FILE
EOF
