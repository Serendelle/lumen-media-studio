#!/bin/sh
# This is intentionally destructive only with an explicit command-line opt-in.
set -eu
umask 077
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
task_compose="$task_root/deploy/compose.sh"
if [ "$#" -ne 2 ] || [ "$2" != --replace-existing-data ]; then
    printf '%s\n' 'Usage: sh deploy/restore.sh /absolute/path/to/backup --replace-existing-data' >&2
    printf '%s\n' 'Restores the database and storage together. Current database changes will be replaced.' >&2
    exit 1
fi
task_backup=$(CDPATH= cd -- "$1" && pwd)
python3 "$task_root/deploy/verify-backup.py" "$task_backup"
if [ -z "$(sh "$task_compose" ps --status running -q db)" ]; then
    printf '%s\n' 'Start only the database before restoring: sh deploy/compose.sh up -d db' >&2
    exit 1
fi
printf '%s\n' 'Stopping web and api. A failed restore leaves both stopped for recovery.'
sh "$task_compose" stop web api
sh "$task_compose" exec -T db sh -c 'dropdb --force --if-exists -U "$POSTGRES_USER" "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" -O "$POSTGRES_USER" "$POSTGRES_DB"'
sh "$task_compose" exec -T db sh -c 'pg_restore --exit-on-error --single-transaction --no-owner --no-privileges -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$task_backup/database.dump"
task_stamp=$(date -u +%Y%m%dT%H%M%SZ)
sh "$task_compose" run --rm --no-deps -T --entrypoint sh api -c '
    set -eu
    task_previous="/data/storage.before-restore.$1"
    if [ -e "$task_previous" ]; then printf "%s\n" "Previous restore path already exists" >&2; exit 1; fi
    if [ -d /data/storage ]; then mv -- /data/storage "$task_previous"; fi
    tar --no-same-owner -xzf - -C /data
' restore "$task_stamp" < "$task_backup/storage.tar.gz"
printf 'Paired restore finished. Previous files were retained at /data/storage.before-restore.%s.\n' "$task_stamp"
printf '%s\n' 'Services remain stopped. Select the compatible release, then run: sh deploy/compose.sh up -d --force-recreate --wait api web'
