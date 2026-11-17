#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; fi
./scripts/local-db.sh start
if [[ ! -f .local/jwt-secret ]]; then (umask 077; openssl rand -hex 32 > .local/jwt-secret); fi
export TICKET_JWT_SECRET="${TICKET_JWT_SECRET:-$(cat .local/jwt-secret)}"
export TICKET_DB_URL="${TICKET_DB_URL:-jdbc:postgresql://127.0.0.1:${TICKET_PG_PORT:-55432}/tickets}"
export TICKET_DEMO_ENABLED=true
exec ./mvnw -B -ntp spring-boot:run
