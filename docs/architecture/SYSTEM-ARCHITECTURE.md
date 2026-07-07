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
- `common`: errors, validation, response models, security helpers

## Worker Internal Modules

Planned worker modules:

- `queue`: RabbitMQ consumer and message acknowledgement
- `probe`: FFprobe command execution and result parsing
- `transcode`: FFmpeg single-quality HLS command construction and execution
- `storage`: MinIO read/write operations for raw and HLS assets
- `status`: direct JDBC status updates and failure recording in PostgreSQL
- `cleanup`: temporary file cleanup after success or failure

Worker concurrency is fixed at 1 for MVP.

## Primary Data Flows

### Upload And Queue

1. Admin submits a video upload to `/api/v1/admin/movies/{movieId}/video`.
2. Backend validates authentication, `ROLE_ADMIN`, MIME type, size, and non-empty file.
3. Backend stores the raw video object in private MinIO bucket `vod-raw`.
4. Backend creates or updates the `video_assets` row with status `UPLOADED`.
5. Backend creates an `encoding_jobs` row with status `QUEUED`.
6. Backend publishes the RabbitMQ message to `video-encoding-queue`.
7. Backend marks the `video_assets` row as `QUEUED`.
8. Backend returns `202 Accepted`.

### Encoding

1. Worker consumes one RabbitMQ job.
2. Worker marks the job and asset `PROCESSING` through direct JDBC writes to PostgreSQL.
3. Worker reads the raw object from MinIO into worker temp storage.
4. Worker runs FFprobe and stores useful metadata.
5. Worker runs FFmpeg and generates one HLS output variant.
6. Worker uploads `master.m3u8` and `.ts` segments to private MinIO bucket `vod-hls`.
7. Worker marks the asset and job `READY`, or `FAILED` with a safe reason, through direct JDBC writes to PostgreSQL.

### Playback And Progress

1. User requests `/api/v1/movies/{movieId}/playback`.
2. Backend verifies JWT, movie visibility, and asset status `READY`.
3. Backend returns playback metadata, resume position, and canonical HLS manifest path under `/hls/*`.
4. Frontend player requests the manifest and segments through Nginx, sending the bearer token.
5. Backend authorizes each HLS request and streams the matching MinIO object through Nginx.
6. Frontend sends progress to `/api/v1/movies/{movieId}/progress` every 5 to 10 seconds.
7. Backend buffers progress in Redis and periodically flushes it to PostgreSQL.

## Consistency Model

- PostgreSQL is authoritative for users, roles, catalog data, playback progress, watchlist, history, ratings, video assets, and encoding jobs.
- Redis is a temporary buffer/cache only; any durable user state must be flushed to PostgreSQL.
- RabbitMQ is the only encoding job handoff between backend and worker.
- MinIO stores raw and HLS media objects; object keys are referenced from PostgreSQL.
- Encoding is asynchronous; an uploaded video is not playable until status is `READY`.

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
