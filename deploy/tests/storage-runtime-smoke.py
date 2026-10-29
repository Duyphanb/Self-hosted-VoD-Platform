#!/usr/bin/env python3
"""Storage contract smoke on a disposable vod-auth-audit-* Compose project.

Caller owns project creation and MUST trap `docker compose ... down --volumes`,
including failures. Never use this against a deployment or existing media volume.
Command output is captured: credentials/configuration are never printed.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

SOURCE = "c3ad836aed0d8a7d23c2e44a5eaa3009d08525e3"
TAG = "RELEASE.2026-10-26T00-00-00Z"
DIGEST = "sha256:7bb2aff6b89efe1cb1303c10fc21d579447434ed06649ac9ec05a5e2dbf30ad7"
IMAGE = f"docker.io/pgsty/silo:{TAG}@{DIGEST}"
SBOMS = (
    "sha256:9ff612f43439c768346a4fa403bc757dfb4a5b55181ac732fa630283de13b779",
    "sha256:f0bde32599e2103953b33a24391b5faf2c3741d853e9b02bd0a0cc9b9ba2c632",
)

SCRIPT_CHECKS = {
    "backend-raw-put", "worker-raw-get", "worker-hls-put", "backend-hls-get",
    "backend-raw-read-denied", "worker-raw-delete-denied", "backend-hls-delete-denied",
    "backend-hls-write-denied", "outside-attempt-write-denied", "thumbnail-write-denied",
    "scoped-user-list-denied", "scoped-service-account-denied", "scoped-password-change-denied",
    "scoped-raw-list-denied", "scoped-hls-list-denied", "scoped-bucket-creation-denied",
    "force-delete-nested-put", "force-delete-playlist-put", "force-delete-sibling-put",
    "force-delete-exact-attempt", "force-delete-nested-missing", "force-delete-playlist-missing",
    "force-delete-sibling-kept", "force-delete-broad-prefix-denied", "force-delete-sibling-still-kept",
    "force-delete-sibling-cleanup", "backend-raw-delete", "worker-hls-delete",
}


class SmokeCommandError(RuntimeError):
    """A safe diagnostic constructed here, never from captured command output."""


def run(args, *, timeout=240, check=True):
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", timeout=timeout)
    if check and result.returncode:
        raise SmokeCommandError("Storage smoke command failed (output withheld): " + args[0])
    return result


def require_script_success(result):
    if result.returncode:
        # Only relay a locally defined check name; captured output may contain credentials.
        for label in SCRIPT_CHECKS:
            if "SMOKE_CHECK:" + label + "\n" in result.stderr:
                raise SmokeCommandError("Storage check failed: " + label)
        raise SmokeCommandError("Storage smoke script failed (output withheld)")
    return result.stdout


def validate_config(services):
    server, init = services["minio"], services["minio-init"]
    assert server["image"] == init["image"] == IMAGE, "Storage image pin mismatch"
    assert re.search(r"@sha256:[0-9a-f]{64}$", server["image"]), "Storage image must be digest pinned"
    assert server["environment"]["MINIO_BROWSER"] == "off", "Console must be off"
    assert server["command"] == ["server", "/data"], "Unexpected storage command"
    assert not server.get("ports"), "Storage must not publish ports"
    assert server["healthcheck"]["test"] == ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"], "Storage healthcheck mismatch"
    for key, expected in (("RAW", "vod-raw"), ("HLS", "vod-hls"), ("THUMBNAILS", "vod-thumbnails")):
        assert init["environment"]["MINIO_BUCKET_" + key] == expected, "Bucket contract changed"


    for service, role in (("backend", "BACKEND"), ("worker", "WORKER")):
        app = services[service]["environment"]
        assert app["MINIO_ACCESS_KEY"] == init["environment"]["MINIO_" + role + "_ACCESS_KEY"], "Scoped access key wiring mismatch"
        assert app["MINIO_SECRET_KEY"] == init["environment"]["MINIO_" + role + "_SECRET_KEY"], "Scoped secret key wiring mismatch"
        assert app["MINIO_ACCESS_KEY"] != server["environment"]["MINIO_ROOT_USER"], "App must not use root identity"
        assert app["MINIO_SECRET_KEY"] != server["environment"]["MINIO_ROOT_PASSWORD"], "App must not use root secret"


def verify_artifacts():
    signer = f"https://github.com/pgsty/silo/.github/workflows/docker-release.yml@refs/tags/{TAG}"
    common = ["--repo", "pgsty/silo", "--cert-identity", signer,
              "--source-digest", SOURCE, "--deny-self-hosted-runners"]
    run(["gh", "attestation", "verify", "oci://index.docker.io/pgsty/silo@" + DIGEST, *common])
    for digest in SBOMS:
        run(["gh", "attestation", "verify", "oci://index.docker.io/pgsty/silo@" + digest,
             *common, "--predicate-type", "https://spdx.dev/Document/v2.3"])
    print("PASS signed provenance and both architecture SBOMs", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--verify-only", action="store_true", help="Verify config/provenance/SBOMs before any image use")
    args = parser.parse_args()
    assert args.project.startswith("vod-auth-audit-"), "Use a disposable audit project"
    os.chdir(Path(__file__).resolve().parents[2])
    compose = ["docker", "compose", "-p", args.project, "--env-file", os.devnull,
               "-f", "deploy/docker-compose.yml"]
    config = json.loads(run(compose + ["config", "--format", "json"]).stdout)
    validate_config(config["services"])
    verify_artifacts()
    if args.verify_only:
        return
    run(compose + ["pull", "--quiet", "minio", "minio-init"], timeout=360)
    digests = json.loads(run(["docker", "image", "inspect", "--format", "{{json .RepoDigests}}", IMAGE]).stdout)
    assert any(item.endswith("@" + DIGEST) for item in digests), "Pulled digest mismatch"
    run(compose + ["up", "--wait", "--wait-timeout", "120", "--no-build", "minio"])
    container = run(compose + ["ps", "-q", "minio"]).stdout.strip()
    assert run(["docker", "inspect", "--format", '{{index .Config.Labels "com.docker.compose.project"}}', container]).stdout.strip() == args.project, "Storage container project label mismatch"
    init_logs = []
    for _ in range(2):
        result = run(compose + ["run", "--rm", "--no-deps", "minio-init"])
        init_logs.append(result.stdout + result.stderr)
    listeners = run(compose + ["exec", "-T", "minio", "cat", "/proc/net/tcp", "/proc/net/tcp6"]).stdout
    ports = {int(parts[1].split(":")[1], 16) for line in listeners.splitlines()
             if len(parts := line.split()) > 3 and parts[3] == "0A"
             and parts[1].split(":")[0] != "0B00007F"}  # Docker DNS at 127.0.0.11
    assert ports == {9000}, "Unexpected object-store listener"
    print("PASS digest pull, healthy, init twice, console off", flush=True)
    alias = 'printf \'%s\\n%s\\n\' "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" | mc alias set local http://minio:9000 >/dev/null 2>&1; '
    def client(script):
        return require_script_success(run(compose + ["run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", "minio-init", "-ec", script], check=False))
    client(alias + '''printf scope-test >/tmp/scope
expect() { if [ "$1" != "$2" ]; then printf 'SMOKE_CHECK:%s\\n' "$3" >&2; exit 1; fi; }
backend() { curl -sS --aws-sigv4 aws:amz:us-east-1:s3 --user "$MINIO_BACKEND_ACCESS_KEY:$MINIO_BACKEND_SECRET_KEY" -o /dev/null -w '%{http_code}' "$@"; }
worker() { curl -sS --aws-sigv4 aws:amz:us-east-1:s3 --user "$MINIO_WORKER_ACCESS_KEY:$MINIO_WORKER_SECRET_KEY" -o /dev/null -w '%{http_code}' "$@"; }
raw="http://minio:9000/$MINIO_BUCKET_RAW/ci-smoke/scoped.mp4"
hls="http://minio:9000/$MINIO_BUCKET_HLS/video-assets/ci-smoke/attempt-1/master.m3u8"
expect "$(backend -X PUT --data-binary @/tmp/scope "$raw")" 200 backend-raw-put
expect "$(worker "$raw")" 200 worker-raw-get
expect "$(worker -X PUT --data-binary @/tmp/scope "$hls")" 200 worker-hls-put
expect "$(backend "$hls")" 200 backend-hls-get
expect "$(backend "$raw")" 403 backend-raw-read-denied
expect "$(worker -X DELETE "$raw")" 403 worker-raw-delete-denied
expect "$(backend -X DELETE "$hls")" 403 backend-hls-delete-denied
expect "$(backend -X PUT --data-binary @/tmp/scope "$hls")" 403 backend-hls-write-denied
expect "$(worker -X PUT --data-binary @/tmp/scope "http://minio:9000/$MINIO_BUCKET_HLS/outside-attempt/file.ts")" 403 outside-attempt-write-denied
expect "$(backend -X PUT --data-binary @/tmp/scope "http://minio:9000/$MINIO_BUCKET_THUMBNAILS/forbidden")" 403 thumbnail-write-denied
printf '%s\n%s\n' "$MINIO_BACKEND_ACCESS_KEY" "$MINIO_BACKEND_SECRET_KEY" | mc alias set backend http://minio:9000 >/dev/null 2>&1
printf '%s\n%s\n' "$MINIO_WORKER_ACCESS_KEY" "$MINIO_WORKER_SECRET_KEY" | mc alias set worker http://minio:9000 >/dev/null 2>&1
# JSON is captured in the disposable container and never logged. Verify an IAM
# denial rather than accidentally accepting a connection/argument failure.
denied() {
 label="$1"; shift
 if "$@" >/tmp/denied.json 2>/dev/null; then expect 0 1 "$label"; fi
 # The bundled Silo image intentionally has no grep; inspect JSON with shell builtins.
 case "$(cat /tmp/denied.json)" in
  *'"Code"'*'"AccessDenied"'*|*'"code"'*'"AccessDenied"'*|*'"Code"'*'"XMinioAdminAccessDenied"'*|*'"code"'*'"XMinioAdminAccessDenied"'*) ;;
  *) expect 0 1 "$label";;
 esac
}
denied scoped-user-list-denied mc --json admin user list backend
for role in backend worker; do
 case "$role" in backend) own_key="$MINIO_BACKEND_ACCESS_KEY"; new_secret="$MINIO_BACKEND_SECRET_KEY";;
   worker) own_key="$MINIO_WORKER_ACCESS_KEY"; new_secret="$MINIO_WORKER_SECRET_KEY";; esac
 denied scoped-service-account-denied mc --json admin user svcacct add "$role" "$own_key"
 printf '%s\n' "$new_secret" | denied scoped-password-change-denied mc --json admin user add "$role" "$own_key"
done
for role in backend worker; do
 expect "$("$role" "http://minio:9000/$MINIO_BUCKET_RAW?list-type=2")" 403 scoped-raw-list-denied
 expect "$("$role" "http://minio:9000/$MINIO_BUCKET_HLS?list-type=2")" 403 scoped-hls-list-denied
 expect "$("$role" -X PUT "http://minio:9000/ci-forbidden-bucket")" 403 scoped-bucket-creation-denied
done
# Silo exact-prefix delete is authorized on the prefix object, without listing.
attempt="http://minio:9000/$MINIO_BUCKET_HLS/video-assets/ci-force-delete/attempt-1/"
sibling="http://minio:9000/$MINIO_BUCKET_HLS/video-assets/ci-force-delete/attempt-10/master.m3u8"
expect "$(worker -X PUT --data-binary @/tmp/scope "${attempt}segments/one.ts")" 200 force-delete-nested-put
expect "$(worker -X PUT --data-binary @/tmp/scope "${attempt}master.m3u8")" 200 force-delete-playlist-put
expect "$(worker -X PUT --data-binary @/tmp/scope "$sibling")" 200 force-delete-sibling-put
expect "$(worker -X DELETE -H 'x-minio-force-delete: true' "$attempt")" 204 force-delete-exact-attempt
expect "$(backend "${attempt}segments/one.ts")" 404 force-delete-nested-missing
expect "$(backend "${attempt}master.m3u8")" 404 force-delete-playlist-missing
expect "$(backend "$sibling")" 200 force-delete-sibling-kept
expect "$(worker -X DELETE -H 'x-minio-force-delete: true' "http://minio:9000/$MINIO_BUCKET_HLS/video-assets/")" 403 force-delete-broad-prefix-denied
expect "$(backend "$sibling")" 200 force-delete-sibling-still-kept
expect "$(worker -X DELETE "$sibling")" 204 force-delete-sibling-cleanup
expect "$(backend -X DELETE "$raw")" 204 backend-raw-delete
expect "$(worker -X DELETE "$hls")" 204 worker-hls-delete
''')
    print("PASS scoped operations, IAM/self-credential denials and exact attempt-prefix cleanup", flush=True)
    checksum = client(alias + '''head -c 1048576 /dev/urandom >/tmp/o
want=$(sha256sum /tmp/o); want=${want%% *}
for b in "$MINIO_BUCKET_RAW" "$MINIO_BUCKET_HLS" "$MINIO_BUCKET_THUMBNAILS"; do
 mc stat "local/$b" >/dev/null
 policy=$(mc anonymous get --json "local/$b")
 case "$policy" in *'"permission":"private"'*) ;; *) exit 1;; esac
 mc cp -q /tmp/o "local/$b/ci-smoke/o" >/dev/null
 got=$(mc cat "local/$b/ci-smoke/o" | sha256sum); [ "${got%% *}" = "$want" ]
 [ "$(curl -s -o /dev/null -w '%{http_code}' "http://minio:9000/$b/ci-smoke/o")" = 403 ]
 [ "$(curl -s -o /dev/null -w '%{http_code}' "http://minio:9000/$b?list-type=2")" = 403 ]
 [ "$(curl -s -X PUT --data-binary @/tmp/o -o /dev/null -w '%{http_code}' "http://minio:9000/$b/ci-smoke/anon")" = 403 ]
done
printf '%s' "$want"''').strip()
    assert re.fullmatch(r"[0-9a-f]{64}", checksum), "Invalid checksum output"
    def current_server_logs():
        current = run(compose + ["ps", "-q", "minio"]).stdout.strip()
        result = run(["docker", "logs", current])
        return result.stdout + result.stderr
    server_history = [current_server_logs()]
    for command in (["restart", "minio"], ["up", "-d", "--force-recreate", "--no-deps", "minio"]):
        run(compose + command)
        run(compose + ["up", "--wait", "--wait-timeout", "120", "--no-build", "minio"])
        client(alias + 'for b in "$MINIO_BUCKET_RAW" "$MINIO_BUCKET_HLS" "$MINIO_BUCKET_THUMBNAILS"; do '
               'got=$(mc cat "local/$b/ci-smoke/o" | sha256sum); [ "${got%% *}" = "' + checksum + '" ]; done')
        server_history.append(current_server_logs())
    print("PASS three private buckets, SHA256 put/get, anonymous denial, restart/recreate persistence", flush=True)
    # Exercise the actual bounded init entrypoint, not just a separate mc invocation.
    bad = run(compose + ["run", "--rm", "--no-deps", "-e", "MINIO_SECRET_KEY=deliberately-wrong-test-only",
                         "minio-init"], timeout=150, check=False)
    assert bad.returncode != 0 and "Storage initialization failed" in bad.stderr, "Bad credentials did not fail bounded init"
    combined_logs = "".join(init_logs + server_history) + bad.stdout + bad.stderr + current_server_logs()
    secrets = [config["services"]["minio"]["environment"]["MINIO_ROOT_PASSWORD"],
               config["services"]["minio-init"]["environment"]["MINIO_BACKEND_SECRET_KEY"],
               config["services"]["minio-init"]["environment"]["MINIO_WORKER_SECRET_KEY"]]
    assert all(secret not in combined_logs for secret in secrets), "Storage credential leaked into logs"
    client(alias + 'for b in "$MINIO_BUCKET_RAW" "$MINIO_BUCKET_HLS" "$MINIO_BUCKET_THUMBNAILS"; do mc rm -q "local/$b/ci-smoke/o" >/dev/null; done')
    print("PASS bounded wrong-credential init and secret-free storage logs", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, SmokeCommandError) as error:
        # All assertion messages are fixed strings; our command error contains only the tool name.
        raise SystemExit("FAIL storage smoke: " + (str(error) or type(error).__name__))
    except Exception as error:
        # Avoid tracebacks that can include command/configuration payloads.
        raise SystemExit("FAIL storage smoke: " + type(error).__name__)
