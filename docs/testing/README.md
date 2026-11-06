# Testing Docs

Use this folder for verification evidence and hardening guides.

## Planned Contents

- manual E2E checklist
- Postman collection notes
- Newman run plan
- smoke test checklist
- known limitations
- test evidence index

## Current Hardening Guides

- [Sprint 2 Auth closeout](SPRINT-2-CLOSEOUT.md)
- [Dependency security](DEPENDENCY-SECURITY.md)
- [Upload transport/storage rehearsal](UPLOAD-REHEARSAL.md)
- [Worker encode measurement (#124)](WORKER-ENCODE-MEASUREMENT.md) (template until the arm64 run is recorded)

## Upload edge checks

Run `python -m unittest discover -s deploy/tests -p 'test_*.py'` from the repository root
for the static route, inheritance and limit-equality checks. With Docker available, run
`python deploy/tests/nginx-edge-smoke.py`; it creates and removes its own
`vod-auth-audit-*` network and containers. No application JVM or existing Compose volumes
are used. It checks Nginx syntax, Content-Length/chunked streaming, route body limits,
edge JSON errors, unchanged backend error bodies, and HLS/health routing.
Scratch files use the system temporary directory and are removed on exit. For a
Docker Desktop bind mount, set `VOD_SMOKE_TMP` to an existing host directory that
Docker can access (for example a temporary directory on D: on Windows). Only the
smoke's generated child directory is removed; the supplied parent is retained.

`sh deploy/tests/production-config-smoke.sh` checks the production preflight separately,
including rejection of an upload limit that differs from the frozen 2048 MiB edge limit.
The regular CI `Skeleton build checks` job runs all three checks. The manual synthetic
2,000,000,000-byte rehearsal is **not VPS evidence**; actual VPS measurements and concurrent
upload headroom remain the public-deployment gate in
[#180](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/180).

## Worker media tests (#123)

Test media is generated at test time; no media file is committed. `worker/test-support`
generates each `MediaFixture` with FFmpeg's lavfi sources into the test's temporary directory:

| Fixtures | Stands for |
| --- | --- |
| `h264-aac.mp4` (High), `vp9-opus.webm`, `h264-high10-flac.mkv`, `portrait-h264-aac.mp4`, `silent-h264.mp4`, `hevc-main10-hdr-ac3.mkv` | The valid corpus: 13 s, 640x360 (360x640 portrait), PQ/BT.2020 tags on the HDR source |
| `ebml-under-mp4-key.mp4`, `text-under-mp4-key.mp4`, `hls-playlist-under-mp4-key.mp4`, `ffconcat-under-mkv-key.mkv` | Content that does not match its key; the forced demuxer refuses it |
| `truncated-moov-at-end.mp4`, `truncated-faststart.mp4` | Truncation: the first never probes, the second probes but encodes short |
| `audio-only.mp4`, `cover-art-only.mp4`, `mpeg2-video.mkv`, `nine-streams.mkv`/`.mp4`, `over-cap-2560x1440.mp4` | Well-formed files ADR-010 D26 rejects (no video, decoder outside the allowlist, caps) |

The default `mvn -B -ntp verify` in `worker/` excludes the `ffmpeg` JUnit tag and runs the
unit tests of the playlist reader and the ADR-010 D27 output checks. `sh deploy/tests/worker-ffmpeg-tests.sh`
(Linux, or Git Bash with Docker Desktop) builds the worker image's `ffmpeg-test` stage, runs
`mvn -Pffmpeg-tests test` inside it so the tests use the runtime image's own ffmpeg/ffprobe, and then
requires every `*FfmpegTests` class to have run with no skipped test. Name new FFmpeg-tagged
classes `*FfmpegTests` so this evidence check covers them. The CI `Worker Maven build and test` job
runs the script, and `python deploy/tests/committed-media-check.py`, which rejects tracked files
that are media by name (any case) or by content signature.

What the tests pin for FFmpeg 7.1.5 (the ADR-010 D25 binary):
- The D27 reference encode passes the output checks on the whole valid corpus: exit 0 under
  `-xerror`, 6/6/1 s segments and an EXTINF sum within 1 s of the probed duration. This is the
  evidence ADR-010 D27 asks for before Issue 5.5; Issue 5.5's command builder replaces the reference.
- The truncated faststart file exits non-zero but still leaves a VOD playlist ending in
  `#EXT-X-ENDLIST`; the duration check rejects it as well.
- Output `-color_primaries`/`-color_trc` options alone leave PQ/BT.2020 tags on the 8-bit output;
  the reference retags BT.709 with the `setparams` filter. Issue 5.5 needs this.
- `-max_streams 8` makes FFprobe fail on a nine-track MP4 but drop the ninth Matroska track and exit 0.
  Issue 5.4 must not rely on the probed stream count alone.

## arm64 worker job and encode measurement (#124)

The non-required `arm64 worker` workflow builds the images natively on `ubuntu-24.04-arm`. It runs the
FFmpeg-tagged tests above, `jvm-limits-smoke.py` under the production overlay, and the Silo storage smoke.
It then runs `worker-encode-measurement.py`, which measures FFprobe and the D27 reference encode in the worker
image at the production worker limits. [WORKER-ENCODE-MEASUREMENT.md](WORKER-ENCODE-MEASUREMENT.md) describes
both scripts and holds the record the arm64 run fills in. `python -m unittest discover -s deploy/tests -p 'test_*.py'`
covers their parsing, fits and safety checks without Docker. The measurement keeps its copy of the reference
command equal to `ReferenceHlsEncodeFfmpegTests.referenceArguments`.

## Purpose

- prove the upload -> transcode -> playback flow
- map tests back to requirements and architecture
- document manual checks that are not automated yet
- support local and VPS demo readiness

## Boundary

Do not put requirements or architecture source-of-truth here. Link back to `docs/requirements/` and `docs/architecture/` when needed.
