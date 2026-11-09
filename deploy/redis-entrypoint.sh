#!/bin/sh
# Redis always requires a password (#106). The secret reaches redis-server as
# configuration on stdin (`-` as the last argument), never through argv, the
# Compose command or logs. Only the backend receives REDIS_PASSWORD.
set -eu
fail() { echo "Redis startup rejected: $1" >&2; exit 1; }
password="${REDIS_PASSWORD:-}"
case "$password" in
  *[![:space:]]*) ;;
  *) fail "REDIS_PASSWORD must not be blank" ;;
esac
# A Redis configuration line splits on whitespace and treats quotes specially.
case "$password" in
  *[[:space:]]*|*\"*|*\'*) fail "REDIS_PASSWORD must not contain whitespace or quotes" ;;
esac
exec docker-entrypoint.sh "$@" - <<EOF
requirepass $password
EOF
