#!/bin/sh
set -eu
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
task_env_file=${LUMEN_ENV_FILE:-"$task_root/deploy/.env"}
if [ ! -f "$task_env_file" ]; then
    printf '%s\n' 'Missing deployment config. Run python3 deploy/init-config.py --origin https://YOUR_DOMAIN' >&2
    exit 1
fi
task_https=${LUMEN_HTTPS:-}
if [ "${1:-}" = --https ]; then
    task_https=1
    shift
fi
if [ -z "$task_https" ]; then
    task_https=$(sed -n 's/^LUMEN_HTTPS=\([01]\)\r\{0,1\}$/\1/p' "$task_env_file" | tail -n 1)
    task_https=${task_https:-0}
fi
case "$task_https" in
    0) exec docker compose --project-directory "$task_root" --env-file "$task_env_file" -f "$task_root/compose.yaml" "$@" ;;
    1) exec docker compose --project-directory "$task_root" --env-file "$task_env_file" -f "$task_root/compose.yaml" -f "$task_root/deploy/compose.https.yaml" "$@" ;;
    *) printf '%s\n' 'LUMEN_HTTPS must be 0 or 1.' >&2; exit 1 ;;
esac
