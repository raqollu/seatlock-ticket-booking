#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; fi
if [[ -z "${TEST_DATABASE_URL:-}" ]]; then
  ./scripts/local-db.sh start
  export TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:${TICKET_PG_PORT:-55432}/tickets_test"
fi
exec ./mvnw -B -ntp verify "$@"
