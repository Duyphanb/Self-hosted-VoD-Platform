#!/bin/sh

set -eu

repository_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
check_script="$repository_root/deploy/tests/production-config-check.sh"
temp_directory=$(mktemp -d)
log_file="$temp_directory/output.log"
env_file="$temp_directory/production.env"
config_json="$temp_directory/compose.json"
compose_project="vod-production-config-test-$$"

set_synthetic_values() {
  postgres_user_value="ci_postgres_user"
  postgres_password_value="ci-postgres-password-46"
  rabbitmq_username_value="ci_rabbitmq_user"
  rabbitmq_password_value="ci-rabbitmq-password-46"
  minio_access_key_value="ci_minio_access_key"
  minio_secret_key_value="ci-minio-secret-key-46"
  jwt_secret_value="ci-jwt-signing-secret-with-at-least-thirty-two-bytes"
}

set_published_defaults() {
  postgres_user_value="vod_app"
  postgres_password_value="change-me"
  rabbitmq_username_value="vod_app"
  rabbitmq_password_value="change-me"
  minio_access_key_value="vod_minio"
  minio_secret_key_value="change-me"
  jwt_secret_value="change-me-use-a-long-random-secret"
}

value_for() {
  case "$1" in
    POSTGRES_USER) printf '%s' "$postgres_user_value" ;;
    POSTGRES_PASSWORD) printf '%s' "$postgres_password_value" ;;
    RABBITMQ_USERNAME) printf '%s' "$rabbitmq_username_value" ;;
    RABBITMQ_PASSWORD) printf '%s' "$rabbitmq_password_value" ;;
    MINIO_ACCESS_KEY) printf '%s' "$minio_access_key_value" ;;
    MINIO_SECRET_KEY) printf '%s' "$minio_secret_key_value" ;;
    JWT_SECRET) printf '%s' "$jwt_secret_value" ;;
    *) printf '%s\n' "Unknown test variable: $1" >&2; exit 1 ;;
  esac
}

write_env_file() {
  omitted_variable="${1:-}"
  empty_variable="${2:-}"
  : > "$env_file"

  for credential_name in \
    POSTGRES_USER \
    POSTGRES_PASSWORD \
    RABBITMQ_USERNAME \
    RABBITMQ_PASSWORD \
    MINIO_ACCESS_KEY \
    MINIO_SECRET_KEY \
    JWT_SECRET
  do
    [ "$credential_name" = "$omitted_variable" ] && continue
    if [ "$credential_name" = "$empty_variable" ]; then
      printf '%s=\n' "$credential_name" >> "$env_file"
    else
      printf '%s=%s\n' "$credential_name" "$(value_for "$credential_name")" >> "$env_file"
    fi
  done
}

run_check() {
  POSTGRES_USER="$postgres_user_value" \
  POSTGRES_PASSWORD="$postgres_password_value" \
  RABBITMQ_USERNAME="$rabbitmq_username_value" \
  RABBITMQ_PASSWORD="$rabbitmq_password_value" \
  MINIO_ACCESS_KEY="$minio_access_key_value" \
  MINIO_SECRET_KEY="$minio_secret_key_value" \
  JWT_SECRET="$jwt_secret_value" \
    sh "$check_script"
}

run_production_compose() {
  POSTGRES_USER="$postgres_user_value" \
  POSTGRES_PASSWORD="$postgres_password_value" \
  RABBITMQ_USERNAME="$rabbitmq_username_value" \
  RABBITMQ_PASSWORD="$rabbitmq_password_value" \
  MINIO_ACCESS_KEY="$minio_access_key_value" \
  MINIO_SECRET_KEY="$minio_secret_key_value" \
  JWT_SECRET="$jwt_secret_value" \
    docker compose \
      -p "$compose_project" \
      -f deploy/docker-compose.yml \
      -f deploy/docker-compose.prod.yml \
      "$@"
}

run_production_compose_from_file() {
  env \
    -u POSTGRES_USER \
    -u POSTGRES_PASSWORD \
    -u RABBITMQ_USERNAME \
    -u RABBITMQ_PASSWORD \
    -u MINIO_ACCESS_KEY \
    -u MINIO_SECRET_KEY \
    -u JWT_SECRET \
    docker compose \
      --env-file "$env_file" \
      -p "$compose_project" \
      -f deploy/docker-compose.yml \
      -f deploy/docker-compose.prod.yml \
      "$@"
}

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

expect_default_rejection() {
  variable_name="$1"
  published_default="$2"

  set_synthetic_values
  case "$variable_name" in
    POSTGRES_USER) postgres_user_value="$published_default" ;;
    POSTGRES_PASSWORD) postgres_password_value="$published_default" ;;
    RABBITMQ_USERNAME) rabbitmq_username_value="$published_default" ;;
    RABBITMQ_PASSWORD) rabbitmq_password_value="$published_default" ;;
    MINIO_ACCESS_KEY) minio_access_key_value="$published_default" ;;
    MINIO_SECRET_KEY) minio_secret_key_value="$published_default" ;;
    JWT_SECRET) jwt_secret_value="$published_default" ;;
  esac

  : > "$log_file"
  if run_check > "$log_file" 2>&1; then
    printf '%s\n' "$variable_name published default was accepted" >&2
    exit 1
  fi

  assert_log_contains \
    "Production configuration rejected: $variable_name uses a published development default"
  if grep -Fq "$published_default" "$log_file"; then
    printf '%s\n' "$variable_name value leaked into preflight output" >&2
    exit 1
  fi
}

trap cleanup EXIT

cd "$repository_root"

docker compose \
  --env-file .env.example \
  -f deploy/docker-compose.yml \
  config --quiet
printf '%s\n' "Local Compose configuration passed"

set_synthetic_values
run_check >/dev/null

expect_default_rejection "POSTGRES_USER" "vod_app"
expect_default_rejection "POSTGRES_PASSWORD" "change-me"
expect_default_rejection "RABBITMQ_USERNAME" "vod_app"
expect_default_rejection "RABBITMQ_PASSWORD" "change-me"
expect_default_rejection "MINIO_ACCESS_KEY" "vod_minio"
expect_default_rejection "MINIO_SECRET_KEY" "change-me"
expect_default_rejection "JWT_SECRET" "change-me-use-a-long-random-secret"
printf '%s\n' "Published-default rejection checks passed"

for variable_name in \
  POSTGRES_USER \
  POSTGRES_PASSWORD \
  RABBITMQ_USERNAME \
  RABBITMQ_PASSWORD \
  MINIO_ACCESS_KEY \
  MINIO_SECRET_KEY \
  JWT_SECRET
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

: > "$log_file"
if run_production_compose up --no-build --wait --wait-timeout 30 \
  postgres rabbitmq minio > "$log_file" 2>&1
then
  printf '%s\n' "Infrastructure started after a failed production preflight" >&2
  exit 1
fi

for service_name in postgres rabbitmq minio
do
  if [ -n "$(run_production_compose ps --status running -q "$service_name")" ]; then
    printf '%s\n' "$service_name started before the production preflight passed" >&2
    exit 1
  fi
done
printf '%s\n' "Production dependency gate check passed"

set_synthetic_values
run_production_compose config --format json > "$config_json"
python3 - "$config_json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as config_file:
    services = json.load(config_file)["services"]

expected = {
    "production-config-check": {
        "POSTGRES_USER": "ci_postgres_user",
        "POSTGRES_PASSWORD": "ci-postgres-password-46",
        "RABBITMQ_USERNAME": "ci_rabbitmq_user",
        "RABBITMQ_PASSWORD": "ci-rabbitmq-password-46",
        "MINIO_ACCESS_KEY": "ci_minio_access_key",
        "MINIO_SECRET_KEY": "ci-minio-secret-key-46",
        "JWT_SECRET": "ci-jwt-signing-secret-with-at-least-thirty-two-bytes",
    },
    "postgres": {"POSTGRES_USER": "ci_postgres_user", "POSTGRES_PASSWORD": "ci-postgres-password-46"},
    "rabbitmq": {"RABBITMQ_DEFAULT_USER": "ci_rabbitmq_user", "RABBITMQ_DEFAULT_PASS": "ci-rabbitmq-password-46"},
    "minio": {"MINIO_ROOT_USER": "ci_minio_access_key", "MINIO_ROOT_PASSWORD": "ci-minio-secret-key-46"},
    "flyway": {"FLYWAY_USER": "ci_postgres_user", "FLYWAY_PASSWORD": "ci-postgres-password-46"},
    "minio-init": {"MINIO_ACCESS_KEY": "ci_minio_access_key", "MINIO_SECRET_KEY": "ci-minio-secret-key-46"},
    "backend": {
        "SPRING_DATASOURCE_USERNAME": "ci_postgres_user",
        "SPRING_DATASOURCE_PASSWORD": "ci-postgres-password-46",
        "JWT_SECRET": "ci-jwt-signing-secret-with-at-least-thirty-two-bytes",
        "RABBITMQ_USERNAME": "ci_rabbitmq_user",
        "RABBITMQ_PASSWORD": "ci-rabbitmq-password-46",
        "MINIO_ACCESS_KEY": "ci_minio_access_key",
        "MINIO_SECRET_KEY": "ci-minio-secret-key-46",
    },
    "worker": {
        "SPRING_DATASOURCE_USERNAME": "ci_postgres_user",
        "SPRING_DATASOURCE_PASSWORD": "ci-postgres-password-46",
        "RABBITMQ_USERNAME": "ci_rabbitmq_user",
        "RABBITMQ_PASSWORD": "ci-rabbitmq-password-46",
        "MINIO_ACCESS_KEY": "ci_minio_access_key",
        "MINIO_SECRET_KEY": "ci-minio-secret-key-46",
    },
}

for service_name, environment in expected.items():
    actual_environment = services[service_name]["environment"]
    for variable_name, expected_value in environment.items():
        assert actual_environment[variable_name] == expected_value, (service_name, variable_name)

for service_name in ("postgres", "rabbitmq", "minio", "flyway", "minio-init"):
    dependency = services[service_name]["depends_on"]["production-config-check"]
    assert dependency["condition"] == "service_completed_successfully", service_name
PY
printf '%s\n' "Canonical credential alignment checks passed"

run_production_compose run --rm --no-deps production-config-check >/dev/null

printf '%s\n' "Production configuration smoke checks passed"
