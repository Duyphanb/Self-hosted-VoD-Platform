#!/usr/bin/env python3
"""Issue 5.8: the hardened worker in a running, disposable Compose stack.

Run it after `docker compose up --wait` on a dedicated project whose edge listens on loopback. It signs in
as an existing administrator whose address and password come from NEWMAN_ADMIN_EMAIL and
NEWMAN_ADMIN_PASSWORD; neither is printed.

1. Runtime configuration: the worker container runs as a non-root user, with a read-only root filesystem,
   every capability dropped, no-new-privileges, a positive pids limit, a 30 s stop timeout and no tmpfs.
   Its only writable path is a named, disk-backed volume on /tmp that holds WORKER_TEMP_DIR and the JVM's
   java.io.tmpdir. Writes to the image paths fail, and writes to both temp paths succeed.
2. End to end: a 4 s H.264/AAC clip, generated with the worker image's own ffmpeg, is uploaded through
   Nginx. The worker claims, probes, encodes and uploads it, and the asset/job pair becomes READY with the
   attempt's master key. The master playlist and at least one segment exist under the attempt prefix, the
   movie reports videoStatus READY, and no job directory remains in WORKER_TEMP_DIR.
3. Thread budget: the container's peak task count (cgroup pids.peak, which counts threads) during the job
   stays below its pids limit, and both numbers are printed.

Output names the checks and safe identifiers only, never tokens, passwords or response bodies.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

LOCAL_STACK_PROJECT = "vod-platform"
CLIP_SECONDS = 4
NO_NEW_PRIVILEGES = {"no-new-privileges", "no-new-privileges:true"}


class SmokeError(AssertionError):
    pass


def check(condition, message):
    if not condition:
        raise SmokeError(message)


def hardening_failures(inspected, image_user):
    """Compose hardening problems in one `docker inspect` object; an empty list means it is hardened."""
    config, host = inspected.get("Config") or {}, inspected.get("HostConfig") or {}
    failures = []
    user = (config.get("User") or image_user or "").split(":", 1)[0]
    if user in ("", "0", "root"):
        failures.append("the container user is root")
    if host.get("ReadonlyRootfs") is not True:
        failures.append("the root filesystem is writable")
    if [cap.upper() for cap in host.get("CapDrop") or []] != ["ALL"]:
        failures.append("not every capability is dropped")
    if host.get("CapAdd"):
        failures.append("capabilities are added back")
    if not NO_NEW_PRIVILEGES & set(host.get("SecurityOpt") or []):
        failures.append("no-new-privileges is not set")
    if host.get("Privileged"):
        failures.append("the container is privileged")
    if not isinstance(host.get("PidsLimit"), int) or host["PidsLimit"] <= 0:
        failures.append("no positive pids limit")
    if config.get("StopTimeout") != 30:
        failures.append("the stop timeout is not 30 s")
    if host.get("Tmpfs") or any(mount.get("Type") == "tmpfs" for mount in inspected.get("Mounts") or []):
        failures.append("a tmpfs mount exists")
    temp = [mount for mount in inspected.get("Mounts") or [] if mount.get("Destination") == "/tmp"]
    if len(temp) != 1 or temp[0].get("Type") != "volume" or temp[0].get("RW") is not True:
        failures.append("/tmp is not one writable named volume")
    writable = {mount.get("Destination") for mount in inspected.get("Mounts") or [] if mount.get("RW")}
    if writable - {"/tmp"}:
        failures.append("writable mounts other than /tmp: " + ", ".join(sorted(writable - {"/tmp"})))
    environment = dict(item.split("=", 1) for item in config.get("Env") or [] if "=" in item)
    temp_dir = environment.get("WORKER_TEMP_DIR", "")
    if not temp_dir.startswith("/tmp/") or temp_dir.rstrip("/") == "/tmp" or ".." in temp_dir.split("/"):
        failures.append("WORKER_TEMP_DIR is not below the /tmp volume")
    return failures


def multipart(field, filename, content_type, content):
    boundary = "vod-worker-smoke-" + uuid.uuid4().hex
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{filename}\"\r\n"
            f"Content-Type: {content_type}\r\n\r\n").encode("ascii")
    return "multipart/form-data; boundary=" + boundary, head + content + f"\r\n--{boundary}--\r\n".encode("ascii")


def parse_job_row(row):
    """`asset|job|attempt|master key|failure` from the polling query, or None before the pair exists."""
    if not row:
        return None
    parts = row.split("|")
    check(len(parts) == 5, "Unexpected job row shape")
    return {"asset": parts[0], "job": parts[1], "attempt": int(parts[2]), "master": parts[3], "failure": parts[4]}


class Stack:
    def __init__(self, project, base_url):
        self.project, self.base_url = project, base_url.rstrip("/")
        self.worker = self.container("worker")
        self.postgres = self.container("postgres")
        self.minio = self.container("minio")

    @staticmethod
    def run(arguments, label, input_text=None, timeout=120):
        result = subprocess.run(arguments, input=input_text, text=True, encoding="utf-8", capture_output=True,
                                timeout=timeout)
        check(result.returncode == 0, label + " failed (details withheld)")
        return result.stdout

    def container(self, service):
        name = f"{self.project}-{service}-1"
        label = self.run(["docker", "inspect", "--format", '{{index .Config.Labels "com.docker.compose.project"}}',
                          name], "inspect " + service).strip()
        check(label == self.project, service + " container is not in the disposable project")
        return name

    def exec(self, container, script, label, *arguments):
        return self.run(["docker", "exec", "-i", container, "sh", "-c", script, "sh", *arguments], label)

    def sql(self, statement):
        return self.run(["docker", "exec", "-i", self.postgres, "sh", "-c",
                         'psql -X -q -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
                        "SQL", input_text=statement).strip()

    def request(self, path, token=None, body=None, content_type=None, timeout=60):
        headers = {"Authorization": "Bearer " + token} if token else {}
        if content_type:
            headers["Content-Type"] = content_type
        req = urllib.request.Request(self.base_url + path, data=body, headers=headers,
                                     method="POST" if body is not None else "GET")
        try:
            response = urllib.request.urlopen(req, timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            content = response.read()
            return response.status, json.loads(content) if content else None


def verify_runtime(stack):
    inspected = json.loads(stack.run(["docker", "inspect", stack.worker], "inspect worker"))[0]
    image_user = json.loads(stack.run(["docker", "image", "inspect", inspected["Image"]], "inspect image"))[0][
        "Config"].get("User")
    failures = hardening_failures(inspected, image_user)
    check(not failures, "Worker hardening: " + "; ".join(failures))
    uid = stack.exec(stack.worker, "id -u", "worker id").strip()
    check(uid not in ("", "0"), "The worker process runs as root")
    probe = (
        'for path in /opt/worker /usr/local /; do if (: > "$path/.vod-smoke-probe") 2>/dev/null; then '
        'echo "$path"; rm -f "$path/.vod-smoke-probe"; fi; done')
    check(stack.exec(stack.worker, probe, "read-only probe").strip() == "", "An image path accepted a write")
    # The running JVM proves java.io.tmpdir: embedded Tomcat creates its base directory there at startup.
    # (A second JVM started with docker exec would also raise the container's pids.peak measured below.)
    tomcat = stack.exec(stack.worker, 'find /tmp -mindepth 1 -maxdepth 1 -type d -name "tomcat.*" -user "$(id -u)"',
                        "java tmpdir").split()
    check(tomcat, "The worker JVM wrote no Tomcat base directory to the /tmp volume")
    options = stack.exec(stack.worker, 'printf "%s" "${JAVA_TOOL_OPTIONS:-}"', "JVM options")
    check("java.io.tmpdir" not in options, "JAVA_TOOL_OPTIONS moves java.io.tmpdir off the /tmp volume")
    writable = (
        'set -e; [ "$(stat -f -c %T /tmp)" != tmpfs ]; '
        'p="/tmp/.vod-smoke-$$"; : > "$p"; rm "$p"; '
        '[ -d "$WORKER_TEMP_DIR" ] && [ ! -L "$WORKER_TEMP_DIR" ] && [ "$(stat -c %u "$WORKER_TEMP_DIR")" = "$(id -u)" ]; '
        'q="$WORKER_TEMP_DIR/.vod-smoke-$$"; : > "$q"; rm "$q"')
    stack.exec(stack.worker, writable, "temp volume probe")
    print(f"PASS worker runs as uid {uid} with a read-only root, no capabilities, no-new-privileges, "
          f"pids limit {inspected['HostConfig']['PidsLimit']} and a disk-backed /tmp volume", flush=True)
    return inspected["Image"], inspected["HostConfig"]["PidsLimit"]


def generate_clip(image, directory):
    user = ["--user", f"{os.getuid()}:{os.getgid()}"] if hasattr(os, "getuid") else []
    Stack.run(["docker", "run", "--rm", "--network", "none", *user,
               "--mount", f"type=bind,source={directory},target=/out", "--entrypoint", "ffmpeg", image,
               "-nostdin", "-hide_banner", "-loglevel", "error",
               "-f", "lavfi", "-i", f"testsrc2=size=320x180:rate=25:duration={CLIP_SECONDS}",
               "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:duration={CLIP_SECONDS}",
               "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart",
               "-shortest", "/out/clip.mp4"], "generate clip")
    clip = Path(directory) / "clip.mp4"
    check(clip.is_file() and clip.stat().st_size > 0, "The generated clip is missing")
    return clip.read_bytes()


def pids_peak(stack):
    raw = stack.exec(stack.worker, "cat /sys/fs/cgroup/pids.peak 2>/dev/null || true", "pids peak").strip()
    return int(raw) if raw.isdigit() else None


def end_to_end(stack, image, pids_limit, timeout):
    email, password = os.environ.get("NEWMAN_ADMIN_EMAIL"), os.environ.get("NEWMAN_ADMIN_PASSWORD")
    check(bool(email and password), "NEWMAN_ADMIN_EMAIL and NEWMAN_ADMIN_PASSWORD are required")
    status, session = stack.request("/api/v1/auth/login", body=json.dumps({"email": email, "password": password})
                                    .encode(), content_type="application/json")
    check(status == 200 and session and session.get("accessToken"), "Administrator login failed")
    token = session["accessToken"]

    movie = uuid.uuid4()
    stack.sql("INSERT INTO movies (id, title, slug, description, status, created_at, updated_at) VALUES "
              f"('{movie}', 'Worker stack smoke {movie.hex[:8]}', 'worker-stack-smoke-{movie}', "
              "'Issue 5.8 worker stack smoke fixture.', 'DRAFT', now(), now());")
    with tempfile.TemporaryDirectory(prefix="vod-worker-smoke-") as directory:
        directory = os.path.realpath(directory)
        os.chmod(directory, 0o777)
        clip = generate_clip(image, directory)
    content_type, body = multipart("file", "clip.mp4", "video/mp4", clip)
    status, accepted = stack.request(f"/api/v1/admin/movies/{movie}/video", token, body, content_type, timeout=120)
    check(status == 202 and accepted and accepted.get("status") == "QUEUED", f"Upload was not accepted ({status})")
    asset = str(uuid.UUID(accepted["videoAssetId"]))
    print(f"PASS clip of {len(clip)} bytes accepted through Nginx as asset {asset}", flush=True)

    query = ("SELECT a.status || '|' || j.status || '|' || j.attempt || '|' || coalesce(a.hls_master_object_key, '')"
             " || '|' || coalesce(a.failure_reason, '') FROM video_assets a JOIN encoding_jobs j"
             f" ON j.video_asset_id = a.id WHERE a.id = '{asset}' ORDER BY j.attempt DESC LIMIT 1;")
    deadline, row = time.monotonic() + timeout, None
    while time.monotonic() < deadline:
        row = parse_job_row(stack.sql(query))
        if row and row["asset"] in ("READY", "FAILED"):
            break
        time.sleep(1)
    check(row is not None and row["asset"] in ("READY", "FAILED"), f"The job did not finish within {timeout} s")
    check(row["asset"] == "READY" and row["job"] == "READY",
          f"The job ended {row['asset']}/{row['job']} with code {row['failure'] or 'none'}")
    prefix = f"video-assets/{asset}/attempt-{row['attempt']}/"
    check(row["master"] == prefix + "master.m3u8", "The READY asset stores an unexpected master key")
    print(f"PASS worker encoded the clip to READY under {prefix}", flush=True)

    listing = stack.exec(stack.minio, 'printf \'%s\\n%s\\n\' "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" | '
                         'mc alias set smoke http://127.0.0.1:9000 >/dev/null 2>&1 && '
                         'mc ls --recursive "smoke/vod-hls/$1"', "list HLS objects", prefix)
    names = [line.split()[-1] for line in listing.splitlines() if line.strip()]
    check("master.m3u8" in names, "The master playlist is missing from vod-hls")
    segments = [name for name in names if name.startswith("segments/") and name.endswith(".ts")]
    check(segments, "No HLS segment exists in vod-hls")
    print(f"PASS vod-hls holds master.m3u8 and {len(segments)} segment(s) for the attempt", flush=True)

    status, detail = stack.request(f"/api/v1/movies/{movie}", token)
    check(status == 200 and detail and detail.get("videoStatus") == "READY", "The movie does not report READY")
    leftovers = stack.exec(stack.worker, 'find "$WORKER_TEMP_DIR" -mindepth 1 -maxdepth 1 -name "job-*"',
                           "job directories").strip()
    check(leftovers == "", "A job directory remains in WORKER_TEMP_DIR")
    print("PASS movie reports videoStatus READY and the worker removed its job directory", flush=True)

    peak = pids_peak(stack)
    if peak is None:
        print(f"INFO cgroup pids.peak is not available; pids limit {pids_limit} was not measured", flush=True)
    else:
        check(peak < pids_limit, f"Peak task count {peak} reached the pids limit {pids_limit}")
        print(f"PASS peak task count {peak} (threads included) stayed below the pids limit {pids_limit}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project", required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--timeout", type=int, default=300, help="seconds to wait for the job (default 300)")
    args = parser.parse_args()
    check(args.project != LOCAL_STACK_PROJECT, "Use a dedicated, disposable Compose project")
    check(urllib.parse.urlparse(args.base_url).hostname in ("localhost", "127.0.0.1"), "Use a loopback base URL")
    stack = Stack(args.project, args.base_url)
    image, pids_limit = verify_runtime(stack)
    end_to_end(stack, image, pids_limit, args.timeout)


if __name__ == "__main__":
    try:
        main()
    except (SmokeError, subprocess.TimeoutExpired) as error:
        print("FAIL " + str(error), file=sys.stderr)
        sys.exit(1)
