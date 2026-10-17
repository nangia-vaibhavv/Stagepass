#!/bin/sh
set -eu

if [ -n "${DATABASE_URL:-}" ] && [ -z "${DATABASE_JDBC_URL:-}" ]; then
  case "$DATABASE_URL" in
    jdbc:*) export DATABASE_JDBC_URL="$DATABASE_URL" ;;
    postgres://*) export DATABASE_JDBC_URL="jdbc:postgresql://${DATABASE_URL#postgres://}" ;;
    *) export DATABASE_JDBC_URL="jdbc:${DATABASE_URL}" ;;
  esac
fi

export DATABASE_USER="${DATABASE_USER:-${PGUSER:-stagepass}}"
export DATABASE_PASSWORD="${DATABASE_PASSWORD:-${PGPASSWORD:-local-development-only}}"
exec java -XX:MaxRAMPercentage=60 -jar /app/app.jar "$@"
