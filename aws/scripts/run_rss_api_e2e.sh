#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AWS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$AWS_DIR/.." && pwd)"

if [ -f "$AWS_DIR/generated/rss-api.env" ]; then
  set -a
  # shellcheck disable=SC1091
  source "$AWS_DIR/generated/rss-api.env"
  set +a
elif [ -f "$REPO_ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  source "$REPO_ROOT/.env"
  set +a
fi

python3 -m unittest "$AWS_DIR/tests/test_rss_api_e2e.py"
