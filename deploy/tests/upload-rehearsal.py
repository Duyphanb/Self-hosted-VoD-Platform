#!/usr/bin/env python3
"""Manual GitHub-hosted synthetic upload measurement; never target a deployment.

The fixture only has a recognized signature. It is not decodable video, and this
measures transport/storage rather than encoding or target-VPS capacity.
All subprocess output is captured because Compose/HTTP/SQL can contain credentials.
"""
import argparse
from datetime import datetime, timezone
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
FIXTURE_BYTES = 2_000_000_000
LABEL = "synthetic 2,000,000,000-byte transport/storage rehearsal — not VPS evidence"
PROJECT_PATTERN = re.compile(r"vod-auth-audit-rehearsal-[a-z0-9][a-z0-9_-]*\Z")
RESERVE_BYTES = 2 * 1024**3
SIGNATURE = b"\x00\x00\x00\x18ftypisom"


class RehearsalError(RuntimeError):
    """Only fixed, non-sensitive diagnostics may cross this boundary."""


def command(args, *, env=None, input_text=None, timeout=120, check=True):
    try:
        result = subprocess.run(args, cwd=ROOT, env=env, input=input_text,
                                capture_output=True, text=True, encoding="utf-8", timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise RehearsalError("A rehearsal command could not finish (details withheld)") from None
    if check and result.returncode:
        raise RehearsalError("A rehearsal command failed (details withheld)")
    return result


def validate_project(project):
    if not PROJECT_PATTERN.fullmatch(project):
        raise RehearsalError("Use a dedicated vod-auth-audit-rehearsal-* project")


def project_resources(project, resource):
    validate_project(project)
    flag = "-aq" if resource == "container" else "-q"
    return command(["docker", resource, "ls", flag, "--filter",
                    "label=com.docker.compose.project=" + project]).stdout.split()


def cleanup_project(project):
    """Fallback after cancellation: select exact Compose labels, never names/globs."""
    for resource, removal in (("container", ["-f", "-v"]), ("network", []), ("volume", [])):
        for resource_id in project_resources(project, resource):
            # Recheck ownership just before removal, including named volumes.
            label = command(["docker", resource, "inspect", "--format",
                             '{{index .Labels "com.docker.compose.project"}}' if resource != "container"
                             else '{{index .Config.Labels "com.docker.compose.project"}}',
                             resource_id]).stdout.strip()
            if label != project:
                raise RehearsalError("Cleanup ownership check failed")
            command(["docker", resource, "rm", *removal, resource_id])


def new_environment(port):
    # Do not inherit arbitrary developer deployment overrides, URLs or credentials.
    env = {key: value for key, value in os.environ.items()
           if key in ("PATH", "HOME", "USERPROFILE", "SYSTEMROOT", "TEMP", "TMP",
                      "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "GH_TOKEN", "GITHUB_TOKEN")}
    # #106: bootstrap identities plus independent application identities. PostgreSQL role
    # names must be lowercase identifiers, which audit_<hex> satisfies.
    for prefix in ("POSTGRES", "POSTGRES_OWNER", "POSTGRES_BACKEND", "POSTGRES_WORKER"):
        env[prefix + "_USER"] = "audit_" + secrets.token_hex(8)
        env[prefix + "_PASSWORD"] = secrets.token_urlsafe(32)
    for prefix in ("RABBITMQ", "RABBITMQ_BACKEND", "RABBITMQ_WORKER"):
        env[prefix + "_USERNAME"] = "audit_" + secrets.token_hex(8)
        env[prefix + "_PASSWORD"] = secrets.token_urlsafe(32)
    env["REDIS_PASSWORD"] = secrets.token_urlsafe(32)
    for prefix in ("MINIO", "MINIO_BACKEND", "MINIO_WORKER"):
        env[prefix + "_ACCESS_KEY"] = "audit_" + secrets.token_hex(12)
        env[prefix + "_SECRET_KEY"] = secrets.token_urlsafe(48)
    env.update({"JWT_SECRET": secrets.token_urlsafe(48), "NGINX_HTTP_PORT": f"0.0.0.0:{port}",
                "NGINX_HTTPS_PORT": "0.0.0.0:18444", "MAX_UPLOAD_SIZE_MB": "2048",
                "CORS_ALLOWED_ORIGINS": f"http://localhost:{port}"})
    return env


def compose_command(project):
    return ["docker", "compose", "-p", project, "--env-file", os.devnull,
            "-f", str(ROOT / "deploy/docker-compose.yml"),
            "-f", str(ROOT / "deploy/docker-compose.prod.yml")]


def allocated_bytes(path):
    stat = path.stat()
    return stat.st_blocks * 512 if hasattr(stat, "st_blocks") else None


def make_fixture(path, size=FIXTURE_BYTES):
    if size < len(SIGNATURE):
        raise ValueError("Fixture must fit the signature")
    with path.open("xb") as fixture:
        fixture.write(SIGNATURE)
        fixture.truncate(size)
    return {"logical_bytes": path.stat().st_size, "allocated_bytes": allocated_bytes(path),
            "sparse": True, "description": "ftyp signature followed by zeros; not decodable media"}


def memory_bytes(value):
    """Docker stats MemUsage is e.g. '123.4MiB / 768MiB'."""
    used = value.split("/", 1)[0].strip()
    match = re.fullmatch(r"([0-9.]+)\s*(B|kB|KB|MB|GB|TB|KiB|MiB|GiB|TiB)", used)
    if not match:
        raise RehearsalError("Unrecognized docker stats memory unit")
    units = {"B": 1, "kB": 1000, "KB": 1000, "MB": 1000**2, "GB": 1000**3, "TB": 1000**4,
             "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3, "TiB": 1024**4}
    return int(float(match.group(1)) * units[match.group(2)])


def parse_memory_stat(source):
    """Read cgroup-v2 byte counters, rejecting incomplete or malformed probes."""
    counters = {}
    for line in source.splitlines():
        match = re.fullmatch(r"\s*([A-Za-z0-9_]+)\s+([0-9]+)\s*", line)
        if match is None or match.group(1) in counters:
            raise RehearsalError("Malformed cgroup memory.stat sample")
        counters[match.group(1)] = int(match.group(2))
    if not {"anon", "file"}.issubset(counters):
        raise RehearsalError("Incomplete cgroup memory.stat sample")
    return {"anon_bytes": counters["anon"], "file_bytes": counters["file"]}


def container_memory_stat(container):
    # Exit 42 is emitted only for a genuinely absent file in a searchable cgroup
    # directory. Docker/exec failures, unreadable files and bad data stay fatal.
    probe = ('if [ -d /sys/fs/cgroup ] && [ -x /sys/fs/cgroup ] '
             '&& [ ! -e /sys/fs/cgroup/memory.stat ]; then exit 42; fi; '
             'cat /sys/fs/cgroup/memory.stat')
    result = command(["docker", "exec", container, "sh", "-c", probe], timeout=15, check=False)
    if result.returncode == 42:
        return {"status": "unavailable", "reason": "cgroup v1 or memory.stat is not exposed",
                "anon_bytes": None, "file_bytes": None}
    if result.returncode:
        raise RehearsalError("Cannot read container cgroup memory.stat")
    return {"status": "available", **parse_memory_stat(result.stdout)}


def disk_usage_bytes(path):
    try:
        result = shutil.disk_usage(path)
        return {"total_bytes": result.total, "used_bytes": result.used, "free_bytes": result.free}
    except PermissionError:
        # GitHub's runner user may not traverse /var/lib/docker. Probe the exact
        # DockerRootDir read-only; substituting a parent can measure another FS.
        result = command(["sudo", "-n", "df", "-B1", "--output=size,used,avail", "--", str(path)])
        lines = result.stdout.splitlines()
        if len(lines) != 2 or not re.fullmatch(r"\s*\d+\s+\d+\s+\d+\s*", lines[1]):
            raise RehearsalError("Cannot measure disk capacity at the exact Docker data root")
        total, used, free = map(int, lines[1].split())
        if total <= 0 or used + free > total:
            raise RehearsalError("Disk capacity probe returned inconsistent counters")
        return {"total_bytes": total, "used_bytes": used, "free_bytes": free}


def disk_snapshot(directory, docker_root):
    return {"runner_temp": disk_usage_bytes(directory), "docker_data_root": disk_usage_bytes(docker_root)}


def require_capacity(directory, docker_root, fixture_allocated=0, before_build=False):
    snapshot = disk_snapshot(directory, docker_root)
    # Raw object and Tomcat spool coexist; reserve includes other writes. A sparse
    # fixture avoids another 2 GB allocation but is explicitly reported as such.
    minimum = FIXTURE_BYTES * 2 + fixture_allocated + RESERVE_BYTES
    if before_build:
        minimum += 4 * 1024**3  # build/image allowance, followed by another check
    if min(item["free_bytes"] for item in snapshot.values()) < minimum:
        raise RehearsalError("Insufficient free disk for fixture/spool/raw plus safety reserve")
    return snapshot


class Sampler:
    def __init__(self, ids, backend, directory, docker_root, env):
        self.ids, self.backend, self.directory, self.docker_root, self.env = ids, backend, directory, docker_root, env
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.samples = []
        self.failures = 0

    def sample(self):
        stats = command(["docker", "stats", "--no-stream", "--format", "{{json .}}", *self.ids.values()], timeout=15)
        by_id = {container[:12]: service for service, container in self.ids.items()}
        memory = {}
        for line in stats.stdout.splitlines():
            row = json.loads(line)
            service = by_id.get(row["ID"][:12])
            if service:
                memory[service] = memory_bytes(row["MemUsage"])
        if set(memory) != set(self.ids):
            raise RehearsalError("Incomplete docker stats sample")
        memory_stat = {service: container_memory_stat(container)
                       for service, container in self.ids.items()}
        spool = command(["docker", "exec", self.backend, "sh", "-c",
                         'find "$TEMP_UPLOAD_DIR" -ignore_readdir_race -maxdepth 1 -type f '
                         '-name "upload_*.tmp" -printf "%s %b\\n"'], timeout=15)
        logical = allocated = 0
        for line in spool.stdout.splitlines():
            size, blocks = map(int, line.split())
            logical += size
            allocated += blocks * 512
        sample = {"at": datetime.now(timezone.utc).isoformat(), "container_memory_bytes": memory,
                  "container_memory_stat": memory_stat,
                  "spool_logical_bytes": logical, "spool_allocated_bytes": allocated,
                  "disk": disk_snapshot(self.directory, self.docker_root)}
        self.samples.append(sample)
        if min(part["free_bytes"] for part in sample["disk"].values()) < RESERVE_BYTES // 2:
            raise RehearsalError("Free disk fell below the emergency reserve")

    def run(self):
        while not self.stop.is_set():
            try:
                self.sample()
            except Exception:
                self.failures += 1
            self.stop.wait(0.25)

    def finish(self):
        self.stop.set()
        self.thread.join(timeout=35)
        if self.thread.is_alive():
            raise RehearsalError("Measurement sampler did not stop")

    def summary(self):
        if not self.samples or self.failures:
            raise RehearsalError("Measurement samples missing or incomplete")
        memory = {service: max(sample["container_memory_bytes"][service] for sample in self.samples)
                  for service in self.ids}
        memory_stat = {}
        for service in self.ids:
            observed = [sample["container_memory_stat"][service] for sample in self.samples]
            if len({sample["status"] for sample in observed}) != 1:
                raise RehearsalError("Cgroup memory.stat availability changed during measurement")
            if observed[0]["status"] == "unavailable":
                memory_stat[service] = observed[0]
            else:
                memory_stat[service] = {"status": "available",
                                        "anon_bytes": max(sample["anon_bytes"] for sample in observed),
                                        "file_bytes": max(sample["file_bytes"] for sample in observed)}
        return {"sample_count": len(self.samples), "sampling_failures": self.failures,
                "sampling_note": ("Observed peaks from sequential docker stats --no-stream, memory.stat and spool/disk polls; "
                                  "may miss shorter peaks. MemUsage includes active page cache, is bounded by the container "
                                  "memory limit, and is neither JVM heap nor process RSS."),
                "container_memory_observed_peak_bytes": memory,
                "container_memory_stat_observed_peaks": memory_stat,
                "spool_logical_observed_peak_bytes": max(row["spool_logical_bytes"] for row in self.samples),
                "spool_allocated_observed_peak_bytes": max(row["spool_allocated_bytes"] for row in self.samples),
                "minimum_disk_free_bytes": {part: min(row["disk"][part]["free_bytes"] for row in self.samples)
                                            for part in ("runner_temp", "docker_data_root")}}


def json_request(port, path, body=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=30)
    try:
        connection.request("POST" if body is not None else "GET", path,
                           body=None if body is None else json.dumps(body),
                           headers={} if body is None else {"Content-Type": "application/json"})
        response = connection.getresponse()
        raw = response.read(1024 * 1024)
        return response.status, json.loads(raw)
    finally:
        connection.close()


def prepare_admin_and_movie(port, postgres, env):
    email = "rehearsal-" + uuid.uuid4().hex + "@example.test"
    password = "Rehearsal-" + secrets.token_urlsafe(24)
    status, profile = json_request(port, "/api/v1/auth/register",
                                   {"email": email, "password": password, "displayName": "Rehearsal Admin"})
    if status != 201:
        raise RehearsalError("Rehearsal registration failed")
    user_id, movie_id = uuid.UUID(profile["id"]), uuid.uuid4()
    sql = ("BEGIN;\nINSERT INTO user_roles (user_id, role_id) SELECT '" + str(user_id)
           + "', id FROM roles WHERE name = 'ROLE_ADMIN' ON CONFLICT DO NOTHING;\n"
           "INSERT INTO movies (id, title, slug, description, status, created_at, updated_at) VALUES ('"
           + str(movie_id) + "', 'Synthetic rehearsal', 'synthetic-" + movie_id.hex
           + "', 'Transport/storage fixture only', 'DRAFT', now(), now());\nCOMMIT;\n")
    database_sql(postgres, sql, env)
    status, session = json_request(port, "/api/v1/auth/login", {"email": email, "password": password})
    if status != 200 or not isinstance(session.get("accessToken"), str):
        raise RehearsalError("Rehearsal administrator login failed")
    return movie_id, session["accessToken"]


def database_sql(postgres, sql, env):
    return command(["docker", "exec", "-i", postgres, "sh", "-c",
                    'psql -X -q -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
                   input_text=sql, env=env).stdout.strip()


def send_upload(port, movie_id, token, fixture, sampler=None):
    boundary = "rehearsal-" + secrets.token_hex(16)
    prefix = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.mp4\"\r\n"
              "Content-Type: video/mp4\r\n\r\n").encode()
    suffix = f"\r\n--{boundary}--\r\n".encode()
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=660)
    started = time.monotonic()
    try:
        connection.putrequest("POST", f"/api/v1/admin/movies/{movie_id}/video")
        connection.putheader("Authorization", "Bearer " + token)
        connection.putheader("Content-Type", "multipart/form-data; boundary=" + boundary)
        connection.putheader("Content-Length", str(len(prefix) + fixture.stat().st_size + len(suffix)))
        connection.endheaders()
        connection.send(prefix)
        with fixture.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                if sampler is not None and sampler.failures:
                    raise RehearsalError("Upload stopped because a measurement or disk-safety probe failed")
                connection.send(chunk)
        connection.send(suffix)
        # Client-side send() completion: bytes handed to the TCP stack. This is
        # not an acknowledgement that every byte reached Tomcat/MinIO.
        sent = time.monotonic()
        response = connection.getresponse()
        raw = response.read(1024 * 1024)
        completed = time.monotonic()
        timings = {"http_status": response.status, "send_seconds": sent - started,
                   "saving_wait_seconds": completed - sent, "total_client_seconds": completed - started,
                   "timing_note": "Saving wait starts after final multipart byte send() returns; includes response-body receipt"}
        if response.status != 202:
            return None, timings
        body = json.loads(raw)
        asset_id = uuid.UUID(body["videoAssetId"])
        if body.get("status") != "QUEUED":
            raise RehearsalError("Synthetic upload returned an unexpected status")
        return asset_id, timings
    finally:
        connection.close()


def write_report(output, report):
    output.mkdir(parents=True, exist_ok=True)
    (output / "measurement.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    lines = ["# " + LABEL, "", "Result: **" + report["result"] + "**", "",
             "No encoding, ARM/WAN throughput or target-VPS disk-headroom claim. Public deployment remains gated by #180.", "",
             "The worker is deliberately not started: this fixture is an ftyp signature plus zeros, not decodable media.", "",
             "```json", json.dumps(report, indent=2), "```", ""]
    summary = "\n".join(lines)
    (output / "summary.md").write_text(summary, encoding="utf-8")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as destination:
            destination.write(summary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--output-dir")
    parser.add_argument("--cleanup-only", action="store_true")
    args = parser.parse_args()
    validate_project(args.project)
    if args.cleanup_only:
        cleanup_project(args.project)
        print("PASS rehearsal project cleanup")
        return
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
        raise RehearsalError("The 2 GB rehearsal runs only on a GitHub-hosted workflow_dispatch runner")
    if os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch" or os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise RehearsalError("Dispatch the merged workflow on main")
    if not args.output_dir:
        raise RehearsalError("An artifact output directory is required")
    for resource in ("container", "network", "volume"):
        if project_resources(args.project, resource):
            raise RehearsalError("Rehearsal project already exists; choose a fresh project")
    output = Path(args.output_dir).resolve()
    report = {"label": LABEL, "result": "FAIL", "started_at": datetime.now(timezone.utc).isoformat(),
              "commit": os.environ.get("GITHUB_SHA"), "fixture_bytes": FIXTURE_BYTES,
              "overlay": "deploy/docker-compose.prod.yml", "worker_started": False}
    env = new_environment(18088)
    compose = compose_command(args.project)
    sampler = None
    failure = None
    temporary = tempfile.TemporaryDirectory(prefix=args.project + "-", dir=os.environ["RUNNER_TEMP"])
    directory = Path(temporary.name)
    try:
        report["phase"] = "pre-build disk capacity"
        docker_root = Path(command(["docker", "info", "--format", "{{.DockerRootDir}}"]).stdout.strip())
        report["docker_data_root"] = str(docker_root)
        report["disk_before_build"] = require_capacity(directory, docker_root, before_build=True)
        report["phase"] = "storage provenance verification"
        command([sys.executable, "deploy/tests/storage-runtime-smoke.py", "--project", args.project, "--verify-only"],
                env=env, timeout=300)
        # Docker builds run sequentially and before services, no competing JVM/stack.
        report["phase"] = "backend image build"
        command(compose + ["build", "backend"], env=env, timeout=1800)
        report["phase"] = "frontend image build"
        command(compose + ["build", "frontend"], env=env, timeout=900)
        report["phase"] = "runtime image pull"
        command(compose + ["pull", "--quiet", "--ignore-buildable"], env=env, timeout=600)
        report["phase"] = "fixture and post-build disk capacity"
        fixture = directory / "synthetic.mp4"
        report["fixture"] = make_fixture(fixture)
        report["disk_before_stack"] = require_capacity(directory, docker_root, report["fixture"]["allocated_bytes"] or 0)
        report["phase"] = "disposable production-overlay startup"
        command(compose + ["up", "-d", "--wait", "--wait-timeout", "240", "--no-build", "nginx"],
                env=env, timeout=300)
        ids = {}
        for service in ("postgres", "redis", "rabbitmq", "minio", "backend", "frontend", "nginx"):
            ids[service] = command(compose + ["ps", "-q", service], env=env).stdout.strip()
            if not re.fullmatch(r"[0-9a-f]{64}", ids[service]):
                raise RehearsalError("A rehearsal service is missing")
        if command(compose + ["ps", "-q", "worker"], env=env).stdout.strip():
            raise RehearsalError("The synthetic rehearsal must not start the worker")
        status, health = json_request(18088, "/actuator/health")
        if status != 200 or health.get("status") != "UP":
            raise RehearsalError("Rehearsal dependency readiness failed")
        report["phase"] = "administrator and movie fixtures"
        movie_id, token = prepare_admin_and_movie(18088, ids["postgres"], env)
        report["disk_before_upload"] = require_capacity(directory, docker_root)
        sampler = Sampler(ids, ids["backend"], directory, docker_root, env)
        sampler.sample()  # Fail immediately if a probe is unavailable.
        sampler.thread.start()
        report["phase"] = "synthetic upload and measurement"
        asset_id, report["client"] = send_upload(18088, movie_id, token, fixture, sampler)
        sampler.finish()
        report["measurement"] = sampler.summary()
        report["samples"] = sampler.samples
        if asset_id is None:
            raise RehearsalError("Synthetic upload did not return 202 (body withheld)")
        report["phase"] = "database and raw-object verification"
        pair = database_sql(ids["postgres"],
                            "SELECT count(*) FROM video_assets a JOIN encoding_jobs j ON j.video_asset_id = a.id "
                            f"WHERE a.id = '{asset_id}' AND a.movie_id = '{movie_id}' AND a.status = 'QUEUED' "
                            "AND j.status = 'QUEUED' AND j.attempt = 1;", env)
        if pair != "1":
            raise RehearsalError("Accepted upload did not persist the asset/job pair")
        # The disposable root identity verifies the exact object size, without
        # exposing application keys, credentials or object names to artifacts.
        size = command(["docker", "exec", ids["minio"], "sh", "-c",
                        'printf "%s\\n%s\\n" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" | '
                        'mc alias set audit http://127.0.0.1:9000 >/dev/null && '
                        'mc stat --json "audit/vod-raw/$1/$2.mp4"', "sh", str(movie_id), str(asset_id)], env=env).stdout
        if json.loads(size)["size"] != FIXTURE_BYTES:
            raise RehearsalError("Stored raw object has the wrong size")
        report["raw_object_bytes"] = FIXTURE_BYTES
        report["disk_after_upload"] = disk_snapshot(directory, docker_root)
        if report["measurement"]["spool_logical_observed_peak_bytes"] <= 0:
            raise RehearsalError("The spool probe did not observe an upload file")
        report["result"] = "PASS"
        report["phase"] = "complete"
    except Exception:
        # Do not persist exception/HTTP bodies, environment, tokens or SQL output.
        failure = "Rehearsal failed; details withheld to protect generated credentials"
        report["failure"] = failure
    finally:
        if sampler and sampler.thread.is_alive():
            try:
                sampler.finish()
            except Exception:
                failure = "Measurement sampler did not stop cleanly"
                report["result"] = "FAIL"
        if sampler:
            report["samples"] = sampler.samples
            report["sampling_failures"] = sampler.failures
        try:
            command(compose + ["down", "--volumes", "--remove-orphans", "--timeout", "20"],
                    env=env, timeout=120, check=False)
        except Exception:
            pass  # Exact-label fallback below works without Compose interpolation.
        try:
            cleanup_project(args.project)
            report["cleanup"] = "PASS"
        except Exception:
            report["cleanup"] = "FAIL; workflow fallback must remove exact project resources"
            report["result"] = "FAIL"
            failure = "Rehearsal cleanup failed"
        try:
            temporary.cleanup()
        except OSError:
            report["fixture_cleanup"] = "FAIL"
            report["result"] = "FAIL"
            failure = "Generated fixture cleanup failed"
        report["completed_at"] = datetime.now(timezone.utc).isoformat()
        write_report(output, report)
    if failure:
        raise RehearsalError(failure)
    print("PASS " + LABEL)


if __name__ == "__main__":
    def cancelled(_signum, _frame):
        raise RehearsalError("Rehearsal interrupted")
    signal.signal(signal.SIGTERM, cancelled)
    signal.signal(signal.SIGINT, cancelled)
    try:
        main()
    except RehearsalError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("Rehearsal failed (details withheld)", file=sys.stderr)
        sys.exit(1)
