"""Issue 5.8: worker runtime hardening in Compose and worker-stack-smoke.py checks that need no running stack."""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("worker_stack_smoke", Path(__file__).with_name("worker-stack-smoke.py"))
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


def hardened(**changes):
    inspected = {
        "Config": {"User": "", "StopTimeout": 30, "Env": ["WORKER_TEMP_DIR=/tmp/vod-worker", "PATH=/usr/bin"]},
        "HostConfig": {"ReadonlyRootfs": True, "CapDrop": ["ALL"], "CapAdd": None,
                       "SecurityOpt": ["no-new-privileges:true"], "Privileged": False, "PidsLimit": 256,
                       "Tmpfs": None},
        "Mounts": [{"Type": "volume", "Name": "p_worker_temp", "Destination": "/tmp", "RW": True}],
    }
    for path, value in changes.items():
        section, key = path.split(".")
        if section == "Mounts":
            inspected["Mounts"] = value
        else:
            inspected[section][key] = value
    return inspected


class HardeningChecks(unittest.TestCase):
    def test_the_compose_hardening_passes(self):
        self.assertEqual(SMOKE.hardening_failures(hardened(), "10001:10001"), [])

    def test_each_missing_control_is_named(self):
        cases = {
            "the container user is root": (hardened(), "root"),
            "the root filesystem is writable": (hardened(**{"HostConfig.ReadonlyRootfs": False}), "10001"),
            "not every capability is dropped": (hardened(**{"HostConfig.CapDrop": ["NET_RAW"]}), "10001"),
            "capabilities are added back": (hardened(**{"HostConfig.CapAdd": ["CHOWN"]}), "10001"),
            "no-new-privileges is not set": (hardened(**{"HostConfig.SecurityOpt": None}), "10001"),
            "the container is privileged": (hardened(**{"HostConfig.Privileged": True}), "10001"),
            "no positive pids limit": (hardened(**{"HostConfig.PidsLimit": 0}), "10001"),
            "the stop timeout is not 30 s": (hardened(**{"Config.StopTimeout": 10}), "10001"),
            "a tmpfs mount exists": (hardened(**{"HostConfig.Tmpfs": {"/run": ""}}), "10001"),
            "/tmp is not one writable named volume": (
                hardened(**{"Mounts.all": [{"Type": "tmpfs", "Destination": "/tmp", "RW": True}]}), "10001"),
            "WORKER_TEMP_DIR is not below the /tmp volume": (
                hardened(**{"Config.Env": ["WORKER_TEMP_DIR=/var/vod-worker"]}), "10001"),
        }
        for expected, (inspected, user) in cases.items():
            with self.subTest(expected):
                self.assertIn(expected, " ".join(SMOKE.hardening_failures(inspected, user)))

    def test_an_explicit_root_user_overrides_the_image_user(self):
        failures = SMOKE.hardening_failures(hardened(**{"Config.User": "0:0"}), "10001:10001")
        self.assertIn("the container user is root", failures)

    def test_a_second_writable_mount_is_rejected(self):
        mounts = hardened()["Mounts"] + [{"Type": "bind", "Destination": "/opt/worker/data", "RW": True}]
        failures = SMOKE.hardening_failures(hardened(**{"Mounts.all": mounts}), "10001")
        self.assertIn("writable mounts other than /tmp: /opt/worker/data", failures)

    def test_a_temp_dir_escaping_the_volume_is_rejected(self):
        for temp_dir in ("/tmp", "/tmp/", "/tmp/../var/x", "relative/tmp"):
            with self.subTest(temp_dir):
                inspected = hardened(**{"Config.Env": ["WORKER_TEMP_DIR=" + temp_dir]})
                self.assertIn("WORKER_TEMP_DIR is not below the /tmp volume",
                              SMOKE.hardening_failures(inspected, "10001"))


class SmokeHelpers(unittest.TestCase):
    def test_multipart_body_has_one_file_part(self):
        content_type, body = SMOKE.multipart("file", "clip.mp4", "video/mp4", b"\x00media")
        boundary = content_type.split("boundary=", 1)[1]
        self.assertTrue(content_type.startswith("multipart/form-data; boundary="))
        self.assertTrue(body.startswith(f"--{boundary}\r\n".encode()))
        self.assertIn(b'name="file"; filename="clip.mp4"\r\nContent-Type: video/mp4\r\n\r\n\x00media\r\n', body)
        self.assertTrue(body.endswith(f"\r\n--{boundary}--\r\n".encode()))

    def test_job_rows_parse_and_reject_other_shapes(self):
        self.assertIsNone(SMOKE.parse_job_row(""))
        row = SMOKE.parse_job_row("READY|READY|2|video-assets/a/attempt-2/master.m3u8|")
        self.assertEqual(row, {"asset": "READY", "job": "READY", "attempt": 2,
                               "master": "video-assets/a/attempt-2/master.m3u8", "failure": ""})
        with self.assertRaises(SMOKE.SmokeError):
            SMOKE.parse_job_row("READY|READY")


class DockerfileFloor(unittest.TestCase):
    def test_the_image_records_one_ffmpeg_floor_and_a_non_root_user(self):
        text = (ROOT / "worker/Dockerfile").read_text(encoding="utf-8")
        floors = [line for line in text.splitlines() if line.startswith("ARG FFMPEG_MIN_VERSION=")]
        self.assertEqual(len(floors), 1)
        self.assertRegex(floors[0], r"^ARG FFMPEG_MIN_VERSION=\d+:\d+\.\d+\.\d+-\S+$")
        self.assertIn('dpkg --compare-versions "$(dpkg-query -W -f=\'${Version}\' ffmpeg)" ge "${FFMPEG_MIN_VERSION}"',
                      text)
        final = text.rsplit("\nFROM ", 1)[1]
        self.assertTrue(final.startswith("runtime-base\n") or final.startswith("runtime-base\r\n"))
        self.assertIn("USER 10001:10001", final)


@unittest.skipUnless(shutil.which("docker"), "docker compose is needed to render the configuration")
class ComposeHardening(unittest.TestCase):
    @staticmethod
    def render(*files):
        command = ["docker", "compose", "--env-file", os.devnull]
        environment = dict(os.environ)
        for name in files:
            path = ROOT / "deploy" / name
            command += ["-f", str(path)]
            # Required production variables (${NAME:?...}) get throwaway values; nothing is started.
            for variable in re.findall(r"\$\{([A-Z0-9_]+):\?", path.read_text(encoding="utf-8")):
                environment.setdefault(variable, "18099" if variable.endswith("_PORT")
                                       else "render-only-" + variable.lower())
        result = subprocess.run(command + ["config", "--format", "json"], capture_output=True, text=True,
                                env=environment, timeout=60)
        if result.returncode != 0:
            raise AssertionError("docker compose config failed for " + " + ".join(files) + ": "
                                 + result.stderr.strip()[-300:])
        return json.loads(result.stdout)

    def assert_hardened(self, configuration):
        worker = configuration["services"]["worker"]
        self.assertIs(worker.get("read_only"), True)
        self.assertEqual(worker.get("cap_drop"), ["ALL"])
        self.assertFalse(worker.get("cap_add"))
        self.assertIn("no-new-privileges:true", worker.get("security_opt") or [])
        self.assertFalse(worker.get("pids_limit"))
        self.assertEqual(worker["deploy"]["resources"]["limits"]["pids"], 256)
        self.assertEqual(worker.get("stop_grace_period"), "30s")
        self.assertIn(worker.get("user"), (None, ""))
        self.assertFalse(worker.get("tmpfs"))
        self.assertEqual(worker.get("volumes"), [{"type": "volume", "source": "worker_temp", "target": "/tmp"}])
        self.assertIn("worker_temp", configuration["volumes"])
        self.assertTrue(worker["environment"]["WORKER_TEMP_DIR"].startswith("/tmp/"))

    def test_the_base_file_hardens_the_worker(self):
        self.assert_hardened(self.render("docker-compose.yml"))

    def test_the_production_overlay_keeps_the_hardening(self):
        self.assert_hardened(self.render("docker-compose.yml", "docker-compose.prod.yml"))


if __name__ == "__main__":
    unittest.main()
