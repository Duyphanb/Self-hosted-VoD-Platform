#!/usr/bin/env python3
"""Disposable edge-smoke upstream; consumes Content-Length and chunked bodies."""

import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import time


STATES = {}
LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass

    def respond(self, status, body, content_type="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def handle_request(self):
        self.connection.settimeout(15)
        if self.path.startswith("/api/v1/__smoke_state__/"):
            with LOCK:
                state = dict(STATES.get(self.path.rsplit("/", 1)[1], {}))
            self.respond(200, json.dumps(state).encode())
            return

        token = self.headers.get("X-Smoke-Token", "")
        state = {"bytes": 0, "complete": False,
                 "requestId": self.headers.get("X-Request-ID", "")}
        with LOCK:
            STATES[token] = state

        if self.headers.get("X-Smoke-Hang") == "yes":
            # The connection and headers were accepted, but no response is sent.
            time.sleep(60)
            self.close_connection = True
            return

        digest = hashlib.sha256()
        prefix = bytearray()

        def consume(size):
            remaining = size
            while remaining:
                block = self.rfile.read(min(4096, remaining))
                if not block:
                    raise ConnectionError("Incomplete smoke body")
                digest.update(block)
                if len(prefix) < 8:
                    prefix.extend(block[:8 - len(prefix)])
                with LOCK:
                    state["bytes"] += len(block)
                remaining -= len(block)

        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            while True:
                size = int(self.rfile.readline().split(b";", 1)[0].strip(), 16)
                if not size:
                    while self.rfile.readline() != b"\r\n":
                        pass
                    break
                consume(size)
                if self.rfile.read(2) != b"\r\n":
                    raise ValueError("Invalid smoke chunk terminator")
        else:
            consume(int(self.headers.get("Content-Length", "0")))

        with LOCK:
            state["complete"] = True
        if self.headers.get("X-Stub-Status") in ("500", "503"):
            status = int(self.headers["X-Stub-Status"])
            payload = {"timestamp": "2026-11-02T00:00:00Z", "status": status,
                       "code": "UPLOAD_STORAGE_UNAVAILABLE" if status == 503 else "UPLOAD_OUTCOME_UNKNOWN",
                       "message": "The upload could not be completed.",
                       "requestId": state["requestId"]}
            # Deliberate whitespace/newline: interception or re-serialization fails equality.
            self.respond(status, json.dumps(payload, indent=2).encode() + b"\n")
            return

        if self.path.startswith("/hls/"):
            content_type = "application/vnd.apple.mpegurl" if self.path.endswith(".m3u8") else "video/mp2t"
            self.respond(200, self.path.encode(), content_type)
            return
        if self.path == "/actuator/health":
            self.respond(200, b'{"status":"UP","stub":"backend"}')
            return

        payload = {"path": self.path, "protocol": self.request_version,
                   "bytes": state["bytes"], "sha256": digest.hexdigest(), "prefix": prefix.hex(),
                   "requestId": state["requestId"], "host": self.headers.get("Host"),
                   "realIp": self.headers.get("X-Real-IP"),
                   "forwardedFor": self.headers.get("X-Forwarded-For"),
                   "forwardedProto": self.headers.get("X-Forwarded-Proto"),
                   "authorization": self.headers.get("Authorization"),
                   "transferEncoding": self.headers.get("Transfer-Encoding")}
        self.respond(202 if "/admin/movies/" in self.path and "/video" in self.path else 200,
                     json.dumps(payload).encode())

    do_GET = handle_request
    do_POST = handle_request


if __name__ == "__main__":
    # Port 80 supplies the frontend alias; 8080 is the backend upstream.
    frontend = ThreadingHTTPServer(("0.0.0.0", 80), Handler)
    threading.Thread(target=frontend.serve_forever, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
