#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AWS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$AWS_DIR/.." && pwd)"
TF_DIR="$AWS_DIR/terraform"
GENERATED_ENV="$AWS_DIR/generated/rss-api.env"
APP_ENV_FILE="$REPO_ROOT/.env"
DEPLOY_SCRIPT="$SCRIPT_DIR/deploy_rss_api.sh"
E2E_SCRIPT="$SCRIPT_DIR/run_rss_api_e2e.sh"

APP_NAME="${APP_NAME:-rss-ai}"
REGION="${AWS_REGION:-$(aws configure get region 2>/dev/null || true)}"
REGION="${REGION:-us-east-1}"

RUN_TESTS=1
RUN_SMOKE=1
PREFLIGHT_ONLY=0
REQUIRE_CODEX_AUTH=0

failures=0
warnings=0

usage() {
  cat <<'EOF'
Deploy the complete RSS AI backend after checking local and AWS preconditions.

Usage:
  aws/scripts/deploy_backend.sh [options]

Options:
  --preflight-only       Run precondition checks only; do not deploy.
  --skip-tests           Skip backend unit tests before deployment.
  --skip-smoke           Skip deployed API/browser smoke tests after deployment.
  --require-codex-auth   Fail preflight if ~/.codex/auth.json is missing or invalid.
  -h, --help             Show this help.

Environment:
  AWS_REGION             AWS region, defaults to configured region or us-east-1.
  APP_NAME               Resource prefix, defaults to rss-ai.
  TERRAFORM_STATE_BUCKET Required S3 bucket for Terraform remote state.
  TERRAFORM_STATE_KEY    S3 key for Terraform state, defaults to rss-ai/terraform.tfstate.
  TERRAFORM_STATE_REGION S3 state bucket region, defaults to AWS_REGION.
  API_TOKEN              Optional fixed API token. Existing generated token is reused otherwise.
  ROTATE_API_TOKEN=1     Generate a new API token during deployment.
  OPENAI_API_KEY         Optional OpenAI-compatible key for backend AI calls.
  MINIMAX_API_KEY        Optional MiniMax key alias.
  OPENAI_API_BASE        Optional OpenAI-compatible base URL.
  AI_MODEL               Default OpenAI-compatible model.
  EMBEDDING_MODEL        Default embeddings model.
  OPENAI_CODEX_MODEL     Default Codex subscription model.
  OPENAI_CODEX_CLIENT_VERSION
                         Codex client version override.
  S3_TTS_CACHE_EXPIRATION_DAYS
                         Days to keep generated TTS audio cache objects. Default: 30.
  S3_BROWSER_RESULTS_EXPIRATION_DAYS
                         Days to keep oversized browser-fetch result objects. Default: 7.
  S3_CACHE_NONCURRENT_EXPIRATION_DAYS
                         Days to keep old cache object versions. Default: 1.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --preflight-only)
      PREFLIGHT_ONLY=1
      ;;
    --skip-tests)
      RUN_TESTS=0
      ;;
    --skip-smoke)
      RUN_SMOKE=0
      ;;
    --require-codex-auth)
      REQUIRE_CODEX_AUTH=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

info() {
  printf '==> %s\n' "$*"
}

ok() {
  printf '  OK   %s\n' "$*"
}

warn() {
  warnings=$((warnings + 1))
  printf '  WARN %s\n' "$*" >&2
}

fail() {
  failures=$((failures + 1))
  printf '  FAIL %s\n' "$*" >&2
}

run_check() {
  local description="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    ok "$description"
  else
    fail "$description"
  fi
}

require_file() {
  local path="$1"
  if [ -f "$path" ]; then
    ok "Required file exists: ${path#$REPO_ROOT/}"
  else
    fail "Required file missing: ${path#$REPO_ROOT/}"
  fi
}

require_executable_script() {
  local path="$1"
  if [ -x "$path" ]; then
    ok "Executable script exists: ${path#$REPO_ROOT/}"
  elif [ -f "$path" ]; then
    fail "Script is not executable: ${path#$REPO_ROOT/}"
  else
    fail "Script missing: ${path#$REPO_ROOT/}"
  fi
}

require_command() {
  local command_name="$1"
  if command -v "$command_name" >/dev/null 2>&1; then
    ok "Command available: $command_name"
  else
    fail "Command missing: $command_name"
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

STATE_BUCKET="${TERRAFORM_STATE_BUCKET:-$(existing_env_value TERRAFORM_STATE_BUCKET "$APP_ENV_FILE")}"
STATE_BUCKET="${STATE_BUCKET:-krebs-terraform-states}"
STATE_KEY="${TERRAFORM_STATE_KEY:-$(existing_env_value TERRAFORM_STATE_KEY "$APP_ENV_FILE")}"
STATE_KEY="${STATE_KEY:-rss-ai/terraform.tfstate}"
STATE_REGION="${TERRAFORM_STATE_REGION:-$(existing_env_value TERRAFORM_STATE_REGION "$APP_ENV_FILE")}"
STATE_REGION="${STATE_REGION:-$REGION}"

terraform_init() {
  if [ -z "$STATE_BUCKET" ]; then
    fail "TERRAFORM_STATE_BUCKET is required in the shell or ignored .env file"
    return 1
  fi
  terraform -chdir="$TF_DIR" init -input=false \
    -backend-config="bucket=$STATE_BUCKET" \
    -backend-config="key=$STATE_KEY" \
    -backend-config="region=$STATE_REGION" \
    -backend-config="encrypt=true"
}

check_codex_auth() {
  local auth_path="$HOME/.codex/auth.json"
  if [ ! -f "$auth_path" ]; then
    if [ "$REQUIRE_CODEX_AUTH" = "1" ]; then
      fail "Codex auth is required but missing: $auth_path"
    else
      warn "Codex auth not found; codex_subscription provider will need auth before use: $auth_path"
    fi
    return
  fi

  if python3 - "$auth_path" <<'PY' >/dev/null 2>&1
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
tokens = payload.get("tokens") or {}
if not tokens.get("access_token") or not tokens.get("refresh_token"):
    raise SystemExit(1)
PY
  then
    ok "Codex auth JSON is present and has access/refresh tokens"
  elif [ "$REQUIRE_CODEX_AUTH" = "1" ]; then
    fail "Codex auth JSON is invalid or missing tokens.access_token/tokens.refresh_token"
  else
    warn "Codex auth JSON exists but is invalid or missing required token fields"
  fi
}

check_optional_llm_credentials() {
  local openai_key minimax_key codex_auth
  openai_key="${OPENAI_API_KEY:-$(existing_env_value OPENAI_API_KEY "$APP_ENV_FILE")}"
  minimax_key="${MINIMAX_API_KEY:-$(existing_env_value MINIMAX_API_KEY "$APP_ENV_FILE")}"
  codex_auth="$HOME/.codex/auth.json"
  if [ -n "$openai_key" ] || [ -n "$minimax_key" ] || [ -f "$codex_auth" ]; then
    ok "At least one backend LLM credential source is configured"
  else
    warn "No OpenAI-compatible key and no Codex auth found; RSS features deploy, AI calls will require configuration"
  fi
}

check_aws() {
  local bucket_location repo_name
  if ! aws sts get-caller-identity --query Account --output text >/dev/null 2>&1; then
    fail "AWS credentials are not valid for sts:GetCallerIdentity"
    return
  fi
  ok "AWS credentials are valid"

  if [ -z "$STATE_BUCKET" ] || [ -z "$STATE_KEY" ] || [ -z "$STATE_REGION" ]; then
    fail "Terraform S3 backend config is incomplete; set TERRAFORM_STATE_BUCKET, TERRAFORM_STATE_KEY, and TERRAFORM_STATE_REGION"
  else
    ok "Terraform backend settings are present"
    if aws s3api head-bucket --bucket "$STATE_BUCKET" --region "$STATE_REGION" >/dev/null 2>&1; then
      ok "Terraform state bucket is accessible"
      bucket_location="$(aws s3api get-bucket-location --bucket "$STATE_BUCKET" --region "$STATE_REGION" --query LocationConstraint --output text 2>/dev/null || true)"
      if [ "$bucket_location" = "None" ] && [ "$STATE_REGION" = "us-east-1" ]; then
        ok "Terraform state bucket region matches us-east-1"
      elif [ "$bucket_location" = "$STATE_REGION" ]; then
        ok "Terraform state bucket region matches configured region"
      else
        fail "Terraform state bucket region mismatch: expected configured region, got ${bucket_location:-unknown}"
      fi
    else
      fail "Terraform state bucket is not accessible"
    fi
  fi

  repo_name="${APP_NAME}-browser-fetcher"
  if aws ecr describe-repositories --repository-names "$repo_name" --region "$REGION" >/dev/null 2>&1; then
    ok "Browser ECR repository is accessible: $repo_name"
  else
    warn "Browser ECR repository is absent or not readable; deploy will create it through Terraform if permissions allow"
  fi
}

preflight() {
  info "Checking local tools"
  require_command aws
  require_command terraform
  require_command docker
  require_command python3
  require_command zip
  require_command openssl
  require_command awk
  run_check "python3 pip is available" python3 -m pip --version
  run_check "Docker daemon is reachable" docker info

  info "Checking repository layout"
  require_executable_script "$DEPLOY_SCRIPT"
  require_executable_script "$E2E_SCRIPT"
  require_file "$TF_DIR/backend.tf"
  require_file "$TF_DIR/providers.tf"
  require_file "$TF_DIR/variables.tf"
  require_file "$AWS_DIR/lambda/rss_api/app.py"
  require_file "$AWS_DIR/lambda/rss_api/requirements.txt"
  require_file "$AWS_DIR/lambda/browser_fetcher/Dockerfile"
  require_file "$AWS_DIR/lambda/browser_fetcher/app.py"
  require_file "$AWS_DIR/lambda/browser_fetcher/requirements.txt"

  info "Checking AWS and Terraform backend"
  check_aws

  info "Checking LLM/Codex configuration"
  check_codex_auth
  check_optional_llm_credentials

  if [ "$failures" -gt 0 ]; then
    printf '\nPreflight failed with %s failure(s) and %s warning(s).\n' "$failures" "$warnings" >&2
    exit 1
  fi

  info "Initializing and validating Terraform"
  terraform_init
  terraform -chdir="$TF_DIR" fmt -check
  terraform -chdir="$TF_DIR" validate

  if [ "$RUN_TESTS" = "1" ]; then
    info "Running backend unit tests"
    python3 -m unittest discover "$AWS_DIR/tests"
  else
    warn "Backend unit tests skipped by --skip-tests"
  fi

  printf '\nPreflight passed with %s warning(s).\n' "$warnings"
}

load_generated_env() {
  if [ ! -f "$GENERATED_ENV" ]; then
    fail "Generated backend env missing after deploy: ${GENERATED_ENV#$REPO_ROOT/}"
    exit 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "$GENERATED_ENV"
  set +a
}

post_deploy_smoke() {
  info "Running deployed API smoke tests"
  "$E2E_SCRIPT"

  info "Running browser Lambda smoke test"
  load_generated_env
  local body_file meta_file
  body_file="$(mktemp)"
  meta_file="$(mktemp)"
  trap 'rm -f "$body_file" "$meta_file"' RETURN
  aws lambda invoke \
    --function-name "$RSS_BROWSER_LAMBDA" \
    --cli-binary-format raw-in-base64-out \
    --payload '{"url":"https://example.com","requestId":"backend-deploy-smoke"}' \
    "$body_file" >"$meta_file"
  python3 - "$meta_file" "$body_file" <<'PY'
import json
import sys

meta = json.load(open(sys.argv[1], encoding="utf-8"))
body = json.load(open(sys.argv[2], encoding="utf-8"))
content = body.get("content") or ""
if meta.get("StatusCode") != 200 or meta.get("FunctionError") or body.get("error"):
    raise SystemExit(f"browser smoke failed: meta={meta!r} body={body!r}")
if "Fetched via browser automation" not in content and not body.get("s3Key"):
    raise SystemExit(f"browser smoke returned no browser content marker or S3 key: {body!r}")
print("  OK   Browser Lambda returned extracted content")
PY
  trap - RETURN
  rm -f "$body_file" "$meta_file"
}

post_deploy_terraform_plan() {
  info "Checking Terraform no-drift plan"
  load_generated_env

  local openai_key minimax_key openai_base ai_model embedding_model codex_model codex_client_version tts_cache_days browser_result_days noncurrent_days
  openai_key="${OPENAI_API_KEY:-$(existing_env_value OPENAI_API_KEY "$APP_ENV_FILE")}"
  minimax_key="${MINIMAX_API_KEY:-$(existing_env_value MINIMAX_API_KEY "$APP_ENV_FILE")}"
  openai_base="${OPENAI_API_BASE:-$(existing_env_value OPENAI_API_BASE "$APP_ENV_FILE")}"
  openai_base="${openai_base:-https://api.openai.com/v1}"
  ai_model="${AI_MODEL:-$(existing_env_value AI_MODEL "$APP_ENV_FILE")}"
  ai_model="${ai_model:-gpt-5.4}"
  embedding_model="${EMBEDDING_MODEL:-$(existing_env_value EMBEDDING_MODEL "$APP_ENV_FILE")}"
  embedding_model="${embedding_model:-text-embedding-3-small}"
  codex_model="${OPENAI_CODEX_MODEL:-$(existing_env_value OPENAI_CODEX_MODEL "$APP_ENV_FILE")}"
  codex_model="${codex_model:-gpt-5.4}"
  codex_client_version="${OPENAI_CODEX_CLIENT_VERSION:-$(existing_env_value OPENAI_CODEX_CLIENT_VERSION "$APP_ENV_FILE")}"
  codex_client_version="${codex_client_version:-0.118.0}"
  tts_cache_days="${S3_TTS_CACHE_EXPIRATION_DAYS:-$(existing_env_value S3_TTS_CACHE_EXPIRATION_DAYS "$APP_ENV_FILE")}"
  tts_cache_days="${tts_cache_days:-30}"
  browser_result_days="${S3_BROWSER_RESULTS_EXPIRATION_DAYS:-$(existing_env_value S3_BROWSER_RESULTS_EXPIRATION_DAYS "$APP_ENV_FILE")}"
  browser_result_days="${browser_result_days:-7}"
  noncurrent_days="${S3_CACHE_NONCURRENT_EXPIRATION_DAYS:-$(existing_env_value S3_CACHE_NONCURRENT_EXPIRATION_DAYS "$APP_ENV_FILE")}"
  noncurrent_days="${noncurrent_days:-1}"

  terraform -chdir="$TF_DIR" plan -input=false \
    -var "app_name=$APP_NAME" \
    -var "aws_region=${RSS_AWS_REGION:-$REGION}" \
    -var "api_token=$RSS_API_TOKEN" \
    -var "api_lambda_zip_path=$AWS_DIR/dist/rss-api.zip" \
    -var "browser_image_uri=$RSS_BROWSER_IMAGE_URI" \
    -var "openai_api_key=$openai_key" \
    -var "minimax_api_key=$minimax_key" \
    -var "openai_api_base=$openai_base" \
    -var "ai_model=$ai_model" \
    -var "embedding_model=$embedding_model" \
    -var "codex_model=$codex_model" \
    -var "codex_client_version=$codex_client_version" \
    -var "s3_tts_cache_expiration_days=$tts_cache_days" \
    -var "s3_browser_results_expiration_days=$browser_result_days" \
    -var "s3_cache_noncurrent_expiration_days=$noncurrent_days"
}

preflight

if [ "$PREFLIGHT_ONLY" = "1" ]; then
  info "Preflight-only mode complete; deployment not run"
  exit 0
fi

info "Deploying complete backend"
"$DEPLOY_SCRIPT"

if [ "$RUN_SMOKE" = "1" ]; then
  post_deploy_smoke
  post_deploy_terraform_plan
else
  warn "Post-deploy smoke checks skipped by --skip-smoke"
fi

info "Backend deployment completed"
