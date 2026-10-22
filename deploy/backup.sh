#!/bin/sh
# A paired metadata + original-file backup. Stop the API while taking both parts.
set -eu
umask 077
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
task_compose="$task_root/deploy/compose.sh"
task_leave_stopped=0
if [ "${1:-}" = --leave-stopped ]; then task_leave_stopped=1; shift; fi
if [ "$#" -ne 0 ]; then
    printf '%s\n' 'Usage: sh deploy/backup.sh [--leave-stopped]' >&2
    exit 1
fi
if [ -z "$(sh "$task_compose" ps --status running -q db)" ]; then
    printf '%s\n' 'Start the database before backing it up.' >&2
    exit 1
fi
task_count_active() {
    sh "$task_compose" exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "SELECT count(*) FROM tasks WHERE status IN ('"'"'QUEUED'"'"','"'"'RUNNING'"'"')"'
}
task_active=$(task_count_active)
case "$task_active" in
    0) ;;
    *) printf '%s\n' 'Processing tasks are still active (or the schema is unavailable). Finish/cancel them before the maintenance backup.' >&2; exit 1 ;;
esac
task_api_running=$(sh "$task_compose" ps --status running -q api)
task_web_running=$(sh "$task_compose" ps --status running -q web)
task_success=0
task_resume() {
    task_status=$?
    trap - EXIT HUP INT TERM
    if [ "$task_leave_stopped" = 0 ] || [ "$task_success" = 0 ]; then
        if [ -n "$task_api_running" ]; then sh "$task_compose" start api || true; fi
        if [ -n "$task_web_running" ]; then sh "$task_compose" start web || true; fi
    fi
    exit "$task_status"
}
trap task_resume EXIT HUP INT TERM
printf '%s\n' 'Entering maintenance: stopping web and api for a consistent backup.'
sh "$task_compose" stop web api
# A request may have arrived between the first check and maintenance shutdown.
if [ "$(task_count_active)" != 0 ]; then
    printf '%s\n' 'A task arrived during maintenance shutdown. Services will resume; retry after tasks settle.' >&2
    exit 1
fi
task_stamp=$(date -u +%Y%m%dT%H%M%SZ)
task_backup_root="$task_root/data/deploy/backups"
mkdir -p "$task_backup_root"
task_stage=$(mktemp -d "$task_backup_root/.partial-$task_stamp-XXXXXX")
sh "$task_compose" exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner --no-privileges' > "$task_stage/database.dump"
sh "$task_compose" run --rm --no-deps -T --entrypoint tar api -czf - -C /data storage > "$task_stage/storage.tar.gz"
sh "$task_compose" images > "$task_stage/images.txt"
if command -v git >/dev/null 2>&1; then
    git -C "$task_root" rev-parse HEAD > "$task_stage/revision.txt"
else
    printf '%s\n' 'Git revision unavailable on this host.' > "$task_stage/revision.txt"
fi
(cd "$task_stage" && sha256sum database.dump storage.tar.gz images.txt revision.txt > SHA256SUMS)
python3 "$task_root/deploy/verify-backup.py" "$task_stage"
task_final="$task_backup_root/$task_stamp"
if [ -e "$task_final" ]; then task_final="$task_stage-complete"; fi
mv -- "$task_stage" "$task_final"
task_success=1
printf 'Backup completed: %s\n' "$task_final"
if [ "$task_leave_stopped" = 1 ]; then
    printf '%s\n' 'web and api remain stopped for maintenance.'
fi
