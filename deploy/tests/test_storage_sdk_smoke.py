"""SDK smoke gating and safe diagnostics without Docker or credentials."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("sdk_smoke", Path(__file__).with_name("storage-sdk-smoke.py"))
SDK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SDK)


class SdkSmokeTests(unittest.TestCase):
    def test_only_executed_passing_suite_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "suite.xml"
            with self.assertRaisesRegex(AssertionError, "report missing"):
                SDK.require_executed_tests(report)
            for attributes in ('tests="0"', 'tests="1" skipped="1"',
                               'tests="1" failures="1"', 'tests="1" errors="1"'):
                report.write_text("<testsuite " + attributes + "/>", encoding="utf-8")
                with self.subTest(attributes=attributes), self.assertRaises(AssertionError):
                    SDK.require_executed_tests(report)
            report.write_text('<testsuite tests="4" skipped="0" failures="0" errors="0"/>', encoding="utf-8")
            SDK.require_executed_tests(report)

    def test_cleanup_does_not_replace_original_failure(self):
        def fail(message):
            raise AssertionError(message)
        with self.assertRaisesRegex(AssertionError, "original failure"):
            SDK.preserve_first_failure(lambda: fail("original failure"), lambda: fail("cleanup failure"))
        with self.assertRaisesRegex(AssertionError, "cleanup failure"):
            SDK.preserve_first_failure(lambda: None, lambda: fail("cleanup failure"))

    def test_assertion_diagnostic_is_recorded_before_cleanup_failure(self):
        events = []
        def action():
            raise AssertionError("StorageRuntimeTests were skipped")
        def cleanup():
            events.append("cleanup")
            raise RuntimeError("cleanup payload")
        with self.assertRaisesRegex(AssertionError, "were skipped"):
            SDK.preserve_first_failure(action, cleanup, lambda error: events.append(str(error)))
        self.assertEqual(events, ["StorageRuntimeTests were skipped", "cleanup"])

    def test_redacts_all_storage_credentials_including_root_audit_pair(self):
        env = {name: "fixture-" + str(index) for index, name in enumerate((
            "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY", "MINIO_BACKEND_SECRET_KEY", "MINIO_WORKER_SECRET_KEY",
            "MINIO_TEST_ROOT_ACCESS_KEY", "MINIO_TEST_ROOT_SECRET_KEY"))}
        self.assertEqual(SDK.redacted(" ".join(env.values()).encode(), env), " ".join(["[redacted]"] * len(env)))
