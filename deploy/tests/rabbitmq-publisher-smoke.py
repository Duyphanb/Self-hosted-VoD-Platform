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
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET


# (reactor, module, suite, identity). #106: the scoped worker user declares the ADR-010 topology;
# the permission suite uses both scoped users. The publisher suite needs administrator operations
# (purge, delete, policies) and runs last because it stops the broker.
SUITES = (
    ("worker", "queue", "com.vodplatform.worker.queue.messaging.EncodingQueueBrokerTests", "WORKER"),
    ("backend", "common", "com.vodplatform.common.EncodingBrokerPermissionTests", None),
    ("backend", "common", "com.vodplatform.common.EncodingPublisherBrokerTests", "ADMIN"),
)
TEST_POSTGRES_LABEL = "com.vodplatform.broker-smoke.project"


def require_disposable_project(project):
    if not re.fullmatch(r"(?:vod-auth-audit|s5w7)-[a-z0-9][a-z0-9_-]*", project):
        raise AssertionError("Disposable audit project required")


def require_fresh_project(run, project):
    require_disposable_project(project)
    for resource, arguments in (("volume", ["ls", "-q"]), ("ps", ["-aq"]),
                                ("network", ["ls", "-q"])):
        existing = run(["docker", resource] + arguments
                       + ["--filter", "label=com.docker.compose.project=" + project])
        if existing.strip():
            raise AssertionError("Audit project already exists; refusing reuse")
    if project.startswith("s5w7-") and named_postgres_containers(run, project):
        raise AssertionError("Audit PostgreSQL fixture already exists; refusing reuse")


def named_postgres_containers(run, project):
    return run(["docker", "ps", "-aq", "--filter", "label=" + TEST_POSTGRES_LABEL + "=" + project]).split()


def cleanup_named_postgres(run, project):
    """The s5w7 publisher suite replaces Ryuk with exact fixture ownership checks."""
    require_disposable_project(project)
    if not project.startswith("s5w7-"):
        raise AssertionError("Named PostgreSQL fixture requires an s5w7 project")
    for container in named_postgres_containers(run, project):
        if not re.fullmatch(r"[a-f0-9]{12,64}", container):
            raise AssertionError("Audit PostgreSQL container id is invalid")
        owner = run(["docker", "container", "inspect", "--format",
                     '{{.Name}} {{index .Config.Labels "' + TEST_POSTGRES_LABEL + '"}}', container])
        if owner.split() != ["/" + project + "-test-postgres", project]:
            raise AssertionError("Audit PostgreSQL fixture ownership mismatch")
        run(["docker", "container", "rm", "-f", "-v", container])


def port_overlay(port):
    """The data network is internal (#106) and cannot publish ports, so the broker also joins
    a disposable network that carries only the loopback port."""
    return {"services": {"rabbitmq": {"ports": [f"127.0.0.1:{port}:5672"],
                                      "networks": ["vod_internal", "audit_published"]}},
            "networks": {"audit_published": {}}}


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


BROKER_HOSTNAME = "rabbitmq"
RECREATION_QUEUE = "audit-74-recreation"


def pinned_broker_version(compose_text):
    """The x.y.z of the one digest-pinned official RabbitMQ image in the base Compose file (#74)."""
    versions = re.findall(r"(?m)^\s+image:\s*docker\.io/library/rabbitmq:(\d+\.\d+\.\d+)-alpine"
                          r"@sha256:[0-9a-f]{64}\s*$", compose_text)
    if len(versions) != 1:
        raise AssertionError("Compose must pin one official rabbitmq:x.y.z-alpine image by digest")
    return versions[0]


def require_broker_version(reported, pinned):
    reported = reported.strip()
    if reported != pinned:
        raise AssertionError("Running RabbitMQ " + reported[:32] + " does not match pinned " + pinned)


def require_recreated_queue(rows, queue):
    """The probe queue and its one persistent message survived container recreation."""
    matches = [row for row in rows if row.get("name") == queue]
    if len(matches) != 1:
        raise AssertionError("Recreated broker lost the durable probe queue")
    row = matches[0]
    if row.get("durable") is not True or row.get("type") != "classic":
        raise AssertionError("Recreated probe queue is not a durable classic queue")
    if row.get("messages") != 1 or row.get("messages_persistent") != 1:
        raise AssertionError("Recreated broker lost the persistent probe message")


def _shortstr(value):
    data = value.encode("utf-8")
    if len(data) > 255:
        raise AssertionError("AMQP short string too long")
    return bytes([len(data)]) + data


def _longstr(data):
    return struct.pack(">I", len(data)) + data


EMPTY_TABLE = struct.pack(">I", 0)


def amqp_frame(kind, channel, payload):
    return struct.pack(">BHI", kind, channel, len(payload)) + payload + b"\xce"


def amqp_method(channel, class_id, method_id, arguments=b""):
    return amqp_frame(1, channel, struct.pack(">HH", class_id, method_id) + arguments)


class AmqpFrames:
    """Reads AMQP 0-9-1 frames; a close from the broker fails with its reply code only."""

    def __init__(self, sock):
        self.sock = sock

    def _exact(self, size):
        data = b""
        while len(data) < size:
            chunk = self.sock.recv(size - len(data))
            if not chunk:
                raise AssertionError("Broker closed the AMQP socket")
            data += chunk
        return data

    def method(self, expected):
        kind, _, size = struct.unpack(">BHI", self._exact(7))
        payload = self._exact(size)
        if self._exact(1) != b"\xce" or kind != 1 or size < 4:
            raise AssertionError("Malformed AMQP method frame")
        ids = struct.unpack(">HH", payload[:4])
        if ids in ((10, 50), (20, 40)):
            code = struct.unpack(">H", payload[4:6])[0] if size >= 6 else 0
            raise AssertionError("Broker closed the AMQP " + ("connection" if ids[0] == 10 else "channel")
                                 + " with " + str(code))
        if ids != expected:
            raise AssertionError("Unexpected AMQP method " + str(ids) + ", expected " + str(expected))
        return payload[4:]


def publish_persistent(address, username, password, queue, body, timeout=10):
    """Declare a durable queue and publish one persistent, mandatory, broker-confirmed message.

    The standard library has no AMQP client: this is only the minimal AMQP 0-9-1 exchange the
    recreation check needs, so the smoke stays dependency-free.
    """
    with socket.create_connection(address, timeout=timeout) as sock:
        frames = AmqpFrames(sock)
        sock.sendall(b"AMQP\x00\x00\x09\x01")
        frames.method((10, 10))  # connection.start
        response = b"\0" + username.encode("utf-8") + b"\0" + password.encode("utf-8")
        sock.sendall(amqp_method(0, 10, 11, EMPTY_TABLE + _shortstr("PLAIN") + _longstr(response)
                                 + _shortstr("en_US")))
        _, frame_max, _ = struct.unpack(">HIH", frames.method((10, 30))[:8])  # connection.tune
        sock.sendall(amqp_method(0, 10, 31, struct.pack(">HIH", 1, frame_max, 0)))
        sock.sendall(amqp_method(0, 10, 40, _shortstr("/") + _shortstr("") + b"\x00"))
        frames.method((10, 41))
        sock.sendall(amqp_method(1, 20, 10, _shortstr("")))
        frames.method((20, 11))
        sock.sendall(amqp_method(1, 85, 10, b"\x00"))  # confirm.select
        frames.method((85, 11))
        # queue.declare: durable only (bit 1), no arguments.
        sock.sendall(amqp_method(1, 50, 10, struct.pack(">H", 0) + _shortstr(queue) + b"\x02" + EMPTY_TABLE))
        frames.method((50, 11))
        # basic.publish to the default exchange, mandatory; header: content-type + delivery-mode 2.
        sock.sendall(amqp_method(1, 60, 40, struct.pack(">H", 0) + _shortstr("") + _shortstr(queue) + b"\x01")
                     + amqp_frame(2, 1, struct.pack(">HHQH", 60, 0, len(body), 0x9000)
                                  + _shortstr("application/octet-stream") + b"\x02")
                     + amqp_frame(3, 1, body))
        if struct.unpack(">Q", frames.method((60, 80))[:8])[0] != 1:  # basic.ack, not return/nack
            raise AssertionError("Broker confirmed an unexpected delivery tag")
        sock.sendall(amqp_method(0, 10, 50, struct.pack(">H", 200) + _shortstr("") + struct.pack(">HH", 0, 0)))
        frames.method((10, 51))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--maven-repository")
    args = parser.parse_args()
    require_disposable_project(args.project)
    root = Path(__file__).resolve().parents[2]
    os.chdir(root)
    env = os.environ.copy()
    env.pop("ENCODING_TEST_RABBITMQ_PROJECT", None)
    named_test_postgres = args.project.startswith("s5w7-")
    if named_test_postgres:
        env["ENCODING_TEST_RABBITMQ_PROJECT"] = args.project
        # The broker and its PostgreSQL test fixture are cleaned by this launcher.
        env["TESTCONTAINERS_RYUK_DISABLED"] = "true"
        env["TESTCONTAINERS_CHECKS_DISABLE"] = "true"
    for role in ("", "BACKEND_", "WORKER_"):
        env["RABBITMQ_" + role + "USERNAME"] = "audit_" + secrets.token_hex(16)
        env["RABBITMQ_" + role + "PASSWORD"] = secrets.token_urlsafe(40)
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
        overlay.write_text(json.dumps(port_overlay(port)), encoding="utf-8")
        compose = ["docker", "compose", "-p", args.project, "--env-file", os.devnull,
                   "-f", "deploy/docker-compose.yml", "-f", str(overlay)]
        container = None

        def verify_baseline_and_recreation(previous):
            """#74: the pinned version runs, and data survives a Compose recreation (image bump or
            config change) because the fixed hostname keeps the node name. Returns the new container."""
            def owned(container_id):
                if not re.fullmatch(r"[a-f0-9]{12,64}", container_id):
                    raise AssertionError("Disposable RabbitMQ container is missing")
                fields = run(["docker", "inspect", "--format",
                              '{{index .Config.Labels "com.docker.compose.project"}} {{.Config.Hostname}}',
                              container_id]).split()
                if fields != [args.project, BROKER_HOSTNAME]:
                    raise AssertionError("RabbitMQ container ownership or hostname mismatch")
                return container_id

            owned(previous)
            pinned = pinned_broker_version((root / "deploy/docker-compose.yml").read_text(encoding="utf-8"))
            require_broker_version(run(["docker", "exec", previous, "rabbitmqctl", "-q", "version"]), pinned)
            erlang = run(["docker", "exec", previous, "rabbitmq-diagnostics", "-q", "erlang_version"]).strip()
            print("RabbitMQ " + pinned + " on " + erlang.split(" [", 1)[0][:64], flush=True)
            publish_persistent(("127.0.0.1", port), env["RABBITMQ_USERNAME"], env["RABBITMQ_PASSWORD"],
                               RECREATION_QUEUE, b"#74 recreation probe")
            run(compose + ["up", "-d", "--wait", "--wait-timeout", "120", "--force-recreate", "rabbitmq"])
            current = owned(run(compose + ["ps", "-q", "rabbitmq"]).strip())
            if current == previous:
                raise AssertionError("RabbitMQ container was not recreated")
            run(["docker", "exec", current, "rabbitmqctl", "await_startup"], timeout=120)
            run(["docker", "exec", current, "rabbitmq-diagnostics", "-q", "check_port_connectivity"])
            rows = json.loads(run(["docker", "exec", current, "rabbitmqctl", "--quiet", "list_queues", "name",
                                   "durable", "type", "messages", "messages_persistent", "--formatter", "json"]))
            require_recreated_queue(rows, RECREATION_QUEUE)
            run(["docker", "exec", current, "rabbitmqctl", "--quiet", "delete_queue", RECREATION_QUEUE])
            return current

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
            container = verify_baseline_and_recreation(container)

            for name, value in {
                "HOST": "127.0.0.1", "PORT": str(port), "CONTAINER": container,
                "QUEUE": env["RABBITMQ_ENCODING_QUEUE"], "REQUIRED": "true",
                "BACKEND_USERNAME": env["RABBITMQ_BACKEND_USERNAME"],
                "BACKEND_PASSWORD": env["RABBITMQ_BACKEND_PASSWORD"],
                "WORKER_USERNAME": env["RABBITMQ_WORKER_USERNAME"],
                "WORKER_PASSWORD": env["RABBITMQ_WORKER_PASSWORD"],
            }.items():
                env["ENCODING_TEST_RABBITMQ_" + name] = value
            env["RABBITMQ_HOST"] = "127.0.0.1"
            env["RABBITMQ_PORT"] = str(port)
            # Delete every report before any test, so a later failure cannot accept old evidence.
            for reactor, module, suite, _ in SUITES:
                report_path(root, reactor, module, suite).unlink(missing_ok=True)
            for reactor, module, suite, identity in SUITES:
                for field in ("USERNAME", "PASSWORD"):
                    if identity is None:  # The suite reads both scoped identities itself.
                        env.pop("ENCODING_TEST_RABBITMQ_" + field, None)
                    else:
                        prefix = "RABBITMQ_" if identity == "ADMIN" else "RABBITMQ_" + identity + "_"
                        env["ENCODING_TEST_RABBITMQ_" + field] = env[prefix + field]
                maven = [mvn, "-f", reactor + "/pom.xml", "-B", "-ntp", "-pl", module, "-am", "test",
                         "-Dtest=" + suite.rsplit(".", 1)[1], "-Dsurefire.failIfNoSpecifiedTests=false",
                         "-DargLine=-Xmx384m"]
                if args.maven_repository:
                    maven.insert(5, "-Dmaven.repo.local=" + args.maven_repository)
                run(maven, timeout=900, log=suite.rsplit(".", 1)[1] + "-test.log")
                require_executed_tests(report_path(root, reactor, module, suite))

        def record_failure(error):
            first_failure(str(error) if isinstance(error, AssertionError) else type(error).__name__)
            # Keep broker logs separate from the first command failure. Never print raw logs.
            result = subprocess.run(compose + ["logs", "--no-color", "rabbitmq"],
                                    env=env, capture_output=True, timeout=30)
            save("broker.log", result.stdout + result.stderr)

        def cleanup():
            try:
                run(compose + ["down", "--volumes", "--remove-orphans"])
            finally:
                if named_test_postgres:
                    cleanup_named_postgres(run, args.project)

        preserve_first_failure(check, cleanup, record_failure)
        print("PASS RabbitMQ pinned version, data across recreation, scoped worker/backend declarations and permissions, "
              "wire properties, persistence, confirms, returns, nack, timeout and DLQ routing", flush=True)


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        raise SystemExit("FAIL RabbitMQ publisher smoke: " + str(error))
    except Exception as error:
        raise SystemExit("FAIL RabbitMQ publisher smoke: " + type(error).__name__ + " (details withheld)")
