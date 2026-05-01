#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AWS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$AWS_DIR/.." && pwd)"
TF_DIR="$AWS_DIR/terraform"
WEB_DIR="$REPO_ROOT/web"
GENERATED_DIR="$AWS_DIR/generated"
BACKEND_ENV="$GENERATED_DIR/rss-api.env"
WEB_ENV="$GENERATED_DIR/rss-web.env"
APP_ENV_FILE="$REPO_ROOT/.env"

APP_NAME="${APP_NAME:-rss-ai}"
REGION="${AWS_REGION:-$(aws configure get region 2>/dev/null || true)}"
REGION="${REGION:-us-east-1}"
WEB_DOMAIN_NAME="${WEB_DOMAIN_NAME:-rss.bitslovers.com}"
WEB_EMBED_API_TOKEN="${WEB_EMBED_API_TOKEN:-0}"

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

terraform_init() {
  terraform init \
    -backend-config="bucket=$STATE_BUCKET" \
    -backend-config="key=$STATE_KEY" \
    -backend-config="region=$STATE_REGION" \
    -backend-config="encrypt=true"
}

require_command aws
require_command npm
require_command node
require_command terraform

aws sts get-caller-identity >/dev/null
mkdir -p "$GENERATED_DIR"

STATE_BUCKET="${TERRAFORM_STATE_BUCKET:-$(existing_env_value TERRAFORM_STATE_BUCKET "$APP_ENV_FILE")}"
STATE_BUCKET="${STATE_BUCKET:-$(existing_env_value TERRAFORM_STATE_BUCKET "$BACKEND_ENV")}"
STATE_BUCKET="${STATE_BUCKET:-krebs-terraform-states}"
STATE_KEY="${TERRAFORM_STATE_KEY:-$(existing_env_value TERRAFORM_STATE_KEY "$APP_ENV_FILE")}"
STATE_KEY="${STATE_KEY:-$(existing_env_value TERRAFORM_STATE_KEY "$BACKEND_ENV")}"
STATE_KEY="${STATE_KEY:-rss-ai/terraform.tfstate}"
STATE_REGION="${TERRAFORM_STATE_REGION:-$(existing_env_value TERRAFORM_STATE_REGION "$APP_ENV_FILE")}"
STATE_REGION="${STATE_REGION:-$(existing_env_value TERRAFORM_STATE_REGION "$BACKEND_ENV")}"
STATE_REGION="${STATE_REGION:-$REGION}"

echo "Initializing Terraform backend..."
(
  cd "$TF_DIR"
  terraform_init >/dev/null
)

RSS_API_BASE_URL="${RSS_API_BASE_URL:-$(existing_env_value RSS_API_BASE_URL "$BACKEND_ENV")}"
RSS_API_BASE_URL="${RSS_API_BASE_URL:-$(existing_env_value RSS_API_BASE_URL "$APP_ENV_FILE")}"
if [ -z "$RSS_API_BASE_URL" ]; then
  RSS_API_BASE_URL="$(cd "$TF_DIR" && terraform output -raw api_base_url 2>/dev/null || true)"
fi

RSS_API_TOKEN="${RSS_API_TOKEN:-$(existing_env_value RSS_API_TOKEN "$BACKEND_ENV")}"
RSS_API_TOKEN="${RSS_API_TOKEN:-$(existing_env_value RSS_API_TOKEN "$APP_ENV_FILE")}"
CONFIG_TOKEN=""
if [ "$WEB_EMBED_API_TOKEN" = "1" ]; then
  CONFIG_TOKEN="$RSS_API_TOKEN"
fi

if [ -z "$RSS_API_BASE_URL" ]; then
  echo "RSS_API_BASE_URL was not found. Deploy the backend first or set RSS_API_BASE_URL." >&2
  exit 1
fi
if [ "$WEB_EMBED_API_TOKEN" = "1" ] && [ -z "$CONFIG_TOKEN" ]; then
  echo "WEB_EMBED_API_TOKEN=1 was requested, but RSS_API_TOKEN was not found." >&2
  exit 1
fi

BROWSER_IMAGE_URI="${BROWSER_IMAGE_URI:-$(existing_env_value RSS_BROWSER_IMAGE_URI "$BACKEND_ENV")}"
BROWSER_IMAGE_URI="${BROWSER_IMAGE_URI:-$(existing_env_value RSS_BROWSER_IMAGE_URI "$APP_ENV_FILE")}"
if [ -z "$BROWSER_IMAGE_URI" ]; then
  BROWSER_LAMBDA="$(cd "$TF_DIR" && terraform output -raw browser_lambda_name 2>/dev/null || true)"
  BROWSER_LAMBDA="${BROWSER_LAMBDA:-${APP_NAME}-browser-fetcher}"
  BROWSER_IMAGE_URI="$(aws lambda get-function --function-name "$BROWSER_LAMBDA" --region "$REGION" --query 'Code.ImageUri' --output text 2>/dev/null || true)"
fi
if [ -z "$BROWSER_IMAGE_URI" ] || [ "$BROWSER_IMAGE_URI" = "None" ]; then
  echo "Unable to find the existing browser Lambda image URI. Run aws/scripts/deploy_rss_api.sh first." >&2
  exit 1
fi

echo "Installing web dependencies..."
(
  cd "$WEB_DIR"
  if [ -f package-lock.json ]; then
    npm ci
  else
    npm install
  fi
)

echo "Building Vue web app..."
(
  cd "$WEB_DIR"
  npm run build
)

node - "$WEB_DIR/dist/config.json" "$RSS_API_BASE_URL" "$CONFIG_TOKEN" <<'NODE'
const fs = require('node:fs');
const [file, apiBaseUrl, apiToken] = process.argv.slice(2);
fs.writeFileSync(file, `${JSON.stringify({ apiBaseUrl, apiToken }, null, 2)}\n`);
NODE

echo "Creating/updating S3 static website bucket..."
(
  cd "$TF_DIR"
  terraform apply \
    -target=aws_s3_bucket.web_static \
    -target=aws_s3_bucket_ownership_controls.web_static \
    -target=aws_s3_bucket_public_access_block.web_static \
    -target=aws_s3_bucket_server_side_encryption_configuration.web_static \
    -target=aws_s3_bucket_website_configuration.web_static \
    -target=aws_s3_bucket_policy.web_static_public_read \
    -var "app_name=$APP_NAME" \
    -var "aws_region=$REGION" \
    -var "browser_image_uri=$BROWSER_IMAGE_URI" \
    -var "web_domain_name=$WEB_DOMAIN_NAME" \
    -auto-approve
)

WEB_BUCKET="$(cd "$TF_DIR" && terraform output -raw web_bucket_name)"
WEB_ENDPOINT="$(cd "$TF_DIR" && terraform output -raw web_website_endpoint)"
WEB_WEBSITE_DOMAIN="$(cd "$TF_DIR" && terraform output -raw web_website_domain)"

echo "Uploading static assets..."
aws s3 sync "$WEB_DIR/dist/assets" "s3://${WEB_BUCKET}/assets" \
  --delete \
  --cache-control "public,max-age=31536000,immutable" \
  --sse AES256 >/dev/null
aws s3 sync "$WEB_DIR/dist" "s3://${WEB_BUCKET}" \
  --delete \
  --exclude "assets/*" \
  --cache-control "no-cache,max-age=0" \
  --sse AES256 >/dev/null
aws s3 cp "$WEB_DIR/dist/index.html" "s3://${WEB_BUCKET}/index.html" \
  --cache-control "no-cache,max-age=0,must-revalidate" \
  --content-type "text/html; charset=utf-8" \
  --sse AES256 >/dev/null
aws s3 cp "$WEB_DIR/dist/config.json" "s3://${WEB_BUCKET}/config.json" \
  --cache-control "no-store" \
  --content-type "application/json; charset=utf-8" \
  --sse AES256 >/dev/null

cat > "$WEB_ENV" <<EOF
RSS_WEB_DOMAIN=$WEB_DOMAIN_NAME
RSS_WEB_BUCKET=$WEB_BUCKET
RSS_WEB_WEBSITE_ENDPOINT=$WEB_ENDPOINT
RSS_WEB_WEBSITE_DOMAIN=$WEB_WEBSITE_DOMAIN
RSS_WEB_API_BASE_URL=$RSS_API_BASE_URL
RSS_WEB_CONFIG_HAS_EMBEDDED_TOKEN=$WEB_EMBED_API_TOKEN
EOF

cat <<EOF
Deployed RSS AI web app.

Website endpoint: http://$WEB_ENDPOINT
DNS target for $WEB_DOMAIN_NAME: $WEB_ENDPOINT
Generated env: $WEB_ENV

Create a CNAME for $WEB_DOMAIN_NAME pointing to $WEB_ENDPOINT.
The deployed config does not include the API token unless WEB_EMBED_API_TOKEN=1 was set.
EOF
