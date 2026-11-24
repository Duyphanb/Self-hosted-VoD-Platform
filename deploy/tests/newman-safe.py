#!/usr/bin/env python3
"""Run pinned Newman and emit only names independently allowlisted by the collection source."""
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

TEST_NAME = re.compile(r"""pm\.test\(\s*(['"])([^'"\\\r\n]{1,160})\1\s*,""")
SAFE_NAME = re.compile(r"[\w .,:;()/+'!#=<>?%-]{1,160}\Z")


def static_names(collection):
    """Map collection item names to literal assertion names in their scripts and ancestors."""
    names = {}

    def visit(node, inherited):
        scripts = ["\n".join(event.get("script", {}).get("exec", [])) for event in node.get("event", [])]
        allowed = inherited | {match.group(2) for script in scripts for match in TEST_NAME.finditer(script)
                               if SAFE_NAME.fullmatch(match.group(2))}
        if "request" in node:
            name = node.get("name", "")
            if SAFE_NAME.fullmatch(name):
                names.setdefault(name, set()).update(allowed)
        for child in node.get("item", []):
            visit(child, allowed)

    visit(collection, set())
    return names


def diagnostics(report, allowed):
    for failure in report.get("run", {}).get("failures", []):
        item = (failure.get("source") or {}).get("name")
        assertion = (failure.get("error") or {}).get("test")
        if not isinstance(item, str) or item not in allowed:
            yield "Newman failure (unknown item)"
        elif not isinstance(assertion, str) or assertion not in allowed[item]:
            yield f"Item: {item}"
        else:
            yield f"Item: {item} | Assertion: {assertion}"


def run(collection, arguments):
    allowed = static_names(json.loads(Path(collection).read_text(encoding="utf-8")))
    npx = shutil.which("npx.cmd" if sys.platform == "win32" else "npx")
    if not npx:
        return 1
    with tempfile.TemporaryDirectory(prefix="vod-newman-") as directory:
        report = Path(directory) / "report.json"
        result = subprocess.run([npx, "--yes", "newman@6.2.2", "run", collection, *arguments,
                                 "--reporters", "json", "--reporter-json-export", str(report)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=240)
        if result.returncode and report.is_file():
            for line in diagnostics(json.loads(report.read_text(encoding="utf-8")), allowed):
                print(line)
        return result.returncode


if __name__ == "__main__":
    try:
        sys.exit(run(sys.argv[1], sys.argv[2:]))
    except Exception:
        print("Newman failure (details withheld)")
        sys.exit(1)
