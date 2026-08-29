#!/bin/sh

set -eu

fail() {
  printf '%s\n' "Production configuration rejected: $1" >&2
  exit 1
}

reject_published_default() {
  variable_name="$1"
  value="$2"
  published_default="$3"

  case "$value" in
    *[![:space:]]*) ;;
    *) fail "$variable_name must not be blank" ;;
  esac
  [ "$value" != "$published_default" ] \
    || fail "$variable_name uses a published development default"
}

reject_published_default "POSTGRES_USER" "${POSTGRES_USER:-}" "vod_app"
reject_published_default "POSTGRES_PASSWORD" "${POSTGRES_PASSWORD:-}" "change-me"
reject_published_default "RABBITMQ_USERNAME" "${RABBITMQ_USERNAME:-}" "vod_app"
reject_published_default "RABBITMQ_PASSWORD" "${RABBITMQ_PASSWORD:-}" "change-me"
reject_published_default "MINIO_ACCESS_KEY" "${MINIO_ACCESS_KEY:-}" "vod_minio"
reject_published_default "MINIO_SECRET_KEY" "${MINIO_SECRET_KEY:-}" "change-me"
reject_published_default \
  "JWT_SECRET" \
  "${JWT_SECRET:-}" \
  "change-me-use-a-long-random-secret"

printf '%s\n' "Production credential preflight passed"
