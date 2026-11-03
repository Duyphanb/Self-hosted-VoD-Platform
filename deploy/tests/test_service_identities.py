"""Exercise the #106 identity scripts' real shell logic with stub tools, without Docker."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]
SHELL = shutil.which("sh")
if SHELL is None:
    git_shell = Path("C:/Program Files/Git/bin/sh.exe")
    SHELL = str(git_shell) if git_shell.exists() else None

RECORDING_ENTRYPOINT = '''#!/bin/sh
printf '%s\\n' "$@" > "$AUDIT/args"
cat > "$AUDIT/stdin"
'''


class ScriptFixture:
    """A temporary directory with stub executables first on PATH and an audit folder."""

    def __init__(self, test):
        self.directory = tempfile.TemporaryDirectory(prefix=".identity-test-", dir=REPOSITORY / "deploy/tests")
        test.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.audit = self.path / "audit"
        self.audit.mkdir()

    def tool(self, name, body):
        executable = self.path / name
        executable.write_text(body, encoding="utf-8", newline="\n")
        executable.chmod(0o755)

    def script(self, relative, replacements=()):
        source = (REPOSITORY / relative).read_text(encoding="utf-8")
        for old, new in replacements:
            assert source.count(old) >= 1, "Fixture target changed: " + old
            source = source.replace(old, new)
        target = self.path / Path(relative).name
        target.write_text(source, encoding="utf-8", newline="\n")
        return target

    def run(self, script, arguments=(), **environment):
        # Never inherit a developer's service settings; each test states its own.
        env = {name: value for name, value in os.environ.items()
               if not name.startswith(("POSTGRES_", "RABBITMQ_", "REDIS_", "PG"))}
        env.update(PATH=str(self.path) + os.pathsep + os.environ["PATH"], AUDIT=self.audit.as_posix(), LC_ALL="C")
        env.update(environment)
        return subprocess.run([SHELL, str(script), *arguments], env=env, stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, encoding="utf-8", timeout=20)

    def read(self, name):
        target = self.audit / name
        return target.read_text(encoding="utf-8") if target.exists() else None


@unittest.skipUnless(SHELL, "POSIX shell is required for identity script tests")
class RedisEntrypointTests(unittest.TestCase):
    def start(self, password):
        fixture = ScriptFixture(self)
        fixture.tool("docker-entrypoint.sh", RECORDING_ENTRYPOINT)
        environment = {} if password is None else {"REDIS_PASSWORD": password}
        result = fixture.run(REPOSITORY / "deploy/redis-entrypoint.sh", ("redis-server", "--appendonly", "no"),
                             **environment)
        return fixture, result

    def test_password_reaches_redis_on_stdin_and_never_in_arguments(self):
        for password in ("fixture-redis-secret", "base64+/=", "dollar$sign`tick\\slash#hash"):
            with self.subTest(password=password):
                fixture, result = self.start(password)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(fixture.read("args").splitlines(), ["redis-server", "--appendonly", "no", "-"])
                self.assertEqual(fixture.read("stdin"), "requirepass " + password + "\n")
                self.assertNotIn(password, result.stdout + result.stderr)

    def test_blank_whitespace_and_quoted_passwords_fail_before_redis_starts(self):
        unsafe = "must not contain whitespace or quotes"
        cases = {None: "must not be blank", "": "must not be blank", "   ": "must not be blank",
                 "two words": unsafe, "tab\there": unsafe, 'double"quote': unsafe, "single'quote": unsafe}
        for password, reason in cases.items():
            with self.subTest(password=repr(password)):
                fixture, result = self.start(password)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Redis startup rejected: REDIS_PASSWORD " + reason, result.stderr)
                self.assertIsNone(fixture.read("args"))
                if password and password.strip():
                    self.assertNotIn(password, result.stdout + result.stderr)


@unittest.skipUnless(SHELL, "POSIX shell is required for identity script tests")
class RabbitmqEntrypointTests(unittest.TestCase):
    BASE = {"RABBITMQ_DEFAULT_USER": "fixture-admin", "RABBITMQ_DEFAULT_PASS": "fixture-admin-secret",
            "RABBITMQ_BACKEND_USERNAME": "fixture-backend", "RABBITMQ_BACKEND_PASSWORD": "fixture-backend-secret",
            "RABBITMQ_WORKER_USERNAME": "fixture-worker", "RABBITMQ_WORKER_PASSWORD": "fixture-worker-secret",
            "RABBITMQ_ENCODING_QUEUE": "video-encoding-queue"}

    def start(self, **overrides):
        fixture = ScriptFixture(self)
        fixture.tool("docker-entrypoint.sh", RECORDING_ENTRYPOINT)
        fixture.tool("chown", '#!/bin/sh\nprintf \'%s\\n\' "$@" >> "$AUDIT/chown"\n')
        runtime = fixture.path / "run"
        config = fixture.path / "conf.d"
        config.mkdir()
        script = fixture.script("deploy/rabbitmq-entrypoint.sh", (
            ("/run/vod-rabbitmq", runtime.as_posix()), ("/etc/rabbitmq/conf.d", config.as_posix())))
        environment = dict(self.BASE)
        environment.update(overrides)
        environment = {name: value for name, value in environment.items() if value is not None}
        result = fixture.run(script, ("rabbitmq-server",), **environment)
        definitions = runtime / "definitions.json"
        return fixture, result, definitions, config / "90-vod-definitions.conf"

    def test_definitions_hold_the_administrator_and_scoped_users(self):
        fixture, result, definitions, config = self.start()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(fixture.read("args").splitlines(), ["rabbitmq-server"])
        self.assertEqual(config.read_text(encoding="utf-8"), "load_definitions = " + definitions.as_posix() + "\n")
        self.assertEqual(sorted(fixture.read("chown").split()), sorted(
            ["rabbitmq:rabbitmq", definitions.parent.as_posix(), definitions.as_posix(), config.as_posix()]))
        document = json.loads(definitions.read_text(encoding="utf-8"))
        self.assertEqual(document["vhosts"], [{"name": "/"}])
        self.assertEqual(document["users"], [
            {"name": "fixture-admin", "password": "fixture-admin-secret", "tags": ["administrator"]},
            {"name": "fixture-backend", "password": "fixture-backend-secret", "tags": []},
            {"name": "fixture-worker", "password": "fixture-worker-secret", "tags": []}])
        permissions = {entry["user"]: entry for entry in document["permissions"]}
        self.assertEqual({entry["vhost"] for entry in document["permissions"]}, {"/"})
        self.assertEqual((permissions["fixture-admin"]["configure"], permissions["fixture-admin"]["write"],
                          permissions["fixture-admin"]["read"]), (".*", ".*", ".*"))
        q = "video-encoding-queue"
        topology = "^(" + q + "|" + q + "\\.dlx|" + q + "\\.dlq)$"
        self.assertEqual(permissions["fixture-backend"], {
            "user": "fixture-backend", "vhost": "/", "configure": topology,
            "write": "^(amq\\.default|" + q + "\\.dlx|" + q + "\\.dlq)$", "read": "^(" + q + "|" + q + "\\.dlx)$"})
        self.assertEqual(permissions["fixture-worker"], {
            "user": "fixture-worker", "vhost": "/", "configure": topology,
            "write": "^(" + q + "\\.dlx|" + q + "\\.dlq)$", "read": "^(" + q + "|" + q + "\\.dlx)$"})
        for secret in ("fixture-admin-secret", "fixture-backend-secret", "fixture-worker-secret"):
            self.assertNotIn(secret, result.stdout + result.stderr + fixture.read("args") + fixture.read("chown"))

    def test_scoped_permissions_match_only_the_encoding_topology(self):
        _, result, definitions, _ = self.start(RABBITMQ_ENCODING_QUEUE="jobs.v1")
        self.assertEqual(result.returncode, 0, result.stderr)
        permissions = {entry["user"]: entry for entry in json.loads(definitions.read_text(encoding="utf-8"))["permissions"]}

        def allowed(user, kind, resource):
            return re.search(permissions[user][kind], resource) is not None

        for user in ("fixture-backend", "fixture-worker"):
            for resource in ("jobs.v1", "jobs.v1.dlx", "jobs.v1.dlq"):
                self.assertTrue(allowed(user, "configure", resource), (user, resource))
            for resource in ("jobsXv1", "jobs.v1.other", "other", "amq.default", "jobs.v1.dlqX", "Xjobs.v1"):
                self.assertFalse(allowed(user, "configure", resource), (user, resource))
            self.assertTrue(allowed(user, "read", "jobs.v1"))
            self.assertTrue(allowed(user, "read", "jobs.v1.dlx"))
            self.assertFalse(allowed(user, "read", "jobs.v1.dlq"), user)
            self.assertTrue(allowed(user, "write", "jobs.v1.dlx"))
            self.assertTrue(allowed(user, "write", "jobs.v1.dlq"))
            self.assertFalse(allowed(user, "write", "jobs.v1"), user)
            self.assertFalse(allowed(user, "write", "amq.direct"), user)
        self.assertTrue(allowed("fixture-backend", "write", "amq.default"))
        self.assertFalse(allowed("fixture-worker", "write", "amq.default"))

    def test_json_special_characters_are_escaped(self):
        password = 'quote"back\\slash-\u00e9'
        _, result, definitions, _ = self.start(RABBITMQ_BACKEND_PASSWORD=password, RABBITMQ_WORKER_USERNAME='odd"user\\')
        self.assertEqual(result.returncode, 0, result.stderr)
        users = {user["name"]: user["password"] for user in json.loads(definitions.read_text(encoding="utf-8"))["users"]}
        self.assertEqual(users["fixture-backend"], password)
        self.assertIn('odd"user\\', users)

    def test_invalid_configuration_fails_before_the_broker_starts(self):
        cases = {"missing admin": {"RABBITMQ_DEFAULT_USER": None}, "blank worker secret": {"RABBITMQ_WORKER_PASSWORD": " "},
                 "control character": {"RABBITMQ_BACKEND_PASSWORD": "tab\there"},
                 "backend reuses admin": {"RABBITMQ_BACKEND_USERNAME": "fixture-admin"},
                 "worker reuses backend": {"RABBITMQ_WORKER_USERNAME": "fixture-backend"},
                 "secret reuse": {"RABBITMQ_WORKER_PASSWORD": "fixture-admin-secret"},
                 "reserved queue": {"RABBITMQ_ENCODING_QUEUE": "amq.jobs"},
                 "unsafe queue": {"RABBITMQ_ENCODING_QUEUE": "jobs|other"},
                 "long queue": {"RABBITMQ_ENCODING_QUEUE": "q" * 252}}
        for label, overrides in cases.items():
            with self.subTest(label):
                fixture, result, definitions, config = self.start(**overrides)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stderr.strip(), "Broker identity initialization failed: configuration validation")
                self.assertIsNone(fixture.read("args"))
                self.assertFalse(definitions.exists())
                self.assertFalse(config.exists())


@unittest.skipUnless(SHELL, "POSIX shell is required for identity script tests")
class PostgresInitTests(unittest.TestCase):
    BASE = {"POSTGRES_HOST": "postgres", "POSTGRES_PORT": "5432", "POSTGRES_DB": "fixture_platform",
            "POSTGRES_USER": "fixture_bootstrap", "POSTGRES_PASSWORD": "fixture-bootstrap-secret",
            "POSTGRES_OWNER_USER": "fixture_owner", "POSTGRES_OWNER_PASSWORD": "fixture-owner-secret",
            "POSTGRES_BACKEND_USER": "fixture_backend", "POSTGRES_BACKEND_PASSWORD": "fixture-backend-secret",
            "POSTGRES_WORKER_USER": "fixture_worker", "POSTGRES_WORKER_PASSWORD": "fixture-worker-secret"}
    SECRETS = ("fixture-bootstrap-secret", "fixture-owner-secret", "fixture-backend-secret", "fixture-worker-secret")
    # The stub records argv/connection environment and the provisioning script. FAIL_AFTER
    # simulates an error after a marker; NO_SERVER fails the connection probe.
    PSQL = '''#!/bin/sh
printf '%s\\n' "$@" >> "$AUDIT/argv"
printf '%s|%s|%s|%s|%s\\n' "$PGHOST" "$PGPORT" "$PGDATABASE" "$PGUSER" "$PGPASSWORD" >> "$AUDIT/connection"
case " $* " in
  *" -c "*) [ -z "${NO_SERVER:-}" ] || exit 2; exit 0 ;;
esac
cat > "$AUDIT/script.sql"
if [ -n "${FAIL_AFTER:-}" ]; then
  printf 'VOD_STEP:session settings\\nVOD_STEP:%s\\n' "$FAIL_AFTER"
  echo "ERROR: statement quoting $POSTGRES_OWNER_PASSWORD" >&2
  exit 3
fi
printf 'VOD_STEP:complete\\n'
'''

    def start(self, **overrides):
        fixture = ScriptFixture(self)
        fixture.tool("psql", self.PSQL)
        fixture.tool("sleep", "#!/bin/sh\nexit 0\n")
        environment = dict(self.BASE)
        environment.update(overrides)
        environment = {name: value for name, value in environment.items() if value is not None}
        result = fixture.run(REPOSITORY / "deploy/postgres-init.sh", **environment)
        for secret in self.SECRETS:
            self.assertNotIn(secret, result.stdout + result.stderr)
            self.assertNotIn(secret, fixture.read("argv") or "")
        return fixture, result

    def test_provisioning_reads_every_secret_from_the_environment(self):
        fixture, result = self.start()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "Database roles and schema ownership initialized")
        self.assertEqual(set(fixture.read("connection").splitlines()),
                         {"postgres|5432|fixture_platform|fixture_bootstrap|fixture-bootstrap-secret"})
        script = fixture.read("script.sql")
        for secret in self.SECRETS:
            self.assertNotIn(secret, script)
        for variable in ("POSTGRES_OWNER_PASSWORD", "POSTGRES_BACKEND_PASSWORD", "POSTGRES_WORKER_PASSWORD"):
            self.assertIn("\\getenv " + variable.split("_")[1].lower() + "_password " + variable, script)
        # Server-log suppression precedes the transaction that carries the passwords.
        self.assertLess(script.index("SET log_min_error_statement = 'panic';"), script.index("BEGIN;"))
        self.assertLess(script.index("BEGIN;"), script.index("PASSWORD :'owner_password'"))
        for attributes in ("NOSUPERUSER", "NOCREATEDB", "NOCREATEROLE", "NOREPLICATION", "NOBYPASSRLS"):
            self.assertEqual(script.count(attributes), 3, attributes)
        self.assertIn("GRANT USAGE, CREATE ON SCHEMA public TO :\"owner_user\";", script)
        self.assertIn("GRANT USAGE ON SCHEMA public TO :\"backend_user\", :\"worker_user\";", script)

    def test_invalid_or_reused_identities_fail_before_any_connection(self):
        cases = {"uppercase role": {"POSTGRES_BACKEND_USER": "Fixture_backend"},
                 "dash in role": {"POSTGRES_WORKER_USER": "fixture-worker"},
                 "digit first": {"POSTGRES_OWNER_USER": "1owner"},
                 "reserved prefix": {"POSTGRES_OWNER_USER": "pg_owner"},
                 "too long": {"POSTGRES_WORKER_USER": "w" * 64},
                 "blank role": {"POSTGRES_BACKEND_USER": ""},
                 "missing password": {"POSTGRES_WORKER_PASSWORD": None},
                 "blank password": {"POSTGRES_OWNER_PASSWORD": "  "},
                 "missing database": {"POSTGRES_DB": None},
                 "owner is bootstrap": {"POSTGRES_OWNER_USER": "fixture_bootstrap"},
                 "worker is backend": {"POSTGRES_WORKER_USER": "fixture_backend"},
                 "backend reuses bootstrap secret": {"POSTGRES_BACKEND_PASSWORD": "fixture-bootstrap-secret"},
                 "worker reuses owner secret": {"POSTGRES_WORKER_PASSWORD": "fixture-owner-secret"}}
        for label, overrides in cases.items():
            with self.subTest(label):
                fixture, result = self.start(**overrides)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stderr.strip(), "Database role initialization failed: configuration validation")
                self.assertIsNone(fixture.read("argv"))

    def test_unreachable_server_fails_after_bounded_retries(self):
        fixture, result = self.start(NO_SERVER="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stderr.strip(), "Database role initialization failed: authentication or endpoint unavailable")
        self.assertEqual(fixture.read("argv").count("SELECT 1"), 30)
        self.assertIsNone(fixture.read("script.sql"))

    def test_failure_names_the_last_phase_without_tool_output(self):
        for phase in ("role provisioning", "database privileges", "schema privileges", "ownership transfer"):
            with self.subTest(phase):
                _, result = self.start(FAIL_AFTER=phase)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stderr.strip(), "Database role initialization failed: " + phase)
                self.assertNotIn("ERROR", result.stdout + result.stderr)


class FlywayGrantCallbackTests(unittest.TestCase):
    def test_callback_resets_then_grants_only_the_documented_privileges(self):
        statements = [line.strip() for line in (REPOSITORY / "deploy/flyway/afterMigrate.sql").read_text(
            encoding="utf-8").splitlines() if line.strip() and not line.startswith("--")]
        self.assertEqual(statements, [
            'REVOKE ALL ON ALL TABLES IN SCHEMA public FROM "${backend_role}", "${worker_role}";',
            'REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM "${backend_role}", "${worker_role}";',
            'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "${backend_role}";',
            'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "${backend_role}";',
            'REVOKE ALL ON TABLE "${flyway:table}" FROM "${backend_role}";',
            'GRANT SELECT, UPDATE ON TABLE encoding_jobs, video_assets TO "${worker_role}";'])
        compose = (REPOSITORY / "deploy/docker-compose.yml").read_text(encoding="utf-8")
        self.assertIn("FLYWAY_PLACEHOLDERS_BACKEND_ROLE: ${POSTGRES_BACKEND_USER:-vod_backend}", compose)
        self.assertIn("FLYWAY_PLACEHOLDERS_WORKER_ROLE: ${POSTGRES_WORKER_USER:-vod_worker}", compose)


if __name__ == "__main__":
    unittest.main()
