#!/usr/bin/env python3
"""Fail when a tracked file is audio/video media or an HLS playlist (#122, #123, R-002).

Test media is generated at test time (worker/test-support), so nothing media-like belongs in Git.
The .gitignore globs are case-sensitive on Linux and miss renamed files, so this check reads the
first bytes of every tracked file as well as its name. `.ts` is excluded by name because frontend/
uses it for TypeScript; MPEG-TS content is still caught by its sync bytes.
"""
import argparse
import fnmatch
import subprocess
import sys
from pathlib import Path

MEDIA_EXTENSIONS = {
    "mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "flv", "mpg", "mpeg", "m2ts", "3gp", "ogv", "y4m",
    "m3u8", "m3u", "m4s", "mp3", "m4a", "aac", "ac3", "wav", "flac", "opus", "ogg", "h264", "264", "hevc",
    "265", "ivf", "nut", "mxf", "vob",
}
# Text-only fixtures (for example a playlist a parser test reads) may use media names here; their
# content is still checked.
TEXT_FIXTURE_GLOB = "worker/*/src/test/resources/media-fixtures/*"
SEGMENT_NAME_GLOB = "*/segments/seg_[0-9][0-9][0-9][0-9][0-9].ts"
# ISO-BMFF image brands (AVIF/HEIF) are pictures, not video.
IMAGE_BRANDS = {b"avif", b"avis", b"heic", b"heix", b"heim", b"heis", b"hevc", b"hevx", b"mif1", b"msf1"}
PREFIXES = [
    (b"\x1a\x45\xdf\xa3", "Matroska/WebM (EBML)"),
    (b"OggS", "Ogg"),
    (b"fLaC", "FLAC"),
    (b"ID3", "MP3 (ID3)"),
    (b"FLV\x01", "FLV"),
    (b"\x30\x26\xb2\x75\x8e\x66\xcf\x11", "ASF/WMV"),
    (b"\x00\x00\x01\xba", "MPEG program stream"),
    (b"YUV4MPEG2", "Y4M"),
    (b"DKIF", "IVF"),
    (b"\x06\x0e\x2b\x34", "MXF"),
    (b"nut/multimedia container", "NUT"),
    (b"\x0b\x77", "AC-3"),
    (b"#EXTM3U", "HLS/M3U playlist"),
]
HEAD_BYTES = 4096


def media_kind(head):
    """Return a description when the leading bytes are a media signature, else None."""
    if len(head) >= 12 and head[4:8] == b"ftyp" and head[8:12] not in IMAGE_BRANDS:
        return "ISO-BMFF (MP4/MOV)"
    if len(head) >= 8 and head[4:8] in (b"moov", b"mdat"):
        return "QuickTime"
    if head[:4] == b"RIFF" and head[8:12] in (b"AVI ", b"AVIX", b"WAVE"):
        return "RIFF AVI/WAV"
    for prefix, kind in PREFIXES:
        if head.startswith(prefix):
            return kind
    if len(head) >= 2 and head[0] == 0xFF and head[1] in (0xF1, 0xF9, 0xF2, 0xF3, 0xFA, 0xFB):
        return "AAC/MP3 frame"
    for offset, step in ((0, 188), (4, 192)):
        if len(head) > offset + 2 * step and all(head[offset + i * step] == 0x47 for i in range(3)):
            return "MPEG transport stream"
    return None


def violations(paths, read_head):
    """Return "<path>: <reason>" for each tracked path that is media by name or content."""
    found = []
    for path in paths:
        name = path.rsplit("/", 1)[-1].lower()
        extension = name.rsplit(".", 1)[-1] if "." in name else ""
        text_fixture = fnmatch.fnmatch(path, TEXT_FIXTURE_GLOB)
        if extension in MEDIA_EXTENSIONS and not text_fixture:
            found.append(f"{path}: media file name (.{extension})")
            continue
        if fnmatch.fnmatch(path.lower(), SEGMENT_NAME_GLOB):
            found.append(f"{path}: HLS segment name")
            continue
        head = read_head(path)
        kind = media_kind(head)
        if kind and not (text_fixture and kind == "HLS/M3U playlist"):
            found.append(f"{path}: {kind} content")
    return found


def tracked_files(cwd=None):
    result = subprocess.run(["git", "ls-files", "-z"], cwd=cwd, check=True, capture_output=True)
    return [path for path in result.stdout.decode("utf-8").split("\0") if path]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".", help="repository root (default: current directory)")
    args = parser.parse_args(argv)
    root = Path(args.root)

    def read_head(path):
        file = root / path
        if file.is_symlink() or not file.is_file():
            return b""
        with file.open("rb") as handle:
            return handle.read(HEAD_BYTES)

    found = violations(tracked_files(root), read_head)
    for line in found:
        print(f"Committed media is not allowed: {line}", file=sys.stderr)
    if found:
        print("Generate test media at test time instead (worker/test-support, #123).", file=sys.stderr)
        return 1
    print("No committed media files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
