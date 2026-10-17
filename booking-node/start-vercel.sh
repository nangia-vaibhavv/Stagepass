#!/bin/sh
set -eu

database_url_user=""
database_url_password=""

if [ -n "${DATABASE_URL:-}" ] && [ -z "${DATABASE_JDBC_URL:-}" ]; then
  case "$DATABASE_URL" in
    jdbc:*) export DATABASE_JDBC_URL="$DATABASE_URL" ;;
    postgres://*|postgresql://*)
      # PostgreSQL JDBC URLs do not accept user:password@ in the authority.
      # Keep credentials in DATABASE_USER / DATABASE_PASSWORD instead.
      database_url_authority_and_path="${DATABASE_URL#*://}"
      case "$database_url_authority_and_path" in
        *@*)
          database_url_userinfo="${database_url_authority_and_path%@*}"
          database_url_authority_and_path="${database_url_authority_and_path#*@}"
          database_url_user="${database_url_userinfo%%:*}"
          case "$database_url_userinfo" in
            *:*) database_url_password="${database_url_userinfo#*:}" ;;
          esac
          ;;
      esac
      database_url_host_and_path="${database_url_authority_and_path#*@}"
      export DATABASE_JDBC_URL="jdbc:postgresql://${database_url_host_and_path}"
      ;;
    *) export DATABASE_JDBC_URL="jdbc:${DATABASE_URL}" ;;
  esac
fi

export DATABASE_USER="${DATABASE_USER:-${PGUSER:-${database_url_user:-stagepass}}}"
export DATABASE_PASSWORD="${DATABASE_PASSWORD:-${PGPASSWORD:-${database_url_password:-local-development-only}}}"
exec java -XX:MaxRAMPercentage=60 -jar /app/app.jar "$@"
