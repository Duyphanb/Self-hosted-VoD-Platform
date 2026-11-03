"""Validate production credential, upload-limit and JVM-option preflight without services."""
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
# #124 defaults, as issue #124 specifies them.
JVM_DEFAULTS = {"BACKEND_JAVA_TOOL_OPTIONS": "-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError",
                "WORKER_JAVA_TOOL_OPTIONS": "-XX:MaxRAMPercentage=30 -XX:+ExitOnOutOfMemoryError"}


def overlay_jvm_defaults():
    """Each `${NAME:-default}` expression of the production overlay, by variable name."""
    overlay = (REPOSITORY / "deploy/docker-compose.prod.yml").read_text(encoding="utf-8")
    found = {}
    for key, name, default in re.findall(r"^\s+([A-Z_]+): \$\{([A-Z_]+_JAVA_TOOL_OPTIONS):-([^}]*)\}$", overlay, re.M):
        found.setdefault(name, []).append((key, default))
    return found


@unittest.skipUnless(SHELL, "POSIX shell is required for preflight tests")
class StorageCredentialPreflightTests(unittest.TestCase):
    def execute(self, name=None, value=None, cwd=None):
        environment = dict(os.environ, POSTGRES_USER="fixture-postgres", POSTGRES_PASSWORD="fixture-pg-secret",
                           RABBITMQ_USERNAME="fixture-rabbit", RABBITMQ_PASSWORD="fixture-rabbit-secret",
                           MINIO_ACCESS_KEY="fixture-root", MINIO_SECRET_KEY="fixture-root-secret",
                           MINIO_BACKEND_ACCESS_KEY="fixture-backend", MINIO_BACKEND_SECRET_KEY="fixture-backend-secret",
                           MINIO_WORKER_ACCESS_KEY="fixture-worker", MINIO_WORKER_SECRET_KEY="fixture-worker-secret",
                           JWT_SECRET="fixture-jwt-secret-longer-than-thirty-two-bytes", NGINX_HTTP_PORT="80",
                           MINIO_BROWSER="off", MAX_UPLOAD_SIZE_MB="2048", LC_ALL="C", **JVM_DEFAULTS)
        if name:
            environment[name] = value
        result = subprocess.run([SHELL, str(REPOSITORY / "deploy/tests/production-config-check.sh")], cwd=cwd,
                                env=environment, capture_output=True, text=True, encoding="utf-8", timeout=10)
        for sensitive_name in ("MINIO_SECRET_KEY", "MINIO_BACKEND_SECRET_KEY", "MINIO_WORKER_SECRET_KEY"):
            self.assertNotIn(environment[sensitive_name], result.stdout + result.stderr)
        return result

    def test_access_key_rejects_silo_invalid_formats_for_each_identity(self):
        for name in ("MINIO_ACCESS_KEY", "MINIO_BACKEND_ACCESS_KEY", "MINIO_WORKER_ACCESS_KEY"):
            for value in ("xy", "bad=key", "bad,key", " leading-key", "trailing-key ", "\tleading-key", "trailing-key\n"):
                with self.subTest(name=name, format_case=repr(value)):
                    result = self.execute(name, value)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("Production configuration rejected: " + name, result.stderr)
                    self.assertNotIn(value, result.stdout + result.stderr)

    def test_secret_length_rejects_each_identity_and_accepts_boundary(self):
        for name in ("MINIO_SECRET_KEY", "MINIO_BACKEND_SECRET_KEY", "MINIO_WORKER_SECRET_KEY"):
            with self.subTest(name=name):
                result = self.execute(name, "short7x")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(name + " must have at least 8 characters", result.stderr)
                self.assertEqual(self.execute(name, "exactly8").returncode, 0)

    def test_go_unicode_whitespace_is_rejected_only_at_access_key_edges(self):
        unicode_whitespace = ("\u00a0", "\u2003")
        for name in ("MINIO_ACCESS_KEY", "MINIO_BACKEND_ACCESS_KEY", "MINIO_WORKER_ACCESS_KEY"):
            for whitespace in unicode_whitespace:
                for value in (whitespace + "leading-key", "trailing-key" + whitespace):
                    with self.subTest(name=name, codepoint=hex(ord(whitespace)), edge=value.startswith(whitespace)):
                        result = self.execute(name, value)
                        self.assertNotEqual(result.returncode, 0)
                        self.assertIn(name + " must not contain = or , or leading/trailing whitespace", result.stderr)
                        self.assertNotIn(value, result.stdout + result.stderr)
            for value in ("cl\u00e9-\u6c49\u5b57", "inside\u00a0key", "\u200bvalid-key", "valid-key\u200b"):
                with self.subTest(name=name, accepted_unicode=value):
                    self.assertEqual(self.execute(name, value).returncode, 0)

    def test_valid_minimum_access_key_and_baseline_pass(self):
        self.assertEqual(self.execute().returncode, 0)
        self.assertEqual(self.execute("MINIO_BACKEND_ACCESS_KEY", "abc").returncode, 0)

    def test_upload_limit_fails_closed_unless_it_equals_the_edge_literal(self):
        for value in ("1024", "4096", "", "2048MB", "02048", " 2048"):
            with self.subTest(value=value):
                result = self.execute("MAX_UPLOAD_SIZE_MB", value)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("MAX_UPLOAD_SIZE_MB must equal the frozen Nginx upload limit", result.stderr)
        self.assertEqual(self.execute("MAX_UPLOAD_SIZE_MB", "2048").returncode, 0)

    def test_overlay_gives_the_check_and_each_jvm_the_issue_defaults(self):
        expressions = overlay_jvm_defaults()
        self.assertEqual(sorted(expressions), sorted(JVM_DEFAULTS))
        for name, default in JVM_DEFAULTS.items():
            with self.subTest(name=name):
                # One expression for the preflight and one for the JVM, with the same default.
                self.assertEqual(sorted(expressions[name]), sorted([(name, default), ("JAVA_TOOL_OPTIONS", default)]))
                self.assertEqual(self.execute(name, default).returncode, 0)

    def test_jvm_options_must_keep_the_out_of_memory_exit(self):
        for name in JVM_DEFAULTS:
            for value in ("", " ", "-XX:MaxRAMPercentage=65", "-XX:MaxRAMPercentage=65 -XX:-ExitOnOutOfMemoryError",
                          "-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=65 -XX:-ExitOnOutOfMemoryError",
                          "-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryErrors",
                          "-XX:MaxRAMPercentage=65 -XX:+CrashOnOutOfMemoryError"):
                with self.subTest(name=name, value=value):
                    result = self.execute(name, value)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(f"Production configuration rejected: {name} must enable -XX:+ExitOnOutOfMemoryError",
                                  result.stderr)

    def test_jvm_options_must_size_the_heap_from_the_memory_limit(self):
        for name in JVM_DEFAULTS:
            for value in ("-XX:+ExitOnOutOfMemoryError", "-XX:+ExitOnOutOfMemoryError -Xmx512m",
                          "-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=",
                          "-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=0",
                          "-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=101",
                          "-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=065",
                          "-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=65.5",
                          "-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=-5",
                          "-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=sixty",
                          "-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=0"):
                with self.subTest(name=name, value=value):
                    result = self.execute(name, value)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(f"{name} must set -XX:MaxRAMPercentage to a whole number from 1 to 100", result.stderr)

    def test_jvm_options_reject_quoting_the_jvm_would_parse_differently(self):
        for name in JVM_DEFAULTS:
            for value in ("'-XX:+ExitOnOutOfMemoryError' -XX:MaxRAMPercentage=65",
                          '"-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=65"',
                          "-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=65 -Dpath=C:\\temp"):
                with self.subTest(name=name, value=value):
                    result = self.execute(name, value)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(f"{name} must list plain options without quotes or backslashes", result.stderr)

    def test_jvm_options_accept_overrides_that_keep_both_properties(self):
        for name in JVM_DEFAULTS:
            for value in ("-XX:-ExitOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=50",
                          "-XX:MaxRAMPercentage=1 -XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=100",
                          "-XX:+UseSerialGC\t-XX:MaxRAMPercentage=40 -XX:+ExitOnOutOfMemoryError -Xss512k"):
                with self.subTest(name=name, value=value):
                    result = self.execute(name, value)
                    self.assertEqual(result.returncode, 0, result.stderr)

    def test_jvm_options_are_not_glob_expanded(self):
        with tempfile.TemporaryDirectory() as directory:
            # A file named like a valid option must not turn a pattern into that option.
            option_file = "-XX:MaxRAMPercentage=65"
            try:
                (Path(directory) / option_file).touch()
            except OSError:
                pass
            if os.listdir(directory) != [option_file]:  # NTFS reads ':' as an alternate data stream
                self.skipTest("this file system cannot name a file like a JVM option")
            for name in JVM_DEFAULTS:
                with self.subTest(name=name):
                    result = self.execute(name, "-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=6?", cwd=directory)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(f"{name} must set -XX:MaxRAMPercentage", result.stderr)


if __name__ == "__main__":
    unittest.main()
