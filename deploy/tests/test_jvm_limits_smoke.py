"""Checks of jvm-limits-smoke.py (#124) that need no Docker."""
import importlib.util
import json
import os
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("jvm_limits_smoke", Path(__file__).with_name("jvm-limits-smoke.py"))
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)
BACKEND_OPTIONS = "-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
FLAGS = """[Global flags]
     bool ExitOnOutOfMemoryError                   = true                                      {product} {environment}
   size_t InitialHeapSize                          = 12582912                                  {product} {ergonomic}
   size_t MaxHeapSize                              = 524288000                                 {product} {ergonomic}
   double MaxRAMPercentage                         = 65.000000                                 {product} {environment}
     bool UseSerialGC                              = true                                      {product} {ergonomic}
"""


def observed(**overrides):
    values = {"java_tool_options": BACKEND_OPTIONS, "main_jvm_picked_up_options": True,
              "flags": SMOKE.parse_flags(FLAGS), "memory_limit_bytes": 768 * 1024**2,
              "stats": {"memory_usage_peak_bytes": 320 * 1024**2}}
    values.update(overrides)
    return values


EXPECTED = {"java_tool_options": BACKEND_OPTIONS, "max_ram_percentage": 65, "memory_limit_bytes": 768 * 1024**2,
            "cpus": 1.0}


class JvmLimitsSmokeTests(unittest.TestCase):
    def test_flags_and_settings_are_parsed_from_jvm_output(self):
        flags = SMOKE.parse_flags(FLAGS)
        self.assertEqual(flags["MaxHeapSize"], "524288000")
        self.assertEqual(flags["ExitOnOutOfMemoryError"], "true")
        self.assertEqual(flags["MaxRAMPercentage"], "65.000000")
        with self.assertRaises(SMOKE.SmokeError):
            SMOKE.parse_flags("Picked up JAVA_TOOL_OPTIONS: -XX:+ExitOnOutOfMemoryError\n")
        settings = SMOKE.parse_settings("VM settings:\n    Max. Heap Size (Estimated): 483.38M\n"
                                        "Operating System Metrics:\n    Provider: cgroupv2\n"
                                        "    Effective CPU Count: 1\n    Memory Limit: 768.00M\n")
        self.assertEqual(settings, {"max_heap_estimated": "483.38M", "cgroup_provider": "cgroupv2",
                                    "effective_cpu_count": "1", "memory_limit": "768.00M"})

    def test_overlay_settings_are_read_from_the_composed_configuration(self):
        services = {"backend": {"environment": {"JAVA_TOOL_OPTIONS": BACKEND_OPTIONS},
                                "deploy": {"resources": {"limits": {"cpus": 1, "memory": "805306368"}}}},
                    "worker": {"environment": {"JAVA_TOOL_OPTIONS": "-XX:MaxRAMPercentage=30 -XX:+ExitOnOutOfMemoryError"},
                               "deploy": {"resources": {"limits": {"cpus": "1.00", "memory": "1024M"}}}}}
        expected = SMOKE.expected_settings(services)
        self.assertEqual(expected["backend"], EXPECTED)
        self.assertEqual(expected["worker"]["memory_limit_bytes"], 1024**3)
        self.assertEqual(expected["worker"]["max_ram_percentage"], 30)
        services["worker"]["environment"]["JAVA_TOOL_OPTIONS"] = "-XX:MaxRAMPercentage=30"
        with self.assertRaises(SMOKE.SmokeError):
            SMOKE.expected_settings(services)

    def test_a_jvm_running_as_the_overlay_intends_passes(self):
        self.assertEqual(SMOKE.check_service("backend", EXPECTED, observed()), [])

    def test_each_departure_from_the_overlay_is_reported(self):
        default_heap = SMOKE.parse_flags(FLAGS.replace("524288000", "201326592"))
        cases = {"did not receive": observed(java_tool_options=""),
                 "did not report picking up": observed(main_jvm_picked_up_options=False),
                 "ExitOnOutOfMemoryError is not enabled": observed(flags={**observed()["flags"],
                                                                          "ExitOnOutOfMemoryError": "false"}),
                 "MaxRAMPercentage differs": observed(flags={**observed()["flags"], "MaxRAMPercentage": "25.000000"}),
                 "memory limit differs": observed(memory_limit_bytes=0),
                 "MaxHeapSize does not follow": observed(flags=default_heap),
                 "reached the limit": observed(stats={"memory_usage_peak_bytes": 768 * 1024**2})}
        for expected_text, values in cases.items():
            with self.subTest(case=expected_text):
                failures = SMOKE.check_service("backend", EXPECTED, values)
                self.assertEqual(len(failures), 1, failures)
                self.assertIn(expected_text, failures[0])

    def test_stats_rows_map_to_services_and_incomplete_samples_fail(self):
        ids = {"backend": "a" * 64, "worker": "b" * 64}
        rows = [{"ID": "a" * 12, "MemUsage": "300MiB / 768MiB", "CPUPerc": "1.50%", "PIDs": "32"},
                {"ID": "b" * 12, "MemUsage": "145.2MiB / 1GiB", "CPUPerc": "0.20%", "PIDs": "29"}]
        sample = SMOKE.parse_stats("\n".join(json.dumps(row) for row in rows), ids)
        self.assertEqual(sample["backend"]["memory_usage_bytes"], 300 * 1024**2)
        self.assertEqual(sample["worker"]["memory_limit_bytes"], 1024**3)
        self.assertEqual(sample["worker"]["pids"], 29)
        with self.assertRaises(SMOKE.SmokeError):
            SMOKE.parse_stats(json.dumps(rows[0]), ids)

    def test_sizes_cover_compose_and_docker_stats_units(self):
        for text, expected in (("805306368", 805306368), ("768M", 768 * 1024**2), ("768MiB", 768 * 1024**2),
                               ("1.5GiB", int(1.5 * 1024**3)), ("123456k", 123456 * 1024), ("100MB", 100_000_000)):
            with self.subTest(text=text):
                self.assertEqual(SMOKE.size_bytes(text), expected)
        with self.assertRaises(SMOKE.SmokeError):
            SMOKE.size_bytes("12 parsecs")

    def test_project_must_be_a_fresh_dedicated_name(self):
        for project in ("", "vod-platform", "Upper", "-leading", "a b", "x" * 64):
            with self.subTest(project=project), self.assertRaises(SMOKE.SmokeError):
                SMOKE.validate_project(project)
        SMOKE.validate_project("vod-auth-audit-arm64-1-1-jvm")

    def test_compose_settings_and_jvm_overrides_are_not_inherited(self):
        ambient = {"POSTGRES_HOST": "production.invalid", "JWT_SECRET": "old-secret",
                   "BACKEND_JAVA_TOOL_OPTIONS": "-Xmx64m", "WORKER_JAVA_TOOL_OPTIONS": "-Xmx64m",
                   "COMPOSE_FILE": "other.yml", "DOCKER_DEFAULT_PLATFORM": "linux/amd64",
                   "MINIO_ENDPOINT": "http://production.invalid", "PATH": os.environ.get("PATH", "")}
        with patch.dict(os.environ, ambient):
            environment = SMOKE.new_environment()
        for name in ("POSTGRES_HOST", "BACKEND_JAVA_TOOL_OPTIONS", "WORKER_JAVA_TOOL_OPTIONS", "COMPOSE_FILE",
                     "DOCKER_DEFAULT_PLATFORM", "MINIO_ENDPOINT"):
            self.assertNotIn(name, environment)
        self.assertNotEqual(environment["JWT_SECRET"], "old-secret")
        self.assertEqual(environment["PATH"], ambient["PATH"])
        self.assertEqual(environment["MAX_UPLOAD_SIZE_MB"], "2048")
        self.assertFalse(environment["NGINX_HTTP_PORT"].startswith("127."))
        self.assertEqual(len({environment[name] for name in ("MINIO_ACCESS_KEY", "MINIO_BACKEND_ACCESS_KEY",
                                                              "MINIO_WORKER_ACCESS_KEY")}), 3)

    def test_cleanup_removes_only_resources_labelled_with_this_project(self):
        with patch.object(SMOKE, "project_resources",
                          return_value={"container": ["owned"], "network": [], "volume": []}), \
                patch.object(SMOKE, "command", return_value=SimpleNamespace(stdout="another-project\n")) as command:
            with self.assertRaises(SMOKE.SmokeError):
                SMOKE.cleanup_project("vod-auth-audit-unit")
        self.assertEqual(command.call_args.args[0][:3], ["docker", "container", "inspect"])

    def test_failures_withhold_command_output(self):
        secret = "fixture-sensitive-output"
        with patch.object(SMOKE.subprocess, "run", return_value=SimpleNamespace(returncode=1, stdout=secret,
                                                                                stderr=secret)):
            with self.assertRaises(SMOKE.SmokeError) as raised:
                SMOKE.command(["docker", "compose", "config"])
        self.assertNotIn(secret, str(raised.exception))


if __name__ == "__main__":
    unittest.main()
