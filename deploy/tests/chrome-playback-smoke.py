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
import uuid

from proxy_network import disposable_proxy_environment, start_with_proxy_retry

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "deploy/docker-compose.yml"
SERVICES = ("backend", "frontend", "worker")
PREFIX = "vod-auth-audit-chrome-"


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


def create_clip(image):
    args = ["docker", "run", "--rm", "--network", "none", "--entrypoint", "ffmpeg", image,
            "-nostdin", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24:duration=120",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=120",
            "-c:v", "libx264", "-threads", "1", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
            "-movflags", "frag_keyframe+empty_moov", "-f", "mp4", "pipe:1"]
    return command(args, "generate 120-second H.264/AAC fixture", binary=True).stdout


def wait_for_ready(stack, asset):
    started = time.monotonic()
    while time.monotonic() - started < 360:
        status = stack.sql(f"SELECT status FROM video_assets WHERE id = '{asset}';")
        if status == "READY":
            print(f"READY wait seconds: {time.monotonic() - started:.2f}", flush=True)
            return
        if status == "FAILED":
            raise SmokeFailure("worker marked the uploaded asset FAILED")
        time.sleep(1)
    raise SmokeFailure("uploaded asset did not reach READY in 360 seconds")


def seed(project, base_url, env, worker_image):
    worker = runpy.run_path(str(ROOT / "deploy/tests/worker-stack-smoke.py"))
    stack = worker["Stack"](project, base_url)
    clip = create_clip(worker_image)
    command([sys.executable, str(ROOT / "deploy/tests/newman-catalog-admin.py"),
             "--project", project, "--base-url", base_url], "register and grant fixture administrator", env=env)
    login = json.dumps({"email": env["NEWMAN_ADMIN_EMAIL"], "password": env["NEWMAN_ADMIN_PASSWORD"]}).encode()
    status, auth = stack.request("/api/v1/auth/login", body=login, content_type="application/json")
    if status != 200:
        raise SmokeFailure("fixture administrator login rejected")
    movie = {"title": "Chrome playback smoke", "slug": "chrome-playback-smoke",
             "description": "Generated browser smoke fixture.", "releaseYear": 2024,
             "maturityRating": "T13", "genres": ["Smoke"], "people": [], "status": "PUBLISHED"}
    status, created = stack.request("/api/v1/movies", auth["accessToken"],
                                    json.dumps(movie).encode(), "application/json")
    if status != 201:
        raise SmokeFailure("published movie creation rejected")
    movie_id = uuid.UUID(created["id"])
    content_type, body = worker["multipart"]("file", "chrome-fixture.mp4", "video/mp4", clip)
    status, accepted = stack.request(f"/api/v1/admin/movies/{movie_id}/video", auth["accessToken"],
                                     body, content_type, timeout=120)
    if status != 202:
        raise SmokeFailure("fixture upload rejected")
    asset = uuid.UUID(accepted["videoAssetId"])
    wait_for_ready(stack, asset)
    return movie_id, asset


def run_browser(project, base_url, env):
    stage = "prepare fixture"
    try:
        movie, asset = seed(project, base_url, env, f"{project}-worker")
        browser_env = os.environ.copy()
        for key in ("DEBUG", "PWDEBUG", "NODE_DEBUG"):
            browser_env.pop(key, None)
        browser_env.update({
            "VOD_CHROME_BASE_URL": base_url, "VOD_CHROME_MOVIE_ID": str(movie), "VOD_CHROME_ASSET_ID": str(asset),
            "VOD_CHROME_EMAIL": env["NEWMAN_ADMIN_EMAIL"], "VOD_CHROME_PASSWORD": env["NEWMAN_ADMIN_PASSWORD"],
        })
        stage = "Chrome playback scenario"
        npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
        with tempfile.TemporaryDirectory(prefix=project + "-") as output:
            browser_env.update({
                "TMP": output, "TEMP": output, "TMPDIR": output,
                "VOD_CHROME_OUTPUT": str(Path(output) / "results"),
            })
            result = command([npm, "--prefix", "frontend", "run", "test:e2e:chrome"],
                             stage, env=browser_env, timeout=360, check=False)
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
    parser.add_argument("--project", default=PREFIX + secrets.token_hex(6))
    args = parser.parse_args()
    project = args.project
    if not re.fullmatch(PREFIX + r"[a-z0-9][a-z0-9-]{0,35}", project):
        raise SmokeFailure("use a fresh vod-auth-audit-chrome-* project")
    if project_resources(project):
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

    try:
        print(f"Building disposable project {project}", flush=True)
        command(compose + ["build", *SERVICES], "build Compose images", env=env, timeout=1200)
        result = start_with_proxy_retry(start, down, env)
        if result.returncode and "rabbitmq" in result.stderr:
            print("Retrying broker startup on the disposable project", flush=True)
            result = start()
        if result.returncode:
            raise SmokeFailure("Compose project did not become healthy")
        run_browser(project, f"http://127.0.0.1:{port}", env)
    finally:
        down()
        for image in images:
            if command(["docker", "image", "inspect", image], "inspect image", check=False).returncode == 0:
                command(["docker", "image", "rm", image], "remove project image")
        if project_resources(project):
            raise SmokeFailure("disposable project cleanup left resources")
        print(f"Cleanup verified for {project}: zero containers, volumes or networks", flush=True)


def cancelled(_signum, _frame):
    raise SmokeFailure("Chrome smoke cancelled")


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, cancelled)
    try:
        main()
    except SmokeFailure as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        sys.exit(1)
