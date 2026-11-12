#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
if [[ $# != 1 ]]; then echo 'Usage: bash deploy-wechat-release.sh /absolute/release/directory' >&2; exit 2; fi
release=$(realpath "$1")
repo=${SADP_REPO:-"$HOME/Projects/SADP-final"}
stage=${SADP_DEPLOY_DIR:-"$HOME/.local/share/sadp-deploy"}
override="$stage/compose.offline.yaml"
cd "$release"
sha256sum -c SHA256SUMS
revision=$(python3 -c 'import json; print(json.load(open("manifest.json"))["revision"])')
[[ $revision =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid revision' >&2; exit 1; }
cd "$repo"
[[ -z $(git status --porcelain --untracked-files=no) ]] || { echo 'Tracked server source changes must be preserved before deploying.' >&2; exit 1; }
[[ -f .env && -f $override ]] || { echo 'Existing .env and deployment override are required.' >&2; exit 1; }
docker info >/dev/null
compose=(docker compose -p sadp-final -f "$repo/compose.deploy.yaml" -f "$override")
"${compose[@]}" config --quiet
container=$("${compose[@]}" ps -q app)
[[ -n $container ]] || { echo 'A running application image is required as the offline runtime base.' >&2; exit 1; }
old_image=$(docker inspect "$container" --format '{{.Image}}')
old_revision=$(git rev-parse HEAD)
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$stage/backups/wechat-$stamp-$$"
mkdir -p "$backup"
chmod 700 "$backup"
cp .env "$backup/app.env"
cp "$override" "$backup/compose.offline.yaml"
cp compose.deploy.yaml "$backup/compose.deploy.yaml"
"${compose[@]}" exec -T postgres pg_dump -U checkcollect -d checkcollect -Fc > "$backup/database.dump"
old_tag="sadp-final-app:rollback-$stamp-$$"
docker image tag "$old_image" "$old_tag"
python3 - "$backup/state.json" "$repo" "$override" "$old_tag" "$old_image" "$old_revision" <<'PY'
import json, sys
path, repo, override, tag, image, revision = sys.argv[1:]
with open(path, 'w') as out:
    json.dump(dict(repo=repo, override=override, oldTag=tag, oldImage=image, oldRevision=revision), out)
PY
cp "$release/rollback-wechat-release.sh" "$backup/rollback.sh"
printf 'Previous source: %s\nPrevious image: %s\nBackup: %s\n' "$old_revision" "$old_image" "$backup"
new_tag="sadp-final-app:wechat-$revision"
docker build --pull=false --network=none --build-arg "RUNTIME_BASE=$old_tag" --build-arg "REVISION=$revision" \
  -f "$release/Dockerfile.wechat-runtime" -t "$new_tag" "$release"
docker image inspect "$new_tag" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' | grep -Fx "$revision"
changed=false
failure() {
  status=$?
  trap - ERR
  if [[ $changed == true ]]; then
    echo 'Deployment failed; restoring the previous application image and configuration.' >&2
    bash "$backup/rollback.sh" "$backup" || echo "Automatic rollback failed. Use $backup/rollback.sh manually." >&2
  fi
  exit "$status"
}
trap failure ERR
git fetch "$release/source.bundle" HEAD
changed=true
git switch --detach "$revision"
python3 "$release/rewrite-app-image.py" "$override" "$new_tag"
"${compose[@]}" config --quiet
"${compose[@]}" up -d --force-recreate --no-build --pull never --no-deps app
ready=false
for attempt in $(seq 1 60); do
  if curl -fsS --max-time 3 http://127.0.0.1:8080/api/auth/health | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"' 2>/dev/null; then ready=true; break; fi
  sleep 2
done
[[ $ready == true ]]
public_base=$(python3 - <<'PY'
from pathlib import Path
for line in Path('.env').read_text().splitlines():
    if line.startswith('FRONTEND_BASE_URL='):
        value = line.split('=', 1)[1].strip().strip(chr(34) + chr(39)).rstrip('/')
        if not value.startswith('https://'): raise SystemExit('FRONTEND_BASE_URL must use HTTPS')
        print(value)
        break
else: raise SystemExit('FRONTEND_BASE_URL is required')
PY
)
curl -fsS --max-time 15 http://127.0.0.1:8080/api/auth/wechat/mini/status | python3 -c 'import json,sys; assert json.load(sys.stdin)["configured"] is True'
curl -fsS --max-time 15 "$public_base/api/auth/health" | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'
curl -fsS --max-time 15 "$public_base/api/auth/wechat/mini/status" | python3 -c 'import json,sys; assert json.load(sys.stdin)["configured"] is True'
curl -fsS --max-time 15 "$public_base/login" -o /dev/null
container=$("${compose[@]}" ps -q app)
new_image=$(docker inspect "$container" --format '{{.Image}}')
[[ $new_image == "$(docker image inspect "$new_tag" --format '{{.Id}}')" ]]
docker image inspect "$new_image" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' | grep -Fx "$revision"
cp "$release/manifest.json" "$stage/current-wechat-release.json"
printf 'Running image: %s\nRelease: %s\nRollback: bash %q %q\n' "$new_image" "$revision" "$backup/rollback.sh" "$backup"
echo 'Server checks passed. Real wx.login and device pairing approval are still required.'
