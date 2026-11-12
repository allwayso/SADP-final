#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
if [[ $# != 1 ]]; then echo 'Usage: bash rollback-wechat-release.sh /absolute/backup/directory' >&2; exit 2; fi
backup=$(realpath "$1")
mapfile -t saved < <(python3 - "$backup/state.json" <<'PY'
import json, sys
state = json.load(open(sys.argv[1]))
for key in ['repo', 'override', 'oldTag', 'oldImage', 'oldRevision']: print(state[key])
PY
)
[[ ${#saved[@]} == 5 ]] || { echo 'Invalid rollback state' >&2; exit 1; }
repo=${saved[0]}; override=${saved[1]}; old_tag=${saved[2]}; old_image=${saved[3]}; old_revision=${saved[4]}
[[ $old_revision =~ ^[0-9a-f]{40}$ ]] || exit 1
[[ $(docker image inspect "$old_tag" --format '{{.Id}}') == "$old_image" ]]
cd "$repo"
[[ -z $(git status --porcelain --untracked-files=no) ]] || { echo 'Preserve tracked source changes before rollback.' >&2; exit 1; }
git switch --detach "$old_revision"
cp "$backup/app.env" .env
chmod 600 .env
cp "$backup/compose.offline.yaml" "$override"
compose=(docker compose -p sadp-final -f "$repo/compose.deploy.yaml" -f "$override")
"${compose[@]}" config --quiet
"${compose[@]}" up -d --force-recreate --no-build --pull never --no-deps app
for attempt in $(seq 1 60); do
  if curl -fsS --max-time 3 http://127.0.0.1:8080/api/auth/health | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"' 2>/dev/null; then
    current=$("${compose[@]}" ps -q app)
    [[ $(docker inspect "$current" --format '{{.Image}}') == "$old_image" ]]
    echo "Restored application image $old_image. Database, photos and other services were not restored or replaced."
    exit 0
  fi
  sleep 2
done
echo 'Rollback health check failed. Inspect application logs; database.dump is for deliberate recovery only.' >&2
exit 1
