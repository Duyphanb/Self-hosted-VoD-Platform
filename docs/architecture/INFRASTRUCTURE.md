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

Issue 5.8 adds and documents a disk-backed writable volume/mount for `WORKER_TEMP_DIR` under the worker's read-only root. It must not use tmpfs; the raw-size free-disk check guards capacity for the raw copy plus generated HLS. Keep this separate from backend `TEMP_UPLOAD_DIR`, which stays in the container layer. The volume/mount wiring is future Sprint 5 work, not added by this docs change.

## MinIO Buckets

PGSTY Silo provides this contract under the existing `minio` service name; see the [ADR-004 addendum](adr/ADR-004.md#runtime-addendum). Server and initialization share one verified digest. `MINIO_BROWSER: "off"` disables the console in local and production stacks; no storage port is published. The server curl `/minio/health/live` probe is unchanged; application credentials now use the [scoped mapping and positive timeouts](#storage-identities-and-policies) below, with an authenticated backend storage readiness probe. [Backup/restore and a temporary local console](../deployment/README.md#object-storage-backup-and-restore) are documented separately.

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

### Storage identities and policies

Issue #169 implements the [ADR-004 SDK choice](adr/ADR-004.md#runtime-addendum). `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` in the host environment remain root credentials for the server, bootstrap and explicit operator maintenance inside a one-off internal `minio-init` container (the production gate validates them without contacting storage). Applications never receive them. `MINIO_BACKEND_ACCESS_KEY`/`MINIO_BACKEND_SECRET_KEY` and `MINIO_WORKER_ACCESS_KEY`/`MINIO_WORKER_SECRET_KEY` are independent dedicated identities; Compose maps each pair to the corresponding application's existing `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` variables. Production requires all six values, rejects published defaults and reused identities/secrets, validates access keys of at least three characters with no `=`, `,` or edge whitespace, and secrets of at least eight characters. Keep dedicated users free of additional policies/groups.

`deploy/minio-init.sh` creates/secures the three buckets, overwrites and re-enables the configured users, updates their `vod-backend`/`vod-worker` policies and attaches each policy idempotently. It passes credentials to `mc` through stdin and suppresses tool output; failures name only the provisioning step. It does not remove old identities or reconcile extra memberships. Existing initialized volumes are retained; supply the new independent application credentials and rerun init before starting apps. Follow [rotation/revocation](../deployment/README.md#storage-identity-rotation-and-revocation), including removal of retired users and post-restore IAM reconciliation. Never remove a volume to adopt these accounts.

Resource notation below uses the configured bucket names; default raw/HLS buckets are `vod-raw`/`vod-hls`:

| Identity | Actions | Resources |
|---|---|---|
| Backend raw writer/compensation | `s3:PutObject`, `s3:DeleteObject`, `s3:AbortMultipartUpload` | `arn:aws:s3:::<raw>/*` |
| Backend HLS reader | `s3:GetObject` | `arn:aws:s3:::<hls>/video-assets/*/attempt-*/*` |
| Worker raw reader | `s3:GetObject` | `arn:aws:s3:::<raw>/*` |
| Worker attempt writer/cleanup | `s3:PutObject`, `s3:DeleteObject`, `s3:AbortMultipartUpload` | `arn:aws:s3:::<hls>/video-assets/*/attempt-*/*` |
| Both identities (explicit Deny) | `admin:CreateServiceAccount`, `admin:ChangeMyPassword` | Self-service operations, denied without a resource restriction |

Only object ARNs are needed for these operations; no bare bucket ARN is present. No ListBucket, GetBucketLocation, bucket creation, anonymous policy, general IAM administration, thumbnail access, backend raw GET, backend HLS write/delete, or worker raw write/delete is granted. The self-service service-account/password actions require the explicit Deny above because Silo otherwise permits them for the caller's own identity. Silo cannot block a regular user's STS AssumeRole through policy; incident revocation must remove the parent user, as the [runbook](../deployment/README.md#incident-response-and-retired-users) describes. The fixed SDK region `us-east-1` avoids bucket-region discovery permissions; path-style access uses `MINIO_ENDPOINT` (default `http://minio:9000`). These policies scope worker output to attempt-shaped paths, not per-job ownership; trusted DB keys and application access checks remain required.

Without listing, worker exact-attempt cleanup is supported by Silo's DeleteObject extension `x-minio-force-delete: true` on `video-assets/{id}/attempt-{n}/`. The trailing `/` is required: deleting `attempt-1/` must retain sibling `attempt-10/`; broader prefixes such as `video-assets/` are denied. This is a Silo extension, not portable AWS S3 behavior. A future AWS S3 adapter would need an explicitly approved prefix-conditioned `s3:ListBucket` permission and list/delete implementation. Issue 5.7 owns the final cleanup mechanism; #169 pins this policy behavior in smoke without adding ListBucket. Root operator purge/audit with listing is a separate [runbook procedure](../deployment/README.md#media-purge-and-orphan-audit).

The backend adapter accepts a caller-owned InputStream, known non-negative length and non-null content type; it never reads the whole object into an array and does not close the caller's input. The caller must close a returned GET stream to release HTTP resources. Negative length, null stream and null content type are rejected before I/O. SDK/transport failures become fixed safe IOExceptions with no cause. The SDK redacts signing credentials, but diagnostic messages retain the internal URL, bucket and object key, so the adapter drops those diagnostics.

`StorageProperties` maps `MINIO_*` through application configuration, validates an HTTP(S) origin, non-blank credentials and positive timeouts, and has no credential defaults. Compose and `.env.example` supply local placeholders. `MINIO_CONNECT_TIMEOUT_MS=10000`, `MINIO_READ_TIMEOUT_MS=60000` and `MINIO_WRITE_TIMEOUT_MS=60000` feed the named OkHttp setters. Executing HTTP calls also have a call timeout equal to the largest of these three values; OkHttp's async call timeout does not cover dispatcher queue time or interrupt a blocked JVM DNS resolver. These are per-call budgets, not a total-object upload deadline; the PUT backstop below prevents the SDK abort deadlock.

`BoundedStorageDns` separately bounds each resolver wait by the connect budget (capped at 2000 ms for health). A JVM-wide daemon pool permits at most 16 actual DNS lookups, uses direct handoff with no queue and returns a fixed safe failure when full. Timed-out lookups are cancelled best effort; if native DNS ignores interruption, its worker remains occupied until that lookup ends, so repeated failures cannot create unlimited resolver threads. Idle workers expire after 30 seconds. Clients do not close the shared pool.

The shared client has an explicit dispatcher with 64 global/32 per-host requests and a fair internal backstop of eight concurrent PUTs. The invariant is `active PUTs < maxRequestsPerHost`, leaving headroom for SDK abort callbacks, GET and DELETE. Raising dispatcher limits alone is insufficient: minio-java 9.0.3 synchronously joins abort from a failed multipart callback before releasing that call's slot. The eight PUTs bound SDK part buffers to roughly 64–96 MiB within the 768 MiB production backend; this is an internal SDK/heap safeguard above the expected MVP load, not global upload rate or connection admission control from ADR-010. A caller waiting for a PUT permit gets at most `MINIO_CONNECT_TIMEOUT_MS` before the same safe storage IOException. No retry orchestration/upload endpoint is implemented here. Issue 5.2 must carry these safeguards into the worker client.

Backend storage readiness uses a separate short-budget client/dispatcher and an authenticated GET of `video-assets/readiness-probe/attempt-0/never-written` in `MINIO_BUCKET_HLS`. Its non-UUID asset segment cannot collide with a generated real asset. The key is never written. `NoSuchKey` proves authenticated access and means UP; access/signature errors, transport failure or timeout mean DOWN with a fixed safe detail. The health client's connect/read/write/call timeouts are capped at 2000 ms and SDK retries are disabled. A dedicated single daemon worker runs the probe, while an outer 2000 ms future wait bounds the health caller even if the underlying operation stalls. The single-probe permit is held until that actual operation finishes, including after the caller times out; concurrent probes return the same safe DOWN immediately and cannot accumulate tasks. It requires no bucket HEAD, listing or additional grant; see [OBSERVABILITY.md](OBSERVABILITY.md#health-checks).

`storage-runtime-smoke.py` runs init twice, proves privacy, scoped operations, self-service/bucket/list denials, exact-prefix force-delete behavior and secret-free logs, with provenance/SBOM checks retained. Its caller must create a fresh disposable project and trap cleanup, including failures. `storage-sdk-smoke.py` owns its fresh project and cleanup: it waits for the server, requires a successful one-shot init, then exercises the production Java configuration with generated 128 MiB objects under `-Xmx64m`. It checks GET/PUT/DELETE and IAM denials, failed multipart aborts for both scoped identities (root verifies no incomplete upload remains), and readiness UP/DOWN for correct/wrong credentials. PASS requires executed, unskipped successful surefire tests and an in-test smaller-than-object heap assertion. Redacted first-failure diagnostics survive cleanup errors. Cleanup must remove only the disposable resources created for that run. CI runs both checks; normal backend tests skip the explicitly opt-in Silo test, while no-Docker regression tests exercise concurrent failed multipart calls and subsequent GET on a loopback fake S3, bounded DNS/pool exhaustion and a stalled readiness probe.

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
| `WORKER_TEMP_DIR` | `/tmp/vod-worker`; disk-backed writable volume, not tmpfs, added/documented in 5.8; raw-size free-disk check; owned `job-*` children swept before listener startup |
| `WORKER_FFPROBE_TIMEOUT_SECONDS` | Positive bound, to be measured in Issue 5.4/#124 |
| `WORKER_FFMPEG_TIMEOUT_MIN_SECONDS`, `WORKER_FFMPEG_TIMEOUT_FACTOR` | Deadline `max(min, factor * duration)`; values to be measured in Issue 5.5/5.7/#124 |
| `WORKER_MAX_DURATION_SECONDS`, `WORKER_MAX_WIDTH`, `WORKER_MAX_HEIGHT`, `WORKER_MAX_STREAMS` | Positive caps to be measured/validated in Issue 5.4/5.7/#124 |

Document bounded storage/JDBC waits per operation and the raw-size-based free-disk/output allowance in Issue 5.7. DB outage at claim/finish holds delivery and retries in-process with capped backoff delays, never FAILED or DLQ; it has no retry-exhaustion shutdown path. Worker `stop_grace_period` is 30s. Before the listener starts: sweep owned temp dirs, conditionally fail orphaned PROCESSING job/asset pairs, delete their attempt prefixes, then start consumption. No backend PROCESSING reconciler or QUEUED age timeout. Recovery assumes one backend relay and one non-overlapping worker.

#74 decides the RabbitMQ version and consumer-timeout sizing before Sprint 5, including how it preserves D20 during DB-outage retries. Use classic durable queues with DLX/DLQ (identical backend/worker declarations), AUTO acknowledgement after terminal persistence, `default-requeue-rejected=false` and prefetch 1. On 4.3+ classic queues no consumer-timeout setting is needed. If staying on 3.13, render `consumer_timeout` from an environment setting into a mounted `conf.d` file in base/prod Compose above the media-processing deadline from #103/#124, with final sizing/DB-outage handling owned by #74, not an application DB-retry budget. Do not assume config environment interpolation or use immutable `x-consumer-timeout`; verify rendering, a long encode with a second queued job and DB-outage handling. This change does not upgrade the broker (#74) or select the FFmpeg base (#54).

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

For a post-send network error or 502/503/504, the SPA reports an unknown upload result without inferring acceptance from MovieDetail.videoStatus. No automatic body resubmit: deliberate re-upload yields 409 while an asset is in-flight, or creates a newer asset (extra storage reclaimed by manual purge) if the first finished. The direct newest-asset view belongs to #168; no new endpoint is introduced here.

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
