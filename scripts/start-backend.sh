#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$project_root/.env" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ ! "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      echo '环境文件仅支持 KEY=value，不支持命令、export 或多行值。' >&2
      exit 1
    fi
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then value="${value:1:${#value}-2}"; fi
    if [[ -z "${!key:-}" ]]; then export "$key=$value"; fi
  done < "$project_root/.env"
fi
: "${MYSQL_PASSWORD:?请在 .env 或环境变量中设置 MYSQL_PASSWORD}"
: "${RABBITMQ_PASSWORD:?请在 .env 或环境变量中设置 RABBITMQ_PASSWORD}"
if [[ "${1:-}" == '--verify-database' ]]; then
  exec mvn -f "$project_root/backend/pom.xml" clean verify -Pmysql-it
fi
exec mvn -f "$project_root/backend/pom.xml" spring-boot:run
