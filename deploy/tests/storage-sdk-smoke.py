#!/usr/bin/env python3
"""Run the real Java storage adapter with a smaller heap than its generated objects.

Owns a fresh disposable Compose project; never reuses an existing project/volume.
Credentials are generated in memory, passed only through environment, never printed.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    args = parser.parse_args()
    assert args.project.startswith("vod-auth-audit-"), "Disposable audit project required"
    root = Path(__file__).resolve().parents[2]
    os.chdir(root)
    env = os.environ.copy()
    for name in ("MINIO_ACCESS_KEY", "MINIO_SECRET_KEY", "MINIO_BACKEND_ACCESS_KEY",
                 "MINIO_BACKEND_SECRET_KEY", "MINIO_WORKER_ACCESS_KEY", "MINIO_WORKER_SECRET_KEY"):
        env[name] = "audit_" + secrets.token_hex(20)

    def run(command, timeout=240):
        result = subprocess.run(command, env=env, capture_output=True, timeout=timeout)
        if result.returncode:
            diagnostic = (result.stdout + result.stderr).decode("utf-8", errors="replace")
            for key, value in env.items():
                if key.startswith("MINIO_") and ("KEY" in key) and value:
                    diagnostic = diagnostic.replace(value, "[redacted]")
            (root / "backend/target/169-sdk-diagnostic.log").write_text(diagnostic, encoding="utf-8")
            raise RuntimeError("Storage SDK check failed (command output withheld)")
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
        try:
            run(compose + ["up", "-d", "--wait", "--wait-timeout", "120", "minio", "minio-init"])
            env["MINIO_TEST_ENDPOINT"] = f"http://127.0.0.1:{port}"
            for role in ("BACKEND", "WORKER"):
                for kind in ("ACCESS", "SECRET"):
                    env[f"MINIO_TEST_{role}_{kind}_KEY"] = env[f"MINIO_{role}_{kind}_KEY"]
            mvn = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
            assert mvn, "Java 21 and Maven are required for SDK smoke"
            run([mvn, "-f", "backend/pom.xml", "-B", "-ntp", "-pl", "common", "-am", "test",
                 "-Dtest=StorageRuntimeTests", "-Dsurefire.failIfNoSpecifiedTests=false", "-DargLine=-Xmx64m"], timeout=600)
            print("PASS Java SDK 128 MiB PUT/GET/DELETE with 64 MiB heap and scoped IAM denials", flush=True)
        finally:
            run(compose + ["down", "--volumes", "--remove-orphans"])


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        raise SystemExit("FAIL storage SDK smoke: " + type(error).__name__ + " (details withheld)")
