"""Guard the frozen upload edge structure and defaults without Docker/services."""
from dataclasses import dataclass
import json
from pathlib import Path
import re
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]
UPLOAD_PATTERN = r"^/api/v1/admin/movies/[^/]+/video$"
TOKEN = re.compile(r'''\s+|\#[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{};]|[^\s{};'"\#]+''')


@dataclass
class Directive:
    name: str
    arguments: tuple
    children: list | None = None


def parse_nginx(source):
    """A small quote/comment/brace-aware reader; JSON strings are single tokens."""
    tokens = []
    cursor = 0
    for match in TOKEN.finditer(source):
        if match.start() != cursor:
            raise AssertionError("Unrecognized Nginx configuration syntax")
        cursor = match.end()
        value = match.group()
        if value.isspace() or value.startswith("#"):
            continue
        tokens.append(value)
    if cursor != len(source):
        raise AssertionError("Unrecognized trailing Nginx configuration syntax")
    position = 0

    def block(nested=False):
        nonlocal position
        statements = []
        while position < len(tokens):
            if tokens[position] == "}":
                if not nested:
                    raise AssertionError("Unexpected closing Nginx brace")
                position += 1
                return statements
            words = []
            while position < len(tokens) and tokens[position] not in ("{", "}", ";"):
                word = tokens[position]
                words.append(word[1:-1] if word.startswith(("'", '"')) else word)
                position += 1
            if not words or position == len(tokens):
                raise AssertionError("Incomplete Nginx directive")
            terminator = tokens[position]
            position += 1
            if terminator == "{":
                statements.append(Directive(words[0], tuple(words[1:]), block(True)))
            elif terminator == ";":
                statements.append(Directive(words[0], tuple(words[1:])))
            else:
                raise AssertionError("Missing Nginx directive terminator")
        if nested:
            raise AssertionError("Unclosed Nginx block")
        return statements

    return block()


def select(nodes, name, arguments=None):
    return [node for node in nodes if node.name == name
            and (arguments is None or node.arguments == arguments)]


def descend(nodes):
    for node in nodes:
        yield node
        if node.children is not None:
            yield from descend(node.children)


class NginxUploadEdgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tree = parse_nginx((REPOSITORY / "deploy/nginx/nginx.conf").read_text(encoding="utf-8"))
        cls.http, = select(cls.tree, "http")
        cls.server, = select(cls.http.children, "server")
        cls.api, = select(cls.server.children, "location", ("/api/v1/",))
        cls.upload, = select(cls.api.children, "location")

    def require_directive(self, nodes, name, *arguments):
        self.assertEqual(select(nodes, name), [Directive(name, tuple(arguments))])

    def test_only_exact_upload_path_has_the_large_unbuffered_nested_location(self):
        self.assertEqual(self.upload.arguments, ("~", UPLOAD_PATTERN))
        self.assertEqual(select(self.upload.children, "location"), [])
        self.require_directive(self.http.children, "client_max_body_size", "1m")
        self.require_directive(self.upload.children, "client_max_body_size", "2048m")
        self.require_directive(self.upload.children, "proxy_request_buffering", "off")
        self.require_directive(self.upload.children, "proxy_read_timeout", "600s")
        self.require_directive(self.upload.children, "proxy_pass", "http://backend_upstream")
        all_nodes = list(descend(self.tree))
        self.assertEqual(select(all_nodes, "client_max_body_size"),
                         [Directive("client_max_body_size", ("1m",)),
                          Directive("client_max_body_size", ("2048m",))])
        self.assertEqual(select(all_nodes, "proxy_request_buffering"),
                         [Directive("proxy_request_buffering", ("off",))])
        # No more-specific prefix may steal upload requests from this nested regex.
        for location in select(self.server.children, "location"):
            self.assertFalse(any(argument.startswith("/api/v1/") and argument != "/api/v1/"
                                 for argument in location.arguments), location.arguments)
        for path in ("/api/v1/admin/movies/id/video", "/api/v1/admin/movies/a-b/video"):
            self.assertIsNotNone(re.fullmatch(UPLOAD_PATTERN, path))
        for path in ("/api/v1/admin/movies/id/video/", "/api/v1/admin/movies/id/videos",
                     "/api/v1/admin/movies/a/b/video", "/api/v1/admin/movies//video",
                     "/API/v1/admin/movies/id/video", "/hls/id/video"):
            self.assertIsNone(re.fullmatch(UPLOAD_PATTERN, path))

    def test_upload_inherits_http11_forwarded_headers_and_error_mappings(self):
        self.require_directive(self.api.children, "proxy_http_version", "1.1")
        self.assertEqual(select(self.api.children, "proxy_set_header"), [
            Directive("proxy_set_header", ("Host", "$host")),
            Directive("proxy_set_header", ("X-Real-IP", "$remote_addr")),
            Directive("proxy_set_header", ("X-Forwarded-For", "$proxy_add_x_forwarded_for")),
            Directive("proxy_set_header", ("X-Forwarded-Proto", "$scheme")),
            Directive("proxy_set_header", ("X-Request-ID", "$request_id")),
            Directive("proxy_set_header", ("Authorization", "$http_authorization")),
        ])
        for forbidden in ("proxy_set_header", "add_header", "error_page", "proxy_http_version",
                          "client_body_timeout", "send_timeout", "proxy_send_timeout"):
            self.assertEqual(select(self.upload.children, forbidden), [], forbidden)
        for name in ("client_body_timeout", "send_timeout", "proxy_send_timeout"):
            # An absent directive uses the frozen 60s default; explicit values must agree.
            for directive in select(list(descend(self.tree)), name):
                self.assertEqual(directive.arguments, ("60s",))

    def test_error_mappings_are_api_only_and_backend_errors_are_not_intercepted(self):
        self.require_directive(self.api.children, "proxy_intercept_errors", "off")
        mappings = select(self.api.children, "error_page")
        self.assertEqual(len(mappings), 4)
        self.assertEqual({node.arguments for node in mappings},
                         {(str(status), "=", f"@api_error_{status}") for status in (413, 502, 503, 504)})
        self.assertEqual(select(list(descend(self.tree)), "error_page"), mappings)
        self.assertEqual(select(list(descend(self.tree)), "proxy_intercept_errors"),
                         select(self.api.children, "proxy_intercept_errors"))
        self.assertEqual(select(self.server.children, "add_header"), [
            Directive("add_header", ("X-Content-Type-Options", "nosniff", "always")),
            Directive("add_header", ("X-Frame-Options", "DENY", "always")),
            Directive("add_header", ("Referrer-Policy", "no-referrer", "always")),
        ])
        codes = {413: "PAYLOAD_TOO_LARGE", 502: "UPSTREAM_UNAVAILABLE",
                 503: "UPSTREAM_UNAVAILABLE", 504: "UPSTREAM_TIMEOUT"}
        for status, code in codes.items():
            with self.subTest(status=status):
                error, = select(self.server.children, "location", (f"@api_error_{status}",))
                self.require_directive(error.children, "default_type", "application/json")
                self.assertEqual(select(error.children, "types"), [Directive("types", (), [])])
                response, = select(error.children, "return")
                self.assertEqual(response.arguments[0], str(status))
                payload = json.loads(response.arguments[1])
                self.assertEqual(set(payload), {"timestamp", "status", "code", "message", "requestId"})
                self.assertEqual(payload["status"], status)
                self.assertIs(type(payload["status"]), int)
                self.assertEqual(payload["code"], code)
                self.assertEqual(payload["timestamp"], "$time_iso8601")
                self.assertEqual(payload["requestId"], "$request_id")
                self.assertIsInstance(payload["message"], str)
                self.assertTrue(payload["message"])
                self.assertNotIn("$", payload["message"])
                for forbidden in ("add_header", "error_page", "proxy_set_header"):
                    self.assertEqual(select(error.children, forbidden), [])

    def test_hls_and_health_routing_keep_their_existing_directives(self):
        expected = parse_nginx("""
            location = /hls { return 404; }
            location /hls/ {
                proxy_pass http://backend_upstream/hls/;
                proxy_http_version 1.1;
                proxy_set_header Host $host;
                proxy_set_header X-Real-IP $remote_addr;
                proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
                proxy_set_header X-Forwarded-Proto $scheme;
                proxy_set_header X-Request-ID $request_id;
                proxy_set_header Authorization $http_authorization;
            }
            location = /actuator/health {
                proxy_pass http://backend_upstream/actuator/health;
                proxy_set_header Host $host;
                proxy_set_header X-Request-ID $request_id;
            }
        """)
        for node in expected:
            self.assertEqual(select(self.server.children, "location", node.arguments), [node])

    def test_upload_defaults_equal_the_frozen_mib_limit_at_every_layer(self):
        def read(path):
            return (REPOSITORY / path).read_text(encoding="utf-8")

        def single_number(pattern, text):
            matches = re.findall(pattern, text, re.MULTILINE)
            self.assertEqual(len(matches), 1, pattern)
            return int(matches[0])

        limits = {
            "nginx": int(select(self.upload.children, "client_max_body_size")[0].arguments[0][:-1]),
            "env": single_number(r"^MAX_UPLOAD_SIZE_MB=(\d+)$", read(".env.example")),
            "compose": single_number(r"MAX_UPLOAD_SIZE_MB: \$\{MAX_UPLOAD_SIZE_MB:-(\d+)\}",
                                     read("deploy/docker-compose.yml")),
            "preflight": single_number(r"^edge_upload_limit_mb=(\d+)$",
                                       read("deploy/tests/production-config-check.sh")),
            "spa": single_number(r"^export const MAX_UPLOAD_SIZE_MB = (\d+);$",
                                 read("frontend/src/features/movies/uploadLimits.ts")),
        }
        spring = re.findall(r"max-(file|request)-size: \$\{MAX_UPLOAD_SIZE_MB:(\d+)\}MB",
                            read("backend/common/src/main/resources/application.yml"))
        self.assertEqual({kind for kind, _ in spring}, {"file", "request"})
        self.assertEqual(len(spring), 2)
        limits.update({"spring-" + kind: int(value) for kind, value in spring})
        preflight = read("deploy/docker-compose.prod.yml").split("\n  postgres:", 1)[0]
        limits["production-compose"] = single_number(
            r"MAX_UPLOAD_SIZE_MB: \$\{MAX_UPLOAD_SIZE_MB:-(\d+)\}", preflight)
        self.assertEqual(limits, {name: 2048 for name in limits})


if __name__ == "__main__":
    unittest.main()
