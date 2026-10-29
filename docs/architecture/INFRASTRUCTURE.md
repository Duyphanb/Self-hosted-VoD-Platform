# Infrastructure Architecture

Status: Frozen for MVP implementation

## Deployment Model

The MVP uses Docker Compose for both local development and the Oracle Cloud Free Tier VPS demo. Kubernetes is out of scope.

Nginx is the only public edge in both local Compose and VPS runtime.

## Runtime Components

| Component | Runtime | Purpose | Public? |
|---|---|---|---|
| `nginx` | Nginx | Frontend static serving, API proxy, HLS proxy, HTTPS on VPS | Yes |
| `frontend` | React build artifact | Browser UI built by Vite and served by Nginx | No direct container exposure |
| `backend` | Java 21 Spring Boot | Core API, auth, upload, HLS proxy, progress, admin APIs | Internal behind Nginx |
| `worker` | Java 21 Spring Boot with FFmpeg/FFprobe in `PATH` | Video processing | Internal only |
| `postgres` | PostgreSQL | Source-of-truth database | Internal only |
| `redis` | Redis | Cache and playback-progress buffer | Internal only |
| `rabbitmq` | RabbitMQ | Encoding job queue | Internal only |
| `minio` | PGSTY Silo (MinIO-compatible, digest pinned) | Private object storage | Internal only; console disabled |

## Public Routing

Nginx routes public browser traffic:

| Public Path | Upstream |
|---|---|
| `/` | frontend static build |
| `/api/v1/*` | backend |
| `/hls/*` | backend HLS proxy |
| `/actuator/health` | backend health endpoint, exposed only if deployment needs it |

`/hls/*` through Nginx to the backend HLS proxy is the only MVP browser playback path; MinIO is never public.

## Local Network

Compose service names are stable DNS names:

- `nginx`
- `backend`
- `worker`
- `postgres`
- `redis`
- `rabbitmq`
- `minio`

Application configuration must use service names instead of hardcoded IP addresses.

## Port Baseline

| Service | Internal Port | Public Local Port | VPS Exposure |
|---|---:|---|---|
| Nginx HTTP | 80 | `localhost:80` | 80 |
| Nginx HTTPS | 443 | not required locally | 443 |
| Backend | 8080 | none | none |
| PostgreSQL | 5432 | none | none |
| Redis | 6379 | none | none |
| RabbitMQ | 5672 | none | none |
| RabbitMQ management | 15672 | none by default | none |
| Silo S3 API (`minio`) | 9000 | none | none |

Development-only direct port exposure may be added in a local override file, not in the MVP base Compose file.

## Persistent Volumes

Required named volumes:

- `postgres_data`
- `rabbitmq_data`
- `minio_data`

Redis persistence is not required for MVP because Redis is a cache/progress buffer. Durable playback progress belongs in PostgreSQL.

## MinIO Buckets

PGSTY Silo provides this contract under the existing `minio` service name; see the [ADR-004 addendum](adr/ADR-004.md#runtime-addendum). Server and initialization share one verified digest. `MINIO_BROWSER: "off"` disables the console in local and production stacks; no storage port is published. The curl `/minio/health/live` probe and all backend/worker `MINIO_*` settings remain unchanged. [Backup/restore and a temporary local console](../deployment/README.md#object-storage-backup-and-restore) are documented separately.

| Bucket | Purpose | Access |
|---|---|---|
| `vod-raw` | Original uploaded files | Private |
| `vod-hls` | HLS playlists and `.ts` segments | Private, read by backend HLS proxy |
| `vod-thumbnails` | Future poster/thumbnail objects | Private, deferred until thumbnail feature |

Key layout: raw `{movieId}/{videoAssetId}.{ext}`; HLS `video-assets/{videoAssetId}/attempt-{n}/master.m3u8` and `.../segments/*.ts`. Backend derives the private attempt prefix from the READY asset's stored master key; public HLS paths are unchanged. Retention/compensation follows [ADR-010](adr/ADR-010.md#hls-objects-and-cleanup-d22-d23); manual purge/orphan audit follows the [runbook](../deployment/README.md#media-purge-and-orphan-audit). Buckets remain private.

## Environment Configuration

All runtime configuration comes from environment variables. `.env.example` is the safe template and must not contain real secrets.

Required configuration groups:

- `APP_*` and public URL
- `SERVER_PORT`
- `SPRING_*`
- `JWT_*`
- `POSTGRES_*`
- `REDIS_*`
- `RABBITMQ_*`
- `MINIO_*`
- upload size, MIME allowlist and `TEMP_UPLOAD_DIR`
- `FFMPEG_PATH`, `FFPROBE_PATH`, `WORKER_TEMP_DIR`, `WORKER_CONCURRENCY` and `WORKER_PREFETCH`
- `NGINX_HTTP_PORT`, `NGINX_HTTPS_PORT`, `PUBLIC_DOMAIN`, `LETSENCRYPT_EMAIL`

Frontend public runtime values should point at Nginx paths:

- API base: `/api/v1`
- HLS base: `/hls`

### Upload and encoding settings

These are the approved settings to wire into `.env.example`, application configuration and Compose in Issues 4.2/4.4/4.6/5.1/5.7/5.8; this document does not claim those bindings exist yet.

| Setting | Rule / default |
|---|---|
| `MAX_UPLOAD_SIZE_MB` | 2048 MiB; both Spring file/request limits, Nginx upload literal and SPA constant must match |
| `TEMP_UPLOAD_DIR` | `/tmp/vod-uploads`, dedicated backend container-layer directory; threshold 0B, no named volume/tmpfs |
| `ENCODING_PUBLISH_CONFIRM_TIMEOUT_MS` | 5000; confirms plus mandatory returns |
| `ENCODING_RELAY_INTERVAL_SECONDS` | 10; relay ignores jobs younger than twice confirm timeout |
| `RABBITMQ_ENCODING_QUEUE` | `video-encoding-queue`; direct DLX `<queue>.dlx`, DLQ/routing key `<queue>.dlq` |
| `WORKER_CONCURRENCY`, `WORKER_PREFETCH` | 1 each; exactly one non-overlapping worker replica |
| `WORKER_TEMP_DIR` | `/tmp/vod-worker`; dedicated `job-*` children, swept before listener startup |
| `WORKER_FFPROBE_TIMEOUT_SECONDS` | Positive bound, to be measured in Issue 5.4/#124 |
| `WORKER_FFMPEG_TIMEOUT_MIN_SECONDS`, `WORKER_FFMPEG_TIMEOUT_FACTOR` | Deadline `max(min, factor * duration)`; values to be measured in Issue 5.5/5.7/#124 |
| `WORKER_MAX_DURATION_SECONDS`, `WORKER_MAX_WIDTH`, `WORKER_MAX_HEIGHT`, `WORKER_MAX_STREAMS` | Positive caps to be measured/validated in Issue 5.4/5.7/#124 |

Document bounded storage/JDBC waits, total DB-retry window and the raw-size-based free-disk/output allowance in Issue 5.7. DB retry-window exhaustion stops the listener/worker without ack or poison rejection; startup recovery resolves the retained state. Worker `stop_grace_period` is 30s. Before the listener starts: sweep owned temp dirs, conditionally fail orphaned PROCESSING job/asset pairs, delete their attempt prefixes, then start consumption. No backend PROCESSING reconciler or QUEUED age timeout. Recovery assumes one backend relay and one non-overlapping worker.

#74 decides the RabbitMQ version before Sprint 5. Use classic durable queues with DLX/DLQ (identical backend/worker declarations), AUTO acknowledgement after terminal persistence, `default-requeue-rejected=false` and prefetch 1. On 4.3+ classic queues no consumer-timeout setting is needed. If staying on 3.13, render `consumer_timeout` from an environment setting into a mounted `conf.d` file in base/prod Compose, above the measured maximum whole-job deadline plus margin. Do not assume config environment interpolation or use immutable `x-consumer-timeout`; verify rendering and a long encode with a second queued job. Exact deadline is to be measured in Issue 5.7/#124. This change does not upgrade the broker (#74) or select the FFmpeg base (#54).

## Nginx Responsibilities

Nginx must:

- serve the frontend build
- proxy `/api/v1/*` to backend
- proxy `/hls/*` to backend HLS proxy
- forward `Authorization` and `X-Request-ID` headers to backend
- terminate HTTPS on VPS
- apply basic security headers
- enforce upload body size at least as strictly as backend configuration on the upload route; all other routes have a 1 MiB default
- return correct HLS MIME types when applicable

HLS MIME baseline:

- `.m3u8`: `application/vnd.apple.mpegurl`
- `.ts`: `video/mp2t`

### Upload transport

Owner-approved 2026-10-29 in #52/#116; implement with Issue 4.6 once the upload endpoint exists:

- Set global `client_max_body_size 1m`.
- Inside the existing `location /api/v1/`, add a nested regex matching only `^/api/v1/admin/movies/[^/]+/video$`. Backend UUID validation remains authoritative. Its own `proxy_pass http://backend_upstream;` has no URI part, as required for a regex location. Inherit parent's `proxy_http_version 1.1`, all `proxy_set_header` directives and `error_page` mappings; a sibling location would not inherit them.
- Only that location has `client_max_body_size 2048m`, `proxy_request_buffering off`, `proxy_read_timeout 600s`. Content-Length bodies stream with buffering off; HTTP/1.1 upstream also prevents buffering chunked requests. This is transport handling, not a resumable/chunked browser upload feature.
- Keep `client_body_timeout`, `send_timeout` and `proxy_send_timeout` at their 60s inactivity defaults; do not add a conflicting 120s body timeout. Verify Tomcat body-read timeout alignment in Issue 4.6. The 600s read setting bounds idle waiting after sending upstream, not total upload duration.
- Spring `max-file-size` and `max-request-size` both use `${MAX_UPLOAD_SIZE_MB:2048}MB`; `location=${TEMP_UPLOAD_DIR:/tmp/vod-uploads}`, `file-size-threshold=0B`. Nginx `m` and Spring `MB` mean MiB. Multipart overhead counts against the request ceiling; no MAX+1 bean/headroom is selected.
- The literal Nginx value and SPA limit constant are guarded against `.env.example` by smoke assertions; production preflight fails closed if `MAX_UPLOAD_SIZE_MB` differs. `.env.example` gains that consistency comment in Issue 4.6, not a new Vite build-argument mechanism.
- Backend creates/validates the dedicated spool and non-recursively removes only stale regular Tomcat `upload_*.tmp` files before the connector starts. Never sweep a general temp directory or follow symlinks. Request cleanup removes completed/aborted multipart files. Header auth precedes parsing; map `MaxUploadSizeExceededException` to 413 `PAYLOAD_TOO_LARGE`.

Under `/api/v1/`, map edge-generated 413/502/503/504 through separate named error locations (or an equivalent status-preserving map) to `application/json` `ApiError`: `timestamp=$time_iso8601`, numeric original `status`, safe fixed `message`, `requestId=$request_id`, and codes `PAYLOAD_TOO_LARGE`, `UPSTREAM_UNAVAILABLE` (502/503), `UPSTREAM_TIMEOUT` (504). Use the same request ID sent upstream as `X-Request-ID`; keep `proxy_intercept_errors off` so backend JSON is not replaced. Ensure an early body-limit error selects the JSON mapping. Do not use a single named return that flattens every status to 413.

#52 runtime evidence: small/auth routes reject >1 MiB; upload permits a valid 2 MiB request and rejects >2048 MiB; edge errors match JSON schema/status/request ID; HLS/health routing stays unchanged. The stub needs its own `client_max_body_size 0` and a marker/202 upload response (stock Nginx's 1m would reject the accepted case). Use deterministic unavailable and accept-without-response stubs for 502/504; a test-only short timeout can accelerate the 504, with an assertion that production stays 600s. Add backend-direct oversize/413, unauthorized-before-parse/no-temp-file and cancelled-upload tests. Record one genuine 2 GB prod-overlay upload in Issue 4.6/4.7: client duration, saving wait after 100%, temp peak, memory and actual VPS disk size, all **to be measured**. No such run is claimed here.

## VPS Deployment Baseline

Target:

- Oracle Cloud Free Tier Ampere A1
- 2 OCPU
- 12 GB RAM
- Ubuntu Linux
- Docker and Docker Compose
- Nginx container in the Compose stack
- HTTPS through Let's Encrypt

Deployment must preserve named volumes across releases.

## CI/CD Direction

Future CI must:

- run backend tests
- run worker tests
- run frontend lint/build/tests
- validate Docker builds for frontend, backend, and worker
- avoid publishing images unless tests pass

Future CD must:

- connect to VPS by SSH
- pull images
- run `docker compose up -d`
- preserve volumes
- run smoke checks

## Deferred Infrastructure

Deferred until MVP is stable:

- Kubernetes
- managed AWS production stack
- S3/CloudFront migration
- Prometheus/Grafana/Loki stack
- autoscaling
- multi-node deployment
