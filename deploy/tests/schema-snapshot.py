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
import re
import subprocess
import sys
import time

POSTGRES_IMAGE = "postgres:16.15-alpine@sha256:0cefaabd882e42bcb2a2f3dc56d3ce4770560ba62fd7f197f1a2e008fd5731ab"
FLYWAY_IMAGE = "flyway/flyway:12.10.0"
ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "backend" / "common" / "src" / "main" / "resources" / "db" / "migration"
SNAPSHOT = Path(__file__).with_name("schema-snapshot.sql")
# pg_dump lines that vary with its release or session, not with the schema.
VOLATILE_LINE = re.compile(r"--.*|SET \w+ = .*;|SELECT pg_catalog\.set_config\(.*\);|\\(un)?restrict \w+")
DOLLAR_TAG = re.compile(r"\$(?:[A-Za-z_][A-Za-z_0-9]*)?\$")


def quote_state(line, state):
    """Return the quoting state after line: None, an open quote ("'", "E'" or '"'), or a dollar-quote tag."""
    i = 0
    while i < len(line):
        char = line[i]
        if state is None:
            if line.startswith("--", i):
                return None
            if char in "'\"":
                escaped = char == "'" and i > 0 and line[i - 1] in "Ee" and (i == 1 or not line[i - 2].isalnum())
                state = "E'" if escaped else char
            elif char == "$" and not (i > 0 and (line[i - 1].isalnum() or line[i - 1] == "_")):
                tag = DOLLAR_TAG.match(line, i)
                if tag:
                    state = tag.group()
                    i = tag.end() - 1
        elif state.startswith("$"):
            if line.startswith(state, i):
                i += len(state) - 1
                state = None
        elif state == "E'" and char == "\\":
            i += 1
        elif char == state[-1]:
            if line.startswith(char * 2, i):
                i += 1
            else:
                state = None
        i += 1
    return state


def normalize(dump):
    """Drop volatile pg_dump lines and spacing, never touching text inside literals, identifiers or bodies."""
    lines = []
    state = None
    for line in dump.split("\n"):
        outside = state is None
        state = quote_state(line, state)
        if outside and state is None:
            line = line.rstrip()
            if VOLATILE_LINE.fullmatch(line) or (not line and (not lines or not lines[-1])):
                continue
        lines.append(line)
    while lines and not lines[-1]:
        lines.pop()
    return "\n".join(lines) + "\n"


def docker(*args, **kwargs):
    # Decode as UTF-8 everywhere; the default locale encoding differs on Windows.
    return subprocess.run(["docker", *args], check=True, capture_output=True, encoding="utf-8", **kwargs)


def dump(container, user, database):
    return docker("exec", container, "pg_dump", "-U", user, "-d", database, "--encoding=UTF8", "--schema-only",
                  "--no-owner", "--no-privileges", "--exclude-table=public.flyway_schema_history").stdout


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
    expected = SNAPSHOT.read_bytes().decode("utf-8") if SNAPSHOT.exists() else ""
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
