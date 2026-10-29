"""Exercise init's real shell/stdin protocol without Docker or storage access."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]
SHELL = shutil.which("sh")
if SHELL is None:
    git_shell = Path("C:/Program Files/Git/bin/sh.exe")
    SHELL = str(git_shell) if git_shell.exists() else None


@unittest.skipUnless(SHELL, "POSIX shell is required for init protocol tests")
class StorageInitProtocolTests(unittest.TestCase):
    def execute(self, failed_step=""):
        with tempfile.TemporaryDirectory(prefix=".storage-init-test-", dir=REPOSITORY / "deploy/tests") as directory:
            temporary = Path(directory)
            # Keep all files created by the real script inside this test fixture.
            source = (REPOSITORY / "deploy/minio-init.sh").read_text(encoding="utf-8")
            script = temporary / "init.sh"
            script.write_text(source.replace("/tmp/", temporary.as_posix() + "/"), encoding="utf-8", newline="\n")
            executable = temporary / "mc"
            executable.write_text('''#!/bin/sh
set -eu
for argument in "$@"; do
 case "$argument" in "$MINIO_SECRET_KEY"|"$MINIO_BACKEND_SECRET_KEY"|"$MINIO_WORKER_SECRET_KEY") exit 70;; esac
done
case "$1 $2 $3" in
 'alias set local')
  [ "$#" = 4 ]
  IFS= read -r access
  IFS= read -r secret
  [ "$access" = "$MINIO_ACCESS_KEY" ] && [ "$secret" = "$MINIO_SECRET_KEY" ]
  printf alias >> "$AUDIT/protocol"
  ;;
 'admin user add')
  [ "$#" = 5 ]
  IFS= read -r secret
  case "$5" in
   "$MINIO_BACKEND_ACCESS_KEY") [ "$secret" = "$MINIO_BACKEND_SECRET_KEY" ]; [ "$FAIL_STEP" != backend ];;
   "$MINIO_WORKER_ACCESS_KEY") [ "$secret" = "$MINIO_WORKER_SECRET_KEY" ]; [ "$FAIL_STEP" != worker ];;
   *) exit 71;;
  esac
  printf user >> "$AUDIT/protocol"
  ;;
 'admin policy create') cp "$6" "$AUDIT/$5.json" ;;
esac
''', encoding="utf-8", newline="\n")
            executable.chmod(0o755)
            environment = dict(os.environ, PATH=str(temporary) + os.pathsep + os.environ["PATH"],
                               AUDIT=temporary.as_posix(), FAIL_STEP=failed_step,
                               MINIO_ACCESS_KEY="fixture-root", MINIO_SECRET_KEY="fixture-root-secret",
                               MINIO_BACKEND_ACCESS_KEY="fixture-backend", MINIO_BACKEND_SECRET_KEY="fixture-backend-secret",
                               MINIO_WORKER_ACCESS_KEY="fixture-worker", MINIO_WORKER_SECRET_KEY="fixture-worker-secret",
                               MINIO_BUCKET_RAW="vod-raw", MINIO_BUCKET_HLS="vod-hls", MINIO_BUCKET_THUMBNAILS="vod-thumbnails")
            result = subprocess.run([SHELL, str(script)], env=environment, capture_output=True, text=True,
                                    encoding="utf-8", timeout=10)
            policies = {name: json.loads((temporary / (name + ".json")).read_text())
                        for name in ("vod-backend", "vod-worker") if (temporary / (name + ".json")).exists()}
            protocol = (temporary / "protocol").read_text() if (temporary / "protocol").exists() else ""
            for name in ("MINIO_SECRET_KEY", "MINIO_BACKEND_SECRET_KEY", "MINIO_WORKER_SECRET_KEY"):
                self.assertNotIn(environment[name], result.stdout + result.stderr)
            return result, protocol, policies

    def test_init_uses_stdin_for_all_secrets_and_installs_scoped_policies(self):
        result, protocol, policies = self.execute()
        self.assertEqual(result.returncode, 0, "Init protocol fixture failed")
        self.assertEqual(protocol, "aliasuseruser")
        self.assertEqual(set(policies), {"vod-backend", "vod-worker"})
        for policy in policies.values():
            deny = [statement for statement in policy["Statement"] if statement["Effect"] == "Deny"]
            self.assertEqual(deny, [{"Effect": "Deny", "Action": ["admin:CreateServiceAccount", "admin:ChangeMyPassword"]}])
            for statement in policy["Statement"]:
                for resource in statement.get("Resource", []):
                    self.assertIn("/", resource, "Bare bucket ARN must not be granted")
                self.assertNotIn("s3:ListBucket", statement["Action"])

    def test_provisioning_failure_names_the_step_without_secrets(self):
        result, _, _ = self.execute("backend")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Storage initialization failed: backend user provisioning", result.stderr)


if __name__ == "__main__":
    unittest.main()
