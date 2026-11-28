#!/usr/bin/env python3
"""Run Chrome against an isolated, real upload/worker/HLS stack."""
import argparse
import json
import os
from pathlib import Path
import re
import runpy
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid

from chrome_process_tree import run_browser_tree
from proxy_network import disposable_proxy_environment, start_with_proxy_retry

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "deploy/docker-compose.yml"
SERVICES = ("backend", "frontend", "worker")
PREFIX = "vod-auth-audit-chrome-"
READY_TIMEOUT_SECONDS = 360
PROFILE_CLEANUP_TIMEOUT_SECONDS = 10
WINDOWS_TRANSIENT_FILE_ERRORS = {5, 32, 33, 145}


class SmokeFailure(RuntimeError):
    """A failure with a fixed label safe for CI."""


def command(args, label, *, env=None, timeout=120, check=True, binary=False):
    try:
        result = subprocess.run(args, cwd=ROOT, env=env, timeout=timeout, capture_output=True)
    except (OSError, subprocess.TimeoutExpired):
        raise SmokeFailure(f"{label} could not finish") from None
    if check and result.returncode:
        raise SmokeFailure(f"{label} failed (exit {result.returncode})")
    if not binary:
        result.stdout = result.stdout.decode("utf-8", "replace")
        result.stderr = result.stderr.decode("utf-8", "replace")
    return result


def environment(port):
    compose_keys = set(re.findall(r"\$\{([A-Z0-9_]+)", COMPOSE.read_text(encoding="utf-8")))
    env = {key: value for key, value in os.environ.items()
           if key not in compose_keys and not key.startswith(("COMPOSE_", "VOD_CHROME_", "DEBUG", "PWDEBUG"))}
    env.update(disposable_proxy_environment())
    env.update({
        "NGINX_HTTP_PORT": f"127.0.0.1:{port}",
        "CORS_ALLOWED_ORIGINS": f"http://127.0.0.1:{port}",
        "AUTH_COOKIE_SECURE": "true",
        "JWT_ACCESS_TOKEN_TTL_MINUTES": "1",
        "MAX_UPLOAD_SIZE_MB": "32",
        "WORKER_FFPROBE_TIMEOUT_SECONDS": "15",
        "WORKER_FFMPEG_TIMEOUT_MIN_SECONDS": "120",
        "WORKER_FFMPEG_TIMEOUT_FACTOR": "8",
    })
    for key in ("JWT_SECRET", "POSTGRES_PASSWORD", "POSTGRES_OWNER_PASSWORD", "POSTGRES_BACKEND_PASSWORD",
                "POSTGRES_WORKER_PASSWORD", "REDIS_PASSWORD", "RABBITMQ_PASSWORD", "RABBITMQ_BACKEND_PASSWORD",
                "RABBITMQ_WORKER_PASSWORD"):
        env[key] = secrets.token_urlsafe(48)
    for prefix in ("MINIO", "MINIO_BACKEND", "MINIO_WORKER"):
        env[prefix + "_ACCESS_KEY"] = "audit_" + secrets.token_hex(12)
        env[prefix + "_SECRET_KEY"] = secrets.token_urlsafe(48)
    return env


def project_resources(project):
    label = "label=com.docker.compose.project=" + project
    return [resource for resource, flags in (("container", "-aq"), ("volume", "-q"), ("network", "-q"))
            if command(["docker", resource, "ls", flags, "--filter", label],
                       "inspect project resources").stdout.strip()]


def clip_containers(project):
    label = "label=vod.chrome-smoke.project=" + project
    return command(["docker", "container", "ls", "-aq", "--filter", label],
                   "inspect owned clip container").stdout.split()


def create_clip(project, image):
    args = ["docker", "run", "--rm", "--name", f"{project}-clip", "--label", f"vod.chrome-smoke.project={project}",
            "--network", "none", "--entrypoint", "ffmpeg", image,
            "-nostdin", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24:duration=120",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=120",
            "-c:v", "libx264", "-threads", "1", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
            "-movflags", "frag_keyframe+empty_moov", "-f", "mp4", "pipe:1"]
    return command(args, "generate 120-second H.264/AAC fixture", binary=True).stdout


def wait_for_ready(stack, asset):
    started = time.monotonic()
    deadline = started + READY_TIMEOUT_SECONDS
    while (remaining := deadline - time.monotonic()) > 0:
        try:
            status = stack.sql(f"SELECT status FROM video_assets WHERE id = '{asset}';", timeout=remaining)
        except subprocess.TimeoutExpired:
            break
        if status == "READY":
            print(f"READY wait seconds: {time.monotonic() - started:.2f}", flush=True)
            return
        if status == "FAILED":
            raise SmokeFailure("worker marked the uploaded asset FAILED")
        time.sleep(max(0, min(1, deadline - time.monotonic())))
    raise SmokeFailure(f"uploaded asset did not reach READY in {READY_TIMEOUT_SECONDS} seconds")


def seed(project, base_url, env, worker_image):
    worker = runpy.run_path(str(ROOT / "deploy/tests/worker-stack-smoke.py"))
    stack = worker["Stack"](project, base_url)
    clip = create_clip(project, worker_image)
    helper_env = {key: value for key, value in env.items() if key.lower() != "no_proxy"}
    helper_env["no_proxy"] = "*"
    command([sys.executable, str(ROOT / "deploy/tests/newman-catalog-admin.py"),
             "--project", project, "--base-url", base_url], "register and grant fixture administrator", env=helper_env)
    login = json.dumps({"email": env["NEWMAN_ADMIN_EMAIL"], "password": env["NEWMAN_ADMIN_PASSWORD"]}).encode()
    status, auth = stack.request("/api/v1/auth/login", body=login, content_type="application/json")
    if status != 200:
        raise SmokeFailure("fixture administrator login rejected")
    movie = {"title": "Chrome playback smoke", "slug": "chrome-playback-smoke",
             "description": "Generated browser smoke fixture.", "releaseYear": 2024,
             "maturityRating": "T13", "genres": ["Smoke"], "people": [], "status": "PUBLISHED"}
    status, created = stack.request("/api/v1/admin/titles", auth["accessToken"],
                                    json.dumps(movie).encode(), "application/json")
    if status != 201:
        raise SmokeFailure("published movie creation rejected")
    movie_id = uuid.UUID(created["id"])
    episode_id = uuid.UUID(created["episode"]["id"])
    content_type, body = worker["multipart"]("file", "chrome-fixture.mp4", "video/mp4", clip)
    status, accepted = stack.request(f"/api/v1/admin/episodes/{episode_id}/video", auth["accessToken"],
                                     body, content_type, timeout=120)
    if status != 202:
        raise SmokeFailure("fixture upload rejected")
    asset = uuid.UUID(accepted["videoAssetId"])
    wait_for_ready(stack, asset)
    return movie_id, episode_id, asset


def remove_browser_output(output):
    # Windows can briefly retain file handles after the job's last process terminates.
    deadline = time.monotonic() + PROFILE_CLEANUP_TIMEOUT_SECONDS
    while True:
        try:
            shutil.rmtree(output)
            return
        except OSError as error:
            if os.name != "nt" or getattr(error, "winerror", None) not in WINDOWS_TRANSIENT_FILE_ERRORS:
                raise
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise
            time.sleep(min(0.2, remaining))


def run_browser(project, base_url, env):
    stage = "prepare fixture"
    try:
        movie, episode, asset = seed(project, base_url, env, f"{project}-worker")
        browser_env = os.environ.copy()
        for key in ("DEBUG", "PWDEBUG", "NODE_DEBUG"):
            browser_env.pop(key, None)
        browser_env.update({
            "VOD_CHROME_BASE_URL": base_url, "VOD_CHROME_MOVIE_ID": str(movie), "VOD_CHROME_EPISODE_ID": str(episode),
            "VOD_CHROME_ASSET_ID": str(asset),
            "VOD_CHROME_EMAIL": env["NEWMAN_ADMIN_EMAIL"], "VOD_CHROME_PASSWORD": env["NEWMAN_ADMIN_PASSWORD"],
        })
        stage = "Chrome playback scenario"
        node = shutil.which("node")
        if node is None:
            raise SmokeFailure("Node.js is required for the Chrome playback scenario")
        output = tempfile.mkdtemp(prefix=project + "-")
        try:
            browser_env.update({
                "TMP": output, "TEMP": output, "TMPDIR": output,
                "VOD_CHROME_OUTPUT": str(Path(output) / "results"),
            })
            cli = ROOT / "frontend/node_modules/@playwright/test/cli.js"
            result = run_browser_tree([node, str(cli), "test", "--config", "playwright.chrome.config.ts"],
                                      cwd=ROOT / "frontend", env=browser_env, timeout=360,
                                      after_stop=lambda: remove_browser_output(output))
        finally:
            if not Path(output).exists():
                print(f"Browser temp cleanup verified for {project}", flush=True)
        for line in result.stdout.splitlines():
            if line.startswith("CHROME_SMOKE "):
                print(line, flush=True)
        if result.returncode:
            raise SmokeFailure("Chrome scenario failed (see allowlisted checkpoints)")
    except SmokeFailure:
        raise
    except Exception:
        raise SmokeFailure(f"{stage} failed (details withheld)") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default=PREFIX + "local", help="Label prefix, extended with a per-run nonce")
    args = parser.parse_args()
    if not re.fullmatch(PREFIX + r"[a-z0-9][a-z0-9-]{0,35}", args.project):
        raise SmokeFailure("use a fresh vod-auth-audit-chrome-* project")
    project = args.project + "-" + secrets.token_hex(8)
    if project_resources(project) or clip_containers(project):
        raise SmokeFailure("project already owns resources")
    images = [f"{project}-{service}:latest" for service in SERVICES]
    for image in images:
        if command(["docker", "image", "inspect", image], "inspect image", check=False).returncode == 0:
            raise SmokeFailure("project image tag already exists")
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    env = environment(port)
    env["NEWMAN_ADMIN_EMAIL"] = f"{project}@example.test"
    env["NEWMAN_ADMIN_PASSWORD"] = "Browser-" + secrets.token_urlsafe(24)
    compose = ["docker", "compose", "--env-file", os.devnull, "-p", project, "-f", str(COMPOSE)]

    def down():
        return command(compose + ["down", "--volumes", "--remove-orphans"], "remove Compose project", env=env)

    def start():
        return command(compose + ["up", "-d", "--no-build", "--wait", "--wait-timeout", "180"],
                       "start Compose project", env=env, timeout=230, check=False)

    def remove_image(image):
        if command(["docker", "image", "inspect", image], "inspect image", check=False).returncode == 0:
            command(["docker", "image", "rm", image], "remove project image")

    def verify_cleanup():
        if project_resources(project) or clip_containers(project):
            raise SmokeFailure("disposable project cleanup left resources")
        print(f"Cleanup verified for {project}: zero Compose resources or clip containers", flush=True)

    def remove_clip():
        clips = clip_containers(project)
        if clips:
            command(["docker", "container", "rm", "-f", *clips], "remove owned clip container", check=False)
            if clip_containers(project):
                raise SmokeFailure("owned clip container still present")

    first_failure = None
    try:
        # This standalone driver uses the shared Stack transport only for its disposable loopback edge.
        urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))
        print(f"Building disposable project {project}", flush=True)
        command(compose + ["build", *SERVICES], "build Compose images", env=env, timeout=1200)
        result = start_with_proxy_retry(start, down, env)
        if result.returncode:
            raise SmokeFailure("Compose project did not become healthy")
        run_browser(project, f"http://127.0.0.1:{port}", env)
    except BaseException as failure:
        first_failure = failure
    finally:
        steps = [("clip container cleanup", remove_clip), ("Compose teardown", down)]
        steps.extend(("project image cleanup", lambda image=image: remove_image(image)) for image in images)
        steps.append(("project resource verification", verify_cleanup))
        for label, cleanup in steps:
            try:
                cleanup()
            except BaseException as failure:
                print(f"FAIL: {label}", file=sys.stderr, flush=True)
                if first_failure is None:
                    first_failure = failure
    if first_failure is not None:
        raise first_failure


def cancelled(_signum, _frame):
    raise SmokeFailure("Chrome smoke cancelled")


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, cancelled)
    signal.signal(signal.SIGINT, cancelled)
    if hasattr(signal, "SIGBREAK"):
        signal.signal(signal.SIGBREAK, cancelled)
    try:
        main()
    except SmokeFailure as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        sys.exit(1)
