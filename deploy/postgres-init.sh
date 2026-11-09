#!/bin/sh
# Least-privilege PostgreSQL roles (#106). The bootstrap superuser (POSTGRES_USER)
# runs only this one-shot init and explicit operator maintenance. It creates or
# updates the schema owner that Flyway uses, the backend DML role and the worker
# role; refuses a role that holds a membership in any other role; limits
# database and schema access to them; and moves public relations created before
# #106 by the bootstrap user to the owner. Flyway's afterMigrate
# callback (deploy/flyway) then grants the table privileges as that owner.
# Passwords reach psql only through the environment (\getenv), never argv, and
# the session keeps statement text out of the server log. Tool output is
# discarded because an error can quote a statement; failures name the step only.
set -eu
umask 077
step="configuration validation"
fail() { echo "Database role initialization failed: $step" >&2; exit 1; }
trap 'fail' HUP INT TERM

role_name() {
  case "$1" in ''|pg_*|[!a-z_]*|*[!a-z0-9_]*) fail ;; esac
  [ "${#1}" -le 63 ] || fail
}
not_blank() { case "$1" in *[![:space:]]*) ;; *) fail ;; esac; }
distinct() {
  while [ "$#" -gt 1 ]; do
    first="$1"
    shift
    for other in "$@"; do [ "$first" != "$other" ] || fail; done
  done
}

bootstrap_user="${POSTGRES_USER:-}"
owner_user="${POSTGRES_OWNER_USER:-}"
backend_user="${POSTGRES_BACKEND_USER:-}"
worker_user="${POSTGRES_WORKER_USER:-}"
not_blank "$bootstrap_user"
not_blank "${POSTGRES_DB:-}"
for role in "$owner_user" "$backend_user" "$worker_user"; do role_name "$role"; done
for secret in "${POSTGRES_PASSWORD:-}" "${POSTGRES_OWNER_PASSWORD:-}" "${POSTGRES_BACKEND_PASSWORD:-}" "${POSTGRES_WORKER_PASSWORD:-}"
do
  not_blank "$secret"
done
distinct "$bootstrap_user" "$owner_user" "$backend_user" "$worker_user"
distinct "$POSTGRES_PASSWORD" "$POSTGRES_OWNER_PASSWORD" "$POSTGRES_BACKEND_PASSWORD" "$POSTGRES_WORKER_PASSWORD"

export PGHOST="${POSTGRES_HOST:-postgres}" PGPORT="${POSTGRES_PORT:-5432}" PGDATABASE="$POSTGRES_DB"
export PGUSER="$bootstrap_user" PGPASSWORD="$POSTGRES_PASSWORD" PGCONNECT_TIMEOUT=5 PGAPPNAME=vod-postgres-init
step="authentication or endpoint unavailable"
attempts=0
until psql -X -q -c 'SELECT 1' >/dev/null 2>&1; do
  attempts=$((attempts + 1))
  [ "$attempts" -lt 30 ] || fail
  sleep 2
done

step="role provisioning"
# Only the VOD_STEP markers below are read back; they name the failing phase.
if ! output=$(psql -X -q -v ON_ERROR_STOP=1 2>/dev/null <<'SQL'
\getenv database POSTGRES_DB
\getenv owner_user POSTGRES_OWNER_USER
\getenv owner_password POSTGRES_OWNER_PASSWORD
\getenv backend_user POSTGRES_BACKEND_USER
\getenv backend_password POSTGRES_BACKEND_PASSWORD
\getenv worker_user POSTGRES_WORKER_USER
\getenv worker_password POSTGRES_WORKER_PASSWORD
\echo VOD_STEP:session settings
-- Role statements carry passwords: keep their text out of the server log, even on error.
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
SET log_min_duration_statement = -1;
SET log_min_duration_sample = -1;
SET log_transaction_sample_rate = 0;
BEGIN;
SELECT pg_advisory_xact_lock(106) AS serialized \gset
\echo VOD_STEP:role provisioning
SELECT format('CREATE ROLE %I', wanted.name)
  FROM (VALUES (:'owner_user'), (:'backend_user'), (:'worker_user')) AS wanted(name)
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = wanted.name) \gexec
\echo VOD_STEP:role membership check
-- A reused role may have been granted another role (pg_read_all_data, a sibling, ...),
-- whose privileges it inherits or can SET ROLE to; the REVOKEs below do not remove that.
-- Refuse instead of guessing which grant was intended; the operator revokes it.
SELECT 'DO $$BEGIN RAISE EXCEPTION ''configured role holds a role membership''; END$$'
 WHERE EXISTS (SELECT FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member
                WHERE r.rolname IN (:'owner_user', :'backend_user', :'worker_user')) \gexec
ALTER ROLE :"owner_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  INHERIT CONNECTION LIMIT -1 VALID UNTIL 'infinity' PASSWORD :'owner_password';
ALTER ROLE :"backend_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  INHERIT CONNECTION LIMIT -1 VALID UNTIL 'infinity' PASSWORD :'backend_password';
ALTER ROLE :"worker_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  INHERIT CONNECTION LIMIT -1 VALID UNTIL 'infinity' PASSWORD :'worker_password';
\echo VOD_STEP:database privileges
-- Only these roles may connect, and only to the application database; none may create temporary tables.
REVOKE ALL ON DATABASE :"database" FROM PUBLIC, :"owner_user", :"backend_user", :"worker_user";
GRANT CONNECT ON DATABASE :"database" TO :"owner_user", :"backend_user", :"worker_user";
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC, %I, %I, %I', datname, :'owner_user', :'backend_user', :'worker_user')
  FROM pg_database
 WHERE datname <> :'database' AND datallowconn \gexec
\echo VOD_STEP:schema privileges
REVOKE ALL ON SCHEMA public FROM PUBLIC, :"owner_user", :"backend_user", :"worker_user";
GRANT USAGE, CREATE ON SCHEMA public TO :"owner_user";
GRANT USAGE ON SCHEMA public TO :"backend_user", :"worker_user";
\echo VOD_STEP:ownership transfer
-- Relations created before #106 belong to the bootstrap user. Linked sequences and
-- indexes follow their table; extension members are left alone.
SELECT format('ALTER %s %s OWNER TO %I',
              CASE c.relkind WHEN 'S' THEN 'SEQUENCE' WHEN 'v' THEN 'VIEW'
                             WHEN 'm' THEN 'MATERIALIZED VIEW' WHEN 'f' THEN 'FOREIGN TABLE'
                             ELSE 'TABLE' END,
              c.oid::regclass, :'owner_user')
  FROM pg_class c
 WHERE c.relnamespace = 'public'::regnamespace
   AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
   AND c.relowner <> (SELECT oid FROM pg_roles WHERE rolname = :'owner_user')
   AND NOT EXISTS (SELECT FROM pg_depend d
                    WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid
                      AND d.deptype IN ('a', 'i', 'e'))
 ORDER BY c.oid \gexec
COMMIT;
\echo VOD_STEP:complete
SQL
)
then
  step=$(printf '%s\n' "${output:-}" | sed -n 's/^VOD_STEP://p' | tail -n 1)
  step="${step:-role provisioning}"
  fail
fi
echo "Database roles and schema ownership initialized"
