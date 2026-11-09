#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -e .env ]]; then
  echo '.env already exists. Edit it directly.'
  exit 0
fi
umask 077
python3 - <<'PY'
from pathlib import Path
import secrets
text = Path('.env.example').read_text()
for old in ['replace-with-local-database-password', 'replace-with-a-unique-initial-password', 'replace-with-a-unique-demo-password']:
    text = text.replace(old, secrets.token_urlsafe(24))
text = text.replace('RABBITMQ_PASSWORD=guest', 'RABBITMQ_PASSWORD=' + secrets.token_urlsafe(24))
text = text.replace('FRONTEND_BASE_URL=http://localhost:5173', 'FRONTEND_BASE_URL=http://localhost:8080')
Path('.env').write_text(text)
PY
chmod 600 .env
echo 'Created .env. Add map and WeChat configuration before public deployment.'
