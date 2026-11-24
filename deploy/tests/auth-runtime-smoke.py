#!/usr/bin/env python3
"""Auth regression checks against a fresh, disposable local Compose project.

Caller creates/destroys the project; never point this at an existing database.
Uses real HTTP, PostgreSQL constraints, and concurrent requests, not mocks.
"""
from auth_cookies import auth_headers, capture_auth_cookie
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import subprocess
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--base-url", required=True)
    args = parser.parse_args()
    assert args.project.startswith("vod-auth-audit-"), "Use a dedicated audit project"
    assert urllib.parse.urlparse(args.base_url).hostname in ("localhost", "127.0.0.1")
    container = args.project + "-postgres-1"
    label = subprocess.check_output([
        "docker", "inspect", "--format",
        '{{index .Config.Labels "com.docker.compose.project"}}', container
    ], text=True).strip()
    assert label == args.project

    def sql(statement):
        result = subprocess.run([
            "docker", "exec", "-i", container, "psql", "-X", "-v", "ON_ERROR_STOP=1",
            "-U", "vod_app", "-d", "vod_platform", "-At"
        ], input=statement, text=True, encoding="utf-8", capture_output=True)
        assert result.returncode == 0, "Fixture SQL failed (details withheld)"
        return result.stdout.strip()

    assert sql("SELECT count(*) FROM users;") == "0", "Database must be fresh"

    def request(path, body=None, token=None, method=None, origin="default", cookie=None, host=None):
        headers = {}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        auth_headers(args.base_url, path, headers, cookie)
        if origin is None:
            headers.pop("Origin", None)
        elif origin != "default":
            headers["Origin"] = origin
        if host is not None:
            headers["Host"] = host
        req = urllib.request.Request(
            args.base_url + path, data=None if body is None else json.dumps(body).encode(),
            headers=headers, method=method or ("POST" if body is not None or path.startswith("/api/v1/auth/") else "GET")
        )
        try:
            response = urllib.request.urlopen(req, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            data = json.loads(raw) if raw and response.headers.get_content_type() == "application/json" else None
            return response.status, capture_auth_cookie(response, data, path)

    password = "Audit-fixture-only!0917"
    email = "Viewer@example.test"
    invalid_names = ["  ", "\u00a0\u2007\u202f", "\0Viewer", "Viewer\0", "View\rer", "View\ner",
                     "\tViewer", "View\u007fer", "View\u009fer", " A ", "\U0001f600", " \U0001f600 ",
                     " " + "A" * 100 + " ", " " * 10000 + "AB", "\U0001f600" * 101]

    def assert_name_error(result):
        status, body = result
        assert status == 400 and body["code"] == "VALIDATION_ERROR", "Expected displayName validation error"
        assert any(error["field"] == "displayName" for error in body["fieldErrors"])

    for name in invalid_names:
        assert_name_error(request("/api/v1/auth/register", {
            "email": "invalid-name@example.test", "password": password, "displayName": name
        }))
    assert sql("SELECT count(*) FROM users;") == "0", "Invalid registrations must not write users"
    print("PASS PostgreSQL registration rejects blank, NUL, CR/LF, tab and other Cc without writes")

    def register(address):
        return request("/api/v1/auth/register", {
            "email": address, "password": password, "displayName": "Audit Viewer"
        })
    def login(address=email, secret=password):
        return request("/api/v1/auth/login", {"email": address, "password": secret})
    def refresh(value):
        return request("/api/v1/auth/refresh", cookie=value)
    def concurrent(callables):
        barrier = threading.Barrier(len(callables))
        def run(fn):
            barrier.wait(timeout=10)
            return fn()
        with ThreadPoolExecutor(max_workers=len(callables)) as pool:
            return list(pool.map(run, callables))

    status, profile = register(email)
    assert status == 201 and set(profile) == {"id", "email", "displayName", "roles"}
    assert profile["roles"] == ["ROLE_USER"]
    assert register(email.lower())[0] == 409
    assert login(email.upper())[0] == 200
    assert register("viewer+tag@example.test")[0] == 201
    assert register("view.er@example.test")[0] == 201
    user_id = str(uuid.UUID(profile["id"]))
    assert sql(f"SELECT status = 'ACTIVE' AND password_hash LIKE '{{bcrypt-sha256}}$2%' FROM users WHERE id = '{user_id}';") == "t"
    print("PASS registration, case identity, DTO, BCrypt storage, default role")

    # Direct persistence writes must obey the same rule, even outside the service.
    direct_id = str(uuid.uuid4())
    sql(f"""DO $$ BEGIN
        BEGIN
            INSERT INTO users (id, email, password_hash, display_name, status, created_at, updated_at)
            SELECT '{direct_id}'::uuid, upper(email), password_hash,
                display_name, status, created_at, updated_at FROM users WHERE id = '{user_id}';
            RAISE EXCEPTION 'Case-duplicate insert unexpectedly succeeded';
        EXCEPTION WHEN unique_violation THEN NULL;
        END;
    END $$;""")
    assert sql(f"SELECT count(*) FROM users WHERE id = '{direct_id}';") == "0"
    for left, right in [("Race@example.test", "race@EXAMPLE.test"), ("same@example.test", "same@example.test")]:
        outcomes = concurrent([lambda: register(left), lambda: register(right)])
        assert sorted(status for status, _ in outcomes) == [201, 409]
    print("PASS database enforcement and concurrent duplicate registration")

    wrong_status, wrong = login(secret="wrong-password")
    missing_status, missing = login("missing@example.test", "wrong-password")
    assert wrong_status == missing_status == 401
    assert {k: v for k, v in wrong.items() if k != "timestamp"} == {k: v for k, v in missing.items() if k != "timestamp"}
    status, session = login()
    assert status == 200 and session["expiresInSeconds"] > 0
    assert request("/api/v1/auth/login", {"email": email, "password": password},
                   origin=args.base_url)[0] == 200, "Same-origin login through Nginx was rejected"
    print("PASS same-origin login through Nginx on the explicitly allowed public origin")
    # A non-auth route avoids ADR-012's Origin filter masking #236 regressions.
    # First prove the alternate origin is not allow-listed, then make it same-
    # origin via Host: success requires the edge's forwarded host AND port.
    edge = urllib.parse.urlsplit(args.base_url)
    alternate = "localhost" if edge.hostname == "127.0.0.1" else "127.0.0.1"
    alternate_host = alternate + (f":{edge.port}" if edge.port else "")
    alternate_origin = f"{edge.scheme}://{alternate_host}"
    assert request("/api/v1/health", origin=alternate_origin)[0] == 403, "Probe origin must not be allow-listed"
    assert request("/api/v1/health", origin=alternate_origin, host=alternate_host)[0] == 200, "Forwarded edge origin was not applied"
    print("PASS non-auth same-origin request with an origin outside the allowlist (#236)")
    for origin in (None, "https://untrusted.example"):
        status, error = request("/api/v1/auth/refresh", origin=origin)
        assert status == 403 and error["code"] == "ORIGIN_NOT_ALLOWED"
    status, error = request("/api/v1/auth/refresh")
    assert status == 401 and error["code"] == "UNAUTHORIZED"
    access, original = session["accessToken"], session.refresh_cookie
    digest = hashlib.sha256(original.encode()).hexdigest()
    assert sql(f"SELECT count(*) FROM refresh_tokens WHERE user_id = '{user_id}' AND token_hash = '{digest}' AND revoked_at IS NULL;") == "1"
    assert request("/api/v1/users/me")[0] == 401
    assert request("/api/v1/users/me", token=access[:-8] + "invalid")[0] == 401
    assert request("/api/v1/users/me", token=access)[1]["id"] == user_id
    assert request("/api/v1/users/me", {"displayName": "Updated", "id": str(uuid.uuid4()), "email": "attacker@example.test", "roles": ["ROLE_ADMIN"]}, access, "PUT")[0] == 200
    current = request("/api/v1/users/me", token=access)[1]
    assert current["id"] == user_id and current["email"] == email and current["roles"] == ["ROLE_USER"]
    print("PASS generic login failures, JWT, hashed refresh storage, profile ownership")

    for name in invalid_names:
        assert_name_error(request("/api/v1/users/me", {"displayName": name}, access, "PUT"))
    assert request("/api/v1/users/me", token=access)[1]["displayName"] == "Updated"
    assert sql(f"SELECT display_name FROM users WHERE id = '{user_id}';") == "Updated"
    valid_name = "Tên  Việt\u200b\u202e\ufeff"
    status, trimmed = request("/api/v1/users/me", {"displayName": "\u00a0 " + valid_name + " \u202f"}, access, "PUT")
    assert status == 200 and trimmed["displayName"] == valid_name
    status, registered = request("/api/v1/auth/register", {
        "email": "trimmed-name@example.test", "password": password,
        "displayName": "\u2007 " + valid_name + " \u3000"
    })
    assert status == 201 and registered["displayName"] == valid_name
    assert sql("SELECT display_name FROM users WHERE email = 'trimmed-name@example.test';") == valid_name
    print("PASS PostgreSQL profile rejects invalid names without writes; both endpoints trim and preserve Cf")

    # H2 varchar counts UTF-16 units; verify the contract's astral code-point bounds on PostgreSQL.
    for count in (2, 51, 100):
        name = "\U0001f600" * count
        address = f"emoji-{count}@example.test"
        status, registered = request("/api/v1/auth/register", {
            "email": address, "password": password, "displayName": name
        })
        assert status == 201 and registered["displayName"] == name
        assert sql(f"SELECT char_length(display_name) FROM users WHERE email = '{address}';") == str(count)
        status, updated = request("/api/v1/users/me", {"displayName": name}, access, "PUT")
        assert status == 200 and updated["displayName"] == name
        assert sql(f"SELECT char_length(display_name) FROM users WHERE id = '{user_id}';") == str(count)
    padded = " " + "\U0001f600" * 98 + " "
    status, registered = request("/api/v1/auth/register", {
        "email": "emoji-padded@example.test", "password": password, "displayName": padded
    })
    assert status == 201 and registered["displayName"] == "\U0001f600" * 98
    status, updated = request("/api/v1/users/me", {"displayName": padded}, access, "PUT")
    assert status == 200 and updated["displayName"] == "\U0001f600" * 98
    print("PASS PostgreSQL code-point bounds: reject single/101 emoji and raw overflow; store 2/51/100 and trim raw-100 padding")

    outcomes = concurrent([lambda: refresh(original), lambda: refresh(original)])
    assert sorted(status for status, _ in outcomes) == [200, 401]
    rotated = next(body for status, body in outcomes if status == 200).refresh_cookie
    assert refresh(original)[0] == 401
    assert refresh(rotated)[0] == 401, "Replay did not revoke the successor session"
    assert request("/api/v1/users/me", token=access)[0] == 401, "Replay did not invalidate the warmed session cache"

    current = login()[1]
    rotated = current.refresh_cookie
    assert request("/api/v1/auth/logout", cookie=rotated)[0] == 204
    assert request("/api/v1/auth/logout", cookie=rotated)[0] == 204
    assert request("/api/v1/auth/logout", method="POST")[0] == 204
    assert refresh(rotated)[0] == 401
    assert request("/api/v1/users/me", token=current["accessToken"])[0] == 401
    print("PASS concurrent rotation, replay rejection and idempotent logout")

    # Hold a real PostgreSQL row lock across expiry, before the HTTP request starts. The wait stays below the
    # 5-second statement timeout and the 6-second request deadline of the auth transactions.
    session = login()[1]
    digest = hashlib.sha256(session.refresh_cookie.encode()).hexdigest()
    sql(f"UPDATE refresh_tokens SET expires_at = now() + interval '3 seconds' WHERE token_hash = '{digest}';")
    holder = subprocess.Popen([
        "docker", "exec", "-i", container, "psql", "-X", "-v", "ON_ERROR_STOP=1",
        "-U", "vod_app", "-d", "vod_platform", "-At"
    ], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        holder.stdin.write(f"BEGIN; SELECT expires_at > now() FROM refresh_tokens WHERE token_hash = '{digest}' FOR UPDATE; SELECT 'LOCKED'; SELECT pg_sleep(4); COMMIT;\n")
        holder.stdin.flush()
        assert holder.stdout.readline().strip() == "BEGIN"
        assert holder.stdout.readline().strip() == "t"
        assert holder.stdout.readline().strip() == "LOCKED"
        assert refresh(session.refresh_cookie)[0] == 401
        holder.communicate(timeout=15)
        assert holder.returncode == 0
    finally:
        if holder.poll() is None:
            holder.kill()
            holder.communicate()
    print("PASS refresh expiration evaluated after acquiring PostgreSQL lock")

    session = login()[1]
    sql(f"UPDATE refresh_tokens SET expires_at = now() - interval '1 minute' WHERE user_id = '{user_id}';")
    assert refresh(session.refresh_cookie)[0] == 401
    session = login()[1]
    sql(f"UPDATE users SET status = 'DISABLED' WHERE id = '{user_id}';")
    assert login()[0] == 401
    assert refresh(session.refresh_cookie)[0] == 401
    assert request("/api/v1/users/me", token=session["accessToken"])[0] == 401
    assert request("/api/v1/health", origin="https://untrusted.example")[0] == 403
    print("PASS expiry, disabled user login/refresh/profile, CORS denial")


if __name__ == "__main__":
    main()
