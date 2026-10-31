#!/usr/bin/env python3
"""Prove the encoding wire contract on an owned, disposable RabbitMQ Compose project.

The image comes from the production Compose file. Generated credentials travel only in
the child environment; command output is captured and redacted before it is retained.
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


SUITES = (
    ("worker", "queue", "com.vodplatform.worker.queue.messaging.EncodingQueueBrokerTests"),
    ("backend", "common", "com.vodplatform.common.EncodingPublisherBrokerTests"),
)


def require_disposable_project(project):
    if not re.fullmatch(r"vod-auth-audit-[a-z0-9][a-z0-9_-]*", project):
        raise AssertionError("Disposable audit project required")


def require_fresh_project(run, project):
    require_disposable_project(project)
    for resource, arguments in (("volume", ["ls", "-q"]), ("ps", ["-aq"]),
                                ("network", ["ls", "-q"])):
        existing = run(["docker", resource] + arguments
                       + ["--filter", "label=com.docker.compose.project=" + project])
        if existing.strip():
            raise AssertionError("Audit project already exists; refusing reuse")


def report_path(root, reactor, module, suite):
    return root / reactor / module / "target/surefire-reports" / ("TEST-" + suite + ".xml")


def require_executed_tests(report):
    if not report.is_file():
        raise AssertionError("Broker test surefire report missing")
    suite = ET.parse(report).getroot()
    if suite.tag != "testsuite" or int(suite.attrib.get("tests", "0")) < 1:
        raise AssertionError("Broker suite did not execute tests")
    for attribute in ("skipped", "failures", "errors"):
        if int(suite.attrib.get(attribute, "0")) != 0:
            raise AssertionError("Broker suite has " + attribute)


def redacted(text, env):
    if isinstance(text, bytes):
        text = text.decode("utf-8", errors="replace")
    values = {value for key, value in env.items() if value and
              (key.startswith("RABBITMQ_") or key.startswith("ENCODING_TEST_RABBITMQ_"))
              and ("PASSWORD" in key or "USERNAME" in key)}
    for value in sorted(values, key=len, reverse=True):
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
            pass  # Cleanup cannot replace the test/declaration failure.
        raise
    else:
        cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    args = parser.parse_args()
    require_disposable_project(args.project)
    root = Path(__file__).resolve().parents[2]
    os.chdir(root)
    env = os.environ.copy()
    env["RABBITMQ_USERNAME"] = "audit_" + secrets.token_hex(16)
    env["RABBITMQ_PASSWORD"] = secrets.token_urlsafe(40)
    env["RABBITMQ_ENCODING_QUEUE"] = "video-encoding-queue"
    env["MAVEN_OPTS"] = "-Xmx192m"
    diagnostics = root / "backend/common/target/rabbitmq-publisher-smoke" / (args.project + "-" + secrets.token_hex(4))
    diagnostics.mkdir(parents=True, exist_ok=True)

    def save(name, output):
        (diagnostics / name).write_text(redacted(output, env), encoding="utf-8")

    def first_failure(output):
        if not (diagnostics / "first-failure.log").exists():
            save("first-failure.log", output)

    def run(command, timeout=240, log=None):
        try:
            result = subprocess.run(command, env=env, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            first_failure((error.stdout or b"") + (error.stderr or b""))
            raise AssertionError("RabbitMQ smoke command timed out (output withheld)") from None
        output = result.stdout + result.stderr
        if log:
            save(log, output)
        if result.returncode:
            first_failure(output)
            raise AssertionError("RabbitMQ smoke command failed (output withheld)")
        return result.stdout.decode("utf-8", errors="replace")

    # Refuse all remnants, including a project with only a leftover network/volume.
    require_fresh_project(run, args.project)
    mvn = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
    if not mvn:
        raise AssertionError("Java 21 and Maven are required for RabbitMQ smoke")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="vod-rabbitmq-smoke-") as directory:
        overlay = Path(directory) / "ports.json"
        overlay.write_text(json.dumps({"services": {"rabbitmq": {
            "ports": [f"127.0.0.1:{port}:5672"]}}}), encoding="utf-8")
        compose = ["docker", "compose", "-p", args.project, "--env-file", os.devnull,
                   "-f", "deploy/docker-compose.yml", "-f", str(overlay)]
        container = None

        def check():
            nonlocal container
            # No app services or existing named volumes participate in this check.
            run(compose + ["up", "-d", "--wait", "--wait-timeout", "120", "rabbitmq"])
            container = run(compose + ["ps", "-q", "rabbitmq"]).strip()
            if not re.fullmatch(r"[a-f0-9]{12,64}", container):
                raise AssertionError("Disposable RabbitMQ container is missing")
            label = run(["docker", "inspect", "--format",
                         '{{index .Config.Labels "com.docker.compose.project"}}', container]).strip()
            if label != args.project:
                raise AssertionError("RabbitMQ container project ownership mismatch")
            # Compose's diagnostics ping can turn healthy before application/listeners finish booting.
            run(["docker", "exec", container, "rabbitmqctl", "await_startup"], timeout=120)
            run(["docker", "exec", container, "rabbitmq-diagnostics", "-q", "check_port_connectivity"])
            for name, value in {
                "HOST": "127.0.0.1", "PORT": str(port), "USERNAME": env["RABBITMQ_USERNAME"],
                "PASSWORD": env["RABBITMQ_PASSWORD"], "CONTAINER": container,
                "QUEUE": env["RABBITMQ_ENCODING_QUEUE"], "REQUIRED": "true",
            }.items():
                env["ENCODING_TEST_RABBITMQ_" + name] = value
            env["RABBITMQ_HOST"] = "127.0.0.1"
            env["RABBITMQ_PORT"] = str(port)
            # Delete both reports before any test, so a later failure cannot accept old evidence.
            for reactor, module, suite in SUITES:
                report_path(root, reactor, module, suite).unlink(missing_ok=True)
            for reactor, module, suite in SUITES:
                run([mvn, "-f", reactor + "/pom.xml", "-B", "-ntp", "-pl", module, "-am", "test",
                     "-Dtest=" + suite.rsplit(".", 1)[1], "-Dsurefire.failIfNoSpecifiedTests=false",
                     "-DargLine=-Xmx384m"], timeout=900, log=reactor + "-test.log")
                require_executed_tests(report_path(root, reactor, module, suite))

        def record_failure(error):
            first_failure(str(error) if isinstance(error, AssertionError) else type(error).__name__)
            # Keep broker logs separate from the first command failure. Never print raw logs.
            result = subprocess.run(compose + ["logs", "--no-color", "rabbitmq"],
                                    env=env, capture_output=True, timeout=30)
            save("broker.log", result.stdout + result.stderr)

        preserve_first_failure(check,
                               lambda: run(compose + ["down", "--volumes", "--remove-orphans"]),
                               record_failure)
        print("PASS RabbitMQ worker/backend declarations, wire properties, persistence, confirms, returns, nack, timeout and DLQ routing", flush=True)


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        raise SystemExit("FAIL RabbitMQ publisher smoke: " + str(error))
    except Exception as error:
        raise SystemExit("FAIL RabbitMQ publisher smoke: " + type(error).__name__ + " (details withheld)")
