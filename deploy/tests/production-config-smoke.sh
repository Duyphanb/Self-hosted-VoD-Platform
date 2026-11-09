#!/bin/sh

set -eu

repository_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
check_script="$repository_root/deploy/tests/production-config-check.sh"
temp_directory=$(mktemp -d)
log_file="$temp_directory/output.log"
env_file="$temp_directory/production.env"
config_json="$temp_directory/compose.json"
empty_env_file="$temp_directory/empty.env"
custom_db_env_file="$temp_directory/custom-db.env"
python_command=$(command -v python3 || command -v python)
run_token=$("$python_command" -c 'import secrets; print(secrets.token_hex(12))')
# Generate independent credentials once; repeated negative cases reuse this fixture.
# None of their entropy is exposed in the Docker project name.
credential_tokens=$("$python_command" -c 'import secrets; print(" ".join(secrets.token_hex(24) for _ in range(15)))')
read -r root_access_token root_secret_token backend_access_token backend_secret_token worker_access_token worker_secret_token postgres_secret_token rabbitmq_secret_token jwt_secret_token owner_db_token backend_db_token worker_db_token backend_mq_token worker_mq_token redis_token <<EOF
$credential_tokens
EOF
unset credential_tokens
compose_project="vod-auth-audit-config-$run_token"
# #124 overlay defaults. An empty override lets Compose apply them, as `${NAME:-default}` does.
backend_java_tool_options_default="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
worker_java_tool_options_default="-XX:MaxRAMPercentage=30 -XX:+ExitOnOutOfMemoryError"

# Every credential the production overlay requires. Each one fails Compose
# configuration when missing or empty, and none reaches argv: values are kept in
# value_<NAME> shell variables and exported only inside subshells.
credential_names="POSTGRES_USER POSTGRES_PASSWORD POSTGRES_OWNER_USER POSTGRES_OWNER_PASSWORD
POSTGRES_BACKEND_USER POSTGRES_BACKEND_PASSWORD POSTGRES_WORKER_USER POSTGRES_WORKER_PASSWORD
REDIS_PASSWORD RABBITMQ_USERNAME RABBITMQ_PASSWORD RABBITMQ_BACKEND_USERNAME RABBITMQ_BACKEND_PASSWORD
RABBITMQ_WORKER_USERNAME RABBITMQ_WORKER_PASSWORD MINIO_ACCESS_KEY MINIO_SECRET_KEY
MINIO_BACKEND_ACCESS_KEY MINIO_BACKEND_SECRET_KEY MINIO_WORKER_ACCESS_KEY MINIO_WORKER_SECRET_KEY JWT_SECRET"
required_names="$credential_names NGINX_HTTP_PORT"
setting_names="$required_names MAX_UPLOAD_SIZE_MB POSTGRES_DB BACKEND_JAVA_TOOL_OPTIONS WORKER_JAVA_TOOL_OPTIONS"

set_value() { eval "value_$1=\$2"; }
value_for() { eval "printf '%s' \"\${value_$1}\""; }

set_synthetic_values() {
  set_value POSTGRES_USER "ci_postgres_$run_token"
  set_value POSTGRES_PASSWORD "ci-postgres-$postgres_secret_token"
  set_value POSTGRES_OWNER_USER "ci_owner_$run_token"
  set_value POSTGRES_OWNER_PASSWORD "ci-db-owner-$owner_db_token"
  set_value POSTGRES_BACKEND_USER "ci_backend_$run_token"
  set_value POSTGRES_BACKEND_PASSWORD "ci-db-backend-$backend_db_token"
  set_value POSTGRES_WORKER_USER "ci_worker_$run_token"
  set_value POSTGRES_WORKER_PASSWORD "ci-db-worker-$worker_db_token"
  set_value REDIS_PASSWORD "ci-redis-$redis_token"
  set_value RABBITMQ_USERNAME "ci_rabbitmq_$run_token"
  set_value RABBITMQ_PASSWORD "ci-rabbitmq-$rabbitmq_secret_token"
  set_value RABBITMQ_BACKEND_USERNAME "ci_mq_backend_$run_token"
  set_value RABBITMQ_BACKEND_PASSWORD "ci-mq-backend-$backend_mq_token"
  set_value RABBITMQ_WORKER_USERNAME "ci_mq_worker_$run_token"
  set_value RABBITMQ_WORKER_PASSWORD "ci-mq-worker-$worker_mq_token"
  set_value MINIO_ACCESS_KEY "ci_root_$root_access_token"
  set_value MINIO_SECRET_KEY "ci-root-secret-$root_secret_token"
  set_value MINIO_BACKEND_ACCESS_KEY "ci_backend_$backend_access_token"
  set_value MINIO_BACKEND_SECRET_KEY "ci-backend-secret-$backend_secret_token"
  set_value MINIO_WORKER_ACCESS_KEY "ci_worker_$worker_access_token"
  set_value MINIO_WORKER_SECRET_KEY "ci-worker-secret-$worker_secret_token"
  set_value JWT_SECRET "ci-jwt-signing-secret-$jwt_secret_token"
  set_value NGINX_HTTP_PORT "80"
  set_value MAX_UPLOAD_SIZE_MB "2048"
  # A non-default database proves Flyway and the applications derive the same URL.
  set_value POSTGRES_DB "ci_platform_$run_token"
  set_value BACKEND_JAVA_TOOL_OPTIONS ""
  set_value WORKER_JAVA_TOOL_OPTIONS ""
  spring_datasource_url_value=""
}

set_published_defaults() {
  set_value POSTGRES_USER "vod_app"
  set_value POSTGRES_PASSWORD "change-me"
  set_value POSTGRES_OWNER_USER "vod_owner"
  set_value POSTGRES_OWNER_PASSWORD "change-me-db-owner"
  set_value POSTGRES_BACKEND_USER "vod_backend"
  set_value POSTGRES_BACKEND_PASSWORD "change-me-db-backend"
  set_value POSTGRES_WORKER_USER "vod_worker"
  set_value POSTGRES_WORKER_PASSWORD "change-me-db-worker"
  set_value REDIS_PASSWORD "change-me-redis"
  set_value RABBITMQ_USERNAME "vod_app"
  set_value RABBITMQ_PASSWORD "change-me"
  set_value RABBITMQ_BACKEND_USERNAME "vod_backend"
  set_value RABBITMQ_BACKEND_PASSWORD "change-me-mq-backend"
  set_value RABBITMQ_WORKER_USERNAME "vod_worker"
  set_value RABBITMQ_WORKER_PASSWORD "change-me-mq-worker"
  set_value MINIO_ACCESS_KEY "vod_minio"
  set_value MINIO_SECRET_KEY "change-me"
  set_value MINIO_BACKEND_ACCESS_KEY "vod_backend"
  set_value MINIO_BACKEND_SECRET_KEY "change-me-backend-only"
  set_value MINIO_WORKER_ACCESS_KEY "vod_worker"
  set_value MINIO_WORKER_SECRET_KEY "change-me-worker-only"
  set_value JWT_SECRET "change-me-use-a-long-random-secret"
  set_value NGINX_HTTP_PORT "127.0.0.1:80"
  set_value MAX_UPLOAD_SIZE_MB "2048"
  set_value POSTGRES_DB "vod_platform"
  set_value BACKEND_JAVA_TOOL_OPTIONS ""
  set_value WORKER_JAVA_TOOL_OPTIONS ""
  spring_datasource_url_value=""
}

# Exports the current fixture into the calling subshell only. An explicit
# SPRING_DATASOURCE_URL is exported only by the split-database case.
export_settings() {
  for setting_name in $setting_names
  do
    eval "$setting_name=\${value_$setting_name}"
    export "$setting_name"
  done
  if [ -n "$spring_datasource_url_value" ]; then
    SPRING_DATASOURCE_URL="$spring_datasource_url_value"
    export SPRING_DATASOURCE_URL
  else
    unset SPRING_DATASOURCE_URL
  fi
}

write_env_file() {
  omitted_variable="${1:-}"
  empty_variable="${2:-}"
  : > "$env_file"

  for setting_name in $setting_names
  do
    [ "$setting_name" = "$omitted_variable" ] && continue
    if [ "$setting_name" = "$empty_variable" ]; then
      printf '%s=\n' "$setting_name" >> "$env_file"
    else
      printf '%s=%s\n' "$setting_name" "$(value_for "$setting_name")" >> "$env_file"
    fi
  done
}

# The production overlay hands the check the same URL expressions as Flyway and
# the applications; this direct invocation mirrors them.
run_check() (
  export_settings
  FLYWAY_URL="jdbc:postgresql://postgres:5432/$POSTGRES_DB"
  SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-$FLYWAY_URL}"
  export FLYWAY_URL SPRING_DATASOURCE_URL
  BACKEND_JAVA_TOOL_OPTIONS="${BACKEND_JAVA_TOOL_OPTIONS:-$backend_java_tool_options_default}"
  WORKER_JAVA_TOOL_OPTIONS="${WORKER_JAVA_TOOL_OPTIONS:-$worker_java_tool_options_default}"
  sh "$check_script"
)

run_production_compose() (
  export_settings
  CORS_ALLOWED_ORIGINS=
  export CORS_ALLOWED_ORIGINS
  docker compose \
    -p "$compose_project" \
    -f deploy/docker-compose.yml \
    -f deploy/docker-compose.prod.yml \
    "$@"
)

run_production_compose_from_file() (
  for setting_name in $setting_names CORS_ALLOWED_ORIGINS SPRING_DATASOURCE_URL
  do
    unset "$setting_name"
  done
  docker compose \
    --env-file "$env_file" \
    -p "$compose_project" \
    -f deploy/docker-compose.yml \
    -f deploy/docker-compose.prod.yml \
    "$@"
)

cleanup() {
  set_synthetic_values
  run_production_compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  if [ -n "$temp_directory" ] && [ -d "$temp_directory" ]; then
    rm -rf -- "$temp_directory"
  fi
}

assert_log_contains() {
  expected_text="$1"
  if ! grep -Fq "$expected_text" "$log_file"; then
    printf '%s: %s\n' \
      "Expected production preflight failure was not observed" \
      "$expected_text" >&2
    exit 1
  fi
}

assert_log_omits() {
  variable_name="$1"
  leaked_value="$2"
  if grep -Fq -- "$leaked_value" "$log_file"; then
    printf '%s\n' "$variable_name value leaked into preflight output" >&2
    exit 1
  fi
}

expect_check_rejection() {
  failure_label="$1"
  expected_text="$2"
  : > "$log_file"
  if run_check > "$log_file" 2>&1; then
    printf '%s\n' "$failure_label was accepted" >&2
    exit 1
  fi
  assert_log_contains "Production configuration rejected: $expected_text"
}

expect_default_rejection() {
  variable_name="$1"
  published_default="$2"

  set_synthetic_values
  set_value "$variable_name" "$published_default"
  expect_check_rejection "$variable_name published default" \
    "$variable_name uses a published development default"
  assert_log_omits "$variable_name" "$published_default"
}

expect_edge_rejection() {
  nginx_http_port="$1"
  expected_reason="$2"

  set_synthetic_values
  set_value NGINX_HTTP_PORT "$nginx_http_port"
  expect_check_rejection "NGINX_HTTP_PORT '$nginx_http_port'" "NGINX_HTTP_PORT $expected_reason"
}

expect_format_rejection() {
  variable_name="$1"
  invalid_value="$2"
  expected_reason="$3"
  set_synthetic_values
  set_value "$variable_name" "$invalid_value"
  expect_check_rejection "$variable_name invalid format" "$variable_name $expected_reason"
  assert_log_omits "$variable_name" "$invalid_value"
}

assert_local_configuration() {
  expected_database="$1"
  "$python_command" - "$config_json" "$expected_database" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as config_file:
    config = json.load(config_file)
services = config["services"]

edge_ports = [(port.get("host_ip", ""), port["published"], port["target"]) for port in services["nginx"]["ports"]]
assert edge_ports == [("127.0.0.1", "80", 80)], edge_ports

# #106 comment: Flyway and the applications derive one URL from POSTGRES_HOST/PORT/DB.
flyway_url = services["flyway"]["environment"]["FLYWAY_URL"]
assert flyway_url == "jdbc:postgresql://postgres:5432/" + sys.argv[2], "Unexpected Flyway URL"
for service_name in ("backend", "worker"):
    assert services[service_name]["environment"]["SPRING_DATASOURCE_URL"] == flyway_url, service_name

# Local defaults keep the same identity split as production.
init = services["postgres-init"]["environment"]
assert services["flyway"]["environment"]["FLYWAY_USER"] == init["POSTGRES_OWNER_USER"], "Flyway must use the owner"
assert services["backend"]["environment"]["SPRING_DATASOURCE_USERNAME"] == init["POSTGRES_BACKEND_USER"], "backend role"
assert services["worker"]["environment"]["SPRING_DATASOURCE_USERNAME"] == init["POSTGRES_WORKER_USER"], "worker role"
broker = services["rabbitmq"]["environment"]
assert services["backend"]["environment"]["RABBITMQ_USERNAME"] == broker["RABBITMQ_BACKEND_USERNAME"], "backend broker user"
assert services["worker"]["environment"]["RABBITMQ_USERNAME"] == broker["RABBITMQ_WORKER_USERNAME"], "worker broker user"
assert services["backend"]["environment"]["REDIS_PASSWORD"] == services["redis"]["environment"]["REDIS_PASSWORD"], "redis"
assert "REDIS_PASSWORD" not in services["worker"]["environment"], "Worker must not receive the Redis password"
for bootstrap in ("POSTGRES_PASSWORD", "RABBITMQ_DEFAULT_PASS"):
    source = init if bootstrap == "POSTGRES_PASSWORD" else broker
    for service_name in ("backend", "worker", "flyway"):
        assert source[bootstrap] not in services[service_name]["environment"].values(), (service_name, bootstrap)
assert config["networks"]["vod_internal"].get("internal") is True, "Data network must be internal"
PY
}

trap cleanup EXIT

cd "$repository_root"

env -u NGINX_HTTP_PORT -u SPRING_DATASOURCE_URL -u POSTGRES_DB docker compose \
  --env-file .env.example \
  -f deploy/docker-compose.yml \
  config --format json > "$config_json"
assert_local_configuration vod_platform

: > "$empty_env_file"
env -u NGINX_HTTP_PORT -u SPRING_DATASOURCE_URL -u POSTGRES_DB docker compose \
  --env-file "$empty_env_file" \
  -f deploy/docker-compose.yml \
  config --format json > "$config_json"
assert_local_configuration vod_platform

printf 'POSTGRES_DB=ci_custom_%s\n' "$run_token" > "$custom_db_env_file"
env -u NGINX_HTTP_PORT -u SPRING_DATASOURCE_URL -u POSTGRES_DB docker compose \
  --env-file "$custom_db_env_file" \
  -f deploy/docker-compose.yml \
  config --format json > "$config_json"
assert_local_configuration "ci_custom_$run_token"
printf '%s\n' "Local Compose configuration passed"

set_synthetic_values
run_check >/dev/null

expect_default_rejection "POSTGRES_USER" "vod_app"
expect_default_rejection "POSTGRES_PASSWORD" "change-me"
expect_default_rejection "POSTGRES_OWNER_USER" "vod_owner"
expect_default_rejection "POSTGRES_OWNER_PASSWORD" "change-me-db-owner"
expect_default_rejection "POSTGRES_BACKEND_USER" "vod_backend"
expect_default_rejection "POSTGRES_BACKEND_PASSWORD" "change-me-db-backend"
expect_default_rejection "POSTGRES_WORKER_USER" "vod_worker"
expect_default_rejection "POSTGRES_WORKER_PASSWORD" "change-me-db-worker"
expect_default_rejection "REDIS_PASSWORD" "change-me-redis"
expect_default_rejection "RABBITMQ_USERNAME" "vod_app"
expect_default_rejection "RABBITMQ_PASSWORD" "change-me"
expect_default_rejection "RABBITMQ_BACKEND_USERNAME" "vod_backend"
expect_default_rejection "RABBITMQ_BACKEND_PASSWORD" "change-me-mq-backend"
expect_default_rejection "RABBITMQ_WORKER_USERNAME" "vod_worker"
expect_default_rejection "RABBITMQ_WORKER_PASSWORD" "change-me-mq-worker"
expect_default_rejection "MINIO_ACCESS_KEY" "vod_minio"
expect_default_rejection "MINIO_SECRET_KEY" "change-me"
expect_default_rejection "MINIO_BACKEND_ACCESS_KEY" "vod_backend"
expect_default_rejection "MINIO_BACKEND_SECRET_KEY" "change-me-backend-only"
expect_default_rejection "MINIO_WORKER_ACCESS_KEY" "vod_worker"
expect_default_rejection "MINIO_WORKER_SECRET_KEY" "change-me-worker-only"
expect_default_rejection "JWT_SECRET" "change-me-use-a-long-random-secret"
printf '%s\n' "Published-default rejection checks passed"

for collision in backend-root-user worker-root-user shared-user backend-root-secret worker-root-secret shared-secret
do
  set_synthetic_values
  case "$collision" in
    backend-root-user) set_value MINIO_BACKEND_ACCESS_KEY "$(value_for MINIO_ACCESS_KEY)" ;;
    worker-root-user) set_value MINIO_WORKER_ACCESS_KEY "$(value_for MINIO_ACCESS_KEY)" ;;
    shared-user) set_value MINIO_WORKER_ACCESS_KEY "$(value_for MINIO_BACKEND_ACCESS_KEY)" ;;
    backend-root-secret) set_value MINIO_BACKEND_SECRET_KEY "$(value_for MINIO_SECRET_KEY)" ;;
    worker-root-secret) set_value MINIO_WORKER_SECRET_KEY "$(value_for MINIO_SECRET_KEY)" ;;
    shared-secret) set_value MINIO_WORKER_SECRET_KEY "$(value_for MINIO_BACKEND_SECRET_KEY)" ;;
  esac
  expect_check_rejection "Reused storage identity/secret" "Scoped storage credentials must be independent"
done
printf '%s\n' "Independent storage identity checks passed"

# #106: application identities never reuse the bootstrap superuser/administrator,
# each other, or any other production secret.
for collision in \
  POSTGRES_OWNER_USER:POSTGRES_USER POSTGRES_BACKEND_USER:POSTGRES_USER \
  POSTGRES_WORKER_USER:POSTGRES_USER POSTGRES_BACKEND_USER:POSTGRES_OWNER_USER \
  POSTGRES_WORKER_USER:POSTGRES_BACKEND_USER RABBITMQ_BACKEND_USERNAME:RABBITMQ_USERNAME \
  RABBITMQ_WORKER_USERNAME:RABBITMQ_USERNAME RABBITMQ_WORKER_USERNAME:RABBITMQ_BACKEND_USERNAME \
  POSTGRES_OWNER_PASSWORD:POSTGRES_PASSWORD POSTGRES_BACKEND_PASSWORD:POSTGRES_PASSWORD \
  POSTGRES_WORKER_PASSWORD:POSTGRES_PASSWORD POSTGRES_WORKER_PASSWORD:POSTGRES_BACKEND_PASSWORD \
  RABBITMQ_BACKEND_PASSWORD:RABBITMQ_PASSWORD RABBITMQ_WORKER_PASSWORD:RABBITMQ_PASSWORD \
  RABBITMQ_WORKER_PASSWORD:POSTGRES_WORKER_PASSWORD REDIS_PASSWORD:POSTGRES_BACKEND_PASSWORD \
  JWT_SECRET:REDIS_PASSWORD
do
  later_name=${collision%%:*}
  earlier_name=${collision#*:}
  set_synthetic_values
  set_value "$later_name" "$(value_for "$earlier_name")"
  expect_check_rejection "$later_name reusing $earlier_name" "$later_name must not reuse $earlier_name"
done
printf '%s\n' "Independent service identity checks passed"

for variable_name in MINIO_ACCESS_KEY MINIO_BACKEND_ACCESS_KEY MINIO_WORKER_ACCESS_KEY
do
  expect_format_rejection "$variable_name" "xy" "must have at least 3 characters"
  for invalid_key in 'bad=key' 'bad,key' ' leading-key' 'trailing-key ' "$(printf '\tleading-key')" "$(printf 'trailing-key\t')" \
    "$(printf '%b' '\0302\0240')leading-key" "trailing-key$(printf '%b' '\0302\0240')" \
    "$(printf '%b' '\0342\0200\0203')leading-key" "trailing-key$(printf '%b' '\0342\0200\0203')"
  do
    expect_format_rejection "$variable_name" "$invalid_key" \
      "must not contain = or , or leading/trailing whitespace"
  done
done
for variable_name in MINIO_SECRET_KEY MINIO_BACKEND_SECRET_KEY MINIO_WORKER_SECRET_KEY
do
  expect_format_rejection "$variable_name" "short7x" "must have at least 8 characters"
done
for variable_name in POSTGRES_OWNER_USER POSTGRES_BACKEND_USER POSTGRES_WORKER_USER
do
  for invalid_role in 'Mixed_case' 'has-dash' '9role' 'pg_reserved'
  do
    expect_format_rejection "$variable_name" "$invalid_role" \
      "must be a lowercase PostgreSQL role name (a-z, 0-9, _), not starting with pg_"
  done
done
for invalid_redis_password in 'redis secret' 'redis"secret' "redis'secret"
do
  expect_format_rejection REDIS_PASSWORD "$invalid_redis_password" "must not contain whitespace or quotes"
done
printf '%s\n' "Credential format checks passed"


expect_edge_rejection "" "must be set explicitly"
expect_edge_rejection "127.0.0.1:80" "must not bind the production edge to loopback"
expect_edge_rejection "127.0.0.1:18087" "must not bind the production edge to loopback"
expect_edge_rejection "[::1]:80" "must be a port or IPv4:port"
expect_edge_rejection "[0:0:0:0:0:0:0:1]:80" "must be a port or IPv4:port"
expect_edge_rejection "::1:80" "must be a port or IPv4:port"
expect_edge_rejection "localhost:80" "must be a port or IPv4:port"
for nginx_http_port in 80 0.0.0.0:80
do
  set_synthetic_values
  set_value NGINX_HTTP_PORT "$nginx_http_port"
  run_check >/dev/null
done
printf '%s\n' "Production edge publication checks passed"

set_synthetic_values
set_value MAX_UPLOAD_SIZE_MB "1024"
expect_check_rejection "An upload limit different from Nginx" "MAX_UPLOAD_SIZE_MB must equal the frozen Nginx upload limit"
if run_production_compose run --rm --no-deps production-config-check > "$log_file" 2>&1; then
  printf '%s\n' "Production Compose preflight accepted an upload limit different from Nginx" >&2
  exit 1
fi
assert_log_contains "MAX_UPLOAD_SIZE_MB must equal the frozen Nginx upload limit"
printf '%s\n' "Production upload-limit equality rejection checks passed"

# #106 comment: an explicit URL that differs from Flyway's would split the stack.
set_synthetic_values
spring_datasource_url_value="jdbc:postgresql://postgres:5432/vod_platform"
expect_check_rejection "A datasource URL different from Flyway" "SPRING_DATASOURCE_URL must equal the Flyway URL"
if run_production_compose run --rm --no-deps production-config-check > "$log_file" 2>&1; then
  printf '%s\n' "Production Compose preflight accepted a datasource URL different from Flyway" >&2
  exit 1
fi
assert_log_contains "SPRING_DATASOURCE_URL must equal the Flyway URL"
printf '%s\n' "Production datasource URL alignment rejection checks passed"

set_synthetic_values
if MINIO_BROWSER=on run_check > "$log_file" 2>&1; then
  printf '%s\n' "Production console was accepted" >&2
  exit 1
fi
assert_log_contains "Production configuration rejected: MINIO_BROWSER must be off"
printf '%s\n' "Production console rejection check passed"

for variable_name in $required_names
do
  set_synthetic_values
  write_env_file "$variable_name" ""
  : > "$log_file"
  if run_production_compose_from_file config --quiet > "$log_file" 2>&1; then
    printf '%s\n' "Production Compose accepted missing $variable_name" >&2
    exit 1
  fi
  assert_log_contains "$variable_name"

  write_env_file "" "$variable_name"
  : > "$log_file"
  if run_production_compose_from_file config --quiet > "$log_file" 2>&1; then
    printf '%s\n' "Production Compose accepted empty $variable_name" >&2
    exit 1
  fi
  assert_log_contains "$variable_name"
done
printf '%s\n' "Missing and empty production-variable checks passed"

set_published_defaults
: > "$log_file"
if run_production_compose run --rm --no-deps production-config-check \
  > "$log_file" 2>&1
then
  printf '%s\n' "Production preflight accepted published defaults" >&2
  exit 1
fi
assert_log_contains \
  "Production configuration rejected: POSTGRES_USER uses a published development default"

set_synthetic_values
set_value NGINX_HTTP_PORT "127.0.0.1:80"
: > "$log_file"
if run_production_compose run --rm --no-deps production-config-check \
  > "$log_file" 2>&1
then
  printf '%s\n' "Production preflight accepted a loopback-bound edge" >&2
  exit 1
fi
assert_log_contains \
  "Production configuration rejected: NGINX_HTTP_PORT must not bind the production edge to loopback"

set_published_defaults
: > "$log_file"
if run_production_compose up --no-build --wait --wait-timeout 30 \
  postgres redis rabbitmq minio > "$log_file" 2>&1
then
  printf '%s\n' "Infrastructure started after a failed production preflight" >&2
  exit 1
fi

# `up --wait` reports only the dependency exit code, not the service's diagnostic.
run_production_compose logs --no-color production-config-check >> "$log_file" 2>&1
assert_log_contains "Production configuration rejected"
for service_name in postgres redis rabbitmq minio
do
  if [ -n "$(run_production_compose ps --status running -q "$service_name")" ]; then
    printf '%s\n' "$service_name started before the production preflight passed" >&2
    exit 1
  fi
done
printf '%s\n' "Production dependency gate check passed"

# #124: an override reaches both the preflight and the JVM, and the preflight
# rejects one that drops the OutOfMemoryError exit.
set_synthetic_values
set_value WORKER_JAVA_TOOL_OPTIONS "-XX:MaxRAMPercentage=25 -XX:+ExitOnOutOfMemoryError"
run_production_compose config --format json > "$config_json"
"$python_command" - "$config_json" "$(value_for WORKER_JAVA_TOOL_OPTIONS)" "$backend_java_tool_options_default" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as config_file:
    services = json.load(config_file)["services"]
worker_options, backend_options = sys.argv[2], sys.argv[3]
assert services["worker"]["environment"]["JAVA_TOOL_OPTIONS"] == worker_options
assert services["production-config-check"]["environment"]["WORKER_JAVA_TOOL_OPTIONS"] == worker_options
assert services["backend"]["environment"]["JAVA_TOOL_OPTIONS"] == backend_options
assert services["production-config-check"]["environment"]["BACKEND_JAVA_TOOL_OPTIONS"] == backend_options
PY
set_value WORKER_JAVA_TOOL_OPTIONS "-XX:MaxRAMPercentage=30"
: > "$log_file"
if run_production_compose run --rm --no-deps production-config-check \
  > "$log_file" 2>&1
then
  printf '%s\n' "Production preflight accepted worker JVM options without the OutOfMemoryError exit" >&2
  exit 1
fi
assert_log_contains \
  "Production configuration rejected: WORKER_JAVA_TOOL_OPTIONS must enable -XX:+ExitOnOutOfMemoryError"
printf '%s\n' "Production JVM option checks passed"

set_synthetic_values
run_production_compose config --format json > "$config_json"
write_env_file
"$python_command" - "$config_json" "$env_file" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as config_file:
    config = json.load(config_file)
services = config["services"]

with open(sys.argv[2], encoding="utf-8") as credentials_file:
    values = dict(line.rstrip("\n").split("=", 1) for line in credentials_file)

database_url = "jdbc:postgresql://postgres:5432/" + values["POSTGRES_DB"]
preflight = {name: values[name] for name in values if name != "POSTGRES_DB"}
preflight.update({
    "FLYWAY_URL": database_url,
    "SPRING_DATASOURCE_URL": database_url,
    "BACKEND_JAVA_TOOL_OPTIONS": "-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError",
    "WORKER_JAVA_TOOL_OPTIONS": "-XX:MaxRAMPercentage=30 -XX:+ExitOnOutOfMemoryError",
})
postgres_roles = {name: values[name] for name in (
    "POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_OWNER_USER", "POSTGRES_OWNER_PASSWORD", "POSTGRES_BACKEND_USER",
    "POSTGRES_BACKEND_PASSWORD", "POSTGRES_WORKER_USER", "POSTGRES_WORKER_PASSWORD", "POSTGRES_DB")}
broker_users = {name: values[name] for name in (
    "RABBITMQ_BACKEND_USERNAME", "RABBITMQ_BACKEND_PASSWORD", "RABBITMQ_WORKER_USERNAME", "RABBITMQ_WORKER_PASSWORD")}
expected = {
    "production-config-check": preflight,
    "postgres": {"POSTGRES_USER": values["POSTGRES_USER"], "POSTGRES_PASSWORD": values["POSTGRES_PASSWORD"],
                 "POSTGRES_DB": values["POSTGRES_DB"]},
    "postgres-init": postgres_roles,
    "flyway": {"FLYWAY_URL": database_url, "FLYWAY_USER": values["POSTGRES_OWNER_USER"],
               "FLYWAY_PASSWORD": values["POSTGRES_OWNER_PASSWORD"],
               "FLYWAY_PLACEHOLDERS_BACKEND_ROLE": values["POSTGRES_BACKEND_USER"],
               "FLYWAY_PLACEHOLDERS_WORKER_ROLE": values["POSTGRES_WORKER_USER"]},
    "redis": {"REDIS_PASSWORD": values["REDIS_PASSWORD"]},
    "rabbitmq": dict(broker_users, RABBITMQ_DEFAULT_USER=values["RABBITMQ_USERNAME"],
                     RABBITMQ_DEFAULT_PASS=values["RABBITMQ_PASSWORD"]),
    "minio": {"MINIO_ROOT_USER": values["MINIO_ACCESS_KEY"], "MINIO_ROOT_PASSWORD": values["MINIO_SECRET_KEY"]},
    "minio-init": {"MINIO_ACCESS_KEY": values["MINIO_ACCESS_KEY"], "MINIO_SECRET_KEY": values["MINIO_SECRET_KEY"], "MINIO_BACKEND_ACCESS_KEY": values["MINIO_BACKEND_ACCESS_KEY"], "MINIO_BACKEND_SECRET_KEY": values["MINIO_BACKEND_SECRET_KEY"], "MINIO_WORKER_ACCESS_KEY": values["MINIO_WORKER_ACCESS_KEY"], "MINIO_WORKER_SECRET_KEY": values["MINIO_WORKER_SECRET_KEY"]},
    "backend": {
        "SPRING_DATASOURCE_URL": database_url,
        "SPRING_DATASOURCE_USERNAME": values["POSTGRES_BACKEND_USER"],
        "SPRING_DATASOURCE_PASSWORD": values["POSTGRES_BACKEND_PASSWORD"],
        "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate",
        "MAX_UPLOAD_SIZE_MB": "2048",
        "JWT_SECRET": values["JWT_SECRET"],
        "CORS_ALLOWED_ORIGINS": "",
        "REDIS_PASSWORD": values["REDIS_PASSWORD"],
        "RABBITMQ_USERNAME": values["RABBITMQ_BACKEND_USERNAME"],
        "RABBITMQ_PASSWORD": values["RABBITMQ_BACKEND_PASSWORD"],
        "MINIO_ACCESS_KEY": values["MINIO_BACKEND_ACCESS_KEY"],
        "MINIO_SECRET_KEY": values["MINIO_BACKEND_SECRET_KEY"],
        "JAVA_TOOL_OPTIONS": "-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError",
    },
    "worker": {
        "SPRING_DATASOURCE_URL": database_url,
        "SPRING_DATASOURCE_USERNAME": values["POSTGRES_WORKER_USER"],
        "SPRING_DATASOURCE_PASSWORD": values["POSTGRES_WORKER_PASSWORD"],
        "RABBITMQ_USERNAME": values["RABBITMQ_WORKER_USERNAME"],
        "RABBITMQ_PASSWORD": values["RABBITMQ_WORKER_PASSWORD"],
        "MINIO_ACCESS_KEY": values["MINIO_WORKER_ACCESS_KEY"],
        "MINIO_SECRET_KEY": values["MINIO_WORKER_SECRET_KEY"],
        "JAVA_TOOL_OPTIONS": "-XX:MaxRAMPercentage=30 -XX:+ExitOnOutOfMemoryError",
    },
}

for service_name, environment in expected.items():
    actual_environment = services[service_name]["environment"]
    for variable_name, expected_value in environment.items():
        assert actual_environment[variable_name] == expected_value, (service_name, variable_name)

# #106 comment: the applications use exactly the database Flyway migrates.
for service_name in ("backend", "worker"):
    assert services[service_name]["environment"]["SPRING_DATASOURCE_URL"] == services["flyway"]["environment"]["FLYWAY_URL"], service_name

# #106: each service receives only its own secrets, never a bootstrap/root or sibling secret.
secret_names = ("POSTGRES_PASSWORD", "POSTGRES_OWNER_PASSWORD", "POSTGRES_BACKEND_PASSWORD", "POSTGRES_WORKER_PASSWORD",
                "REDIS_PASSWORD", "RABBITMQ_PASSWORD", "RABBITMQ_BACKEND_PASSWORD", "RABBITMQ_WORKER_PASSWORD",
                "MINIO_SECRET_KEY", "MINIO_BACKEND_SECRET_KEY", "MINIO_WORKER_SECRET_KEY", "JWT_SECRET")
allowed_secrets = {
    "postgres": {"POSTGRES_PASSWORD"},
    "postgres-init": {"POSTGRES_PASSWORD", "POSTGRES_OWNER_PASSWORD", "POSTGRES_BACKEND_PASSWORD", "POSTGRES_WORKER_PASSWORD"},
    "flyway": {"POSTGRES_OWNER_PASSWORD"},
    "redis": {"REDIS_PASSWORD"},
    "rabbitmq": {"RABBITMQ_PASSWORD", "RABBITMQ_BACKEND_PASSWORD", "RABBITMQ_WORKER_PASSWORD"},
    "minio": {"MINIO_SECRET_KEY"},
    "minio-init": {"MINIO_SECRET_KEY", "MINIO_BACKEND_SECRET_KEY", "MINIO_WORKER_SECRET_KEY"},
    "backend": {"POSTGRES_BACKEND_PASSWORD", "REDIS_PASSWORD", "RABBITMQ_BACKEND_PASSWORD", "MINIO_BACKEND_SECRET_KEY",
                "JWT_SECRET"},
    "worker": {"POSTGRES_WORKER_PASSWORD", "RABBITMQ_WORKER_PASSWORD", "MINIO_WORKER_SECRET_KEY"},
    "frontend": set(),
    "nginx": set(),
    "production-config-check": set(secret_names),
}
assert set(allowed_secrets) == set(services), "Every service needs an explicit secret allowance"
for service_name, allowed in allowed_secrets.items():
    configured = set((services[service_name].get("environment") or {}).values())
    for secret_name in secret_names:
        if secret_name not in allowed:
            assert values[secret_name] not in configured, (service_name, secret_name)

# #106: Nginx and the frontend share only the edge network; the data stores, init jobs and
# worker sit only on the internal network, which has no outside route; the backend joins both.
networks = config["networks"]
assert networks["vod_internal"].get("internal") is True, "Data network must be internal"
assert not networks["vod_edge"].get("internal"), "Edge network must publish Nginx"
for service_name, service in services.items():
    attached = set(service.get("networks") or {})
    if service_name == "production-config-check":
        assert service.get("network_mode") == "none" and not attached, service_name
    elif service_name in ("nginx", "frontend"):
        assert attached == {"vod_edge"}, service_name
    elif service_name == "backend":
        assert attached == {"vod_edge", "vod_internal"}, service_name
    else:
        assert attached == {"vod_internal"}, service_name
        assert not service.get("ports"), service_name

for service_name in ("postgres", "postgres-init", "redis", "rabbitmq", "minio", "flyway", "minio-init", "nginx"):
    dependency = services[service_name]["depends_on"]["production-config-check"]
    assert dependency["condition"] == "service_completed_successfully", service_name
assert services["flyway"]["depends_on"]["postgres-init"]["condition"] == "service_completed_successfully", "flyway"

edge_ports = sorted(
    (port.get("host_ip", ""), port["published"], port["target"])
    for port in services["nginx"]["ports"]
)
assert edge_ports == [("", "443", 443), ("", "80", 80)], edge_ports

# Same assertions as the runtime smoke; config is never printed, as it contains credentials.
import runpy
runpy.run_path("deploy/tests/storage-runtime-smoke.py")["validate_config"](services)
PY
printf '%s\n' "Canonical credential alignment checks passed"

run_production_compose run --rm --no-deps production-config-check >/dev/null

printf '%s\n' "Production configuration smoke checks passed"
