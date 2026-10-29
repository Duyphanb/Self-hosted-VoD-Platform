"""Validate storage credential preflight locally, without Compose or services."""
import os
from pathlib import Path
import shutil
import subprocess
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]
SHELL = shutil.which("sh")
if SHELL is None:
    git_shell = Path("C:/Program Files/Git/bin/sh.exe")
    SHELL = str(git_shell) if git_shell.exists() else None


@unittest.skipUnless(SHELL, "POSIX shell is required for preflight tests")
class StorageCredentialPreflightTests(unittest.TestCase):
    def execute(self, name=None, value=None):
        environment = dict(os.environ, POSTGRES_USER="fixture-postgres", POSTGRES_PASSWORD="fixture-pg-secret",
                           RABBITMQ_USERNAME="fixture-rabbit", RABBITMQ_PASSWORD="fixture-rabbit-secret",
                           MINIO_ACCESS_KEY="fixture-root", MINIO_SECRET_KEY="fixture-root-secret",
                           MINIO_BACKEND_ACCESS_KEY="fixture-backend", MINIO_BACKEND_SECRET_KEY="fixture-backend-secret",
                           MINIO_WORKER_ACCESS_KEY="fixture-worker", MINIO_WORKER_SECRET_KEY="fixture-worker-secret",
                           JWT_SECRET="fixture-jwt-secret-longer-than-thirty-two-bytes", NGINX_HTTP_PORT="80",
                           MINIO_BROWSER="off", LC_ALL="C")
        if name:
            environment[name] = value
        result = subprocess.run([SHELL, str(REPOSITORY / "deploy/tests/production-config-check.sh")],
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


if __name__ == "__main__":
    unittest.main()
