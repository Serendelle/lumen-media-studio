#!/bin/sh
# Build first, take a paired maintenance backup, then replace both application services.
set -eu
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
task_compose="$task_root/deploy/compose.sh"
task_env_file=${LUMEN_ENV_FILE:-"$task_root/deploy/.env"}
if [ "$#" -ne 1 ] || ! printf '%s' "$1" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'; then
    printf '%s\n' 'Usage: sh deploy/release.sh RELEASE_TAG' >&2
    exit 1
fi
task_release=$1
LUMEN_RELEASE="$task_release" sh "$task_compose" build api web
sh "$task_root/deploy/backup.sh" --leave-stopped
python3 - "$task_env_file" "$task_release" <<'PY'
import os
from pathlib import Path
import sys
import tempfile

target = Path(sys.argv[1]).resolve()
lines = target.read_text(encoding="utf-8").splitlines()
updated = [line for line in lines if not line.startswith("LUMEN_RELEASE=")]
updated.append("LUMEN_RELEASE=" + sys.argv[2])
descriptor, temporary = tempfile.mkstemp(prefix=".release-", dir=target.parent)
try:
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
        output.write("\n".join(updated) + "\n")
    os.chmod(temporary, 0o600)
    os.replace(temporary, target)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)
PY
if ! sh "$task_compose" up -d --no-build --force-recreate --wait --wait-timeout 300 api web; then
    sh "$task_compose" stop web api
    printf '%s\n' 'Release failed. Services are stopped; inspect logs. Restore the paired backup before using an older image if a migration ran.' >&2
    exit 1
fi
printf 'Release started and passed container health checks: %s\n' "$task_release"
