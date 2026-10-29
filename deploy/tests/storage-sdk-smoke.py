#!/usr/bin/env python3
"""Run the real Java storage adapter with a smaller heap than its generated objects.

Owns a fresh disposable Compose project; never reuses an existing project/volume.
Credentials are generated in memory, passed only through environment, never printed.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import xml.etree.ElementTree as ET


REPORT = Path("backend/common/target/surefire-reports/TEST-com.vodplatform.common.storage.StorageRuntimeTests.xml")


def require_executed_tests(report):
    assert report.is_file(), "StorageRuntimeTests surefire report missing"
    suite = ET.parse(report).getroot()
    assert int(suite.attrib.get("tests", "0")) >= 1, "StorageRuntimeTests did not execute tests"
    assert int(suite.attrib.get("skipped", "0")) == 0, "StorageRuntimeTests were skipped"
    assert int(suite.attrib.get("failures", "0")) == 0, "StorageRuntimeTests reported failures"
    assert int(suite.attrib.get("errors", "0")) == 0, "StorageRuntimeTests reported errors"


def redacted(text, env):
    if isinstance(text, bytes):
        text = text.decode("utf-8", errors="replace")
    for key, value in env.items():
        if key.startswith("MINIO_") and "KEY" in key and value:
            text = text.replace(value, "[redacted]")
    return text


def preserve_first_failure(action, cleanup, record_failure=lambda error: None):
    try:
        action()
    except BaseException as error:
        try:
            record_failure(error)
        except Exception:
            pass
        try:
            cleanup()
        except Exception:
            pass  # The original failure and its diagnostic must survive cleanup failure.
        raise
    else:
        cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    args = parser.parse_args()
    assert re.fullmatch(r"vod-auth-audit-[a-z0-9][a-z0-9_-]*", args.project), "Disposable audit project required"
    root = Path(__file__).resolve().parents[2]
    os.chdir(root)
    env = os.environ.copy()
    for name in ("MINIO_ACCESS_KEY", "MINIO_SECRET_KEY", "MINIO_BACKEND_ACCESS_KEY",
                 "MINIO_BACKEND_SECRET_KEY", "MINIO_WORKER_ACCESS_KEY", "MINIO_WORKER_SECRET_KEY"):
        env[name] = "audit_" + secrets.token_hex(20)

    diagnostic_dir = root / "backend/common/target/storage-sdk-smoke" / (args.project + "-" + secrets.token_hex(4))
    diagnostic_dir.mkdir(parents=True, exist_ok=True)

    def save_diagnostic(output):
        diagnostic = diagnostic_dir / "first-failure.log"
        if not diagnostic.exists():
            diagnostic.write_text(redacted(output, env), encoding="utf-8")

    def run(command, timeout=240):
        try:
            result = subprocess.run(command, env=env, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            save_diagnostic(redacted(error.stdout or b"", env) + redacted(error.stderr or b"", env))
            raise AssertionError("Storage SDK command timed out (output withheld)") from None
        if result.returncode:
            save_diagnostic(result.stdout + result.stderr)
            raise AssertionError("Storage SDK command failed (output withheld)")
        return result.stdout.decode("utf-8")

    existing = run(["docker", "volume", "ls", "-q", "--filter", "label=com.docker.compose.project=" + args.project])
    containers = run(["docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=" + args.project])
    assert not existing.strip() and not containers.strip(), "Audit project already exists; refusing reuse"
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="vod-storage-sdk-") as temp:
        overlay = Path(temp) / "ports.json"
        overlay.write_text(json.dumps({"services": {"minio": {"ports": [f"127.0.0.1:{port}:9000"]}}}))
        compose = ["docker", "compose", "-p", args.project, "--env-file", os.devnull,
                   "-f", "deploy/docker-compose.yml", "-f", str(overlay)]
        def check():
            run(compose + ["up", "-d", "--wait", "--wait-timeout", "120", "minio"])
            run(compose + ["run", "--rm", "--no-deps", "minio-init"])
            env["MINIO_TEST_ENDPOINT"] = f"http://127.0.0.1:{port}"
            for role in ("BACKEND", "WORKER"):
                for kind in ("ACCESS", "SECRET"):
                    env[f"MINIO_TEST_{role}_{kind}_KEY"] = env[f"MINIO_{role}_{kind}_KEY"]
            env["MINIO_TEST_ROOT_ACCESS_KEY"] = env["MINIO_ACCESS_KEY"]
            env["MINIO_TEST_ROOT_SECRET_KEY"] = env["MINIO_SECRET_KEY"]
            mvn = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
            assert mvn, "Java 21 and Maven are required for SDK smoke"
            (root / REPORT).unlink(missing_ok=True)  # Never accept a stale successful report.
            run([mvn, "-f", "backend/pom.xml", "-B", "-ntp", "-pl", "common", "-am", "test",
                 "-Dtest=StorageRuntimeTests", "-Dsurefire.failIfNoSpecifiedTests=false", "-DargLine=-Xmx64m"], timeout=600)
            require_executed_tests(root / REPORT)

        def record_failure(error):
            # Assertions in this helper are fixed strings; parser/system exceptions may contain payloads.
            save_diagnostic(str(error) if isinstance(error, AssertionError) else type(error).__name__)

        preserve_first_failure(check, lambda: run(compose + ["down", "--volumes", "--remove-orphans"]), record_failure)
        print("PASS Java SDK 128 MiB PUT/GET/DELETE with 64 MiB heap, scoped IAM denials, abort audit and readiness UP/DOWN", flush=True)


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        raise SystemExit("FAIL storage SDK smoke: " + str(error))
    except Exception as error:
        raise SystemExit("FAIL storage SDK smoke: " + type(error).__name__ + " (details withheld)")
