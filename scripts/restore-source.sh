#!/usr/bin/env bash
set -euo pipefail

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

if [[ "$#" -ne 1 ]]; then
    fail 'Usage: bash scripts/restore-source.sh <backup-file>'
fi

if [[ ! -f "$1" ]]; then
    fail 'The backup path must point to an existing file.'
fi

command -v docker >/dev/null 2>&1 || fail 'Docker with Compose is required.'

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
backup_path="$(cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")"
restore_log="$project_root/data/source/restore-source.log"
container_path=''

cleanup() {
    if [[ -n "$container_path" ]]; then
        if ! docker compose exec -T postgres rm -f -- "$container_path" >/dev/null 2>&1; then
            printf 'Warning: could not remove the temporary container file: %s\n' "$container_path" >&2
        fi
    fi
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cd -- "$project_root"
mkdir -p -- "$project_root/data/source"

if ! object_count=$(docker compose exec -T postgres psql -U postgres -d alfa_source -v ON_ERROR_STOP=1 -At -c "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r','p','v','m','S','f');" 2> "$restore_log"); then
    fail "Could not inspect alfa_source. Start PostgreSQL first. Local log: $restore_log"
fi
if [[ "$object_count" != '0' ]]; then
    fail 'alfa_source is not empty. Restore stopped; existing data was not changed.'
fi

if ! container_path=$(docker compose exec -T postgres mktemp /tmp/alfa-source-XXXXXXXXXX 2> "$restore_log"); then
    fail "Could not create a temporary container file. Local log: $restore_log"
fi

if ! docker compose cp "$backup_path" "postgres:$container_path" > "$restore_log" 2>&1; then
    fail "Could not copy the backup into the local container. Local log: $restore_log"
fi

if ! docker compose exec -T postgres pg_restore --list "$container_path" > "$restore_log" 2>&1; then
    fail "The backup could not be read by pg_restore. Local log: $restore_log"
fi

if ! docker compose exec -T postgres pg_restore --username=postgres --dbname=alfa_source --no-owner --no-privileges --single-transaction --exit-on-error "$container_path" > "$restore_log" 2>&1; then
    fail "Restore failed and the transaction was rolled back. Local log: $restore_log"
fi

printf '%s\n' 'Backup restored into alfa_source successfully.'
