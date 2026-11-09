#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
(cd frontend && npm ci && npm run build)
mkdir -p backend/src/main/resources/static
cp -R frontend/dist/. backend/src/main/resources/static/
(cd backend && bash ./mvnw package)
