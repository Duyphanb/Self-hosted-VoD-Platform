import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


IMMUTABILITY = load("migration_immutability_check", "migration-immutability-check.py")
SNAPSHOT = load("schema_snapshot", "schema-snapshot.py")
GIT_ENV = {**os.environ, "GIT_AUTHOR_NAME": "fixture", "GIT_AUTHOR_EMAIL": "fixture@example.test",
           "GIT_COMMITTER_NAME": "fixture", "GIT_COMMITTER_EMAIL": "fixture@example.test"}


class MigrationImmutabilityTests(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name)
        self.git("init", "-q")
        self.write(f"{IMMUTABILITY.MIGRATION_DIR}/V1__init.sql", "CREATE TABLE a (id int);\n")
        self.write(f"{IMMUTABILITY.MIGRATION_DIR}/V2__seed.sql", "INSERT INTO a VALUES (1);\n")
        self.write("README.md", "fixture\n")
        self.base = self.commit()

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.run(["git", "-c", "core.autocrlf=false", *args], cwd=self.repo, env=GIT_ENV,
                              check=True, capture_output=True, text=True).stdout.strip()

    def write(self, path, text):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8", newline="\n")

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "change")
        return self.git("rev-parse", "HEAD")

    def changed(self):
        return IMMUTABILITY.changed_migrations(self.base, self.commit(), cwd=self.repo)

    def test_adding_a_migration_or_changing_other_files_passes(self):
        self.write(f"{IMMUTABILITY.MIGRATION_DIR}/V3__more.sql", "CREATE TABLE b (id int);\n")
        self.write("README.md", "changed\n")
        self.assertEqual(self.changed(), [])

    def test_editing_a_committed_migration_fails(self):
        self.write(f"{IMMUTABILITY.MIGRATION_DIR}/V1__init.sql", "CREATE TABLE a (id bigint);\n")
        self.assertEqual(self.changed(), [f"M\t{IMMUTABILITY.MIGRATION_DIR}/V1__init.sql"])

    def test_deleting_a_committed_migration_fails(self):
        (self.repo / IMMUTABILITY.MIGRATION_DIR / "V2__seed.sql").unlink()
        self.assertEqual(self.changed(), [f"D\t{IMMUTABILITY.MIGRATION_DIR}/V2__seed.sql"])

    def test_renaming_a_committed_migration_fails_as_a_deletion(self):
        os.rename(self.repo / IMMUTABILITY.MIGRATION_DIR / "V2__seed.sql",
                  self.repo / IMMUTABILITY.MIGRATION_DIR / "V2__seed_roles.sql")
        self.assertEqual(self.changed(), [f"D\t{IMMUTABILITY.MIGRATION_DIR}/V2__seed.sql"])

    def test_a_missing_or_zero_base_is_rejected(self):
        for base in ("", "0" * 40, "abc", "f" * 40):
            with self.assertRaises(ValueError):
                IMMUTABILITY.resolve_base(base, cwd=self.repo)
        self.assertEqual(IMMUTABILITY.resolve_base(self.base, cwd=self.repo), self.base)


class SchemaSnapshotTests(unittest.TestCase):

    def test_normalize_drops_release_and_session_lines(self):
        dump = ("--\n-- PostgreSQL database dump\n--\n\n\\restrict abc123\n"
                "SET statement_timeout = 0;\nSELECT pg_catalog.set_config('search_path', '', false);\n\n\n"
                "CREATE TABLE public.a (\n    id integer NOT NULL   \n);\n\n\n"
                "COMMENT ON TABLE public.a IS 'kept';\n\n\\unrestrict abc123\n\n")
        self.assertEqual(SNAPSHOT.normalize(dump),
                         "CREATE TABLE public.a (\n    id integer NOT NULL\n);\n\nCOMMENT ON TABLE public.a IS 'kept';\n")

    def test_normalize_keeps_text_inside_literals_identifiers_and_bodies(self):
        bodies = [
            "    note text DEFAULT 'first\nSET alpha = 1;\n-- not a comment  \n\n\nlast'::text\n",
            "    note text DEFAULT E'it\\'s\nSET alpha = 1;\nlast'::text\n",
            "    \"odd\n-- name\" integer\n",
            "CREATE FUNCTION public.f() RETURNS void AS $fn$\nSET search_path = public;\n-- step  \n\n\n$fn$;\n",
            "CREATE FUNCTION public.g() RETURNS text AS $$\nSELECT 'x';\n-- kept\n$$;\n",
        ]
        for body in bodies:
            with self.subTest(body=body):
                changed = body.replace("alpha", "beta").replace("step", "stop").replace("name", "nome")
                changed = changed.replace("kept", "kopt")
                self.assertIn(body.rstrip("\n"), SNAPSHOT.normalize("SET a = 1;\n" + body))
                self.assertNotEqual(SNAPSHOT.normalize(body), SNAPSHOT.normalize(changed))
        self.assertEqual(SNAPSHOT.normalize("SELECT 'a''b';  \n-- dropped\nSELECT 1;\n"), "SELECT 'a''b';\nSELECT 1;\n")

    def test_committed_snapshot_is_normalized_utf8_without_flyway_history(self):
        text = SNAPSHOT.SNAPSHOT.read_bytes().decode("utf-8")
        self.assertEqual(SNAPSHOT.normalize(text), text)
        self.assertIn("CREATE TABLE public.", text)
        self.assertNotIn("flyway_schema_history", text)

    def test_ci_and_compose_pin_the_image_and_validate_migration_naming(self):
        root = SNAPSHOT.ROOT
        ci = (root / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        compose = (root / "deploy" / "docker-compose.yml").read_text(encoding="utf-8")
        self.assertIn(f"image: {SNAPSHOT.POSTGRES_IMAGE}", ci)
        self.assertNotIn("postgres:16-alpine", ci)
        self.assertIn(SNAPSHOT.FLYWAY_IMAGE, ci)
        self.assertIn("-validateMigrationNaming=true", ci)
        self.assertIn('FLYWAY_VALIDATE_MIGRATION_NAMING: "true"', compose)


if __name__ == "__main__":
    unittest.main()
