#!/usr/bin/env bash
set -euo pipefail

: "${STAGEPASS_JDBC_URL:?Set STAGEPASS_JDBC_URL to the managed PostgreSQL JDBC URL, including sslmode=require}"
: "${STAGEPASS_DB_USER:?Set STAGEPASS_DB_USER}"
: "${STAGEPASS_DB_PASSWORD:?Set STAGEPASS_DB_PASSWORD}"

run_migration() {
  local service="$1"
  local schema="$2"
  docker run --rm \
    -v "$PWD/$service/db/migration:/flyway/sql:ro" \
    flyway/flyway:10.20.1 \
    "-url=$STAGEPASS_JDBC_URL" \
    "-user=$STAGEPASS_DB_USER" \
    "-password=$STAGEPASS_DB_PASSWORD" \
    "-schemas=$schema" \
    "-defaultSchema=$schema" \
    "-placeholders.disableDemoAdmin=true" \
    "-locations=filesystem:/flyway/sql" \
    migrate
}

run_migration auth-service auth
run_migration catalog-service catalog
run_migration payment-service payment
run_migration booking-node booking
