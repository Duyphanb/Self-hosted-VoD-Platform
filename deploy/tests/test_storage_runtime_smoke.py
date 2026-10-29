"""Exercise the real smoke entrypoint's safe failure diagnostics, without Docker/network."""
import ast
import json
import importlib.util
import os
from pathlib import Path
import runpy
import subprocess
import sys
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("storage-runtime-smoke.py").resolve()
SENSITIVE = "fixture-sensitive-command-output"
SPEC = importlib.util.spec_from_file_location("runtime_smoke", SCRIPT)
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class StorageFailureDiagnosticsTests(unittest.TestCase):
    def failure(self, **mock_options):
        original_directory = Path.cwd()
        try:
            with patch.object(sys, "argv", [str(SCRIPT), "--project", "vod-auth-audit-unit"]), \
                    patch("subprocess.run", **mock_options), self.assertRaises(SystemExit) as caught:
                runpy.run_path(str(SCRIPT), run_name="__main__")
            result = str(caught.exception)
            self.assertNotIn(SENSITIVE, result)
            return result
        finally:
            os.chdir(original_directory)

    def test_assertion_names_the_failed_check_without_dumping_config(self):
        config = {"services": {"minio": {"image": "untrusted", "environment": {"MINIO_ROOT_PASSWORD": SENSITIVE}},
                               "minio-init": {"image": "another-image"}}}
        result = subprocess.CompletedProcess(["docker"], 0, stdout=json.dumps(config), stderr="")
        self.assertEqual(self.failure(return_value=result), "FAIL storage smoke: Storage image pin mismatch")

    def test_own_command_error_names_tool_but_withholds_its_output(self):
        result = subprocess.CompletedProcess(["docker"], 1, stdout=SENSITIVE, stderr=SENSITIVE)
        self.assertEqual(self.failure(return_value=result),
                         "FAIL storage smoke: Storage smoke command failed (output withheld): docker")

    def test_unexpected_exceptions_withhold_payload_and_command(self):
        for error in (ValueError(SENSITIVE), RuntimeError(SENSITIVE),
                      subprocess.TimeoutExpired(["docker", SENSITIVE], 1)):
            with self.subTest(kind=type(error).__name__):
                self.assertEqual(self.failure(side_effect=error), "FAIL storage smoke: " + type(error).__name__)

    def test_every_assertion_message_is_a_fixed_string(self):
        for node in ast.walk(ast.parse(SCRIPT.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Assert):
                self.assertIsInstance(node.msg, ast.Constant)
                self.assertIsInstance(node.msg.value, str)

    def test_script_failure_relays_only_known_fixed_check_labels(self):
        result = subprocess.CompletedProcess(["docker"], 1, stdout=SENSITIVE,
                                             stderr="SMOKE_CHECK:force-delete-exact-attempt\n" + SENSITIVE)
        with self.assertRaisesRegex(SMOKE.SmokeCommandError, "Storage check failed: force-delete-exact-attempt"):
            SMOKE.require_script_success(result)
        result.stderr = "SMOKE_CHECK:" + SENSITIVE + "\n"
        with self.assertRaises(SMOKE.SmokeCommandError) as caught:
            SMOKE.require_script_success(result)
        self.assertEqual(str(caught.exception), "Storage smoke script failed (output withheld)")
        self.assertNotIn(SENSITIVE, str(caught.exception))


if __name__ == "__main__":
    unittest.main()
