"""Lightweight checks of rehearsal measurements and destructive-operation guards."""
import importlib.util
import os
from pathlib import Path
import re
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid

SPEC = importlib.util.spec_from_file_location("upload_rehearsal", Path(__file__).with_name("upload-rehearsal.py"))
REHEARSAL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REHEARSAL)


class UploadRehearsalTests(unittest.TestCase):
    def test_memory_peak_units_are_not_confused_with_the_limit(self):
        self.assertEqual(REHEARSAL.memory_bytes("123.5MiB / 768MiB"), int(123.5 * 1024**2))
        self.assertEqual(REHEARSAL.memory_bytes("1.5GiB / 2GiB"), int(1.5 * 1024**3))
        self.assertEqual(REHEARSAL.memory_bytes("100MB / 1GB"), 100_000_000)
        with self.assertRaises(REHEARSAL.RehearsalError):
            REHEARSAL.memory_bytes("unknown")

    def test_sparse_fixture_has_exact_logical_size_and_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.mp4"
            result = REHEARSAL.make_fixture(path, 128 * 1024)
            self.assertEqual(result["logical_bytes"], 128 * 1024)
            with path.open("rb") as fixture:
                self.assertEqual(fixture.read(len(REHEARSAL.SIGNATURE)), REHEARSAL.SIGNATURE)
                fixture.seek(-1, 2)
                self.assertEqual(fixture.read(), b"\x00")
            # Never truncate or overwrite an existing file, even in a test fixture directory.
            with self.assertRaises(FileExistsError):
                REHEARSAL.make_fixture(path, 64)

    def test_cleanup_refuses_non_rehearsal_projects_before_any_command(self):
        with patch.object(REHEARSAL, "command") as command:
            for project in ("vod-platform", "", "vod-auth-audit-other", "vod-auth-audit-rehearsal-*"):
                with self.assertRaises(REHEARSAL.RehearsalError):
                    REHEARSAL.cleanup_project(project)
            command.assert_not_called()

    def test_cleanup_rechecks_exact_label_before_removing(self):
        with patch.object(REHEARSAL, "project_resources", return_value=["owned-id"]), \
                patch.object(REHEARSAL, "command", return_value=SimpleNamespace(stdout="another-project\n")) as command:
            with self.assertRaises(REHEARSAL.RehearsalError):
                REHEARSAL.cleanup_project("vod-auth-audit-rehearsal-unit")
            self.assertEqual(len(command.call_args_list), 1)
            self.assertEqual(command.call_args.args[0][:3], ["docker", "container", "inspect"])

    def test_capacity_requires_both_spool_and_raw_plus_reserve(self):
        free = REHEARSAL.FIXTURE_BYTES * 2 + REHEARSAL.RESERVE_BYTES - 1
        snapshot = {"runner_temp": {"free_bytes": free}, "docker_data_root": {"free_bytes": free}}
        with patch.object(REHEARSAL, "disk_snapshot", return_value=snapshot):
            with self.assertRaises(REHEARSAL.RehearsalError):
                REHEARSAL.require_capacity(Path("."), Path("."))

    def test_permission_denied_disk_probe_uses_exact_path_read_only(self):
        docker_root = Path("/var/lib/docker")
        with patch.object(REHEARSAL.shutil, "disk_usage", side_effect=PermissionError), \
                patch.object(REHEARSAL, "command", return_value=SimpleNamespace(
                    stdout="1B-blocks Used Avail\n1000 400 500\n")) as command:
            self.assertEqual(REHEARSAL.disk_usage_bytes(docker_root),
                             {"total_bytes": 1000, "used_bytes": 400, "free_bytes": 500})
        command.assert_called_once_with(["sudo", "-n", "df", "-B1", "--output=size,used,avail", "--", str(docker_root)])

    def test_privileged_disk_probe_fails_closed_on_error_or_bad_output(self):
        with patch.object(REHEARSAL.shutil, "disk_usage", side_effect=PermissionError):
            with patch.object(REHEARSAL, "command", side_effect=REHEARSAL.RehearsalError("probe failed")):
                with self.assertRaises(REHEARSAL.RehearsalError):
                    REHEARSAL.disk_usage_bytes(Path("/var/lib/docker"))
            for output in ("", "bad output\nunknown\n", "size used free\n100 90 50\n"):
                with patch.object(REHEARSAL, "command", return_value=SimpleNamespace(stdout=output)):
                    with self.assertRaises(REHEARSAL.RehearsalError):
                        REHEARSAL.disk_usage_bytes(Path("/var/lib/docker"))

    def test_disk_probe_does_not_mask_non_permission_errors(self):
        with patch.object(REHEARSAL.shutil, "disk_usage", side_effect=FileNotFoundError), \
                patch.object(REHEARSAL, "command") as command:
            with self.assertRaises(FileNotFoundError):
                REHEARSAL.disk_usage_bytes(Path("/missing/data-root"))
            command.assert_not_called()

    def test_incomplete_sampling_cannot_be_reported_as_success(self):
        sampler = REHEARSAL.Sampler({}, "backend", Path("."), Path("."), {})
        with self.assertRaises(REHEARSAL.RehearsalError):
            sampler.summary()
        sampler.samples = [{"spool_logical_bytes": 1}]
        sampler.failures = 1
        with self.assertRaises(REHEARSAL.RehearsalError):
            sampler.summary()

    def test_ambient_deployment_configuration_is_not_inherited(self):
        with patch.dict(os.environ, {"POSTGRES_HOST": "production.invalid", "JWT_SECRET": "old-secret",
                                     "MINIO_ENDPOINT": "http://production.invalid", "MAX_UPLOAD_SIZE_MB": "1"}):
            environment = REHEARSAL.new_environment(18088)
        self.assertNotIn("POSTGRES_HOST", environment)
        self.assertNotIn("MINIO_ENDPOINT", environment)
        self.assertNotEqual(environment["JWT_SECRET"], "old-secret")
        self.assertEqual(environment["MAX_UPLOAD_SIZE_MB"], "2048")
        self.assertEqual(environment["NGINX_HTTP_PORT"], "0.0.0.0:18088")

    def test_workflow_is_dispatch_only_on_main(self):
        workflow = (REHEARSAL.ROOT / ".github/workflows/upload-rehearsal.yml").read_text(encoding="utf-8")
        trigger = re.search(r'^"on":\n((?:[ \t]+[^\n]*\n|\n)*)', workflow, re.M)
        self.assertIsNotNone(trigger)
        self.assertEqual(trigger.group(1).strip(), "workflow_dispatch:")
        self.assertIn("if: github.ref == 'refs/heads/main'", workflow)
        self.assertIn("--cleanup-only", workflow)

    def test_saving_wait_starts_after_the_last_multipart_byte(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "fixture.mp4"
            REHEARSAL.make_fixture(fixture, 128)
            asset = uuid.uuid4()
            connection = Mock()
            connection.getresponse.return_value = SimpleNamespace(
                status=202, read=lambda _limit: ('{"videoAssetId":"' + str(asset) + '","status":"QUEUED"}').encode())
            with patch.object(REHEARSAL.http.client, "HTTPConnection", return_value=connection), \
                    patch.object(REHEARSAL.time, "monotonic", side_effect=(1.0, 3.0, 7.0)):
                received_asset, timings = REHEARSAL.send_upload(18088, uuid.uuid4(), "unit-token", fixture)
            self.assertEqual(received_asset, asset)
            self.assertEqual(timings["send_seconds"], 2.0)
            self.assertEqual(timings["saving_wait_seconds"], 4.0)
            self.assertEqual(timings["total_client_seconds"], 6.0)
            sent = b"".join(call.args[0] for call in connection.send.call_args_list)
            headers = dict(call.args for call in connection.putheader.call_args_list)
            self.assertEqual(len(sent), int(headers["Content-Length"]))
            self.assertIn(REHEARSAL.SIGNATURE, sent)
            self.assertEqual(sent.count(b'name="file"'), 1)
            connection.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
