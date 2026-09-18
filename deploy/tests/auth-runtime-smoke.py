#!/usr/bin/env python3
"""Auth regression checks against a fresh, disposable local Compose project.

Caller creates/destroys the project; never point this at an existing database.
Uses real HTTP, PostgreSQL constraints, and concurrent requests, not mocks.
"""
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
        ], input=statement, text=True, capture_output=True)
        assert result.returncode == 0, "Fixture SQL failed (details withheld)"
        return result.stdout.strip()

    assert sql("SELECT count(*) FROM users;") == "0", "Database must be fresh"

    def request(path, body=None, token=None, method=None, origin=None):
        headers = {}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        if origin:
            headers["Origin"] = origin
        req = urllib.request.Request(
            args.base_url + path, data=None if body is None else json.dumps(body).encode(),
            headers=headers, method=method or ("POST" if body is not None else "GET")
        )
        try:
            response = urllib.request.urlopen(req, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            data = json.loads(raw) if raw and response.headers.get_content_type() == "application/json" else None
            return response.status, data

    password = "Audit-fixture-only!0917"
    email = "Viewer@example.test"
    def register(address):
        return request("/api/v1/auth/register", {
            "email": address, "password": password, "displayName": "Audit Viewer"
        })
    def login(address=email, secret=password):
        return request("/api/v1/auth/login", {"email": address, "password": secret})
    def refresh(value):
        return request("/api/v1/auth/refresh", {"refreshToken": value})
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
            INSERT INTO users SELECT '{direct_id}'::uuid, upper(email), password_hash,
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
    access, original = session["accessToken"], session["refreshToken"]
    digest = hashlib.sha256(original.encode()).hexdigest()
    assert sql(f"SELECT count(*) FROM refresh_tokens WHERE user_id = '{user_id}' AND token_hash = '{digest}' AND revoked_at IS NULL;") == "1"
    assert request("/api/v1/users/me")[0] == 401
    assert request("/api/v1/users/me", token=access[:-8] + "invalid")[0] == 401
    assert request("/api/v1/users/me", token=access)[1]["id"] == user_id
    assert request("/api/v1/users/me", {"displayName": "Updated", "id": str(uuid.uuid4()), "email": "attacker@example.test", "roles": ["ROLE_ADMIN"]}, access, "PUT")[0] == 200
    current = request("/api/v1/users/me", token=access)[1]
    assert current["id"] == user_id and current["email"] == email and current["roles"] == ["ROLE_USER"]
    print("PASS generic login failures, JWT, hashed refresh storage, profile ownership")

    outcomes = concurrent([lambda: refresh(original), lambda: refresh(original)])
    assert sorted(status for status, _ in outcomes) == [200, 401]
    rotated = next(body for status, body in outcomes if status == 200)["refreshToken"]
    assert refresh(original)[0] == 401
    assert request("/api/v1/auth/logout", {"refreshToken": rotated})[0] == 204
    assert request("/api/v1/auth/logout", {"refreshToken": rotated})[0] == 204
    assert request("/api/v1/auth/logout", method="POST")[0] == 204
    assert refresh(rotated)[0] == 401
    # Existing access JWTs remain valid until expiration under the frozen model.
    assert request("/api/v1/users/me", token=access)[0] == 200
    print("PASS concurrent rotation, replay rejection and idempotent logout")

    # Hold a real PostgreSQL row lock across expiry, before the HTTP request starts.
    session = login()[1]
    digest = hashlib.sha256(session["refreshToken"].encode()).hexdigest()
    sql(f"UPDATE refresh_tokens SET expires_at = now() + interval '5 seconds' WHERE token_hash = '{digest}';")
    holder = subprocess.Popen([
        "docker", "exec", "-i", container, "psql", "-X", "-v", "ON_ERROR_STOP=1",
        "-U", "vod_app", "-d", "vod_platform", "-At"
    ], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        holder.stdin.write(f"BEGIN; SELECT expires_at > now() FROM refresh_tokens WHERE token_hash = '{digest}' FOR UPDATE; SELECT 'LOCKED'; SELECT pg_sleep(6); COMMIT;\n")
        holder.stdin.flush()
        assert holder.stdout.readline().strip() == "BEGIN"
        assert holder.stdout.readline().strip() == "t"
        assert holder.stdout.readline().strip() == "LOCKED"
        assert refresh(session["refreshToken"])[0] == 401
        holder.communicate(timeout=15)
        assert holder.returncode == 0
    finally:
        if holder.poll() is None:
            holder.kill()
            holder.communicate()
    print("PASS refresh expiration evaluated after acquiring PostgreSQL lock")

    session = login()[1]
    sql(f"UPDATE refresh_tokens SET expires_at = now() - interval '1 minute' WHERE user_id = '{user_id}';")
    assert refresh(session["refreshToken"])[0] == 401
    session = login()[1]
    sql(f"UPDATE users SET status = 'DISABLED' WHERE id = '{user_id}';")
    assert login()[0] == 401
    assert refresh(session["refreshToken"])[0] == 401
    assert request("/api/v1/users/me", token=session["accessToken"])[0] == 401
    assert request("/api/v1/health", origin="https://untrusted.example")[0] == 403
    print("PASS expiry, disabled user login/refresh/profile, CORS denial")


if __name__ == "__main__":
    main()
