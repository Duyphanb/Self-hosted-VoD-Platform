#!/usr/bin/env python3
"""Exercise the production Nginx upload edge with disposable Docker-only stubs.

No JVM, real credentials or existing Compose resources are used. The upstream
reads both body framings; a barrier proves forwarding before the client finishes.
"""

import argparse
from datetime import datetime
import hashlib
import http.client
import json
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import time


ROOT = Path(__file__).resolve().parents[2]
UPLOAD = "/api/v1/admin/movies/11111111-1111-1111-1111-111111111111/video"
MIB = 1024 * 1024
SECURITY_HEADERS = {"x-content-type-options": "nosniff", "x-frame-options": "DENY",
                    "referrer-policy": "no-referrer"}


def docker(*arguments, check=True, timeout=180):
    result = subprocess.run(["docker", *arguments], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        # These resources contain no secrets or application request bodies.
        raise AssertionError("Docker command failed: " + result.stderr.strip())
    return result


def request(port, path, body=None, headers=None, method="GET"):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    try:
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        return response.status, dict((key.lower(), value) for key, value in response.getheaders()), response.read()
    finally:
        connection.close()


def assert_security(headers):
    for name, value in SECURITY_HEADERS.items():
        assert headers.get(name) == value, "Security header lost: " + name


def assert_edge_error(response, status, code):
    actual_status, headers, body = response
    assert actual_status == status, f"Expected edge {status}, got {actual_status}"
    assert headers.get("content-type") == "application/json", "Edge error is not JSON"
    assert_security(headers)
    payload = json.loads(body)
    assert set(payload) == {"timestamp", "status", "code", "message", "requestId"}, "Unexpected ApiError shape"
    assert type(payload["status"]) is int and payload["status"] == status, "Original edge status lost"
    assert payload["code"] == code and isinstance(payload["message"], str) and payload["message"], "Unsafe error code/message"
    datetime.fromisoformat(payload["timestamp"].replace("Z", "+00:00"))
    assert re.fullmatch(r"[a-f0-9]{32}", payload["requestId"]), "Unsafe edge request ID"
    assert payload["requestId"] == headers.get("x-smoke-request-id"), "JSON request ID differs from Nginx request ID"
    return payload


def header_only_request(port, path, size):
    with socket.create_connection(("127.0.0.1", port), timeout=10) as connection:
        connection.sendall((f"POST {path} HTTP/1.1\r\nHost: localhost\r\nContent-Length: {size}\r\n"
                            "Connection: close\r\n\r\n").encode("ascii"))
        response = http.client.HTTPResponse(connection)
        response.begin()
        return response.status, dict((key.lower(), value) for key, value in response.getheaders()), response.read()


def state(port, token):
    status, _, body = request(port, "/api/v1/__smoke_state__/" + token)
    assert status == 200, "Upstream state probe failed"
    return json.loads(body)


def wait_for_bytes(port, token):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        observed = state(port, token)
        if observed.get("bytes", 0) > 0:
            assert not observed["complete"], "Upstream unexpectedly completed before remaining bytes were sent"
            return observed
        time.sleep(0.05)
    raise AssertionError("Upstream received no body bytes before the client finished: buffering is enabled")


def streaming_upload(port, chunked):
    token = secrets.token_hex(8)
    boundary = "vod-edge-smoke-boundary"
    file_bytes = b"\x00\x00\x00\x18ftyp" + bytes(2 * MIB - 8)
    payload = ((f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"smoke.mp4\"\r\n"
                "Content-Type: video/mp4\r\n\r\n").encode() + file_bytes + f"\r\n--{boundary}--\r\n".encode())
    first = payload[:64 * 1024]
    framing = "Transfer-Encoding: chunked" if chunked else f"Content-Length: {len(payload)}"
    with socket.create_connection(("127.0.0.1", port), timeout=15) as connection:
        connection.sendall((f"POST {UPLOAD}?probe=stream HTTP/1.1\r\nHost: localhost\r\n{framing}\r\n"
                            f"X-Smoke-Token: {token}\r\nX-Request-ID: client-id-must-be-replaced\r\n"
                            f"Authorization: Bearer smoke-placeholder\r\nContent-Type: multipart/form-data; boundary={boundary}\r\n"
                            "Connection: close\r\n\r\n").encode("ascii"))

        def send_chunk(data):
            if chunked:
                connection.sendall(f"{len(data):x}\r\n".encode() + data + b"\r\n")
            else:
                connection.sendall(data)

        send_chunk(first)
        # The sender deliberately withholds the other ~2 MiB. Log absence alone
        # cannot prove streaming; this upstream-observed barrier can.
        observed = wait_for_bytes(port, token)
        for offset in range(len(first), len(payload), 64 * 1024):
            send_chunk(payload[offset:offset + 64 * 1024])
        if chunked:
            connection.sendall(b"0\r\n\r\n")
        response = http.client.HTTPResponse(connection)
        response.begin()
        headers = dict((key.lower(), value) for key, value in response.getheaders())
        result = json.loads(response.read())
        assert response.status == 202, "2 MiB upload was not accepted"
    assert_security(headers)
    assert result["bytes"] == len(payload) and result["sha256"] == hashlib.sha256(payload).hexdigest(), "Stream body changed"
    assert result["prefix"] == payload[:8].hex(), "Multipart prefix changed"
    assert result["path"] == UPLOAD + "?probe=stream", "URI-less proxy_pass changed path/query"
    assert result["protocol"] == "HTTP/1.1", "Upload did not inherit HTTP/1.1"
    assert result["requestId"] == observed["requestId"] == headers["x-smoke-request-id"], "Request ID inheritance lost"
    assert result["requestId"] != "client-id-must-be-replaced", "Edge did not generate request ID"
    assert result["host"] == "localhost" and result["realIp"] and result["forwardedFor"], "Forwarded headers lost"
    assert result["forwardedProto"] == "http" and result["authorization"] == "Bearer smoke-placeholder", "Header inheritance lost"
    if chunked:
        assert result["transferEncoding"] == "chunked", "Chunked body did not reach HTTP/1.1 upstream"
    print("PASS 2 MiB upload streams before completion (" + ("chunked" if chunked else "Content-Length") + ")", flush=True)


def exercise(port):
    for path in ("/api/v1/auth/login", "/api/v1/movies", UPLOAD + "/", UPLOAD + "s",
                 UPLOAD.replace("/video", "/VIDEO")):
        assert_edge_error(header_only_request(port, path, MIB + 1), 413, "PAYLOAD_TOO_LARGE")
    normal = b"a" * MIB
    status, headers, body = request(port, "/api/v1/movies", normal, method="POST")
    assert status == 200 and json.loads(body)["bytes"] == MIB, "Exactly 1 MiB ordinary request was rejected"
    assert_security(headers)
    assert_edge_error(header_only_request(port, UPLOAD, 2048 * MIB + 1), 413, "PAYLOAD_TOO_LARGE")
    print("PASS global 1 MiB and upload 2048 MiB limits, exact boundary and route isolation", flush=True)

    streaming_upload(port, False)
    streaming_upload(port, True)

    for status in (500, 503):
        actual, headers, body = request(port, UPLOAD, b"stub", {"X-Stub-Status": str(status)}, "POST")
        assert actual == status, "Backend upload status was intercepted"
        request_id = headers["x-smoke-request-id"]
        expected = {"timestamp": "2026-11-02T00:00:00Z", "status": status,
                    "code": "UPLOAD_STORAGE_UNAVAILABLE" if status == 503 else "UPLOAD_OUTCOME_UNKNOWN",
                    "message": "The upload could not be completed.", "requestId": request_id}
        assert body == json.dumps(expected, indent=2).encode() + b"\n", "Backend ApiError bytes were replaced"
        assert headers.get("content-type") == "application/json", "Backend content type was replaced"
        assert_security(headers)
    print("PASS backend 500/503 ApiError bytes and codes pass through unchanged", flush=True)

    assert_edge_error(request(port, "/api/v1/__smoke_refused__"), 502, "UPSTREAM_UNAVAILABLE")
    assert_edge_error(request(port, "/api/v1/__smoke_503__"), 503, "UPSTREAM_UNAVAILABLE")
    token = secrets.token_hex(8)
    before = time.monotonic()
    timeout = assert_edge_error(request(port, UPLOAD, headers={"X-Smoke-Hang": "yes", "X-Smoke-Token": token}),
                                504, "UPSTREAM_TIMEOUT")
    elapsed = time.monotonic() - before
    assert 1.5 <= elapsed < 9, "Test-only upstream read timeout was not exercised"
    assert timeout["requestId"] == state(port, token)["requestId"], "504 JSON and forwarded request IDs differ"
    print("PASS refused upstream 502, nested return 503 and accept-without-response 504 JSON", flush=True)

    for path, content_type in (("/hls/video-assets/smoke/master.m3u8", "application/vnd.apple.mpegurl"),
                               ("/hls/video-assets/smoke/segments/one.ts", "video/mp2t")):
        status, headers, body = request(port, path)
        assert status == 200 and body == path.encode() and headers["content-type"] == content_type, "HLS route changed"
        assert_security(headers)
    status, headers, body = request(port, "/actuator/health")
    assert status == 200 and json.loads(body) == {"status": "UP", "stub": "backend"}, "Health route changed"
    assert_security(headers)
    for path in ("/api/v1", "/hls"):
        assert request(port, path)[0] == 404, "Reserved exact route changed"
    # API JSON handling is route scoped, not a blanket edge error policy.
    status, headers, _ = header_only_request(port, "/login", MIB + 1)
    assert status == 413 and headers["content-type"].startswith("text/html"), "API mappings leaked to SPA routes"
    print("PASS HLS/health and reserved routing, MIME types, security headers and API-only errors", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default="vod-auth-audit-edge-" + secrets.token_hex(6))
    arguments = parser.parse_args()
    assert re.fullmatch(r"vod-auth-audit-[a-z0-9][a-z0-9_-]*", arguments.project), "Disposable audit project required"
    project = arguments.project
    stub, edge = project + "-stub", project + "-edge"
    syntax_names = [project + "-syntax-" + str(index) for index in range(2)]
    for name in (stub, edge, *syntax_names):
        assert docker("container", "inspect", name, check=False).returncode != 0, "Container already exists; refusing reuse"
    assert docker("network", "inspect", project, check=False).returncode != 0, "Network already exists; refusing reuse"
    production = (ROOT / "deploy/nginx/nginx.conf").read_text(encoding="utf-8")
    assert production.count("proxy_read_timeout 600s;") == 1, "Production upload timeout must stay 600s"
    test_config = production.replace("proxy_read_timeout 600s;", "proxy_read_timeout 2s;")
    # Instrument only the server level, so upload/named-location inheritance is
    # exercised exactly as in production. The extra response header attests the
    # same Nginx-generated variable used in JSON and forwarded upstream.
    test_config = test_config.replace('add_header Referrer-Policy "no-referrer" always;',
                                      'add_header Referrer-Policy "no-referrer" always;\n'
                                      '        add_header X-Smoke-Request-ID $request_id always;')
    marker = "        location /api/v1/ {\n"
    assert test_config.count(marker) == 1, "Expected exactly one API parent"
    probes = ("            # Test-only probes inherit the real API error handling.\n"
              "            location = /api/v1/__smoke_503__ { return 503; }\n"
              "            location = /api/v1/__smoke_refused__ { proxy_pass http://backend:8082; }\n")
    test_config = test_config.replace(marker, marker + probes)
    scratch = ROOT / ".lo_174b"
    scratch.mkdir(exist_ok=True)
    network_created = False
    created_containers = []
    syntax_attempts = []
    primary_failure = False
    with tempfile.TemporaryDirectory(prefix=project + "-", dir=scratch) as directory:
        config = Path(directory) / "nginx.conf"
        config.write_text(test_config, encoding="utf-8", newline="\n")
        try:
            docker("network", "create", "--label", "vod.audit=" + project, project)
            network_created = True
            created_containers.append(stub)
            docker("create", "--name", stub, "--label", "vod.audit=" + project,
                   "--network", project, "--network-alias", "backend", "--network-alias", "frontend",
                   "--memory", "96m", "--mount", "type=bind,source=" + str(ROOT / "deploy/tests/nginx-edge-stub.py") + ",target=/stub.py,readonly",
                   "python:3.13-alpine", "python", "/stub.py")
            docker("start", stub)
            # Validate both untouched production and instrumented test configs.
            for name, candidate in zip(syntax_names, (ROOT / "deploy/nginx/nginx.conf", config)):
                syntax_attempts.append(name)
                docker("run", "--rm", "--name", name, "--label", "vod.audit=" + project,
                       "--network", project, "--memory", "64m", "--mount",
                       "type=bind,source=" + str(candidate) + ",target=/etc/nginx/nginx.conf,readonly",
                       "nginx:1.27-alpine", "nginx", "-t")
            print("PASS nginx -t (production 600s and short-timeout smoke copy)", flush=True)
            created_containers.append(edge)
            docker("create", "--name", edge, "--label", "vod.audit=" + project,
                   "--network", project, "--memory", "96m", "--publish", "127.0.0.1::80", "--mount",
                   "type=bind,source=" + str(config) + ",target=/etc/nginx/nginx.conf,readonly", "nginx:1.27-alpine")
            docker("start", edge)
            port = int(docker("port", edge, "80/tcp").stdout.strip().rsplit(":", 1)[1])
            deadline = time.monotonic() + 30
            while True:
                try:
                    if request(port, "/actuator/health")[0] == 200:
                        break
                except (OSError, http.client.HTTPException):
                    pass
                assert time.monotonic() < deadline, "Disposable edge stack did not become ready"
                time.sleep(0.2)
            exercise(port)
        except BaseException:
            primary_failure = True
            # Stubs carry only generated synthetic data; no production resources.
            for name in created_containers:
                print(docker("logs", "--tail", "30", name, check=False).stderr, flush=True)
            raise
        finally:
            # Only resources this invocation created are eligible for removal.
            cleanup_errors = []
            for name in reversed(created_containers + syntax_attempts):
                try:
                    exists = docker("container", "inspect", name, check=False)
                    if exists.returncode == 0:
                        assert json.loads(exists.stdout)[0]["Config"]["Labels"].get("vod.audit") == project, "Resource ownership changed"
                        docker("rm", "-f", "-v", name)
                except Exception:
                    cleanup_errors.append(name)
            if network_created:
                try:
                    docker("network", "rm", project)
                except Exception:
                    cleanup_errors.append(project)
            if cleanup_errors:
                message = "Cleanup incomplete for own resources: " + ", ".join(cleanup_errors)
                if primary_failure:
                    print(message, flush=True)
                else:
                    raise AssertionError(message)
    print("PASS Nginx upload-edge smoke; disposable containers/network removed", flush=True)


if __name__ == "__main__":
    main()
