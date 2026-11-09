"""Disposable-project guards and broker evidence gating without running Docker."""
import importlib.util
from pathlib import Path
import socket
import struct
import tempfile
import threading
import unittest


SPEC = importlib.util.spec_from_file_location("rabbitmq_smoke", Path(__file__).with_name("rabbitmq-publisher-smoke.py"))
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


def run_fake_broker(publish_reply):
    """Run publish_persistent against a scripted AMQP 0-9-1 peer; return the frames it received."""
    replies = {(10, 11): SMOKE.amqp_method(0, 10, 30, struct.pack(">HIH", 2047, 131072, 60)),
               (10, 40): SMOKE.amqp_method(0, 10, 41, b"\x00"),
               (20, 10): SMOKE.amqp_method(1, 20, 11, struct.pack(">I", 0)),
               (85, 10): SMOKE.amqp_method(1, 85, 11),
               (50, 10): SMOKE.amqp_method(1, 50, 11, b"\x05probe" + struct.pack(">II", 0, 0)),
               (10, 50): SMOKE.amqp_method(0, 10, 51)}
    received = []
    server = socket.create_server(("127.0.0.1", 0))
    server.settimeout(10)

    def serve():
        connection, _ = server.accept()
        with connection:
            reader = connection.makefile("rb")
            received.append(reader.read(8))
            connection.sendall(SMOKE.amqp_method(0, 10, 10, b"\x00\x09" + struct.pack(">I", 0)))
            while True:
                header = reader.read(7)
                if len(header) < 7:
                    return
                kind, channel, size = struct.unpack(">BHI", header)
                payload = reader.read(size)
                reader.read(1)
                received.append((kind, channel, payload))
                if kind == 3:
                    connection.sendall(publish_reply)
                elif kind == 1 and struct.unpack(">HH", payload[:4]) in replies:
                    connection.sendall(replies[struct.unpack(">HH", payload[:4])])

    thread = threading.Thread(target=serve, daemon=True)
    thread.start()
    try:
        SMOKE.publish_persistent(server.getsockname(), "user", "pass", "probe", b"body", timeout=5)
    finally:
        thread.join(10)
        server.close()
    return received


class RabbitmqPublisherSmokeTests(unittest.TestCase):
    def test_project_name_cannot_target_normal_or_empty_project(self):
        for name in ("", "vod-platform", "vod-auth-audit-", "vod-auth-audit-X", "vod-auth-audit-../other"):
            with self.subTest(name=name), self.assertRaisesRegex(AssertionError, "Disposable audit project"):
                SMOKE.require_disposable_project(name)
        SMOKE.require_disposable_project("vod-auth-audit-170-ci_1")

    def test_any_existing_resource_prevents_project_reuse(self):
        for existing_at in range(3):
            calls = []
            def run(command):
                calls.append(command)
                return "owned elsewhere" if len(calls) - 1 == existing_at else ""
            with self.subTest(existing_at=existing_at), self.assertRaisesRegex(AssertionError, "refusing reuse"):
                SMOKE.require_fresh_project(run, "vod-auth-audit-170")
            self.assertEqual(len(calls), existing_at + 1)
            self.assertTrue(all(command[-1] == "label=com.docker.compose.project=vod-auth-audit-170"
                                for command in calls))

    def test_only_executed_unskipped_successful_suite_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "suite.xml"
            with self.assertRaisesRegex(AssertionError, "report missing"):
                SMOKE.require_executed_tests(report)
            for attributes in ('tests="0"', 'tests="1" skipped="1"',
                               'tests="1" failures="1"', 'tests="1" errors="1"'):
                report.write_text("<testsuite " + attributes + "/>", encoding="utf-8")
                with self.subTest(attributes=attributes), self.assertRaises(AssertionError):
                    SMOKE.require_executed_tests(report)
            report.write_text('<testsuite tests="4" skipped="0" failures="0" errors="0"/>', encoding="utf-8")
            SMOKE.require_executed_tests(report)

    def test_every_suite_has_its_own_report_and_the_broker_stopping_suite_runs_last(self):
        paths = [SMOKE.report_path(Path("repo"), *suite[:3]) for suite in SMOKE.SUITES]
        self.assertEqual(len(set(paths)), 3)
        self.assertIn("worker/queue/target/surefire-reports", paths[0].as_posix())
        self.assertIn("backend/common/target/surefire-reports", paths[1].as_posix())
        # EncodingPublisherBrokerTests stops the broker at its end.
        self.assertTrue(SMOKE.SUITES[-1][2].endswith(".EncodingPublisherBrokerTests"))

    def test_scoped_users_run_the_declarations_and_only_publisher_outcomes_use_the_administrator(self):
        identities = {suite[2].rsplit(".", 1)[1]: suite[3] for suite in SMOKE.SUITES}
        self.assertEqual(identities, {"EncodingQueueBrokerTests": "WORKER",
                                      "EncodingBrokerPermissionTests": None,
                                      "EncodingPublisherBrokerTests": "ADMIN"})

    def test_published_port_uses_loopback_and_a_disposable_network_beside_the_internal_one(self):
        overlay = SMOKE.port_overlay(15672)
        broker = overlay["services"]["rabbitmq"]
        self.assertEqual(broker["ports"], ["127.0.0.1:15672:5672"])
        # An internal network cannot publish ports (#106); the broker keeps its data network.
        self.assertEqual(broker["networks"], ["vod_internal", "audit_published"])
        self.assertEqual(overlay["networks"], {"audit_published": {}})

    def test_compose_pins_one_official_alpine_broker_by_digest(self):
        compose = (Path(__file__).resolve().parents[1] / "docker-compose.yml").read_text(encoding="utf-8")
        version = SMOKE.pinned_broker_version(compose)
        self.assertRegex(version, r"^4\.4\.\d+$")  # #74 baseline series
        self.assertRegex(compose, r"(?m)^    hostname: rabbitmq$")
        digest = "@sha256:" + "a" * 64
        for text in ("    image: rabbitmq:3.13-alpine\n",
                     "    image: docker.io/library/rabbitmq:4.4-alpine" + digest + "\n",
                     "    image: docker.io/library/rabbitmq:4.4.0-alpine\n",
                     "    image: docker.io/library/rabbitmq:4.4.0-management-alpine" + digest + "\n",
                     ("    image: docker.io/library/rabbitmq:4.4.0-alpine" + digest + "\n") * 2):
            with self.subTest(text=text), self.assertRaisesRegex(AssertionError, "pin one official"):
                SMOKE.pinned_broker_version(text)

    def test_running_version_must_equal_the_pin(self):
        SMOKE.require_broker_version("4.4.0\n", "4.4.0")
        for reported in ("4.4.1", "4.4.0-rc.1", "", "3.13.7"):
            with self.subTest(reported=reported), self.assertRaisesRegex(AssertionError, "does not match"):
                SMOKE.require_broker_version(reported, "4.4.0")

    def test_recreation_requires_the_durable_queue_and_its_persistent_message(self):
        good = {"name": "probe", "durable": True, "type": "classic", "messages": 1, "messages_persistent": 1}
        SMOKE.require_recreated_queue([{"name": "other"}, good], "probe")
        for rows, reason in (([], "lost the durable probe queue"),
                             ([good, good], "lost the durable probe queue"),
                             ([dict(good, durable=False)], "not a durable classic"),
                             ([dict(good, type="quorum")], "not a durable classic"),
                             ([dict(good, messages=0, messages_persistent=0)], "lost the persistent"),
                             ([dict(good, messages_persistent=0)], "lost the persistent")):
            with self.subTest(rows=rows), self.assertRaisesRegex(AssertionError, reason):
                SMOKE.require_recreated_queue(rows, "probe")

    def test_minimal_publisher_speaks_amqp_and_requires_a_broker_ack(self):
        frames = run_fake_broker(SMOKE.amqp_method(1, 60, 80, struct.pack(">QB", 1, 0)))
        methods = [(kind, channel, payload[:4]) for kind, channel, payload in frames[1:]]
        self.assertEqual(frames[0], b"AMQP\x00\x00\x09\x01")
        self.assertEqual(methods, [
            (1, 0, struct.pack(">HH", 10, 11)), (1, 0, struct.pack(">HH", 10, 31)),
            (1, 0, struct.pack(">HH", 10, 40)), (1, 1, struct.pack(">HH", 20, 10)),
            (1, 1, struct.pack(">HH", 85, 10)), (1, 1, struct.pack(">HH", 50, 10)),
            (1, 1, struct.pack(">HH", 60, 40)), (2, 1, struct.pack(">HH", 60, 0)),
            (3, 1, b"body"), (1, 0, struct.pack(">HH", 10, 50))])
        # empty client properties, PLAIN, "\0user\0pass", locale
        self.assertEqual(frames[1][2][4:], b"\x00\x00\x00\x00\x05PLAIN\x00\x00\x00\x0a\x00user\x00pass\x05en_US")
        self.assertEqual(frames[6][2][4:], b"\x00\x00\x05probe\x02\x00\x00\x00\x00")  # durable, no args
        self.assertEqual(frames[7][2][4:], b"\x00\x00\x00\x05probe\x01")  # default exchange, mandatory
        # content-type and delivery-mode 2 (persistent)
        self.assertEqual(frames[8][2][4:], struct.pack(">QH", 4, 0x9000) + b"\x18application/octet-stream\x02")

    def test_minimal_publisher_rejects_return_nack_and_broker_close(self):
        for reply, reason in ((SMOKE.amqp_method(1, 60, 50, struct.pack(">H", 312)), r"\(60, 50\)"),
                              (SMOKE.amqp_method(1, 60, 120, struct.pack(">QB", 1, 0)), r"\(60, 120\)"),
                              (SMOKE.amqp_method(1, 60, 80, struct.pack(">QB", 2, 0)), "unexpected delivery tag"),
                              (SMOKE.amqp_method(1, 20, 40, struct.pack(">H", 406) + b"\x00"), "channel with 406")):
            with self.subTest(reason=reason), self.assertRaisesRegex(AssertionError, reason):
                run_fake_broker(reply)

    def test_redacts_production_and_test_credentials_longest_first(self):
        env = {"RABBITMQ_USERNAME": "audit-user", "RABBITMQ_PASSWORD": "secret",
               "ENCODING_TEST_RABBITMQ_PASSWORD": "secret-long", "RABBITMQ_HOST": "127.0.0.1"}
        self.assertEqual(SMOKE.redacted(b"audit-user secret secret-long 127.0.0.1", env),
                         "[redacted] [redacted] [redacted] 127.0.0.1")

    def test_cleanup_failure_preserves_the_original_and_records_it_first(self):
        events = []
        def action():
            raise AssertionError("original failure")
        def cleanup():
            events.append("cleanup")
            raise RuntimeError("cleanup payload")
        with self.assertRaisesRegex(AssertionError, "original failure"):
            SMOKE.preserve_first_failure(action, cleanup, lambda error: events.append(str(error)))
        self.assertEqual(events, ["original failure", "cleanup"])
        with self.assertRaisesRegex(RuntimeError, "cleanup payload"):
            SMOKE.preserve_first_failure(lambda: None, cleanup)

    def test_diagnostic_error_cannot_prevent_cleanup(self):
        events = []
        def fail():
            raise AssertionError("test failure")
        def record(error):
            raise RuntimeError("diagnostic failure")
        with self.assertRaisesRegex(AssertionError, "test failure"):
            SMOKE.preserve_first_failure(fail, lambda: events.append("cleanup"), record)
        self.assertEqual(events, ["cleanup"])
