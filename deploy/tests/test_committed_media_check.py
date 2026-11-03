import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("committed_media_check", HERE / "committed-media-check.py")
CHECK = importlib.util.module_from_spec(spec)
spec.loader.exec_module(CHECK)
GIT_ENV = {**os.environ, "GIT_AUTHOR_NAME": "fixture", "GIT_AUTHOR_EMAIL": "fixture@example.test",
           "GIT_COMMITTER_NAME": "fixture", "GIT_COMMITTER_EMAIL": "fixture@example.test"}

ISO_BMFF = b"\x00\x00\x00\x20ftypisom\x00\x00\x02\x00"
TS_PACKETS = (b"\x47" + b"\x00" * 187) * 3
M2TS_PACKETS = (b"\x00" * 4 + b"\x47" + b"\x00" * 187) * 3


class MediaKindTests(unittest.TestCase):

    def test_detects_container_and_elementary_stream_signatures(self):
        for head in (ISO_BMFF, b"\x00\x00\x00\x08moov", b"\x1a\x45\xdf\xa3\x01", b"RIFF\x00\x00\x00\x00AVI ",
                     b"RIFF\x00\x00\x00\x00WAVE", b"OggS\x00", b"fLaC", b"ID3\x04", b"\xff\xf1\x50",
                     b"FLV\x01\x05", b"\x00\x00\x01\xba", b"YUV4MPEG2 W2", b"#EXTM3U\n", TS_PACKETS, M2TS_PACKETS):
            self.assertIsNotNone(CHECK.media_kind(head), head[:16])

    def test_ignores_source_documents_and_images(self):
        for head in (b"package com.example;\n", b"PK\x03\x04docx", b"\x89PNG\r\n\x1a\n", b"\xff\xd8\xff\xe0",
                     b"\x00\x00\x00\x1cftypavif\x00\x00\x00\x00", b"\x00\x00\x00\x18ftypheic", b"RIFF\x00\x00\x00\x00WEBP",
                     b"", b"\x47only one sync byte", b"# Media notes mention #EXTM3U later\n"):
            self.assertIsNone(CHECK.media_kind(head), head[:16])


class ViolationTests(unittest.TestCase):

    def check(self, files):
        return CHECK.violations(list(files), lambda path: files[path])

    def test_rejects_media_names_in_any_case_and_segment_names(self):
        found = self.check({"docs/demo.MP4": b"", "a/b/clip.webm": b"", "out/segments/seg_00001.ts": b"x",
                            "docs/notes.m3u8": b"#EXTM3U"})

        self.assertEqual(sorted(found), [
            "a/b/clip.webm: media file name (.webm)",
            "docs/demo.MP4: media file name (.mp4)",
            "docs/notes.m3u8: media file name (.m3u8)",
            "out/segments/seg_00001.ts: HLS segment name",
        ])

    def test_rejects_renamed_media_content(self):
        found = self.check({"frontend/src/logo.svg": ISO_BMFF, "frontend/src/api.ts": TS_PACKETS})

        self.assertEqual(sorted(found), ["frontend/src/api.ts: MPEG transport stream content",
                                         "frontend/src/logo.svg: ISO-BMFF (MP4/MOV) content"])

    def test_allows_typescript_and_text_playlist_fixtures_only(self):
        fixtures = "worker/probe/src/test/resources/media-fixtures"
        found = self.check({"frontend/src/main.ts": b"export {};\n", f"{fixtures}/vod.m3u8": b"#EXTM3U\n",
                            f"{fixtures}/clip.mp4": ISO_BMFF})

        self.assertEqual(found, [f"{fixtures}/clip.mp4: ISO-BMFF (MP4/MOV) content"])


class RepositoryTests(unittest.TestCase):

    def test_reads_tracked_files_only(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            subprocess.run(["git", "init", "-q"], cwd=repo, env=GIT_ENV, check=True)
            (repo / "README.md").write_text("fixture\n")
            (repo / "renamed.txt").write_bytes(ISO_BMFF)
            (repo / "untracked.mp4").write_bytes(ISO_BMFF)
            subprocess.run(["git", "add", "README.md", "renamed.txt"], cwd=repo, env=GIT_ENV, check=True)

            self.assertEqual(sorted(CHECK.tracked_files(repo)), ["README.md", "renamed.txt"])
            self.assertEqual(CHECK.main(["--root", str(repo)]), 1)
            subprocess.run(["git", "rm", "-q", "--cached", "renamed.txt"], cwd=repo, env=GIT_ENV, check=True)
            self.assertEqual(CHECK.main(["--root", str(repo)]), 0)


if __name__ == "__main__":
    unittest.main()
