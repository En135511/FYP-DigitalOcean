#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DEPLOY_DIR="${REPO_ROOT}/deploy/digitalocean"

if ! command -v git >/dev/null 2>&1; then
  echo "git is not installed or not in PATH."
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is not installed or not in PATH."
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose plugin is missing. Install it and retry."
  exit 1
fi

cd "${REPO_ROOT}"
git pull --ff-only

cd "${DEPLOY_DIR}"
if [[ ! -f .env ]]; then
  echo "Missing deploy/digitalocean/.env. Create it from .env.example first."
  exit 1
fi
docker compose --env-file .env -f docker-compose.yml up -d --build
docker compose --env-file .env -f docker-compose.yml ps
