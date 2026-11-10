#!/usr/bin/env python3
"""Exercise worker JDBC and object storage against a fresh PostgreSQL/Silo Compose project.

The production init scripts provision the schema and identities. A test overlay binds the
dependencies only to random loopback ports. The runner owns and removes the project and volumes.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
SUITES = {
    "status": "com.vodplatform.worker.status.WorkerJdbcPostgresTest",
    "storage": "com.vodplatform.worker.storage.WorkerObjectStorageSiloTests",
}


class SmokeError(RuntimeError):
    """A fixed diagnostic that cannot include credentials."""


def require_reports(reports):
    for report in reports:
        if not report.is_file():
            raise SmokeError("Worker dependency test report missing")
        suite = ET.parse(report).getroot()
        if (suite.tag != "testsuite" or int(suite.attrib["tests"]) < 1
                or any(int(suite.attrib[key]) != 0 for key in ("skipped", "errors", "failures"))):
            raise SmokeError("Worker dependency suite did not execute successfully")


def fixture_environment():
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("COMPOSE_", "MINIO_", "POSTGRES_", "SPRING_", "WORKER_", "FLYWAY_"))}
    env.update({
        "POSTGRES_DB": "s5w6_fixture",
        "POSTGRES_BACKEND_USER": "s5w6_backend",
        "POSTGRES_WORKER_USER": "s5w6_worker",
        "MINIO_BUCKET_RAW": "vod-raw",
        "MINIO_BUCKET_HLS": "vod-hls",
        "MINIO_BUCKET_THUMBNAILS": "vod-thumbnails",
        "WORKER_JDBC_STATEMENT_TIMEOUT_MS": "700",
        "WORKER_JDBC_READ_TIMEOUT_SECONDS": "8",
    })
    for identity in ("POSTGRES", "POSTGRES_OWNER", "POSTGRES_BACKEND", "POSTGRES_WORKER"):
        env[identity + "_PASSWORD"] = secrets.token_urlsafe(32)
    for identity in ("MINIO", "MINIO_BACKEND", "MINIO_WORKER"):
        for kind in ("ACCESS", "SECRET"):
            env[f"{identity}_{kind}_KEY"] = "audit_" + secrets.token_hex(20)
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--maven-repository")
    args = parser.parse_args()
    if not re.fullmatch(r"s5w6-[a-z0-9][a-z0-9-]*", args.project):
        raise SmokeError("Use a disposable s5w6-* project")
    mvn = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
    if mvn is None:
        raise SmokeError("Maven is required")

    env = fixture_environment()
    diagnostics = ROOT / "worker/target/worker-dependency-smoke" / (args.project + "-" + secrets.token_hex(4))

    def run(command, label, *, timeout=240, save_output=True, child_env=env):
        try:
            result = subprocess.run(command, cwd=ROOT, env=child_env, capture_output=True, timeout=timeout)
            if result.returncode == 0:
                return result.stdout.decode("utf-8")
            output = result.stdout + result.stderr
        except subprocess.TimeoutExpired as error:
            output = (error.stdout or b"") + (error.stderr or b"")
        if save_output:
            redacted = output.decode("utf-8", errors="replace")
            for key, value in env.items():
                if value and key.startswith(("MINIO_", "POSTGRES_", "SPRING_DATASOURCE_")) and (
                        key.endswith(("_KEY", "_PASSWORD"))):
                    redacted = redacted.replace(value, "[redacted]")
            diagnostics.mkdir(parents=True, exist_ok=True)
            (diagnostics / (label + ".log")).write_text(redacted, encoding="utf-8")
        raise SmokeError("Command failed: " + label)

    project_label = "label=com.docker.compose.project=" + args.project
    for command in (["ps", "-aq"], ["volume", "ls", "-q"], ["network", "ls", "-q"]):
        if run(["docker", *command, "--filter", project_label], "check-project").strip():
            raise SmokeError("Project already exists; refusing reuse")

    with tempfile.TemporaryDirectory(prefix="s5w6-worker-dependencies-") as directory:
        overlay = Path(directory) / "ports.json"
        overlay.write_text(json.dumps({
            "services": {name: {"ports": [{"target": port, "host_ip": "127.0.0.1"}],
                                "networks": ["vod_internal", "audit_published"]}
                         for name, port in (("postgres", 5432), ("minio", 9000))},
            "networks": {"audit_published": {}},
        }), encoding="utf-8")
        compose = ["docker", "compose", "-p", args.project, "--env-file", os.devnull,
                   "-f", "deploy/docker-compose.yml", "-f", str(overlay)]

        def local_port(service, target):
            output = run(compose + ["port", service, str(target)], service + "-port").strip()
            match = re.fullmatch(r"127\.0\.0\.1:([1-9][0-9]{0,4})", output)
            if match is None:
                raise SmokeError("Dependency must publish a loopback port")
            return match.group(1)

        try:
            config = run(compose + ["config", "--format", "json"], "compose-config", save_output=False)
            worker = json.loads(config)["services"]["worker"]["environment"]
            # Check the production identity mapping before pointing the test client at loopback.
            for target, source in (("SPRING_DATASOURCE_USERNAME", "POSTGRES_WORKER_USER"),
                                   ("SPRING_DATASOURCE_PASSWORD", "POSTGRES_WORKER_PASSWORD"),
                                   ("MINIO_ACCESS_KEY", "MINIO_WORKER_ACCESS_KEY"),
                                   ("MINIO_SECRET_KEY", "MINIO_WORKER_SECRET_KEY")):
                if worker[target] != env[source]:
                    raise SmokeError("Compose must map the scoped worker identity")
            run(compose + ["up", "-d", "--wait", "--wait-timeout", "120", "postgres", "minio"], "dependencies-up")
            run(compose + ["run", "--rm", "flyway"], "migrate-schema")
            run(compose + ["run", "--rm", "--no-deps", "minio-init"], "provision-storage")
            test_env = dict(env)
            test_env.update({name: value for name, value in worker.items()
                             if name.startswith(("SPRING_DATASOURCE_", "MINIO_", "WORKER_JDBC_"))})
            test_env["SPRING_DATASOURCE_URL"] = (
                f"jdbc:postgresql://127.0.0.1:{local_port('postgres', 5432)}/{env['POSTGRES_DB']}")
            test_env["MINIO_ENDPOINT"] = "http://127.0.0.1:" + local_port("minio", 9000)
            test_env["WORKER_TEST_POSTGRES_USER"] = env["POSTGRES_WORKER_USER"]
            test_env["WORKER_DEPENDENCY_SMOKE"] = "true"

            reports = [ROOT / "worker" / module / "target/surefire-reports" / f"TEST-{suite}.xml"
                       for module, suite in SUITES.items()]
            for report in reports:
                report.unlink(missing_ok=True)
            maven = [mvn, "-f", "worker/pom.xml", "-B", "-ntp", "-pl", "storage,status", "-am",
                     "-Dtest=" + ",".join(SUITES.values()), "-Dsurefire.failIfNoSpecifiedTests=false", "test"]
            if args.maven_repository:
                maven.insert(5, "-Dmaven.repo.local=" + args.maven_repository)
            run(maven, "worker-boundary-tests", timeout=600, child_env=test_env)
            require_reports(reports)
        except BaseException:
            try:
                run(compose + ["down", "--volumes", "--remove-orphans"], "cleanup")
            except Exception:
                print("Worker dependency smoke cleanup also failed", file=sys.stderr, flush=True)
            raise
        else:
            run(compose + ["down", "--volumes", "--remove-orphans"], "cleanup")
    print("PASS worker PostgreSQL and Silo boundary tests executed; disposable project removed", flush=True)


if __name__ == "__main__":
    try:
        main()
    except SmokeError as error:
        raise SystemExit("FAIL worker dependency smoke: " + str(error))
    except Exception as error:
        raise SystemExit("FAIL worker dependency smoke: " + type(error).__name__ + " (details withheld)")
