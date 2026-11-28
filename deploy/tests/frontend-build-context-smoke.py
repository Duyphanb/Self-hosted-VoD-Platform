#!/usr/bin/env python3
"""Check Docker's filtered context and the production frontend bundle with synthetic markers."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[2]


def copy_frontend(destination):
    tracked = subprocess.check_output(["git", "ls-files", "-z", "--", "frontend"], cwd=ROOT)
    for entry in filter(None, tracked.split(b"\0")):
        relative = Path(os.fsdecode(entry)).relative_to("frontend")
        if any(part.startswith(".env") and part != ".env.example" for part in relative.parts):
            continue
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / "frontend" / relative, target)


def export_build(context, destination, dockerfile):
    subprocess.run(
        ["docker", "build", "--file", dockerfile, "--output", f"type=local,dest={destination}", "."],
        cwd=context,
        input="FROM scratch\nCOPY . /\n" if dockerfile == "-" else None,
        text=True,
        check=True,
    )


def main():
    marker = f"frontend-context-probe-{uuid4().hex}"
    with tempfile.TemporaryDirectory(prefix="vod-frontend-context-") as temp:
        workspace = Path(temp)
        context = workspace / "frontend"
        copy_frontend(context)
        excluded = [".env", ".env.production.local", "src/.env.local", "coverage/fixture.txt"]
        for name in excluded:
            path = context / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"VITE_BUILD_CONTEXT_PROBE={marker}\n", encoding="utf-8")
        for name in (".env.example", "src/.env.example"):
            (context / name).write_text("VITE_BUILD_CONTEXT_PROBE=example\n", encoding="utf-8")

        with (context / "src/main.tsx").open("a", encoding="utf-8") as entry:
            entry.write("\nconsole.info(import.meta.env.VITE_BUILD_CONTEXT_PROBE);\n")

        exported_context = workspace / "filtered"
        export_build(context, exported_context, "-")
        for name in excluded:
            if (exported_context / name).exists():
                raise RuntimeError(f"Docker included {name} in its build context")
        for name in (".env.example", "src/.env.example", "src/main.tsx"):
            if not (exported_context / name).is_file():
                raise RuntimeError(f"Docker omitted expected context file {name}")
        print("Docker context excludes dotenv and coverage fixtures and retains dotenv examples", flush=True)

        image_root = workspace / "image"
        export_build(context, image_root, "Dockerfile")
        bundle_root = image_root / "usr/share/nginx/html"
        bundle_files = [path for path in bundle_root.rglob("*") if path.is_file()]
        if not any(path.suffix == ".js" for path in bundle_files):
            raise RuntimeError("Production image has no frontend JavaScript bundle")
        if any(marker.encode() in path.read_bytes() for path in bundle_files):
            raise RuntimeError("A dotenv fixture reached the production bundle")
        print("Production frontend bundle excludes the dotenv marker", flush=True)


if __name__ == "__main__":
    main()
