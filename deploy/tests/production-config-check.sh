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

# The local default publishes the edge on loopback only. Production must opt in
# explicitly, or the VPS edge would be unreachable.
reject_unpublished_edge() {
  value="$1"

  case "$value" in
    *[![:space:]]*) ;;
    *) fail "NGINX_HTTP_PORT must be set explicitly, for example to 80" ;;
  esac
  # Accept only PORT or IPv4:PORT. Hostnames and IPv6 in any spelling are
  # rejected, so loopback cannot pass through an alternative notation.
  case "$value" in
    *[!0-9.:]*|*:*:*|:*|*:)
      fail "NGINX_HTTP_PORT must be a port or IPv4:port, for example 80" ;;
  esac
  case "$value" in
    127.*)
      fail "NGINX_HTTP_PORT must not bind the production edge to loopback" ;;
  esac
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
reject_unpublished_edge "${NGINX_HTTP_PORT:-}"
[ "${MINIO_BROWSER:-off}" = "off" ] || fail "MINIO_BROWSER must be off"

# Application identities must be independent from root and from each other.
reject_published_default "MINIO_BACKEND_ACCESS_KEY" "${MINIO_BACKEND_ACCESS_KEY:-}" "vod_backend"
reject_published_default "MINIO_BACKEND_SECRET_KEY" "${MINIO_BACKEND_SECRET_KEY:-}" "change-me-backend-only"
reject_published_default "MINIO_WORKER_ACCESS_KEY" "${MINIO_WORKER_ACCESS_KEY:-}" "vod_worker"
reject_published_default "MINIO_WORKER_SECRET_KEY" "${MINIO_WORKER_SECRET_KEY:-}" "change-me-worker-only"
[ "$MINIO_BACKEND_ACCESS_KEY" != "$MINIO_ACCESS_KEY" ] || fail "Scoped storage credentials must be independent"
[ "$MINIO_WORKER_ACCESS_KEY" != "$MINIO_ACCESS_KEY" ] || fail "Scoped storage credentials must be independent"
[ "$MINIO_BACKEND_ACCESS_KEY" != "$MINIO_WORKER_ACCESS_KEY" ] || fail "Scoped storage credentials must be independent"
[ "$MINIO_BACKEND_SECRET_KEY" != "$MINIO_SECRET_KEY" ] || fail "Scoped storage credentials must be independent"
[ "$MINIO_WORKER_SECRET_KEY" != "$MINIO_SECRET_KEY" ] || fail "Scoped storage credentials must be independent"
[ "$MINIO_BACKEND_SECRET_KEY" != "$MINIO_WORKER_SECRET_KEY" ] || fail "Scoped storage credentials must be independent"

printf '%s\n' "Production configuration preflight passed"
