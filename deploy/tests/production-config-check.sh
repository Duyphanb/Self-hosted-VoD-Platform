#!/bin/sh

set -eu

fail() {
  printf '%s\n' "Production configuration rejected: $1" >&2
  exit 1
}

# Go strings.TrimSpace's non-ASCII White_Space codepoints as exact UTF-8 bytes.
# Decode once; the ASCII spaces separate tokens for the loop below.
storage_unicode_whitespace=$(printf '%b' \
  '\0302\0205 \0302\0240 \0341\0232\0200 \0342\0200\0200 \0342\0200\0201 \0342\0200\0202 \0342\0200\0203 \0342\0200\0204 \0342\0200\0205 \0342\0200\0206 \0342\0200\0207 \0342\0200\0210 \0342\0200\0211 \0342\0200\0212 \0342\0200\0250 \0342\0200\0251 \0342\0200\0257 \0342\0201\0237 \0343\0200\0200')

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

validate_storage_access_key() {
  variable_name="$1"
  value="$2"
  [ "${#value}" -ge 3 ] || fail "$variable_name must have at least 3 characters"
  case "$value" in
    *=*|*,*|[[:space:]]*|*[[:space:]])
      fail "$variable_name must not contain = or , or leading/trailing whitespace" ;;
  esac
  # Silo hasSpaceBE uses Go strings.TrimSpace (Unicode White_Space), while
  # Alpine/C-locale [[:space:]] recognizes only the ASCII portion. Match exact
  # UTF-8 sequences at either edge without rejecting Unicode inside a key.
  for whitespace in $storage_unicode_whitespace
  do
    case "$value" in
      "$whitespace"*|*"$whitespace")
        fail "$variable_name must not contain = or , or leading/trailing whitespace" ;;
    esac
  done
}

validate_storage_secret() {
  [ "${#2}" -ge 8 ] || fail "$1 must have at least 8 characters"
}

# Same rule as deploy/postgres-init.sh, which quotes the names in role SQL.
validate_postgres_role() {
  case "$2" in
    pg_*|[!a-z_]*|*[!a-z0-9_]*)
      fail "$1 must be a lowercase PostgreSQL role name (a-z, 0-9, _), not starting with pg_" ;;
  esac
  [ "${#2}" -le 63 ] || fail "$1 must have at most 63 characters"
}

# Each named variable must differ from every variable named before it. The
# names are literals from this script; only names are ever reported.
require_independent() {
  earlier_names=""
  for current_name in "$@"
  do
    eval "current_value=\${$current_name:-}"
    for earlier_name in $earlier_names
    do
      eval "earlier_value=\${$earlier_name:-}"
      [ "$current_value" != "$earlier_value" ] \
        || fail "$current_name must not reuse $earlier_name"
    done
    earlier_names="$earlier_names $current_name"
  done
}

# #124: each JVM sizes its heap from its container memory limit and exits on
# OutOfMemoryError, so the restart policy replaces it instead of leaving a JVM
# that still passes the TCP health check. The overlay supplies the defaults; an
# override must keep both. An absolute heap, generation or RAM size, or disabled
# container support, would let the heap ignore the memory limit: HotSpot raises
# the maximum heap to a larger minimum, initial, new or old size. Those are
# rejected.
# The JVM applies options in order, so the last setting of each option decides,
# as it does here.
validate_jvm_options() {
  variable_name="$1"
  value="$2"
  case "$value" in
    *[\"\'\\]*) fail "$variable_name must list plain options without quotes or backslashes" ;;
  esac
  exit_on_out_of_memory=""
  max_ram_percentage=""
  set -f
  for option in $value
  do
    case "$option" in
      -XX:+ExitOnOutOfMemoryError) exit_on_out_of_memory="enabled" ;;
      -XX:-ExitOnOutOfMemoryError) exit_on_out_of_memory="" ;;
      -XX:MaxRAMPercentage=*) max_ram_percentage="${option#-XX:MaxRAMPercentage=}" ;;
      -Xmx*|-Xms*|-Xmn*|-XX:MaxHeapSize=*|-XX:InitialHeapSize=*|-XX:MinHeapSize=*|-XX:NewSize=*|-XX:OldSize=*|-XX:MaxRAM=*|-XX:-UseContainerSupport)
        fail "$variable_name must size the heap only with -XX:MaxRAMPercentage, without -Xmx, -Xms, -Xmn, -XX:MaxHeapSize, -XX:InitialHeapSize, -XX:MinHeapSize, -XX:NewSize, -XX:OldSize, -XX:MaxRAM or -XX:-UseContainerSupport" ;;
    esac
  done
  set +f
  [ -n "$exit_on_out_of_memory" ] || fail "$variable_name must enable -XX:+ExitOnOutOfMemoryError"
  case "$max_ram_percentage" in
    [1-9]|[1-9][0-9]|100) ;;
    *) fail "$variable_name must set -XX:MaxRAMPercentage to a whole number from 1 to 100" ;;
  esac
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
# Nginx is deliberately a frozen literal, not an env-templated config. Static
# tests compare this value with Nginx, Spring, Compose and the SPA defaults.
edge_upload_limit_mb=2048
[ "${MAX_UPLOAD_SIZE_MB-$edge_upload_limit_mb}" = "$edge_upload_limit_mb" ] \
  || fail "MAX_UPLOAD_SIZE_MB must equal the frozen Nginx upload limit ($edge_upload_limit_mb MiB)"
[ "${MINIO_BROWSER:-off}" = "off" ] || fail "MINIO_BROWSER must be off"

# Application identities must be independent from root and from each other.
reject_published_default "MINIO_BACKEND_ACCESS_KEY" "${MINIO_BACKEND_ACCESS_KEY:-}" "vod_backend"
reject_published_default "MINIO_BACKEND_SECRET_KEY" "${MINIO_BACKEND_SECRET_KEY:-}" "change-me-backend-only"
reject_published_default "MINIO_WORKER_ACCESS_KEY" "${MINIO_WORKER_ACCESS_KEY:-}" "vod_worker"
reject_published_default "MINIO_WORKER_SECRET_KEY" "${MINIO_WORKER_SECRET_KEY:-}" "change-me-worker-only"
validate_storage_access_key "MINIO_ACCESS_KEY" "$MINIO_ACCESS_KEY"
validate_storage_access_key "MINIO_BACKEND_ACCESS_KEY" "$MINIO_BACKEND_ACCESS_KEY"
validate_storage_access_key "MINIO_WORKER_ACCESS_KEY" "$MINIO_WORKER_ACCESS_KEY"
validate_storage_secret "MINIO_SECRET_KEY" "$MINIO_SECRET_KEY"
validate_storage_secret "MINIO_BACKEND_SECRET_KEY" "$MINIO_BACKEND_SECRET_KEY"
validate_storage_secret "MINIO_WORKER_SECRET_KEY" "$MINIO_WORKER_SECRET_KEY"
[ "$MINIO_BACKEND_ACCESS_KEY" != "$MINIO_ACCESS_KEY" ] || fail "Scoped storage credentials must be independent"
[ "$MINIO_WORKER_ACCESS_KEY" != "$MINIO_ACCESS_KEY" ] || fail "Scoped storage credentials must be independent"
[ "$MINIO_BACKEND_ACCESS_KEY" != "$MINIO_WORKER_ACCESS_KEY" ] || fail "Scoped storage credentials must be independent"
[ "$MINIO_BACKEND_SECRET_KEY" != "$MINIO_SECRET_KEY" ] || fail "Scoped storage credentials must be independent"
[ "$MINIO_WORKER_SECRET_KEY" != "$MINIO_SECRET_KEY" ] || fail "Scoped storage credentials must be independent"
[ "$MINIO_BACKEND_SECRET_KEY" != "$MINIO_WORKER_SECRET_KEY" ] || fail "Scoped storage credentials must be independent"

# #106: the schema owner, backend and worker identities are independent from the
# bootstrap superuser/administrator and from each other; Redis requires a password.
reject_published_default "POSTGRES_OWNER_USER" "${POSTGRES_OWNER_USER:-}" "vod_owner"
reject_published_default "POSTGRES_OWNER_PASSWORD" "${POSTGRES_OWNER_PASSWORD:-}" "change-me-db-owner"
reject_published_default "POSTGRES_BACKEND_USER" "${POSTGRES_BACKEND_USER:-}" "vod_backend"
reject_published_default "POSTGRES_BACKEND_PASSWORD" "${POSTGRES_BACKEND_PASSWORD:-}" "change-me-db-backend"
reject_published_default "POSTGRES_WORKER_USER" "${POSTGRES_WORKER_USER:-}" "vod_worker"
reject_published_default "POSTGRES_WORKER_PASSWORD" "${POSTGRES_WORKER_PASSWORD:-}" "change-me-db-worker"
reject_published_default "RABBITMQ_BACKEND_USERNAME" "${RABBITMQ_BACKEND_USERNAME:-}" "vod_backend"
reject_published_default "RABBITMQ_BACKEND_PASSWORD" "${RABBITMQ_BACKEND_PASSWORD:-}" "change-me-mq-backend"
reject_published_default "RABBITMQ_WORKER_USERNAME" "${RABBITMQ_WORKER_USERNAME:-}" "vod_worker"
reject_published_default "RABBITMQ_WORKER_PASSWORD" "${RABBITMQ_WORKER_PASSWORD:-}" "change-me-mq-worker"
reject_published_default "REDIS_PASSWORD" "${REDIS_PASSWORD:-}" "change-me-redis"
validate_postgres_role "POSTGRES_OWNER_USER" "$POSTGRES_OWNER_USER"
validate_postgres_role "POSTGRES_BACKEND_USER" "$POSTGRES_BACKEND_USER"
validate_postgres_role "POSTGRES_WORKER_USER" "$POSTGRES_WORKER_USER"
# deploy/redis-entrypoint.sh writes the password into a Redis configuration line.
case "$REDIS_PASSWORD" in
  *[[:space:]]*|*\"*|*\'*) fail "REDIS_PASSWORD must not contain whitespace or quotes" ;;
esac
require_independent POSTGRES_USER POSTGRES_OWNER_USER POSTGRES_BACKEND_USER POSTGRES_WORKER_USER
require_independent RABBITMQ_USERNAME RABBITMQ_BACKEND_USERNAME RABBITMQ_WORKER_USERNAME
# No secret may serve two identities, within or across services.
require_independent \
  POSTGRES_PASSWORD POSTGRES_OWNER_PASSWORD POSTGRES_BACKEND_PASSWORD POSTGRES_WORKER_PASSWORD \
  RABBITMQ_PASSWORD RABBITMQ_BACKEND_PASSWORD RABBITMQ_WORKER_PASSWORD \
  MINIO_SECRET_KEY MINIO_BACKEND_SECRET_KEY MINIO_WORKER_SECRET_KEY \
  REDIS_PASSWORD JWT_SECRET

# The applications must use the database that Flyway migrates (#106 comment).
[ -n "${FLYWAY_URL:-}" ] || fail "FLYWAY_URL must be set"
[ "${SPRING_DATASOURCE_URL:-}" = "$FLYWAY_URL" ] \
  || fail "SPRING_DATASOURCE_URL must equal the Flyway URL built from POSTGRES_HOST, POSTGRES_PORT and POSTGRES_DB"

validate_jvm_options "BACKEND_JAVA_TOOL_OPTIONS" "${BACKEND_JAVA_TOOL_OPTIONS:-}"
validate_jvm_options "WORKER_JAVA_TOOL_OPTIONS" "${WORKER_JAVA_TOOL_OPTIONS:-}"

printf '%s\n' "Production configuration preflight passed"
