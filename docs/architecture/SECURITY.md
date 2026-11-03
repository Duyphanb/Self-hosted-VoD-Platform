# Security Architecture

Status: Frozen for MVP implementation

## Security Goals

- Protect authenticated user data.
- Restrict admin media-management operations to `ROLE_ADMIN`.
- Prevent unsafe uploads from reaching storage or FFmpeg.
- Keep secrets outside the repository.
- Keep MinIO, PostgreSQL, Redis, RabbitMQ, backend, and worker off the public internet.
- Avoid exposing internal errors, object keys, credentials, stack traces, or sensitive logs.

## Auth Model

MVP uses bearer JWT access tokens plus persisted refresh tokens.

- Access tokens are short-lived JWTs signed with `JWT_SECRET`.
- Refresh tokens use JSON response and request bodies only.
- Refresh tokens are opaque random values stored in PostgreSQL as hashes only.
- Refresh tokens have an expiration timestamp and can be revoked.
- `POST /api/v1/auth/refresh` rotates the refresh token: the old token is revoked and a new token is issued.
- `POST /api/v1/auth/logout` revokes the submitted refresh token when present; the frontend also clears local auth state.
- Passwords are hashed with BCrypt.
- Login passwords share the registration maximum of 72 characters, and refresh and logout tokens are at most 128 characters, both counted in UTF-16 code units. Longer values return 400 `VALIDATION_ERROR` before any lookup or hashing. New hashes pre-hash the full UTF-8 password with SHA-256, so a password longer than 72 bytes is never truncated by BCrypt.
- Login failures return a generic authentication error.
- Email identities are case-insensitive through PostgreSQL `lower(email)` for both lookup and uniqueness. Preserve stored spelling and do not collapse dots or plus tags. Existing identity collisions block migration rather than merging accounts; see [ADR-008](adr/ADR-008.md).

## RBAC Model

Roles:

- `ROLE_USER`
- `ROLE_ADMIN`

Backend enforcement:

- Public endpoints: register, login, refresh.
- Authenticated user endpoints: profile, movies, playback, HLS, progress, watchlist, history, rating, search.
- Admin endpoints: movie mutation, admin movie listing, genre and people management, upload, video asset listing, encoding retry.

Admin endpoints are enforced by URL authorization rules (`/api/v1/admin/**`, `POST /api/v1/movies`, and `PUT`/`DELETE /api/v1/movies/{movieId}`; user sub-resources such as `/api/v1/movies/{movieId}/progress` are not admin endpoints) before request validation runs, with method-level `@PreAuthorize` as a second layer. A `ROLE_USER` request to an admin endpoint returns 403 even when its body is invalid or the target does not exist. A multipart body sent to any operation other than `POST /api/v1/admin/movies/{movieId}/video`, including other methods on that path, is rejected with 400 `MALFORMED_REQUEST` before authentication; the upload operation authorizes from headers before its multipart body is parsed.

Frontend route guards are UX only. Backend authorization is the security boundary.

## Ownership Rules

User-owned data must always be scoped by authenticated user ID:

- profile
- watchlist
- watch history
- playback progress
- ratings

Users must not read or modify another user's records.

## Movie Visibility

Movie visibility is the catalog access rule referenced by playback and HLS checks. See [ADR-009](adr/ADR-009.md).

- A caller without `ROLE_ADMIN` can see only `PUBLISHED` movies.
- Except for the admin operations listed below, every endpoint that takes a `movieId`, read or write (detail, playback, HLS, progress, rating, watchlist), returns the same 404 for a `DRAFT` or `ARCHIVED` movie as for a missing movie. The response must not reveal that the movie exists.
- User-facing lists (movies, search, suggestions, watchlist, history, continue watching) contain only `PUBLISHED` movies, for every role.
- `ROLE_ADMIN` sees and manages every status on `GET`, `PUT`, and `DELETE /api/v1/movies/{movieId}` and under `/api/v1/admin/**`; these are the only exceptions to the 404 rule. For admins, playback, HLS, progress, rating, and watchlist on non-published movies still return 404 unless a later decision changes this rule.
- The catalog module owns one visibility check that other modules reuse; modules must not re-implement the status rule.

## HLS Access Control

MVP HLS access uses the frozen delivery path:

```text
Browser -> Nginx /hls/* -> backend HLS proxy -> MinIO
```

Rules:

- HLS playlist and segment requests require the bearer access token.
- Nginx forwards `Authorization` to backend for `/hls/*`.
- Backend validates JWT, movie visibility, and current READY asset before reading from MinIO. Current is the READY asset with greatest `(created_at, id)` for the movie ([ADR-010](adr/ADR-010.md)).
- Backend streams only objects of that current asset. Missing, hidden, non-ready and superseded assets return 404 on both playlist and segment requests. A switch may interrupt an old stream; the player reopens playback. Derive the attempt prefix from the stored master key and validate segment names; never accept an arbitrary storage key from the client.
- MinIO remains private; HLS is not served through direct MinIO or presigned playback URLs.

## Upload Validation

Upload endpoint safeguards:

- requires authenticated `ROLE_ADMIN`
- validates configured maximum size
- validates allowed MIME types using an allowlist: `video/mp4`, `video/x-matroska`, `video/webm`, and `video/quicktime`
- rejects empty files
- generates server-side object keys
- does not trust user-provided filenames for paths
- stores raw uploads in private MinIO bucket `vod-raw`
- creates status records before queueing work
- logs upload outcome without logging tokens or secrets

Owner-approved 2026-10-29 in #100/#103/#116 ([ADR-010](adr/ADR-010.md)):

- Keep the declared MIME allowlist. Require `ftyp` at offset 4 for the MP4/MOV family, EBML `1A45DFA3` for MKV/WebM, and reject other signatures or family mismatches with 400 `UNSUPPORTED_VIDEO_TYPE`. This rejects renamed text, HLS/ffconcat playlists and legacy QuickTime without `ftyp`; it is not a complete safety check.
- Derive `{ext}` from the allowlisted declared type within the detected family: mp4/quicktime -> mp4/mov, x-matroska/webm -> mkv/webm. MP4 declared as QuickTime remains compatible. Empty/octet-stream declarations are rejected; the SPA fills an empty `File.type` from the allowlisted extension before sending.
- Authorize from headers before multipart parsing. Spring spools at threshold 0B to the dedicated `TEMP_UPLOAD_DIR` in the container layer, then streams to storage with known length and bounded heap. Clean request temp files on completion/abort; validate and sweep only stale regular `upload_*.tmp` files non-recursively before the connector starts (no ApplicationRunner, general `/tmp` sweep or symlink traversal).
- Generate `{movieId}/{videoAssetId}.{ext}` for a new asset per upload. Enforce one in-flight asset per movie with `ux_video_assets_movie_in_flight`; race losers receive 409 `VIDEO_UPLOAD_IN_PROGRESS` and compensate only their own uncommitted raw PUT. Commit new asset/job as QUEUED before publication; publish failure after commit retains media for relay. UPLOADED is reserved.
- Edge upload limit equals backend multipart file/request limit (2048 MiB default); multipart overhead counts toward the total. Other routes have a 1 MiB edge ceiling. [Upload transport](INFRASTRUCTURE.md#upload-transport) defines buffering, idle timeouts, safe ApiError JSON and mandatory consistency checks.
- Browser early errors may appear as status 0. SPA pre-checks complement server enforcement; never replay an upload automatically after 401 or imply Cancel after the body has been sent can cancel committed work. Once the body was fully sent, any 5xx or network error means the upload result is unknown, unless the `ApiError` `code` is `UPLOAD_STORAGE_UNAVAILABLE` (503) or `UPLOAD_DATABASE_ERROR` (500): then the upload was not accepted (no committed asset/job pair), so show a failure and allow manual retry. Edge 502/503/504, `UPLOAD_OUTCOME_UNKNOWN` (raw retained), `INTERNAL_ERROR` and unmapped 500s stay unknown; never infer the result from MovieDetail.videoStatus. Upload error bodies carry a safe `requestId` and no keys, URLs, bucket names or SDK text. Deliberate re-upload is safe: in-flight returns 409 VIDEO_UPLOAD_IN_PROGRESS; after completion it creates a newer asset with extra storage for manual purge. Never auto-resubmit; a direct newest-asset view arrives with #168, not a new endpoint here.

Deferred safeguards:

- virus scanning
- checksum verification
- direct browser-to-storage upload flow

## Media Processing Safeguards

Sprint 5 must enforce the following before passing uploaded bytes to FFprobe/FFmpeg (#103, [ADR-010](adr/ADR-010.md#untrusted-media-handling-d6-d10)):

- Choose `-f mov` for trusted mp4/mov keys or `-f matroska` for mkv/webm keys; never auto-detect a playlist as an input format. Use `-protocol_whitelist file` and an absolute `file:` input path under the owned job directory for both tools. The trusted key extension maps through the fixed ADR-010 table; any other extension fails before a tool runs. Both tools also pass `-max_streams` with `WORKER_MAX_STREAMS` and the D26 `-codec_whitelist`.
- Build `ProcessBuilder` argument lists from typed, validated settings, without a shell or user-controlled codec/argument fragments. Close process stdin for both tools; use FFmpeg `-nostdin`. Do not pass FFmpeg-only `-nostdin` to FFprobe: its [option table](https://github.com/FFmpeg/FFmpeg/blob/n7.1.1/fftools/ffprobe.c) does not define it, unlike the [FFmpeg option table](https://github.com/FFmpeg/FFmpeg/blob/n7.1.1/fftools/ffmpeg_opt.c). Verify the [ADR-010 D25](adr/ADR-010.md#sprint-5-worker-media-decisions-d25-d28) binary in command integration tests. Treat any non-zero exit as failure, and never depend on specific exit values: they differ between FFmpeg 4.4 and 7.1.
- Clear inherited environment except the required `PATH`, `LANG`, `HOME`; drain stdout (`-progress`) and stderr concurrently with bounded readers, so a full pipe can never stall FFmpeg into `ENCODE_TIMEOUT`, and bound FFprobe JSON output. Do not expose raw diagnostics, local paths, media metadata secrets or credentials through errors/logs.
- Enforce env-driven FFprobe timeout, duration/width/height/stream-count caps and FFmpeg timeout `max(WORKER_FFMPEG_TIMEOUT_MIN_SECONDS, WORKER_FFMPEG_TIMEOUT_FACTOR * durationSeconds)`. Kill the process tree on deadline. The product caps are owner policy (ADR-010 D26: 3 hours, long side 1920, short side 1080, 8 streams, either orientation); deadline values are **to be measured using #124**, not copied from research examples. Settings are listed in [INFRASTRUCTURE.md](INFRASTRUCTURE.md#upload-and-encoding-settings). A codec check on the FFprobe JSON does not protect decoders opened during probing; the D26 `-codec_whitelist` does, but it does not restrict demuxers or parsers. Select the first video stream that is not an attached picture; use its duration, or the format duration when it has none, as the one media duration for caps, deadline, disk budget and output validation; require that duration and the rotation-corrected dimensions to be finite and positive; and accept only 0/90/180/270-degree rotation after rounding and normalizing modulo 360 (FFprobe reports −90 for a 270-degree Display Matrix).
- Bound storage/JDBC operations too. Check free space twice as ADR-010 D27 specifies (before download: raw size plus reserve; after probe: reserve plus max(B, 2B − R)), and stop with `INSUFFICIENT_DISK` when a watchdog sees free space fall below the reserve; use a job-scoped directory. Remove the downloaded raw copy after FFmpeg exits, before HLS upload. Clean owned `job-*` directories before starting the listener and on every job exit; do not follow symlinks or permit directory escape.
- Exit 0 is not success. Before READY, validate the HLS output: `#EXT-X-PLAYLIST-TYPE:VOD` and `#EXT-X-ENDLIST`; at least one segment; every URI matches `^segments/[A-Za-z0-9._-]+\.ts$` and names an existing non-empty file; no unreferenced file; and the EXTINF sum is within 1 second of the ADR-010 D26 media duration. Otherwise fail with `ENCODE_FAILED`. FFmpeg runs with `-xerror`.
- Use exactly one worker replica with concurrency/prefetch 1, no overlapping recreation, and a 30s stop grace. Startup conditionally fails orphaned PROCESSING pairs as `WORKER_INTERRUPTED` before consumption. Conditional DB claim/finish bounds a crashing input; DB outages hold delivery with capped in-process backoff, never a poison-message requeue loop.
- Issue 5.8 requires a non-root user, read-only root, a disk-backed writable volume (not tmpfs) for `WORKER_TEMP_DIR`, guarded by the raw-size free-disk check, `cap_drop: ALL`, `no-new-privileges` and a validated `pids_limit`. It adds/documents that volume in INFRASTRUCTURE. The FFmpeg source is [ADR-010 D25](adr/ADR-010.md#sprint-5-worker-media-decisions-d25-d28): Debian trixie packages covered by Debian security advisories, rebuilt on each advisory. Issue 5.8 records the package versions, the version floor and the runtime uid.

Residual MVP risk accepted in ADR-010: a decoder exploit still shares the worker's network and can reach service credentials/process data. MIME/signature/protocol checks do not isolate a decoder RCE. Stronger per-invocation namespace/network isolation (for example nsjail/bubblewrap/gVisor) is post-MVP; known decoder fixes are applied as soon as the selected source ships them. CVEs that Debian postpones between advisories are assessed per ADR-010 D25 (reachability, monthly review, 90-day expiry, switch to an artifact with the fix), and the D26 decoder allowlist reduces the reachable decoders.

Planned negative fixtures (#123) cover:
- a key/content family mismatch (EBML bytes under a `.mp4` key);
- renamed HLS/ffconcat;
- truncated media (moov at the end → `PROBE_FAILED`; faststart → output-validation failure);
- audio-only and cover-art-only ISO-BMFF;
- a decoder outside the allowlist;
- caps;
- deadline and process-tree kill, including a fake binary that spawns a grandchild;
- insufficient disk.

The worker failure codes are fixed in ADR-010 D28: `PROBE_FAILED`, `PROBE_TIMEOUT`, `UNSUPPORTED_MEDIA`, `MEDIA_LIMIT_EXCEEDED`, `ENCODE_FAILED`, `ENCODE_TIMEOUT`, `INSUFFICIENT_DISK`, `STORAGE_ERROR`, `WORKER_INTERRUPTED` and `INTERNAL_ERROR`. #107 adopts this enum and owns the safe messages, redaction and length cap.

## Object Storage Security

MinIO buckets are private by default.

PGSTY Silo currently implements this MinIO-compatible contract ([ADR-004 addendum](adr/ADR-004.md#runtime-addendum)). Server and init use the same immutable image digest, with source-bound signed provenance and architecture SBOM verification in CI. `MINIO_BROWSER: "off"` disables the admin console in both local and production Compose; storage publishes no ports. #169 separates dedicated backend/worker identities from root credentials reserved for bootstrap and explicit operator maintenance, with the exact actions/resources in [INFRASTRUCTURE.md](INFRASTRUCTURE.md#storage-identities-and-policies). Production rejects defaults and reused identities/secrets. The scoped policies grant no bucket/general IAM administration or thumbnail access and explicitly deny `admin:CreateServiceAccount` and `admin:ChangeMyPassword`, including self-targeted requests that Silo otherwise allows. Silo policy cannot block regular-user STS AssumeRole; a compromised parent's STS credentials require removal of that user, not only a new secret. Follow the [rotation and revocation runbook](../deployment/README.md#storage-identity-rotation-and-revocation), including retired-user removal after restore. Running the server image as non-root remains #54.

- `vod-raw` contains original uploads and is never public.
- `vod-hls` contains generated HLS artifacts and is read only by backend HLS proxy.
- `vod-thumbnails` is reserved for the deferred thumbnail feature.
- Backend and worker use environment-provided MinIO credentials.
- Object keys must be generated by the backend or worker and stored in PostgreSQL.
- Cleanup accepts only the exact server-generated request key for uncommitted upload compensation, or DB-derived validated terminal attempt prefixes. Prefix deletes end in `/`, refuse bucket roots/current/non-terminal assets and do not follow client-supplied paths. Retain archive media, current media, newest FAILED raw and in-flight objects. [Manual purge](../deployment/README.md#media-purge-and-orphan-audit) requires reviewed candidates, dry-run and quiesced writers; DB rows remain history.

## Secret Handling

Never commit:

- real `.env`
- JWT secrets
- database passwords
- MinIO credentials
- RabbitMQ credentials
- SSH keys
- cloud credentials

`.env.example` must contain placeholders only.

## API Safeguards

Backend must:

- validate request bodies with Spring Validation
- reject unsupported content types
- enforce upload request size limits equal to the upload edge ceiling; non-upload edge routes stay at 1 MiB
- use consistent error responses
- avoid stack traces in production responses
- use ORM/query parameter binding, not string-concatenated SQL
- keep CORS allowlist environment-driven
- document auth requirements in OpenAPI

Recommended after MVP-local works:

- rate limiting for login, search, and upload
- dependency vulnerability scanning in CI

## Frontend Safeguards

Frontend must:

- clear auth state on logout or refresh failure
- attach bearer tokens to API and HLS requests
- avoid rendering unsafe HTML
- treat API authorization failures as expected states
- hide admin UI for non-admin users while relying on backend for enforcement

## Production Safeguards

Single VPS production demo must include:

- HTTPS through Nginx
- non-default production secrets
- internal Docker network for backend, worker, PostgreSQL, Redis, RabbitMQ, and MinIO
- only ports 80 and 443 publicly exposed
- backups documented before demo data matters
- no verbose error responses in production profile

Nginx headers for MVP:

- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: no-referrer`

CSP and HSTS are deferred until frontend asset paths and HTTPS behavior are verified on the VPS.

## Security Test Expectations

Minimum security evidence:

- anonymous requests rejected for protected endpoints
- user requests rejected for admin endpoints
- non-admin requests for a non-published movie return the same 404 as a missing movie
- user-owned resources cannot be accessed by another user
- HLS requests without bearer token are rejected
- upload rejects invalid MIME type, signature-family mismatch (renamed text, HLS/ffconcat playlists, legacy MOV without `ftyp`) and oversize files
- worker negative fixtures (#123) fail safely with the ADR-010 D28 codes, open no referenced file, never reach READY with partial or cover-art-only output, and leave no process or job directory behind after a deadline kill
- auth failures do not reveal whether email or password was wrong
- `.env.example` contains no real secret values
