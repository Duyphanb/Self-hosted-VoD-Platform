#!/usr/bin/env python3
"""Prepares the fixtures that the Sprint 4 upload Newman collection uses.

Writes the runtime-generated upload files into --fixtures-dir (never committed):
- valid.mp4: a small body with an ISO-BMFF signature (ftyp at offset 4);
- note.txt: plain text, declared as text/plain by the collection;
- oversize.mp4: a valid signature padded to 1.5 MiB, which exceeds MAX_UPLOAD_SIZE_MB=1.

Seeds two DRAFT movies through psql in the disposable Compose project's postgres container:
- an empty movie for the accepted upload;
- a movie that already has an in-flight QUEUED asset/job pair, so the upload gets 409. Its job is
  marked published, so the relay never sends it, and no broker message exists for a worker.

Prints the two movie ids as KEY=VALUE lines for the caller. Only a disposable Compose project on a
loopback address is accepted; nothing secret is printed.
"""
import argparse
import os
from pathlib import Path
import subprocess
import urllib.parse
import uuid

LOCAL_STACK_PROJECT = "vod-platform"
SIGNATURE = b"\x00\x00\x00\x18ftypisom"


def write_fixtures(directory: Path):
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "valid.mp4").write_bytes(SIGNATURE + bytes(4096))
    (directory / "note.txt").write_bytes(b"not a video\n")
    (directory / "oversize.mp4").write_bytes(SIGNATURE + bytes(1536 * 1024))


def seed_sql(upload_movie: uuid.UUID, in_flight_movie: uuid.UUID) -> str:
    asset = uuid.uuid4()
    job = uuid.uuid4()
    rows = []
    for movie, label in ((upload_movie, "upload"), (in_flight_movie, "in-flight")):
        rows.append(
            "INSERT INTO movies (id, title, slug, description, status, created_at, updated_at) VALUES "
            f"('{movie}', 'Newman {label} fixture {movie.hex[:8]}', 'newman-{label}-{movie}', "
            "'Newman Sprint 4 upload fixture.', 'DRAFT', now(), now());"
        )
    rows.append(
        "INSERT INTO video_assets (id, movie_id, raw_bucket, raw_object_key, status, created_at, updated_at) "
        f"VALUES ('{asset}', '{in_flight_movie}', 'vod-raw', '{in_flight_movie}/{asset}.mp4', 'QUEUED', now(), now());"
    )
    rows.append(
        "INSERT INTO encoding_jobs (id, video_asset_id, status, attempt, queued_at, published_at) "
        f"VALUES ('{job}', '{asset}', 'QUEUED', 1, now(), now());"
    )
    return "BEGIN;\n" + "\n".join(rows) + "\nCOMMIT;\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--fixtures-dir", required=True)
    args = parser.parse_args()
    assert args.project != LOCAL_STACK_PROJECT, "Use a dedicated, disposable Compose project"
    assert urllib.parse.urlparse(args.base_url).hostname in ("localhost", "127.0.0.1")

    container = args.project + "-postgres-1"
    label = subprocess.check_output([
        "docker", "inspect", "--format",
        '{{index .Config.Labels "com.docker.compose.project"}}', container
    ], text=True).strip()
    assert label == args.project

    write_fixtures(Path(args.fixtures_dir))
    upload_movie = uuid.uuid4()
    in_flight_movie = uuid.uuid4()
    result = subprocess.run(
        ["docker", "exec", "-i", container, "sh", "-c",
         'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        input=seed_sql(upload_movie, in_flight_movie), text=True, encoding="utf-8", capture_output=True)
    assert result.returncode == 0, "Seeding SQL failed (details withheld)"
    print(f"uploadMovieId={upload_movie}")
    print(f"inFlightMovieId={in_flight_movie}")


if __name__ == "__main__":
    os.umask(0o022)
    main()
