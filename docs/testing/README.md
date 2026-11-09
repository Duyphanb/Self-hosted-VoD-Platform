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

## Backend PostgreSQL tests (#119)

`mvn -B -ntp verify` in `backend/` needs Docker. Every Spring context test in `backend/common`
implements `PostgresTestDatabase` (`src/test/java/com/vodplatform/common/support/`), so its context
runs on one PostgreSQL 16 Testcontainers database that the whole test JVM shares:

- The image is the digest the CI schema-migration service pins (`deploy/tests/test_migration_guards.py`
  keeps them equal), initialised with the ICU `vi` collation like Compose and CI.
- Flyway applies the committed migrations (V1 onward, with naming validation) when each context
  starts, and Hibernate only validates the entity mappings (`ddl-auto=validate`).
  `PostgresMigrationTests` checks both. Flyway is a test-scope dependency only; at runtime the
  Flyway container still owns the schema.
- The container's `@ServiceConnection` details replace `spring.datasource.*` and `spring.flyway.*`,
  so an exported `SPRING_DATASOURCE_URL` cannot point a test at a developer database
  (`PostgresTestDatabaseIsolationTests`). `PostgresTestDatabaseCoverageTests` fails when a
  `@SpringBootTest` class does not implement the interface directly. A subclass declares it again,
  because JUnit reads the skip condition only from directly implemented interfaces.
- The contexts share one database: a test removes the rows it commits (or rolls back with
  `@Transactional`) and uses the `ROLE_USER`/`ROLE_ADMIN` rows seeded by V2 and V3.
- PostgreSQL-only behaviour now runs in the JVM: the `ux_users_email_identity` 409 for a
  case-variant registration racing an uncommitted account (`EmailIdentityPostgresTests`, ADR-008),
  and the upload in-flight index, lock wait and ambiguous-commit suites (`UploadPostgresTests`,
  `UploadEndpointPostgresTests`).

On a machine without Docker, `mvn -B -ntp verify -DskipPostgresTests` reports these tests as
skipped. CI never sets the property, and the `Backend Maven build and test` job requires executed
reports, with no skipped test, for the PostgreSQL-specific suites.

The `@DataJpaTest` slices in `auth`, `catalog` and `encoding` still run on H2 with entity-generated
schemas; those modules cannot see the migrations. Their PostgreSQL suites run against an already
migrated database instead: `CreationOrderPostgresTest` (catalog creation order, lock waits and
deadlock retries) with `CATALOG_TEST_POSTGRES_URL` (credentials in the URL), and
`VideoAssetPostgresTest` with `ENCODING_TEST_POSTGRES_URL`, `_USER` and `_PASSWORD` (the exact
loopback database `vod_upload_test`). The `Flyway schema migration` CI job runs both against its
migrated service, with `ENCODING_TEST_POSTGRES_REQUIRED=true`, and requires their executed reports.
Without those variables both suites are skipped.

## Worker media tests (#123)

Test media is generated at test time; no media file is committed. `worker/test-support`
generates each `MediaFixture` with FFmpeg's lavfi sources into the test's temporary directory:

| Fixtures | Stands for |
| --- | --- |
| `h264-aac.mp4` (High), `vp9-opus.webm`, `h264-high10-flac.mkv`, `portrait-h264-aac.mp4`, `silent-h264.mp4`, `hevc-main10-hdr-ac3.mkv` | The valid corpus: 13 s, 640x360 (360x640 portrait), PQ/BT.2020 tags on the HDR source |
| `rotated-90.mp4`, `rotated-minus-90.mkv`, `rotated-180.mp4`, `rotate-tag-90.mkv` | Valid rotated copies of `h264-aac.mp4` (#194): a Display Matrix of 90, -90 or 180 degrees (counterclockwise), or only the legacy `ROTATE` tag that the Matroska muxer writes (clockwise, so `90` probes as 270; #195) |
| `ebml-under-mp4-key.mp4`, `text-under-mp4-key.mp4`, `hls-playlist-under-mp4-key.mp4`, `ffconcat-under-mkv-key.mkv` | Content that does not match its key; the forced demuxer refuses it |
| `truncated-moov-at-end.mp4`, `truncated-faststart.mp4` | Truncation: the first never probes, the second probes but encodes short |
| `audio-only.mp4`, `cover-art-only.mp4`, `video-with-cover-art.mp4`, `mpeg2-video.mkv`, `mp2-audio.mkv`, `rotated-45.mp4`, `nine-streams.mkv`/`.mp4`, `over-cap-2560x1440.mp4` | Well-formed files ADR-010 D26 rejects (no video, PNG cover art, a video or audio decoder outside the allowlist, a rotation that is not a right angle, caps) |

`FakeMediaTool` in the same module stands in for a media binary in process tests: it runs on the
test JVM's own `java` for the cross-platform runner tests, or as a POSIX shell script for tests
that need one executable path such as `FFPROBE_PATH`. Its spawning modes start a child and record
both PIDs, so a test can prove that no process is left behind: the child shares the tool's output
pipes, and in the exiting modes the tool exits and leaves the child running, with or without its pipes.

The default `mvn -B -ntp verify` in `worker/` excludes the `ffmpeg` JUnit tag and runs the
unit tests of the playlist reader and the ADR-010 D27 output checks, and those of the probe module:
the bounded process runner against `FakeMediaTool` (deadline and interrupt kill of the tool and its
child, a child that outlives the tool, closed stdin, the PATH/LANG/HOME environment, concurrent
bounded draining), the D26 parsing rules on FFprobe JSON, the settings and the command. The runner
lists the tool's descendants every 100 ms while it runs, so it can kill one that outlives the tool;
the detached-child test runs on POSIX systems only, because a Windows child inherits the tool's pipes.
`sh deploy/tests/worker-ffmpeg-tests.sh`
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
  Issue 5.4 (decided in #194) still passes `WORKER_MAX_STREAMS` and treats the demuxer's own report as
  the cap violation: an FFprobe log line containing `exceeds max_streams` (mov: "Number of streams
  exceeds max_streams parameter", matroska: "Number of tracks exceeds max_streams") fails the probe as
  `MEDIA_LIMIT_EXCEEDED`, also after exit 0. Every log line is inspected while it is drained, so a
  flood of other errors cannot hide it. `FfprobeServiceFfmpegTests` proves it on both nine-stream files.
- The D26 decoder names: where a codec has several decoders FFmpeg opens the first registered one,
  so SubRip uses `srt`, ASS `ssa`, MP3 `mp3float` and AV1 `libdav1d`. `DecoderAllowlistFfmpegTests`
  probes an encoded sample of each of the 22 families with the production FFprobe command, and again
  without that family's entry, which must fail with "Codec (name) not on whitelist".
- FFprobe opens a decoder for every stream, cover art included: the PNG cover art of
  `video-with-cover-art.mp4` fails `-codec_whitelist` ("Codec (png) not on whitelist"), so a file with
  embedded cover art is `UNSUPPORTED_MEDIA` even when it also has a real video stream.

The probe module's FFmpeg-tagged `FfprobeServiceFfmpegTests` runs the FFprobe service over this corpus:
valid families and rotations, key/content mismatch in both directions, moov-at-end truncation,
disguised playlists, audio-only, cover-art-only, cover art beside a real video, decoders outside the
allowlist, a 45-degree rotation, the size, duration and stream caps, and a deadline and an interrupt
that kill the `FakeMediaTool` shell fake and its child.

## Purpose

- prove the upload -> transcode -> playback flow
- map tests back to requirements and architecture
- document manual checks that are not automated yet
- support local and VPS demo readiness

## Boundary

Do not put requirements or architecture source-of-truth here. Link back to `docs/requirements/` and `docs/architecture/` when needed.
