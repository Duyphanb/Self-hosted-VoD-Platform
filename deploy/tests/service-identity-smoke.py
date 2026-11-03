#!/usr/bin/env python3
"""#106 least-privilege smoke on a fresh, disposable vod-auth-audit-* Compose project.

Without --running-stack it owns the project and removes it, including on failure. It upgrades a
database migrated the pre-#106 way (bootstrap superuser), then proves each PostgreSQL role can do
only its own operations, rotates a role password, and checks Redis authentication. With
--running-stack it probes a healthy full stack the caller started and owns: the applications hold
only their scoped database and broker sessions, Redis requires its password, and the edge
reaches the backend but no internal service.

Credentials are generated in memory and reach containers only through the environment; command
output is captured and never printed. Failures name a fixed check label only.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PROJECT = re.compile(r"vod-auth-audit-[a-z0-9][a-z0-9_-]*\Z")

# Runs inside a postgres-init container, which holds psql and every role credential. Each check
# prints only its label on failure; a denial must be an authorization error, not any failure.
ROLE_CHECKS = r'''
export PGHOST="$POSTGRES_HOST" PGPORT="$POSTGRES_PORT" PGDATABASE="$POSTGRES_DB" PGCONNECT_TIMEOUT=5
failed() { printf 'SMOKE_CHECK:%s\n' "$1" >&2; exit 1; }
as_role() {
  identity="$1"; shift
  case "$identity" in
    owner) user="$POSTGRES_OWNER_USER"; password="$POSTGRES_OWNER_PASSWORD" ;;
    backend) user="$POSTGRES_BACKEND_USER"; password="$POSTGRES_BACKEND_PASSWORD" ;;
    worker) user="$POSTGRES_WORKER_USER"; password="$POSTGRES_WORKER_PASSWORD" ;;
    stale-backend) user="$POSTGRES_BACKEND_USER"; password="$AUDIT_PREVIOUS_BACKEND_PASSWORD" ;;
  esac
  PGUSER="$user" PGPASSWORD="$password" psql -X -q -At -v ON_ERROR_STOP=1 "$@"
}
allow() { as_role "$2" -c "$3" >/dev/null 2>&1 || failed "$1"; }
expect() { [ "$(as_role "$2" -c "$3" 2>/dev/null)" = "$4" ] || failed "$1"; }
deny() {
  label="$1"; shift
  if as_role "$@" >/dev/null 2>/tmp/denied; then failed "$label"; fi
  case "$(cat /tmp/denied)" in
    *"permission denied"*|*"must be owner of"*) ;;
    *) failed "$label" ;;
  esac
}
refuse_login() {
  label="$1"; shift
  if as_role "$@" >/dev/null 2>/tmp/refused; then failed "$label"; fi
  case "$(cat /tmp/refused)" in *"password authentication failed"*) ;; *) failed "$label" ;; esac
}
self_attributes="SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls
  OR EXISTS (SELECT FROM pg_auth_members m WHERE m.member = r.oid) FROM pg_roles r WHERE rolname = current_user"
for role in owner backend worker; do
  expect "$role-has-no-elevated-attributes-or-memberships" "$role" "$self_attributes" f
done
allow owner-creates-and-drops-tables owner "CREATE TABLE audit_probe (id int); DROP TABLE audit_probe;"
deny owner-cannot-create-roles owner -c "CREATE ROLE audit_escalation"
deny owner-cannot-create-databases owner -c "CREATE DATABASE audit_extra"
allow backend-dml backend "BEGIN;
  INSERT INTO genres (id, name, slug) VALUES (gen_random_uuid(), 'Audit', 'audit-106');
  UPDATE genres SET name = 'Audit 106' WHERE slug = 'audit-106';
  DELETE FROM genres WHERE slug = 'audit-106';
  SELECT count(*) FROM users; SELECT count(*) FROM video_assets; ROLLBACK;"
allow backend-row-locks backend "BEGIN; SELECT id FROM movies FOR UPDATE; SELECT id FROM refresh_tokens FOR UPDATE; ROLLBACK;"
allow backend-sequences backend "SELECT last_value FROM roles_id_seq"
deny backend-cannot-create-tables backend -c "CREATE TABLE audit_probe (id int)"
deny backend-cannot-alter-tables backend -c "ALTER TABLE movies ADD COLUMN audit_probe int"
deny backend-cannot-drop-tables backend -c "DROP TABLE ratings"
deny backend-cannot-truncate backend -c "TRUNCATE genres"
deny backend-cannot-read-migration-history backend -c "SELECT count(*) FROM flyway_schema_history"
deny backend-cannot-create-temporary-tables backend -c "CREATE TEMP TABLE audit_temp (id int)"
deny backend-cannot-connect-to-maintenance-database backend -d postgres -c "SELECT 1"
allow worker-claims-and-finishes-encoding-rows worker "BEGIN;
  SELECT id FROM encoding_jobs FOR UPDATE; SELECT id FROM video_assets FOR UPDATE;
  UPDATE encoding_jobs SET started_at = now() WHERE false;
  UPDATE video_assets SET status = 'PROCESSING' WHERE false; ROLLBACK;"
deny worker-cannot-read-users worker -c "SELECT count(*) FROM users"
deny worker-cannot-read-refresh-tokens worker -c "SELECT count(*) FROM refresh_tokens"
deny worker-cannot-read-movies worker -c "SELECT count(*) FROM movies"
deny worker-cannot-insert-jobs worker -c "INSERT INTO encoding_jobs (id, video_asset_id, status, attempt, queued_at)
  VALUES (gen_random_uuid(), gen_random_uuid(), 'QUEUED', 1, now())"
deny worker-cannot-delete-assets worker -c "DELETE FROM video_assets"
deny worker-cannot-read-migration-history worker -c "SELECT count(*) FROM flyway_schema_history"
deny worker-cannot-create-tables worker -c "CREATE TABLE audit_probe (id int)"
if [ -n "${AUDIT_PREVIOUS_BACKEND_PASSWORD:-}" ]; then
  refuse_login rotated-backend-password-is-rejected stale-backend -c "SELECT 1"
fi
printf 'role checks passed\n'
'''

# Superuser view of the migrated schema: every public relation belongs to the owner role, and
# only the documented application privileges exist. Runs through the server's local socket.
OWNERSHIP_QUERY = r'''
SELECT c.relname || ':' || pg_get_userbyid(c.relowner) || ':' || coalesce(
         (SELECT string_agg(a.grantee_name || '=' || a.privileges, ',' ORDER BY a.grantee_name)
            FROM (SELECT pg_get_userbyid(acl.grantee) AS grantee_name,
                         string_agg(acl.privilege_type, '+' ORDER BY acl.privilege_type) AS privileges
                    FROM aclexplode(c.relacl) acl
                   WHERE acl.grantee <> c.relowner
                   GROUP BY acl.grantee) a), '')
  FROM pg_class c
 WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
 ORDER BY c.relname;
'''

REDIS_CHECKS = r'''
failed() { printf 'SMOKE_CHECK:%s\n' "$1" >&2; exit 1; }
[ "$(redis-cli ping 2>&1)" != PONG ] || failed redis-unauthenticated-ping-denied
[ "$(REDISCLI_AUTH=audit-wrong-password redis-cli ping 2>&1)" != PONG ] || failed redis-wrong-password-denied
[ "$(REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping 2>&1)" = PONG ] || failed redis-password-accepted
case "$(tr '\0' ' ' < /proc/1/cmdline)" in *"$REDIS_PASSWORD"*) failed redis-password-absent-from-argv ;; esac
printf 'redis checks passed\n'
'''


class SmokeError(RuntimeError):
    """A fixed, non-sensitive diagnostic."""


def require_project(project):
    if not PROJECT.fullmatch(project):
        raise SmokeError("Use a disposable vod-auth-audit-* project")


def generated_environment():
    """Fresh credentials for this run only. Role names are valid lowercase identifiers."""
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("POSTGRES_", "REDIS_", "RABBITMQ_", "MINIO_", "SPRING_", "FLYWAY_", "AUDIT_"))}
    token = secrets.token_hex(6)
    env.update({"POSTGRES_DB": "audit_platform_" + token, "POSTGRES_USER": "audit_bootstrap_" + token,
                "POSTGRES_OWNER_USER": "audit_owner_" + token, "POSTGRES_BACKEND_USER": "audit_backend_" + token,
                "POSTGRES_WORKER_USER": "audit_worker_" + token})
    for name in ("POSTGRES_PASSWORD", "POSTGRES_OWNER_PASSWORD", "POSTGRES_BACKEND_PASSWORD",
                 "POSTGRES_WORKER_PASSWORD", "REDIS_PASSWORD"):
        env[name] = secrets.token_urlsafe(32)
    return env


def secret_values(env):
    return [value for key, value in env.items() if key.endswith("PASSWORD") and value]


class Smoke:
    def __init__(self, project, env, env_file):
        self.project, self.env = project, env
        self.compose = ["docker", "compose", "-p", project, "--env-file", env_file, "-f", "deploy/docker-compose.yml"]
        self.outputs = []

    def run(self, args, *, check=True, timeout=240, label="command", record=True):
        try:
            result = subprocess.run(args, cwd=ROOT, env=self.env, capture_output=True, text=True,
                                    encoding="utf-8", errors="replace", timeout=timeout)
        except subprocess.TimeoutExpired:
            raise SmokeError("Timed out: " + label) from None
        if record:  # Only the composed configuration is exempt: it holds the credentials by design.
            self.outputs.append(result.stdout + result.stderr)
        if check and result.returncode:
            labels = re.findall(r"SMOKE_CHECK:([a-z0-9-]+)", result.stderr)
            raise SmokeError("Check failed: " + labels[-1] if labels else "Command failed (output withheld): " + label)
        return result

    def compose_run(self, *args, **options):
        return self.run(self.compose + list(args), **options)

    def require_fresh(self):
        for resource in (["ps", "-aq"], ["volume", "ls", "-q"], ["network", "ls", "-q"]):
            found = self.run(["docker", *resource, "--filter", "label=com.docker.compose.project=" + self.project]).stdout
            if found.strip():
                raise SmokeError("Project already exists; refusing reuse")

    def _psql(self, sql, label):
        result = subprocess.run(self.compose + ["exec", "-T", "postgres", "sh", "-c",
                                                'psql -X -q -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
                                cwd=ROOT, env=self.env, input=sql, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", timeout=60)
        self.outputs.append(result.stdout + result.stderr)
        if result.returncode:
            raise SmokeError("Command failed (output withheld): " + label)
        return result.stdout

    def assert_ownership_and_grants(self):
        owner, backend, worker = (self.env["POSTGRES_OWNER_USER"], self.env["POSTGRES_BACKEND_USER"],
                                  self.env["POSTGRES_WORKER_USER"])
        rows = dict(line.split(":", 1) for line in self._psql(OWNERSHIP_QUERY, "ownership query").splitlines())
        if "flyway_schema_history" not in rows or "encoding_jobs" not in rows:
            raise SmokeError("Check failed: migrated-schema-present")
        dml = backend + "=DELETE+INSERT+SELECT+UPDATE"
        for relation, entry in rows.items():
            relation_owner, grants = entry.split(":", 1)
            if relation_owner != owner:
                raise SmokeError("Check failed: owner-owns-every-public-relation")
            if relation == "flyway_schema_history":
                expected = ""
            elif relation == "roles_id_seq":
                expected = backend + "=SELECT+USAGE"
            elif relation in ("encoding_jobs", "video_assets"):
                expected = ",".join(sorted([dml, worker + "=SELECT+UPDATE"]))
            else:
                expected = dml
            if grants != expected:
                raise SmokeError("Check failed: exact-application-grants")

    def database_checks(self):
        # Pre-#106 layout: the bootstrap superuser migrated and owns every relation.
        self.compose_run("up", "-d", "--wait", "--wait-timeout", "120", "--no-build", "postgres", label="postgres up")
        legacy = dict(self.env, FLYWAY_USER=self.env["POSTGRES_USER"], FLYWAY_PASSWORD=self.env["POSTGRES_PASSWORD"],
                      FLYWAY_LOCATIONS="filesystem:/flyway/sql")
        previous, self.env = self.env, legacy
        try:
            self.compose_run("run", "--rm", "--no-deps", "-e", "FLYWAY_USER", "-e", "FLYWAY_PASSWORD",
                             "-e", "FLYWAY_LOCATIONS", "flyway", label="legacy migration")
        finally:
            self.env = previous
        if self._psql("SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind = 'r' "
                      "AND relowner <> (SELECT oid FROM pg_roles WHERE rolname = current_user);",
                      "legacy ownership").strip() != "0":
            raise SmokeError("Check failed: legacy-layout-prepared")
        print("PASS pre-#106 database migrated by the bootstrap superuser", flush=True)
        # The normal dependency path: postgres-init (roles, ownership), then Flyway as the owner.
        # `run` returns Flyway's own exit status after its dependency conditions held.
        self.compose_run("run", "--rm", "flyway", label="flyway with dependencies")
        self.assert_ownership_and_grants()
        for _ in range(2):  # Both steps are idempotent on an initialized database.
            self.compose_run("run", "--rm", "--no-deps", "postgres-init", label="postgres-init rerun")
            self.compose_run("run", "--rm", "--no-deps", "flyway", label="flyway rerun")
        self.assert_ownership_and_grants()
        print("PASS init and owner migration: ownership transferred, exact grants, idempotent reruns", flush=True)
        self.compose_run("run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", "postgres-init", "-ec", ROLE_CHECKS,
                         label="role checks")
        print("PASS owner, backend and worker roles can do only their own operations", flush=True)
        # Rotation: init updates the configured password; the previous one stops working.
        previous_password = self.env["POSTGRES_BACKEND_PASSWORD"]
        self.env = dict(self.env, POSTGRES_BACKEND_PASSWORD=secrets.token_urlsafe(32),
                        AUDIT_PREVIOUS_BACKEND_PASSWORD=previous_password)
        self.compose_run("run", "--rm", "--no-deps", "-e", "POSTGRES_BACKEND_PASSWORD", "postgres-init",
                         label="rotation init")
        self.compose_run("run", "--rm", "--no-deps", "-e", "POSTGRES_BACKEND_PASSWORD",
                         "-e", "AUDIT_PREVIOUS_BACKEND_PASSWORD", "--entrypoint", "/bin/sh", "postgres-init",
                         "-ec", ROLE_CHECKS, label="rotation checks")
        print("PASS backend password rotation through postgres-init", flush=True)

    def redis_checks(self):
        self.compose_run("up", "-d", "--wait", "--wait-timeout", "60", "--no-build", "redis", label="redis up")
        self.compose_run("exec", "-T", "redis", "sh", "-ec", REDIS_CHECKS, label="redis checks")
        # A client on the internal network without the password is refused too.
        remote = self.compose_run("run", "--rm", "--no-deps", "--entrypoint", "redis-cli", "redis", "-h", "redis",
                                  "ping", check=False, label="remote redis")
        if remote.stdout.strip() == "PONG":
            raise SmokeError("Check failed: redis-remote-unauthenticated-denied")
        print("PASS Redis requires its password; the secret is absent from argv", flush=True)

    def static_checks(self):
        config = json.loads(self.compose_run("config", "--format", "json", label="config", record=False).stdout)
        services, networks = config["services"], config["networks"]
        if networks["vod_internal"].get("internal") is not True or networks["vod_edge"].get("internal"):
            raise SmokeError("Check failed: network-internal-flags")
        for name, service in services.items():
            attached = set(service.get("networks") or {})
            expected = ({"vod_edge"} if name in ("nginx", "frontend") else
                        {"vod_edge", "vod_internal"} if name == "backend" else {"vod_internal"})
            if attached != expected or (name != "nginx" and service.get("ports")):
                raise SmokeError("Check failed: network-membership")
        redis = services["redis"]
        visible = json.dumps([redis.get("entrypoint"), redis.get("command")])
        if self.env["REDIS_PASSWORD"] in visible:
            raise SmokeError("Check failed: redis-password-absent-from-command")
        print("PASS edge/internal network membership and internal data network", flush=True)

    def running_stack_checks(self):
        """Probes in a caller-started full stack after its aggregate health is UP: the apps hold
        only their scoped sessions, Redis requires its password, and Nginx reaches only the backend."""
        inspect = self.run(["docker", "network", "inspect", "--format", "{{.Internal}}",
                            self.project + "_vod_internal", self.project + "_vod_edge"], label="network inspect")
        if inspect.stdout.split() != ["true", "false"]:
            raise SmokeError("Check failed: running-network-internal-flags")
        services = json.loads(self.compose_run("config", "--format", "json", label="config", record=False).stdout)[
            "services"]
        backend, worker = services["backend"]["environment"], services["worker"]["environment"]
        # Every JDBC session belongs to an application role; the backend's pool is open after health UP.
        sessions = set(self._psql("SELECT DISTINCT usename FROM pg_stat_activity "
                                  "WHERE application_name = 'PostgreSQL JDBC Driver';", "database sessions").split())
        if backend["SPRING_DATASOURCE_USERNAME"] not in sessions or not sessions <= {
                backend["SPRING_DATASOURCE_USERNAME"], worker["SPRING_DATASOURCE_USERNAME"]}:
            raise SmokeError("Check failed: applications-use-their-database-roles")
        listed = self.compose_run("exec", "-T", "rabbitmq", "rabbitmqctl", "--quiet", "list_connections", "user",
                                  "--formatter", "json", label="broker connections").stdout
        users = {connection["user"] for connection in json.loads(listed or "[]")}
        if backend["RABBITMQ_USERNAME"] not in users or not users <= {
                backend["RABBITMQ_USERNAME"], worker["RABBITMQ_USERNAME"]}:
            raise SmokeError("Check failed: applications-use-their-broker-users")
        self.compose_run("exec", "-T", "redis", "sh", "-ec", REDIS_CHECKS, label="redis checks")
        print("PASS the running applications use only their scoped identities; Redis requires its password",
              flush=True)
        def from_edge(*command):
            return self.compose_run("exec", "-T", "nginx", *command, check=False, label="edge probe").returncode == 0

        # Each negative probe has a positive control, so a missing tool cannot pass vacuously.
        if not (from_edge("wget", "-q", "-T", "3", "-O", "/dev/null", "http://backend:8080/api/v1/health")
                and from_edge("getent", "hosts", "backend")
                and from_edge("nc", "-z", "-w", "3", self.address("backend", "vod_edge"), "8080")):
            raise SmokeError("Check failed: edge-reaches-backend")
        # Docker DNS answers only for attached networks, so a resolvable name means a shared network.
        for name in ("postgres", "redis", "rabbitmq", "minio", "worker", "flyway", "minio-init", "postgres-init"):
            if from_edge("getent", "hosts", name):
                raise SmokeError("Check failed: edge-cannot-resolve-internal-services")
        # Not even by address: the edge has no route into the internal network.
        for name, port in (("postgres", "5432"), ("minio", "9000"), ("backend", "8080")):
            if from_edge("nc", "-z", "-w", "3", self.address(name, "vod_internal"), port):
                raise SmokeError("Check failed: edge-cannot-reach-internal-addresses")
        print("PASS Nginx reaches the backend but no internal service by name or address", flush=True)

    def address(self, service, network):
        inspected = self.run(["docker", "inspect", "--format", "{{(index .NetworkSettings.Networks \""
                              + self.project + "_" + network + "\").IPAddress}}", self.project + "-" + service + "-1"],
                             label="container address").stdout.strip()
        if not re.fullmatch(r"\d{1,3}(\.\d{1,3}){3}", inspected):
            raise SmokeError("Check failed: container-address-present")
        return inspected

    def assert_no_leaks(self):
        self.compose_run("logs", "--no-color", "postgres", "redis", label="service logs")
        combined = "".join(self.outputs)
        if any(value in combined for value in secret_values(self.env)):
            raise SmokeError("Check failed: credentials-absent-from-output")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--running-stack", action="store_true",
                        help="Only probe the network split of a full stack the caller started")
    args = parser.parse_args()
    require_project(args.project)
    with tempfile.TemporaryDirectory(prefix="vod-identity-smoke-") as directory:
        env_file = Path(directory) / "empty.env"
        env_file.write_text("", encoding="utf-8")
        if args.running_stack:
            # Interpolates like the caller's `up --env-file /dev/null`: pass the same environment.
            smoke = Smoke(args.project, dict(os.environ), str(env_file))
            smoke.running_stack_checks()
            smoke.assert_no_leaks()
            return
        smoke = Smoke(args.project, generated_environment(), str(env_file))
        smoke.require_fresh()
        failure = None
        try:
            smoke.static_checks()
            smoke.database_checks()
            smoke.redis_checks()
            smoke.assert_no_leaks()
        except BaseException as error:  # Preserve the first failure through cleanup.
            failure = error
        finally:
            cleanup = smoke.compose_run("down", "--volumes", "--remove-orphans", check=False, label="cleanup")
            if failure is None and cleanup.returncode:
                failure = SmokeError("Cleanup failed")
        if failure is not None:
            raise failure
    print("PASS least-privilege service identity smoke; disposable project removed", flush=True)


if __name__ == "__main__":
    try:
        main()
    except SmokeError as error:
        raise SystemExit("FAIL service identity smoke: " + str(error))
    except Exception as error:
        # Avoid tracebacks that can include command or configuration payloads.
        raise SystemExit("FAIL service identity smoke: " + type(error).__name__)
