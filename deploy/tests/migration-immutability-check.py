#!/usr/bin/env python3
"""Fail when a change edits, renames or deletes a committed Flyway migration (ADR-003, R-005).

Adding a migration is allowed. A rename counts as deleting the old file.
"""
import argparse
import re
import subprocess
import sys

MIGRATION_DIR = "backend/common/src/main/resources/db/migration"
SHA = re.compile(r"^[0-9a-f]{40}$")


def changed_migrations(base, head="HEAD", cwd=None):
    """Return "<status>\t<path>" lines for committed migrations that head modified or removed."""
    result = subprocess.run(
        ["git", "diff", "--no-renames", "--name-status", "--diff-filter=DMT", base, head, "--", MIGRATION_DIR],
        cwd=cwd, check=True, capture_output=True, text=True,
    )
    return [line for line in result.stdout.splitlines() if line.strip()]


def resolve_base(base, cwd=None):
    if not SHA.match(base or "") or set(base) == {"0"}:
        raise ValueError(f"no comparison base commit was given (got {base!r})")
    found = subprocess.run(["git", "cat-file", "-e", f"{base}^{{commit}}"], cwd=cwd, capture_output=True)
    if found.returncode != 0:
        raise ValueError(f"comparison base {base} is not in this clone; check out with full history")
    return base


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="full SHA of the commit the change is compared with")
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args(argv)
    try:
        base = resolve_base(args.base)
    except ValueError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 2
    changed = changed_migrations(base, args.head)
    if changed:
        print("FAIL: committed migrations must never be edited, renamed or deleted; add a new migration instead.",
              file=sys.stderr)
        for line in changed:
            print(f"  {line}", file=sys.stderr)
        return 1
    print(f"Committed migrations are unchanged since {base[:12]}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
