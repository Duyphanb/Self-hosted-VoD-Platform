#!/usr/bin/env python3
"""Run the real worker application against disposable PostgreSQL, Silo and RabbitMQ."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
SUITE = "com.vodplatform.worker.queue.WorkerOrchestrationIntegrationTests"
DATABASE = "s5w7_fixture"
QUEUE = "s5w7-orchestration-queue"


class SmokeError(RuntimeError):
    """Failure with a fixed diagnostic safe for CI output."""


def fixture_environment():
    excluded = ("COMPOSE_", "DOCKER_HOST", "MINIO_", "POSTGRES_", "RABBITMQ_", "FLYWAY_", "SPRING_", "WORKER_")
    env = {key: value for key, value in os.environ.items() if not key.startswith(excluded)}
    env.update({"POSTGRES_DB": DATABASE, "POSTGRES_BACKEND_USER": "s5w7_backend",
                "POSTGRES_WORKER_USER": "s5w7_worker", "RABBITMQ_ENCODING_QUEUE": QUEUE})
    for role in ("POSTGRES", "POSTGRES_OWNER", "POSTGRES_BACKEND", "POSTGRES_WORKER"):
        env[role + "_PASSWORD"] = secrets.token_urlsafe(32)
    for role in ("RABBITMQ", "RABBITMQ_BACKEND", "RABBITMQ_WORKER"):
        env[role + "_USERNAME"] = "s5w7_" + secrets.token_hex(8)
        env[role + "_PASSWORD"] = secrets.token_urlsafe(32)
    for role in ("MINIO", "MINIO_BACKEND", "MINIO_WORKER"):
        env[role + "_ACCESS_KEY"] = "s5w7_" + secrets.token_hex(8)
        env[role + "_SECRET_KEY"] = secrets.token_urlsafe(32)
    return env


def test_overlay(image, repository):
    credentials = ("POSTGRES_BACKEND_USER", "POSTGRES_BACKEND_PASSWORD", "POSTGRES_WORKER_USER",
                   "POSTGRES_WORKER_PASSWORD", "RABBITMQ_BACKEND_USERNAME", "RABBITMQ_BACKEND_PASSWORD",
                   "RABBITMQ_WORKER_USERNAME", "RABBITMQ_WORKER_PASSWORD", "RABBITMQ_USERNAME",
                   "RABBITMQ_PASSWORD", "MINIO_BACKEND_ACCESS_KEY", "MINIO_BACKEND_SECRET_KEY",
                   "MINIO_WORKER_ACCESS_KEY", "MINIO_WORKER_SECRET_KEY")
    environment = {name: "${" + name + "}" for name in credentials}
    environment.update({"WORKER_ORCHESTRATION_TEST": "true", "HOME": "/tmp",
                        "MAVEN_OPTS": "-Duser.home=/tmp"})
    user = "0:0" if os.name == "nt" else f"{os.getuid()}:{os.getgid()}"
    return {"services": {"orchestration-test": {
        "image": image,
        "user": user,
        "working_dir": "/workspace",
        "entrypoint": ["mvn"],
        "command": ["-B", "-ntp", "-Dmaven.repo.local=/m2/repository", "-pl", "queue", "-am",
                    "-Dtest=" + SUITE, "-Dsurefire.failIfNoSpecifiedTests=false", "test"],
        "environment": environment,
        "volumes": [{"type": "bind", "source": str(ROOT / "worker"), "target": "/workspace"},
                    {"type": "bind", "source": str(repository), "target": "/m2/repository"}],
        # The database, broker and storage are still on the private network and publish no ports.
        "networks": ["vod_internal", "maven_downloads"],
    }}, "networks": {"maven_downloads": {}}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--maven-repository", type=Path, default=(
        Path("F:/m2/repository") if os.name == "nt" else Path.home() / ".m2" / "repository"))
    args = parser.parse_args()
    if not re.fullmatch(r"s5w7-[a-z0-9][a-z0-9-]{0,40}", args.project):
        raise SmokeError("Use a disposable s5w7-* project name")
    args.maven_repository.mkdir(parents=True, exist_ok=True)
    image = args.project + "-ffmpeg-test"
    env = fixture_environment()
    diagnostics = ROOT / "worker/target/worker-orchestration-smoke" / args.project
    secrets_to_hide = sorted({value for name, value in env.items()
                              if value and name.endswith(("_PASSWORD", "_KEY", "_USERNAME"))},
                             key=len, reverse=True)

    def run(command, label, timeout=180):
        try:
            result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, timeout=timeout)
            if result.returncode == 0:
                return result.stdout.decode("utf-8", errors="replace")
            raw = result.stdout + result.stderr
        except subprocess.TimeoutExpired as failure:
            raw = (failure.stdout or b"") + (failure.stderr or b"")
        output = raw.decode("utf-8", errors="replace")
        for secret in secrets_to_hide:
            output = output.replace(secret, "[redacted]")
        diagnostics.mkdir(parents=True, exist_ok=True)
        (diagnostics / (label + ".log")).write_text(output, encoding="utf-8")
        raise SmokeError("Command failed: " + label)

    label = "label=com.docker.compose.project=" + args.project
    for command in (["ps", "-aq"], ["network", "ls", "-q"], ["volume", "ls", "-q"]):
        if run(["docker", *command, "--filter", label], "check-project").strip():
            raise SmokeError("Project already exists; refusing reuse")
    if run(["docker", "image", "ls", "-q", image], "check-image").strip():
        raise SmokeError("Fixture image already exists; refusing reuse")

    with tempfile.TemporaryDirectory(prefix="s5w7-orchestration-") as directory:
        overlay = Path(directory) / "orchestration.json"
        overlay.write_text(json.dumps(test_overlay(image, args.maven_repository.resolve())), encoding="utf-8")
        compose = ["docker", "compose", "-p", args.project, "--env-file", os.devnull,
                   "-f", "deploy/docker-compose.yml", "-f", str(overlay)]
        try:
            run(["docker", "build", "--target", "ffmpeg-test", "-t", image, str(ROOT / "worker")],
                "ffmpeg-image", timeout=900)
            run(compose + ["up", "-d", "--wait", "--wait-timeout", "120", "postgres", "minio", "rabbitmq"],
                "dependencies-up")
            run(compose + ["exec", "-T", "rabbitmq", "rabbitmqctl", "await_startup"], "rabbitmq-startup")
            run(compose + ["run", "--rm", "flyway"], "migrate-schema")
            run(compose + ["run", "--rm", "--no-deps", "minio-init"], "provision-storage")

            report = ROOT / "worker/queue/target/surefire-reports" / ("TEST-" + SUITE + ".xml")
            report.unlink(missing_ok=True)
            run(compose + ["run", "--rm", "--no-deps", "--name", args.project + "-tests", "orchestration-test"],
                "orchestration-tests", timeout=900)
            if not report.is_file():
                raise SmokeError("Orchestration suite report missing")
            evidence = ET.parse(report).getroot().attrib
            if int(evidence["tests"]) < 1 or any(int(evidence[field]) for field in ("errors", "failures", "skipped")):
                raise SmokeError("Orchestration suite did not execute successfully")
            print(f"PASS {evidence['tests']} worker orchestration integration tests", flush=True)
        finally:
            failed = sys.exc_info()[0] is not None
            errors = []
            for command, operation in (
                    (compose + ["down", "--volumes", "--remove-orphans"], "cleanup-compose"),
                    (["docker", "image", "rm", image], "cleanup-image")):
                try:
                    if operation != "cleanup-image" or run(["docker", "image", "ls", "-q", image],
                                                           "find-image").strip():
                        run(command, operation)
                except SmokeError as error:
                    errors.append(error)
            if errors:
                if not failed:
                    raise errors[0]
                print("Orchestration fixture cleanup also failed", file=sys.stderr, flush=True)
    print("PASS disposable orchestration project and image removed", flush=True)


if __name__ == "__main__":
    try:
        main()
    except SmokeError as error:
        raise SystemExit("FAIL worker orchestration smoke: " + str(error))
    except Exception as error:
        raise SystemExit("FAIL worker orchestration smoke: " + type(error).__name__ + " (details withheld)")
