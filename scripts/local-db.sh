#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TASK_ROOT="$PWD"
TASK_PG_PORT="${TICKET_PG_PORT:-55432}"
if [[ -z "${PG_BIN:-}" ]]; then
  for candidate in /opt/homebrew/opt/postgresql@15/bin /opt/homebrew/opt/postgresql@17/bin /opt/homebrew/opt/postgresql@18/bin /usr/lib/postgresql/17/bin /usr/lib/postgresql/16/bin; do
    if [[ -x "$candidate/pg_ctl" ]]; then PG_BIN="$candidate"; break; fi
  done
  if [[ -z "${PG_BIN:-}" ]] && command -v pg_ctl >/dev/null; then PG_BIN="$(dirname "$(command -v pg_ctl)")"; fi
fi
if [[ -z "${PG_BIN:-}" ]]; then
  echo 'PostgreSQL binaries not found. Install PostgreSQL 15+ or set PG_BIN; Docker Compose is another option.' >&2
  exit 1
fi
TASK_DATA="$TASK_ROOT/.local/postgres"
mkdir -p "$TASK_ROOT/.local"
case "${1:-start}" in
  start)
    if [[ ! -f "$TASK_DATA/PG_VERSION" ]]; then
      "$PG_BIN/initdb" -D "$TASK_DATA" -U tickets --auth=trust --encoding=UTF8 --no-locale
    fi
    if ! "$PG_BIN/pg_ctl" -D "$TASK_DATA" status >/dev/null 2>&1; then
      "$PG_BIN/pg_ctl" -D "$TASK_DATA" -l "$TASK_ROOT/.local/postgres.log" -o "-h 127.0.0.1 -p $TASK_PG_PORT -k $TASK_ROOT/.local -c max_connections=80 -c timezone=UTC" -w start
    fi
    for database in tickets tickets_test; do
      if result=$("$PG_BIN/createdb" -h 127.0.0.1 -p "$TASK_PG_PORT" -U tickets "$database" 2>&1); then
        :
      elif [[ "$result" != *"already exists"* ]]; then
        printf '%s\n' "$result" >&2
        exit 1
      fi
    done
    echo "Project database ready on 127.0.0.1:$TASK_PG_PORT (tickets / tickets_test)."
    ;;
  stop) "$PG_BIN/pg_ctl" -D "$TASK_DATA" -m fast -w stop ;;
  status) "$PG_BIN/pg_ctl" -D "$TASK_DATA" status ;;
  *) echo 'Usage: scripts/local-db.sh [start|stop|status]' >&2; exit 1 ;;
esac
