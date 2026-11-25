import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("newman_safe", Path(__file__).with_name("newman-safe.py"))
safe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(safe)


class SafeNewmanTest(unittest.TestCase):
    def test_failure_emits_only_static_names_and_removes_raw_report(self):
        collection = {
            "item": [{
                "name": "Login",
                "request": {},
                "event": [{
                    "script": {
                        "exec": [
                            "pm.test('Status is 200', () => {});",
                            "pm.test('dynamic ' + cookie, () => {});",
                        ],
                    },
                }],
            }],
        }
        private = "token-or-cookie-fixture"
        failures = [
            {"source": {"name": "Login"}, "error": {"test": "Status is 200", "message": private}},
            {"source": {"name": "Login"}, "error": {"test": private}},
            {"source": {"name": private}, "error": {"test": "Status is 200"}},
        ]
        written = []

        def fake_newman(arguments, **_options):
            report = Path(arguments[-1])
            report.write_text(json.dumps({"run": {"failures": failures}, "environment": private}), encoding="utf-8")
            written.append(report)
            return type("Result", (), {"returncode": 1})()

        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "collection.json"
            source.write_text(json.dumps(collection), encoding="utf-8")
            output = io.StringIO()
            with patch.object(safe.shutil, "which", return_value="npx"), \
                    patch.object(safe.subprocess, "run", side_effect=fake_newman), redirect_stdout(output):
                self.assertEqual(safe.run(str(source), []), 1)

        self.assertEqual(output.getvalue().splitlines(), [
            "Item: Login | Assertion: Status is 200", "Item: Login", "Newman failure (unknown item)"
        ])
        self.assertFalse(written[0].parent.exists())
