# System Architecture

Status: Frozen for MVP implementation

## Architecture Decision Summary

The VoD Platform is a modular monolith plus worker system.

- `backend` is the Spring Boot core API and owns business workflows.
- `worker` is a separate runtime for media processing only.
- `frontend` is the React web client served through Nginx in the MVP runtime.
- PostgreSQL is the source of truth for domain data.
- Redis is used only for cache and playback-progress buffering.
- RabbitMQ is used only for asynchronous video encoding jobs.
- MinIO is private object storage for raw video and generated HLS artifacts.
- Nginx is the single public edge for local Compose and VPS runtime traffic.

This is not a microservices architecture. Backend and worker are separate runtimes inside one modular system, not independently owned services.

## Frozen HLS Delivery Path

MVP HLS delivery uses one path only:

```text
Browser
  -> Nginx /hls/*
  -> backend HLS authorization/proxy endpoint
  -> MinIO private HLS object
```

Rules:

- Nginx is the public edge for HLS requests and forwards them to backend.
- Backend validates the JWT and movie/video access for HLS playlist and segment requests.
- Backend reads the HLS object from MinIO and streams it back through Nginx.
- The frontend HLS player must send the same bearer access token on HLS requests.
- No public MinIO, direct object-storage, signed URL, or presigned playback path exists in MVP.

This path favors security and clarity over maximum streaming throughput. It is acceptable for the single-quality MVP and single VPS target.

## Runtime View

```text
Browser
  |
  v
Nginx
  |-- /                  -> frontend static app
  |-- /api/v1/*          -> backend JSON API
  |-- /hls/*             -> backend HLS proxy

backend
  |-- PostgreSQL         -> users, movies, playback, jobs, ratings
  |-- Redis              -> cache and progress buffer
  |-- RabbitMQ           -> encoding job publish
  |-- MinIO              -> raw upload and HLS read proxy

worker
  |-- RabbitMQ           -> encoding job consume
  |-- PostgreSQL         -> status and metadata update
  |-- MinIO              -> read raw video, write HLS artifacts
  |-- FFprobe            -> media metadata extraction
  |-- FFmpeg             -> single-quality HLS generation
```

## Module Boundaries

| Module | Responsibility | Must Not Own |
|---|---|---|
| `frontend` | Routing, UI state, auth guards, playback UI, admin screens | Business authorization decisions |
| `backend` | Auth, RBAC, catalog APIs, upload orchestration, queue publish, HLS authorization/proxy, progress APIs | FFmpeg execution |
| `worker` | Queue consume, media probing, single-quality HLS transcoding, HLS upload, status transitions | User-facing APIs |
| `deploy` | Compose, Nginx, runtime wiring, local/VPS config | Application business logic |
| `docs` | Requirements, architecture, testing, deployment decisions | Generated implementation output |

## Backend Internal Modules

Planned backend modules:

- `auth`: registration, login, refresh, logout, current user
- `user`: profile and account metadata
- `catalog`: movie, genre, people, credits
- `upload`: video upload validation and raw object storage
- `encoding`: video asset status, job publish, admin retry
- `playback`: playback info and access checks
- `stream`: authenticated HLS playlist/segment proxy
- `progress`: progress buffering and persistence orchestration
- `library`: watchlist, watch history, rating
- `search`: PostgreSQL full-text search and suggestions
- `shared`: API error model and `ApiException`, generic API exception handling, the single app-wide `Clock`, reusable validation constraints; no JPA or security; feature modules may depend on it, and it depends on no feature module
- `common`: Spring Boot assembly module: application entry point, configuration, Flyway migration scripts, health endpoints and full-context integration tests; it depends on each implemented backend module

The `shared` module and the assembly role of `common` were approved by the repository owner on 2026-10-18 (#113).

## Worker Internal Modules

Planned worker modules:

- `queue`: versioned IDs-only RabbitMQ consumer, prefetch 1, AUTO acknowledgement after terminal persistence and poison DLQ routing
- `probe`: FFprobe command execution and result parsing
- `transcode`: FFmpeg single-quality HLS command construction and execution
- `storage`: MinIO read/write operations for raw and HLS assets
- `status`: direct JDBC status updates and failure recording in PostgreSQL
- `cleanup`: owned temp cleanup per job/startup and failed/lost-finish/orphan attempt-prefix cleanup

Worker concurrency is fixed at 1 for MVP.

Exactly one worker replica may run, with no overlap on recreation. Listener startup follows temp sweep and conditional failure of orphaned PROCESSING pairs. [ADR-010](adr/ADR-010.md) defines claim/finish, deadlines, recovery and topology. Catalog obtains derived `videoStatus` through a catalog-defined port or set-based SQL, avoiding an encoding/catalog dependency cycle and N+1 reads; the worker does not write `movies`.

## Primary Data Flows

### Upload And Queue

1. Admin submits a video upload to `/api/v1/admin/movies/{movieId}/video`.
2. Nginx streams only this route unbuffered; backend authorizes headers before parsing, spools to dedicated temp disk and validates size, non-empty file, declared MIME and signature family.
3. Backend generates a new asset ID and stores `{movieId}/{videoAssetId}.{ext}` in private `vod-raw`, streaming with known length.
4. In one transaction serialized per movie, insert the new asset and attempt-1 job as `QUEUED`, with `published_at NULL`. The partial unique in-flight index rejects a concurrent loser with 409 `VIDEO_UPLOAD_IN_PROGRESS`; an uncommitted failure compensates only this request's raw key.
5. Commit before publishing the version-1 IDs-only persistent message with mandatory/returns and correlated confirms; set `published_at` on confirm without rewriting status.
6. Return `202 Accepted` even if publication fails; the backend relay republishes unpublished QUEUED jobs after the grace window. UPLOADED is reserved; QUEUED has no age timeout.

### Encoding

1. Worker consumes one RabbitMQ job.
2. Worker validates IDs/latest attempt and conditionally claims the QUEUED job/asset pair in a short JDBC transaction. Duplicates/older valid attempts are skipped and acknowledged; malformed/version/ID mismatches go to DLQ.
3. Worker reads the raw object from MinIO into worker temp storage.
4. Worker runs restricted FFprobe with metadata caps and a process deadline.
5. Worker runs restricted FFmpeg with a duration-derived deadline and generates one HLS variant, then deletes its downloaded raw copy.
6. Worker uploads `master.m3u8` and `.ts` segments under `video-assets/{videoAssetId}/attempt-{n}/` in private `vod-hls`.
7. Worker conditionally finishes both PROCESSING rows to `READY` (HLS keys/metadata) or `FAILED` (safe code) in one JDBC transaction, then AUTO-acks. No transaction spans media work. DB outages retry in-process; failed/lost-finish prefixes and temp files are cleaned best effort.

### Playback And Progress

1. User requests `/api/v1/movies/{movieId}/playback`.
2. Backend verifies JWT and movie visibility first (404), then queries current: greatest `(created_at, id)` READY asset. No current returns 409 `VIDEO_NOT_READY`.
3. Backend returns playback metadata, resume position, and canonical HLS manifest path under `/hls/*`.
4. Frontend player requests the manifest and segments through Nginx, sending the bearer token.
5. Backend authorizes each HLS request against the visible movie and current READY asset, returning 404 for non-current IDs, and derives storage keys from the stored attempt master key. A replacement may interrupt an old stream.
6. Frontend sends progress to `/api/v1/movies/{movieId}/progress` every 5 to 10 seconds.
7. Backend buffers progress in Redis and periodically flushes it to PostgreSQL.

## Consistency Model

- PostgreSQL is authoritative for users, roles, catalog data, playback progress, watchlist, history, ratings, video assets, and encoding jobs.
- Redis is a temporary buffer/cache only; any durable user state must be flushed to PostgreSQL.
- RabbitMQ is the only encoding job handoff between backend and worker.
- MinIO stores raw and HLS media objects; object keys are referenced from PostgreSQL.
- Encoding is asynchronous; an uploaded video is not playable until status is `READY`.
- `encoding_jobs` is the durable handoff record (`published_at` outbox); DB commit precedes RabbitMQ publish and delivery is at-least-once. Paired conditional transitions prevent status regression and duplicate encoding.
- Current/newest are derived per [ADR-010](adr/ADR-010.md); `videoStatus` stays READY while a working current exists, even when a replacement is pending/failed. Retry accepts only newest FAILED; archive keeps media. Minimal automatic compensation and manual superseded purge follow that ADR.

## API Boundary

Public browser traffic uses only:

- Nginx `/` for the frontend app
- Nginx `/api/v1/*` to backend JSON APIs
- Nginx `/hls/*` to backend HLS proxy

The frontend must not call PostgreSQL, Redis, RabbitMQ, MinIO, or internal Docker service URLs.

## Constraints

- No microservices split for MVP.
- No Kafka, Elasticsearch, Kubernetes, DRM, live streaming, payments, or recommendation engine scope.
- No multi-quality HLS in MVP.
- No presigned upload or presigned playback URLs in MVP.
- No implementation code in architecture batches.

## MVP Freeze

This architecture is frozen for MVP implementation. Future changes must update the affected architecture docs and ADRs in the same bounded task.
