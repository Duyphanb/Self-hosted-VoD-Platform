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
- Refresh tokens travel only in the HttpOnly `vod_refresh` cookie (ADR-012 Issue 6.9).
- Refresh tokens are opaque random values stored in PostgreSQL as hashes only.
- Refresh tokens have an expiration timestamp and can be revoked.
- `POST /api/v1/auth/refresh` rotates the refresh token: the old token is revoked and a new token is issued.
- A successful `POST /api/v1/auth/logout` revokes the cookie's session and clears the cookie. The SPA clears memory
  immediately and keeps a pending marker until the server confirms logout.
- Passwords are hashed with BCrypt.
- Login passwords share the registration maximum of 72 characters (UTF-16 code units); longer passwords return 400 `VALIDATION_ERROR`. Cookie values longer than 128 characters are rejected before hashing: refresh returns 401 `UNAUTHORIZED` and clears the cookie; logout ignores them and clears the cookie. New hashes pre-hash the full UTF-8 password with SHA-256, so a password longer than 72 bytes is never truncated by BCrypt.
- Login failures return a generic authentication error.
- Email identities are case-insensitive through PostgreSQL `lower(email)` for both lookup and uniqueness. Preserve stored spelling and do not collapse dots or plus tags. Existing identity collisions block migration rather than merging accounts; see [ADR-008](adr/ADR-008.md).

### Browser session (ADR-012)

[ADR-012](adr/ADR-012.md) supersedes ADR-007.

- The host-only `vod_refresh` cookie uses `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`, with `Max-Age` equal
  to the remaining session lifetime. It stays out of request and response bodies and browser storage. The local
  loopback-only exception for `Secure` is rejected by production preflight. Duplicate cookies are invalid. If a third
  party controls any subdomain of the deployment's domain, ADR-012 requires `__Host-vod_refresh` with `Path=/`.
- The access token is kept in JavaScript memory only and sent only in the `Authorization` header. No cookie authenticates `/api/v1/*` resources or `/hls/*`.
- `refresh` and `logout` take no body and require an allowed `Origin`. `login` and `register` reject a disallowed
  `Origin` when present. `Sec-Fetch-Site: cross-site` is rejected on all four with 403 `ORIGIN_NOT_ALLOWED`.
- Successful login revokes the session of one valid presented cookie. Login ignores duplicate cookies. Refresh
  rejects duplicates with 401 and clears the cookie. Logout clears duplicate cookies without selecting a session.
- The SPA serializes login, refresh/bootstrap and logout with the Web Lock `vod.auth.session.v1`; request timeouts abort inside that lock. Without Web Locks only the documented single-tab fallback is supported. Storage contains only a versioned generation/user-id/logout-pending marker. Every protected request checks it before sending and committing; focus, visibility and pageshow reconcile memory with it. BroadcastChannel only wakes tabs.
- Only refresh 401 ends the local session. Network failures, timeouts and 5xx allow retry. An unconfirmed logout stays pending across reloads and retries logout instead of restoring a session. Blocked storage falls back to memory with a visible notice.
- Local Compose keeps `Secure=true`: Chromium and Firefox accept and send this cookie over loopback HTTP (`localhost` and `127.0.0.1`). Plain HTTP on a LAN address cannot sign in; use HTTPS (#136). The production preflight requires a nonempty public-origin allowlist and `AUTH_COOKIE_SECURE=true`.

- Each sign-in is a server-side session with an absolute 7-day default lifetime (allowed 1–30 days). Rotation keeps the
  same expiry. The 11th active session evicts the oldest. Replaying a rotated refresh token revokes its entire session.
- Access tokens live 5 minutes by default (allowed 1–15), have a 5-second clock skew, and carry `sid` and `ver`.
  Bearer requests validate session ownership, expiry, revocation, account status and `auth_epoch`. Cache entries can
  authorize only for 30 seconds from load start. Backend revocations invalidate after commit. Operator SQL changes
  apply within 30 seconds, with refresh and admin operations reading PostgreSQL directly on each request.
- Admin writes and `/api/v1/admin/**` require both the signed `ROLE_ADMIN` claim and the current database role.
  Authentication state failures return 503 `AUTH_STATE_UNAVAILABLE` and preserve the cookie. Invalid sessions return
  401 `UNAUTHORIZED`. A direct HLS 503 uses the existing player retry path.
- Disabling an account, changing roles or signing out everywhere increments `auth_epoch` and revokes the account's
  sessions in one transaction. See the [deployment runbook](../deployment/README.md).

### Personal data in the auth store

`auth_sessions` holds the session and account IDs and creation, expiry and revocation timestamps. `refresh_tokens`
holds token hashes, account and session IDs, and lifecycle timestamps. A daily job removes sessions that ended more
than 30 days earlier, cascades their refresh rows and removes legacy rows without a session. Tokens and cookies stay
out of logs. A session ID may appear as an identifier.

## RBAC Model

Roles:

- `ROLE_USER`
- `ROLE_ADMIN`

Backend enforcement:

- Public endpoints: register, login, refresh.
- Authenticated user endpoints: profile, movies, playback, HLS, progress, watchlist, history, rating, search.
- Admin endpoints: movie mutation, admin movie listing, genre and people management, upload, video asset listing, encoding retry.

Admin endpoints are enforced by URL authorization rules (`/api/v1/admin/**`, `POST /api/v1/movies`, and `PUT`/`DELETE /api/v1/movies/{movieId}`; user sub-resources such as `/api/v1/movies/{movieId}/progress` are not admin endpoints) before request validation runs, with method-level `@PreAuthorize` as a second layer. A `ROLE_USER` request to an admin endpoint returns 403 even when its body is invalid or the target does not exist. A multipart body sent to any operation other than `POST /api/v1/admin/movies/{movieId}/video`, including other methods on that path, is rejected with 400 `MALFORMED_REQUEST` before authentication; the upload operation authorizes from headers before its multipart body is parsed.

Planned ([ADR-013](adr/ADR-013.md), #244): with S2 the catalog writes move under `/api/v1/admin/titles`, so the URL rules become `/api/v1/admin/**` alone, and the multipart exception moves to `POST /api/v1/admin/episodes/{episodeId}/video`. The old upload path is not kept: the previous SPA build checks the movie before sending a body, so an upload whose check starts after the cutover stops before the removed path. A stale tab whose check succeeded before it gets `400 MALFORMED_REQUEST` (body within the 1 MiB edge limit) or `413` and nothing is stored; reloading recovers ([ADR-013 D13](adr/ADR-013.md#d13-migration-cutover-and-rollback)). With S5, `/hls/admin/**` joins the admin URL rules ([Admin Preview routes](#planned-admin-preview-routes-adr-013)).

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

### Planned: content visibility (ADR-013)

[ADR-013 D3](adr/ADR-013.md#d3-visibility-at-three-levels) replaces this section's movie rule when its slices land (titles and episodes in S2, franchises in S3):

- An episode is visible when it and its title are `PUBLISHED`; a title when it is `PUBLISHED`. A franchise's status gates only its own page, strip, references in title responses and name in search, never its titles. The episode of a `MOVIE` title is always `PUBLISHED` and follows its title.
- Every viewer endpoint that takes a franchise, title or episode ID returns the same 404 for a hidden item as for a missing one, and lists and counts contain only visible items, for every role.
- The admin exceptions are exactly `/api/v1/admin/**` and, from S5, `/hls/admin/**`. `GET /api/v1/titles/{titleId}` is published-only for every role.
- The catalog module keeps one visibility check for all three levels.

### Planned: search and discovery (ADR-015)

[ADR-015](adr/ADR-015.md) (#128) adds search on titles, title suggestions, similar titles and one personal Home row. Each rule takes effect with the slice that ships the feature:

- **Eligibility.** Results, suggestions, similar titles and the personal row contain only visible titles, for every role, through the catalog's visibility check; counts exclude hidden titles before paging. A hidden franchise's name never matches, never appears as `matchedName` and never appears on a card. Suggestions name titles only, never genres or people, so a genre or person linked only to an unpublished title is never revealed ([ADR-015 D1, D10](adr/ADR-015.md#d1-what-search-returns-to-whom)).
- **Caller-only signals.** `GET /api/v1/me/recommendations` reads only the caller's own ratings, history and progress, takes no user parameter, stores and logs nothing, uses no other account's data and answers with `Cache-Control: no-store`. Admin Preview creates no history, so it never seeds a recommendation ([ADR-015 D13](adr/ADR-015.md#d13-the-personal-row-rc-2-backlog-issue-814)).
- **Input.** `q` is trimmed, holds 1-100 code points and no control character (400 `VALIDATION_ERROR` otherwise), and only ever reaches SQL as a bound parameter; full text uses `plainto_tsquery`, never `to_tsquery` on user input, and matching patterns are built from normalized keys that cannot contain `%`, `_` or `\` ([ADR-015 D6](adr/ADR-015.md#d6-query-input)).
- **No search terms in logs.** Nginx's access logs record the path without its query string; the two search API locations and the named error locations discard their error log, because Nginx appends the request line to every error at any severity (one accepted residual: an edge memory-allocation failure while parsing headers); the SPA keeps search state in the URL fragment, which never reaches a server; the backend logs no request parameter, and SQL bind logging stays off ([ADR-015 D15](adr/ADR-015.md#d15-privacy-logs-and-caching)). `Referrer-Policy: no-referrer` stays.
- **Provider data.** Search never calls a provider. TMDB-origin values and provider descriptions are never indexed, and filters and similar titles ignore TMDB-origin values until ADR-014 (planned) allows each use ([ADR-015 D11](adr/ADR-015.md#d11-provider-data)).
- **Planned evidence.** DRAFT and ARCHIVED titles absent from items, counts and `matchedName` for `ROLE_USER` and `ROLE_ADMIN`; a person and a genre linked only to a DRAFT title match and suggest nothing; two users' recommendations isolated; `no-store` on the personal row; a marker query absent from the Nginx access and error logs, also with the backend stopped; a hidden franchise's edits never change results or their order.

## HLS Access Control

MVP HLS access uses the frozen delivery path:

```text
Browser -> Nginx /hls/* -> backend HLS proxy -> MinIO
```

Rules:

- HLS playlist and segment requests require the bearer access token.
- The token is accepted only in the `Authorization` header, on `/hls/*` and `/api/v1/*` alike. An `access_token` query or form parameter is never accepted and no cookie carries an access token, so access tokens never reach URLs, logs or browser history ([ADR-011 D3](adr/ADR-011.md#player-transport-and-authentication-d1-d4)).
- Nginx forwards `Authorization` to backend for `/hls/*`.
- Backend validates JWT, movie visibility, and current READY asset before reading from MinIO. Current is the READY asset with greatest `(created_at, id)` for the movie ([ADR-010](adr/ADR-010.md)).
- Backend streams only objects of that current asset. Missing, hidden, non-ready and superseded assets return 404 on both playlist and segment requests. A switch may interrupt an old stream; the player reopens playback. Derive the attempt prefix from the stored master key and validate segment names; never accept an arbitrary storage key from the client.
- HLS responses, including errors, carry `Cache-Control: no-store`, and Nginx does not cache them: a shared browser must not serve media from its cache after logout, and the next request after a current-asset switch is refused. Media the player already buffered can still play. Every not-found case the application validates returns the same 404 `VIDEO_ASSET_NOT_FOUND`, including a malformed asset ID or segment name.
- Native HLS playback (`video.src`), AirPlay and casting cannot send the header and are unsupported; supporting them needs a new ADR, not a URL token.
- MinIO remains private; HLS is not served through direct MinIO or presigned playback URLs.

Planned ([ADR-013](adr/ADR-013.md)): from S2 "current" and visibility are per episode (the episode and its title must be `PUBLISHED`). From S5 the only playable asset of an episode is the one the admin activated; a READY candidate gets the same 404 as any other non-current asset on the viewer routes.

### Planned: Admin Preview routes (ADR-013)

Owner decision of 2026-11-25: Admin Preview is an exception for checking an encode and entering timed metadata before viewers see it. It lands with activation in ADR-013 S5 ([D9](adr/ADR-013.md#d9-admin-preview-and-the-activation-enablement-set-s5)).

| Route | Caller | Checked on every request | Responses |
|---|---|---|---|
| `GET /api/v1/admin/video-assets/{videoAssetId}/preview` | `ROLE_ADMIN`, session and database role read directly | asset exists, is READY and is not retired (its objects may be purged), whatever the episode and title status | 200 `PreviewInfo`; 401; 403; 404 `VIDEO_ASSET_NOT_FOUND`; 503 `AUTH_STATE_UNAVAILABLE` |
| `GET /hls/admin/video-assets/{videoAssetId}/master.m3u8` | same | as above, plus the stored master key pattern | 200 whole playlist; 401; 403; 404 `VIDEO_ASSET_NOT_FOUND`; 503 `HLS_STORAGE_UNAVAILABLE` or `AUTH_STATE_UNAVAILABLE` |
| `GET /hls/admin/video-assets/{videoAssetId}/segments/{segmentName}` | same | as above, plus the segment name pattern before storage is read | as the playlist |
| `POST /api/v1/admin/video-assets/{videoAssetId}/activation` | same | episode lock, expected generation, READY, not active and not retired; `SAME` needs an active asset | 200; 400; 401; 403; 404; 409 `ACTIVATION_CONFLICT`, `VIDEO_ASSET_NOT_ACTIVATABLE` or `ACTIVATION_CARRY_INVALID`; 503 |

- `/hls/admin/**` joins `ADMIN_ENDPOINTS`, so the admin authentication path reads the session and the database role on every request ([ADR-012](adr/ADR-012.md)); a demoted or signed-out admin is refused on the next request. A `ROLE_USER` caller gets 403 before any asset lookup.
- Every `/hls/admin/*` response, denials included, carries `Cache-Control: no-store`; `HlsRouteFilter` sets it before security runs and logs 401, 403 and 404 denials with the request ID and asset ID, never the token. The bearer token is accepted only in the `Authorization` header, as on the viewer routes. Nginx's `/hls/` location already forwards these paths.
- Preview never writes viewer state: the Preview player sends no progress or history request, and the viewer progress route accepts only the active asset of a visible episode.
- The viewer routes are not loosened: `/hls/video-assets/...` still returns 404 for candidates, superseded assets and hidden content, also for admins.
- Each preview-info call and each activation logs one INFO event with the admin's user ID, the asset and the request ID; this is the audit record.
- Evidence (S5): `ROLE_USER` and a demoted admin get 403 with `no-store` on every `/hls/admin/**` path; a revoked session gets 401; Preview produces zero progress and history requests and rows, including on `pagehide`; viewer routes still return 404 for a candidate.

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
- Browser early errors may appear as status 0. SPA pre-checks complement server enforcement; never replay an
  upload automatically after 401 or imply Cancel after the body has been sent can cancel committed work. Once
  the body was fully sent, any 5xx or network error means the upload result is unknown, with three confirmed
  pre-acceptance exceptions. These are `AUTH_STATE_UNAVAILABLE` (503 before multipart parsing),
  `UPLOAD_STORAGE_UNAVAILABLE` (503), and `UPLOAD_DATABASE_ERROR` (500). Show a failure and allow manual retry
  for those responses. Edge 502/503/504, `UPLOAD_OUTCOME_UNKNOWN` (raw retained), `INTERNAL_ERROR` and unmapped
  500s stay unknown; never infer the result from MovieDetail.videoStatus. Upload error bodies carry a safe
  `requestId` and no keys, URLs, bucket names or SDK text. Deliberate re-upload is safe: in-flight returns 409
  VIDEO_UPLOAD_IN_PROGRESS; after completion it creates a newer asset with extra storage for manual purge. Never
  auto-resubmit; a direct newest-asset view arrives with #168, not a new endpoint here.
- Planned ([ADR-013](adr/ADR-013.md) S2): the upload operation becomes `POST /api/v1/admin/episodes/{episodeId}/video` with these safeguards unchanged. The raw key is `{titleId}/{videoAssetId}.{ext}` (unchanged for existing movies, whose UUID the title keeps), and `ux_video_assets_episode_in_flight` enforces one in-flight asset per episode.

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
- Container hardening (Issue 5.8):
  - The worker image runs as the fixed non-root uid/gid 10001 (`vodworker`, no login shell or home).
  - Both Compose files run it with `read_only: true`, `cap_drop: [ALL]`, `no-new-privileges:true`, a pids limit of 256 under `deploy.resources.limits` (the limit counts threads), `scale: 1` and `stop_grace_period: 30s`.
  - Its only configured writable volume is the disk-backed `worker_temp` on `/tmp` ([Persistent Volumes](INFRASTRUCTURE.md#persistent-volumes)), which holds `WORKER_TEMP_DIR` and `java.io.tmpdir`, guarded by the raw-size free-disk check. No tmpfs is configured. Docker's implicit `/dev/shm` stays writable, so `WORKER_TEMP_DIR` must stay below `/tmp` (an operator requirement that the stack smoke checks).
  - The FFmpeg source is [ADR-010 D25](adr/ADR-010.md#sprint-5-worker-media-decisions-d25-d28): Debian trixie packages covered by Debian security advisories, rebuilt on each advisory and weekly.
  - `worker/Dockerfile` records the ffmpeg version floor (`FFMPEG_MIN_VERSION`). Every build fails below it, and `deploy/tests/worker-image-check.sh` asserts the floor and the non-root uid in CI and logs the installed versions.
  - [Worker runtime image](INFRASTRUCTURE.md#worker-runtime-image) has the versions, the pids sizing and the update procedure.

Residual MVP risk accepted in ADR-010: a decoder exploit still shares the worker's network and can reach service credentials/process data. Since #106 those are only the worker's own [scoped identities](#service-identities-and-network-isolation), and the worker's network has no egress. MIME/signature/protocol checks do not isolate a decoder RCE. Stronger per-invocation namespace/network isolation (for example nsjail/bubblewrap/gVisor) is post-MVP; known decoder fixes are applied as soon as the selected source ships them. CVEs that Debian postpones between advisories are assessed per ADR-010 D25 (reachability, monthly review, 90-day expiry, switch to an artifact with the fix), and the D26 decoder allowlist reduces the reachable decoders.

Planned negative fixtures (#123) cover:
- a key/content family mismatch (EBML bytes under a `.mp4` key);
- renamed HLS/ffconcat;
- truncated media (moov at the end → `PROBE_FAILED`; faststart → output-validation failure);
- audio-only and cover-art-only ISO-BMFF;
- a decoder outside the allowlist;
- caps;
- deadline and process-tree kill, including a fake binary that spawns a grandchild;
- insufficient disk.

The worker failure codes are fixed in ADR-010 D28: `PROBE_FAILED`, `PROBE_TIMEOUT`, `UNSUPPORTED_MEDIA`, `MEDIA_LIMIT_EXCEEDED`, `ENCODE_FAILED`, `ENCODE_TIMEOUT`, `INSUFFICIENT_DISK`, `STORAGE_ERROR`, `WORKER_INTERRUPTED` and `INTERNAL_ERROR`. [ADR-011 D18](adr/ADR-011.md#contract-reconciliation-d15-d18) adopts this enum (#107): the database stores only the code, admin responses carry a fixed safe message from [OBSERVABILITY.md](OBSERVABILITY.md#failure-visibility), and viewers never see either.

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
- Redis password
- SSH keys
- cloud credentials

`.env.example` must contain placeholders only.

## Service Identities and Network Isolation

Decision (#106, extending the #169 storage split): every application holds only its own least-privilege identity for each data store, so a SQL injection in the backend or a parser exploit in the worker does not become a full data-plane compromise. Bootstrap identities (the PostgreSQL superuser, the RabbitMQ administrator and the storage root) stay with their servers, one-shot initialization and explicit operator maintenance; Flyway, the backend and the worker never receive them.

- PostgreSQL: Flyway migrates as a dedicated schema owner. The backend role has DML only on the application tables, with no DDL, `TRUNCATE`, temporary tables or migration-history access. The worker role can only read and update `encoding_jobs` and `video_assets` (the ADR-010 claim, finish and startup recovery). No application role is a superuser, creates roles or databases, bypasses RLS or holds a role membership, and `PUBLIC` has no database or schema access.
- RabbitMQ: the backend and worker users have no management tag and are limited to the ADR-010 encoding queue, its DLX and DLQ on vhost `/`. Only the backend can publish jobs; neither can read the DLQ, which stays an operator task.
- Silo: the scoped backend/worker policies of [#169](#object-storage-security).
- Redis: the server always requires a password, and only the backend receives it.
- Network: only Nginx/frontend share `vod_edge`; only Nginx/backend share the IPv4 `vod_proxy` link. Backend also joins `vod_internal` with the data stores, init jobs and worker; those peers have no egress or published ports. Frontend cannot reach backend, and private internal peers cannot supply trusted forwarded values.

Production preflight rejects the published development identities and secrets, a user name reused by two identities of one service, a secret reused by any two identities, PostgreSQL role names that the init scripts cannot quote safely, and an application datasource URL that differs from the database Flyway migrates. It reports variable names only. The exact roles, grants, permission patterns and network membership are in [INFRASTRUCTURE.md](INFRASTRUCTURE.md#service-identities-and-network); rotation is in the [deployment runbook](../deployment/README.md#service-identity-rotation). Applications held the bootstrap PostgreSQL and RabbitMQ identities before #106, so an existing deployment also rotates those after adopting the split.

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

## Forwarded Headers and Same-Origin Requests

Nginx is the only published edge; the backend port must never be published. Nginx overwrites
`X-Forwarded-For` with its single `$remote_addr`, `X-Forwarded-Host` with `$host`,
`X-Forwarded-Port` with the browser's explicit Host port (otherwise 80 for HTTP or 443 for HTTPS),
and `X-Forwarded-Proto` with `$scheme`. It clears RFC `Forwarded`; client-supplied forwarded
values cannot override this view. The nested upload route inherits these directives.

Owner decision of 2026-11-19 (PR #236 review): the backend uses Tomcat's native `RemoteIpValve`,
honouring these values **only from Nginx's fixed address `/32` on `vod_proxy`**. Compose derives
`FORWARDED_TRUSTED_PROXIES` from `VOD_PROXY_NGINX_IP`, so they cannot drift. A private address alone
confers no trust; even an internal worker/data peer that reaches backend cannot override the
forwarded origin or client IP. Outside Compose the default is exactly `127.0.0.1/32,::1/128`;
`trusted-proxies` remains unset. Keep backend unpublished. Same-origin requests, including a non-default
published port, are not CORS requests. The environment-driven CORS allowlist remains required for
cross-origin clients, with `allowCredentials=false`. ADR-012's separate auth Origin check is owned
by Issue 6.9 and is not replaced by this CORS behavior.

A future load balancer or CDN before Nginx requires an explicit `real_ip` configuration and a
review of the client-IP/forwarded-header trust boundary (#51, #136).

## Frontend Safeguards

Frontend must:

- after Issue 6.9, keep the access token in memory only and never store a token in web storage; run login, refresh and logout inside one cross-tab lock from request to response; keep a non-secret generation marker in `localStorage` and check it before sending and before committing; bootstrap a page load with one refresh call; keep a "logout pending" flag until the server confirms the logout ([ADR-012 D7](adr/ADR-012.md#frontend-session-lifecycle-d7-issue-69))
- clear auth state on logout, or when the server rejects the refresh token (401); a refresh that fails by a network error, a timeout or a 5xx keeps the session and shows a retryable error ([ADR-011 D2](adr/ADR-011.md#player-transport-and-authentication-d1-d4))
- attach bearer tokens to API and HLS requests
- play HLS only through hls.js: check `Hls.isSupported()` first, never assign the manifest to `video.src`, and show an unsupported-browser state otherwise ([ADR-011 D1](adr/ADR-011.md#player-transport-and-authentication-d1-d4))
- read the current token for every HLS request; on a 401, run the shared `ApiClient` recovery once (retry with a newer current token, or join the single-flight refresh) and retry that request once; treat a 401 on that retry, or a refresh rejected with 401, as an expired session; destroy the player when the signed-in identity changes (ADR-011 D2)
- never put a token in a URL and never use `navigator.sendBeacon` for an authenticated call; progress saves at unload use `fetch` with `keepalive` and the header
- avoid rendering unsafe HTML
- treat API authorization failures as expected states
- hide admin UI for non-admin users while relying on backend for enforcement

## Production Safeguards

Single VPS production demo must include:

- HTTPS through Nginx
- non-default production secrets
- internal Docker network (`internal: true`) for backend, worker, PostgreSQL, Redis, RabbitMQ and MinIO; Nginx/frontend share only the edge network, while Nginx/backend share a dedicated IPv4 proxy link (#94/#106)
- least-privilege service identities and an authenticated Redis, as in [Service Identities and Network Isolation](#service-identities-and-network-isolation)
- only ports 80 and 443 publicly exposed
- backups documented before demo data matters
- no verbose error responses in production profile

Nginx headers for MVP:

- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: no-referrer`

CSP and HSTS are deferred until frontend asset paths and HTTPS behavior are verified on the VPS.
Spring Security's HSTS writer is disabled on both security chains, including forwarded HTTPS
requests, so enabling HSTS remains an edge decision.

## Security Test Expectations

Minimum security evidence:

- anonymous requests rejected for protected endpoints
- user requests rejected for admin endpoints
- non-admin requests for a non-published movie return the same 404 as a missing movie
- user-owned resources cannot be accessed by another user
- HLS requests without bearer token are rejected
- a valid token sent only as an `access_token` query parameter is rejected with 401 on HLS and API paths
- HLS requests for a superseded, non-READY or hidden movie's asset, a malformed asset ID and an unsafe segment name all return the same 404
- a user cannot read or write another user's playback progress or watch history
- upload rejects invalid MIME type, signature-family mismatch (renamed text, HLS/ffconcat playlists, legacy MOV without `ftyp`) and oversize files
- worker negative fixtures (#123) fail safely with the ADR-010 D28 codes, open no referenced file, never reach READY with partial or cover-art-only output, and leave no process or job directory behind after a deadline kill
- auth failures do not reveal whether email or password was wrong
- each service identity can perform only its own data-store operations, Redis refuses unauthenticated clients, and Nginx cannot reach an internal service (#106)
- `.env.example` contains no real secret values
