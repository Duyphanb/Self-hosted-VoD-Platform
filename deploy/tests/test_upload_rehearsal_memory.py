"""Cgroup memory measurements must distinguish cache from Docker MemUsage."""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("upload_rehearsal_memory", Path(__file__).with_name("upload-rehearsal.py"))
REHEARSAL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REHEARSAL)


class UploadRehearsalMemoryTests(unittest.TestCase):
    def test_memory_stat_parses_byte_counters_without_conflating_other_fields(self):
        source = "anon 12345\nfile 987654\nkernel 42\nactive_file 800000\ninactive_file 187654\n"
        self.assertEqual(REHEARSAL.parse_memory_stat(source), {"anon_bytes": 12345, "file_bytes": 987654})
        self.assertEqual(REHEARSAL.parse_memory_stat("file 0\nanon 0\n"), {"anon_bytes": 0, "file_bytes": 0})

    def test_memory_stat_rejects_missing_malformed_negative_or_duplicate_counters(self):
        for source in ("", "anon 1\n", "anon 1\nfile -2\n", "anon 1\nfile 2.5\n",
                       "anon 1\nfile 2\nanon 3\n", "anon 1\nfile 2 extra\n", "anon 1\nfile 2\nbroken\n"):
            with self.subTest(source=source), self.assertRaises(REHEARSAL.RehearsalError):
                REHEARSAL.parse_memory_stat(source)

    def test_missing_memory_stat_is_explicit_but_exec_errors_are_fatal(self):
        with patch.object(REHEARSAL, "command", return_value=SimpleNamespace(returncode=42, stdout="")):
            result = REHEARSAL.container_memory_stat("container-id")
            self.assertEqual(result["status"], "unavailable")
            self.assertIsNone(result["anon_bytes"])
            self.assertIsNone(result["file_bytes"])
            self.assertIn("cgroup v1", result["reason"])
        for returncode in (1, 125, 126, 127):
            with self.subTest(returncode=returncode), patch.object(
                    REHEARSAL, "command", return_value=SimpleNamespace(returncode=returncode, stdout="")):
                with self.assertRaises(REHEARSAL.RehearsalError):
                    REHEARSAL.container_memory_stat("container-id")

    def test_available_memory_stat_is_parsed_and_corrupt_success_stays_fatal(self):
        with patch.object(REHEARSAL, "command", return_value=SimpleNamespace(returncode=0, stdout="anon 12\nfile 34\n")):
            self.assertEqual(REHEARSAL.container_memory_stat("container-id"),
                             {"status": "available", "anon_bytes": 12, "file_bytes": 34})
        with patch.object(REHEARSAL, "command", return_value=SimpleNamespace(returncode=0, stdout="anon 12\n")):
            with self.assertRaises(REHEARSAL.RehearsalError):
                REHEARSAL.container_memory_stat("container-id")

    def test_sampler_reads_each_service_and_rejects_partial_memory_stat_failure(self):
        ids = {"backend": "a" * 64, "nginx": "b" * 64}
        stats = "\n".join(json.dumps({"ID": value[:12], "MemUsage": "100MiB / 768MiB"}) for value in ids.values())
        disk = {name: {"free_bytes": 10 * 1024**3} for name in ("runner_temp", "docker_data_root")}
        counters = {"status": "available", "anon_bytes": 10, "file_bytes": 90}
        sampler = REHEARSAL.Sampler(ids, ids["backend"], Path("."), Path("."), {})
        with patch.object(REHEARSAL, "command", side_effect=[SimpleNamespace(stdout=stats), SimpleNamespace(stdout="20 1\n")]), \
                patch.object(REHEARSAL, "container_memory_stat", return_value=counters) as read_stat, \
                patch.object(REHEARSAL, "disk_snapshot", return_value=disk):
            sampler.sample()
        self.assertEqual([call.args[0] for call in read_stat.call_args_list], list(ids.values()))
        self.assertEqual(sampler.samples[0]["container_memory_stat"], {name: counters for name in ids})
        sampler.samples.clear()
        with patch.object(REHEARSAL, "command", return_value=SimpleNamespace(stdout=stats)), \
                patch.object(REHEARSAL, "container_memory_stat", side_effect=REHEARSAL.RehearsalError("probe failed")):
            with self.assertRaises(REHEARSAL.RehearsalError):
                sampler.sample()
        self.assertEqual(sampler.samples, [])

    def test_summary_keeps_independent_observed_peaks_and_explicit_v1_absence(self):
        sampler = REHEARSAL.Sampler({"backend": "a", "nginx": "b"}, "a", Path("."), Path("."), {})
        unavailable = {"status": "unavailable", "reason": "cgroup v1 or memory.stat is not exposed",
                       "anon_bytes": None, "file_bytes": None}
        disk = {name: {"free_bytes": 10 * 1024**3} for name in ("runner_temp", "docker_data_root")}
        for mem, anon, file in ((900, 200, 800), (850, 300, 700)):
            sampler.samples.append({"container_memory_bytes": {"backend": mem, "nginx": 50},
                                    "container_memory_stat": {"backend": {"status": "available", "anon_bytes": anon,
                                                                           "file_bytes": file}, "nginx": unavailable},
                                    "spool_logical_bytes": 1, "spool_allocated_bytes": 512, "disk": disk})
        result = sampler.summary()
        self.assertEqual(result["container_memory_observed_peak_bytes"], {"backend": 900, "nginx": 50})
        self.assertEqual(result["container_memory_stat_observed_peaks"]["backend"],
                         {"status": "available", "anon_bytes": 300, "file_bytes": 800})
        self.assertEqual(result["container_memory_stat_observed_peaks"]["nginx"], unavailable)
        for phrase in ("active page cache", "container memory limit", "neither JVM heap nor process RSS"):
            self.assertIn(phrase, result["sampling_note"])

    def test_availability_changes_cannot_be_hidden_by_a_peak(self):
        sampler = REHEARSAL.Sampler({"backend": "a"}, "a", Path("."), Path("."), {})
        for status in ("available", "unavailable"):
            sampler.samples.append({"container_memory_bytes": {"backend": 1},
                                    "container_memory_stat": {"backend": {"status": status}}})
        with self.assertRaises(REHEARSAL.RehearsalError):
            sampler.summary()


if __name__ == "__main__":
    unittest.main()
