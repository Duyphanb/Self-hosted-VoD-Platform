#!/usr/bin/env python3
"""Compare the Flyway-migrated PostgreSQL schema with the committed snapshot.

Without --container, the script starts a disposable PostgreSQL (the pinned image, ICU `vi` like ADR-009),
migrates it with naming validation, dumps it and removes it. With --container, it dumps an already migrated
database in that container (the CI service), so pg_dump always matches the server version.

A pull request that adds a migration regenerates the snapshot with --update and commits it.
"""
import argparse
import difflib
import os
from pathlib import Path
import subprocess
import sys
import time

POSTGRES_IMAGE = "postgres:16.15-alpine@sha256:0cefaabd882e42bcb2a2f3dc56d3ce4770560ba62fd7f197f1a2e008fd5731ab"
FLYWAY_IMAGE = "flyway/flyway:12.10.0"
ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "backend" / "common" / "src" / "main" / "resources" / "db" / "migration"
SNAPSHOT = Path(__file__).with_name("schema-snapshot.sql")
# Lines that vary with the pg_dump release or session, not with the schema.
VOLATILE_PREFIXES = ("--", "SET ", "SELECT pg_catalog.set_config(", "\\restrict", "\\unrestrict")


def normalize(dump):
    lines = []
    for line in dump.replace("\r\n", "\n").split("\n"):
        line = line.rstrip()
        if line.startswith(VOLATILE_PREFIXES):
            continue
        if not line and (not lines or not lines[-1]):
            continue
        lines.append(line)
    while lines and not lines[-1]:
        lines.pop()
    return "\n".join(lines) + "\n"


def docker(*args, **kwargs):
    return subprocess.run(["docker", *args], check=True, capture_output=True, text=True, **kwargs)


def dump(container, user, database):
    return docker("exec", container, "pg_dump", "-U", user, "-d", database, "--schema-only", "--no-owner",
                  "--no-privileges", "--exclude-table=public.flyway_schema_history").stdout


def migrated_dump():
    container = f"vod-schema-snapshot-{os.getpid()}"
    docker("run", "-d", "--name", container, "--network", "none",
           "-e", "POSTGRES_USER=fixture", "-e", "POSTGRES_PASSWORD=fixture-only", "-e", "POSTGRES_DB=fixture",
           "-e", "POSTGRES_INITDB_ARGS=--locale-provider=icu --icu-locale=vi", POSTGRES_IMAGE)
    try:
        for _ in range(60):
            ready = subprocess.run(["docker", "exec", container, "pg_isready", "-U", "fixture", "-d", "fixture"],
                                   capture_output=True)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("PostgreSQL did not become ready")
        docker("run", "--rm", "--network", f"container:{container}", "-v", f"{MIGRATIONS}:/flyway/sql:ro",
               "-e", "FLYWAY_URL=jdbc:postgresql://localhost:5432/fixture",
               "-e", "FLYWAY_USER=fixture", "-e", "FLYWAY_PASSWORD=fixture-only",
               FLYWAY_IMAGE, "-connectRetries=10", "-validateMigrationNaming=true", "migrate")
        return dump(container, "fixture", "fixture")
    finally:
        subprocess.run(["docker", "rm", "-f", "-v", container], capture_output=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--update", action="store_true", help="rewrite the committed snapshot")
    parser.add_argument("--container", help="dump this already migrated PostgreSQL container instead")
    parser.add_argument("--user", default="vod")
    parser.add_argument("--database", default="vod_upload_test")
    args = parser.parse_args(argv)
    try:
        raw = dump(args.container, args.user, args.database) if args.container else migrated_dump()
    except subprocess.CalledProcessError as error:
        print(f"FAIL: {' '.join(error.cmd[:3])} exited {error.returncode}\n{error.stderr}", file=sys.stderr)
        return 1
    actual = normalize(raw)
    if args.update:
        SNAPSHOT.write_text(actual, encoding="utf-8", newline="\n")
        print(f"Wrote {SNAPSHOT.relative_to(ROOT).as_posix()}.")
        return 0
    expected = SNAPSHOT.read_text(encoding="utf-8") if SNAPSHOT.exists() else ""
    if actual == expected:
        print("The migrated schema matches the committed snapshot.")
        return 0
    sys.stderr.writelines(difflib.unified_diff(
        expected.splitlines(keepends=True), actual.splitlines(keepends=True),
        "committed schema-snapshot.sql", "migrated schema"))
    print("FAIL: the migrated schema differs from deploy/tests/schema-snapshot.sql. If a new migration intends "
          "this change, run `python3 deploy/tests/schema-snapshot.py --update` and commit the snapshot.",
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
