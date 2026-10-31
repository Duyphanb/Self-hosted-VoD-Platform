"""Disposable-project guards and broker evidence gating without running Docker."""
import importlib.util
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("rabbitmq_smoke", Path(__file__).with_name("rabbitmq-publisher-smoke.py"))
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class RabbitmqPublisherSmokeTests(unittest.TestCase):
    def test_project_name_cannot_target_normal_or_empty_project(self):
        for name in ("", "vod-platform", "vod-auth-audit-", "vod-auth-audit-X", "vod-auth-audit-../other"):
            with self.subTest(name=name), self.assertRaisesRegex(AssertionError, "Disposable audit project"):
                SMOKE.require_disposable_project(name)
        SMOKE.require_disposable_project("vod-auth-audit-170-ci_1")

    def test_any_existing_resource_prevents_project_reuse(self):
        for existing_at in range(3):
            calls = []
            def run(command):
                calls.append(command)
                return "owned elsewhere" if len(calls) - 1 == existing_at else ""
            with self.subTest(existing_at=existing_at), self.assertRaisesRegex(AssertionError, "refusing reuse"):
                SMOKE.require_fresh_project(run, "vod-auth-audit-170")
            self.assertEqual(len(calls), existing_at + 1)
            self.assertTrue(all(command[-1] == "label=com.docker.compose.project=vod-auth-audit-170"
                                for command in calls))

    def test_only_executed_unskipped_successful_suite_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "suite.xml"
            with self.assertRaisesRegex(AssertionError, "report missing"):
                SMOKE.require_executed_tests(report)
            for attributes in ('tests="0"', 'tests="1" skipped="1"',
                               'tests="1" failures="1"', 'tests="1" errors="1"'):
                report.write_text("<testsuite " + attributes + "/>", encoding="utf-8")
                with self.subTest(attributes=attributes), self.assertRaises(AssertionError):
                    SMOKE.require_executed_tests(report)
            report.write_text('<testsuite tests="4" skipped="0" failures="0" errors="0"/>', encoding="utf-8")
            SMOKE.require_executed_tests(report)

    def test_worker_and_backend_have_separate_report_locations(self):
        paths = [SMOKE.report_path(Path("repo"), *suite) for suite in SMOKE.SUITES]
        self.assertEqual(len(set(paths)), 2)
        self.assertIn("worker/queue/target/surefire-reports", paths[0].as_posix())
        self.assertIn("backend/common/target/surefire-reports", paths[1].as_posix())

    def test_redacts_production_and_test_credentials_longest_first(self):
        env = {"RABBITMQ_USERNAME": "audit-user", "RABBITMQ_PASSWORD": "secret",
               "ENCODING_TEST_RABBITMQ_PASSWORD": "secret-long", "RABBITMQ_HOST": "127.0.0.1"}
        self.assertEqual(SMOKE.redacted(b"audit-user secret secret-long 127.0.0.1", env),
                         "[redacted] [redacted] [redacted] 127.0.0.1")

    def test_cleanup_failure_preserves_the_original_and_records_it_first(self):
        events = []
        def action():
            raise AssertionError("original failure")
        def cleanup():
            events.append("cleanup")
            raise RuntimeError("cleanup payload")
        with self.assertRaisesRegex(AssertionError, "original failure"):
            SMOKE.preserve_first_failure(action, cleanup, lambda error: events.append(str(error)))
        self.assertEqual(events, ["original failure", "cleanup"])
        with self.assertRaisesRegex(RuntimeError, "cleanup payload"):
            SMOKE.preserve_first_failure(lambda: None, cleanup)

    def test_diagnostic_error_cannot_prevent_cleanup(self):
        events = []
        def fail():
            raise AssertionError("test failure")
        def record(error):
            raise RuntimeError("diagnostic failure")
        with self.assertRaisesRegex(AssertionError, "test failure"):
            SMOKE.preserve_first_failure(fail, lambda: events.append("cleanup"), record)
        self.assertEqual(events, ["cleanup"])
