#!/usr/bin/env python3
"""Prepares the administrator that the Sprint 3 catalog Newman collection signs in as.

Registers NEWMAN_ADMIN_EMAIL with NEWMAN_ADMIN_PASSWORD through the API (a 409 for an existing
account is accepted), then grants ROLE_ADMIN with the first-administrator SQL documented in
docs/deployment/README.md, run exactly as written there. Only a disposable Compose project on a
loopback address is accepted. The password and tokens are never printed.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

LOCAL_STACK_PROJECT = "vod-platform"
DEPLOYMENT_README = Path(__file__).resolve().parents[2] / "docs" / "deployment" / "README.md"


def documented_first_admin_procedure():
    """The psql command and SQL of the README's First Administrator section, as written there."""
    text = DEPLOYMENT_README.read_text(encoding="utf-8")
    section = text.split("\n## First Administrator\n", 1)[1].split("\n## ", 1)[0]
    match = re.search(r"exec -T postgres \\\n\s*sh -c '([^']*)' sh \"\$ADMIN_EMAIL\" <<'SQL'\n(.*?\n)SQL\n", section, re.S)
    assert match, "First-administrator procedure not found in docs/deployment/README.md"
    return match.group(1), match.group(2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--base-url", required=True)
    args = parser.parse_args()
    assert args.project != LOCAL_STACK_PROJECT, "Use a dedicated, disposable Compose project"
    assert urllib.parse.urlparse(args.base_url).hostname in ("localhost", "127.0.0.1")
    email = os.environ["NEWMAN_ADMIN_EMAIL"]
    password = os.environ["NEWMAN_ADMIN_PASSWORD"]

    container = args.project + "-postgres-1"
    label = subprocess.check_output([
        "docker", "inspect", "--format",
        '{{index .Config.Labels "com.docker.compose.project"}}', container
    ], text=True).strip()
    assert label == args.project

    body = json.dumps({"email": email, "password": password, "displayName": "Newman Catalog Admin"}).encode()
    request = urllib.request.Request(args.base_url + "/api/v1/auth/register", data=body,
                                     headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status = response.status
    except urllib.error.HTTPError as error:
        status = error.code
    assert status in (201, 409), f"register returned {status}"

    command, statements = documented_first_admin_procedure()
    result = subprocess.run(["docker", "exec", "-i", container, "sh", "-c", command, "sh", email],
                            input=statements, text=True, encoding="utf-8", capture_output=True)
    assert result.returncode == 0, "SQL failed (details withheld)"
    print("Newman catalog admin is ready")


if __name__ == "__main__":
    main()
