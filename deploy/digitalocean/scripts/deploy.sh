#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${DEPLOY_DIR}"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is not installed or not in PATH."
  exit 1
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created deploy/digitalocean/.env from .env.example"
  echo "Edit deploy/digitalocean/.env, then run this script again."
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose plugin is missing. Install it and retry."
  exit 1
fi

docker compose --env-file .env -f docker-compose.yml up -d --build
docker compose --env-file .env -f docker-compose.yml ps
