#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest
import uuid

SPEC = importlib.util.spec_from_file_location("newman_upload_fixtures", Path(__file__).with_name("newman-upload-fixtures.py"))
fixtures = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixtures)


class NewmanUploadFixturesTest(unittest.TestCase):
    def test_files_have_the_expected_signatures_and_sizes(self):
        with tempfile.TemporaryDirectory() as directory:
            fixtures.write_fixtures(Path(directory))
            valid = (Path(directory) / "valid.mp4").read_bytes()
            oversize = (Path(directory) / "oversize.mp4").read_bytes()
            self.assertEqual(valid[4:8], b"ftyp")
            self.assertLess(len(valid), 1024 * 1024)
            self.assertEqual(oversize[4:8], b"ftyp")
            # Over MAX_UPLOAD_SIZE_MB=1 (MiB) but under Tomcat's 2 MiB swallow limit.
            self.assertGreater(len(oversize), 1024 * 1024)
            self.assertLess(len(oversize), 2 * 1024 * 1024)
            self.assertNotIn(b"ftyp", (Path(directory) / "note.txt").read_bytes())

    def test_in_flight_pair_is_published_so_the_relay_never_sends_it(self):
        upload_movie = uuid.uuid4()
        in_flight_movie = uuid.uuid4()
        sql = fixtures.seed_sql(upload_movie, in_flight_movie)
        self.assertTrue(sql.startswith("BEGIN;") and sql.rstrip().endswith("COMMIT;"))
        self.assertEqual(sql.count("INSERT INTO movies"), 2)
        self.assertIn(f"'{in_flight_movie}', 'vod-raw'", sql)
        self.assertIn("'QUEUED', 1, now(), now()", sql)
        self.assertNotIn(f"'{upload_movie}', 'vod-raw'", sql)


if __name__ == "__main__":
    unittest.main()
