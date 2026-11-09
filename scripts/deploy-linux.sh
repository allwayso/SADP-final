#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then
  echo 'Run bash scripts/init-env.sh first, then edit .env.' >&2
  exit 1
fi
docker compose -f compose.deploy.yaml up -d --build
docker compose -f compose.deploy.yaml ps
