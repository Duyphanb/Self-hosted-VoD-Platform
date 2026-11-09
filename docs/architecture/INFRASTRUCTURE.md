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
| `worker` | Java 21 Spring Boot with FFmpeg/FFprobe from Debian trixie packages ([ADR-010 D25](adr/ADR-010.md#sprint-5-worker-media-decisions-d25-d28)) | Video processing | Internal only |
| `postgres` | PostgreSQL | Source-of-truth database | Internal only |
| `redis` | Redis | Cache and playback-progress buffer | Internal only |
| `rabbitmq` | RabbitMQ 4.4 (Docker official alpine image, digest pinned; see Broker runtime image) | Encoding job queue | Internal only |
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

Compose defines two networks (#106). `vod_edge` carries `nginx`, `frontend` and `backend`. `vod_internal` is `internal: true` and carries `postgres`, `redis`, `rabbitmq`, `minio`, `worker`, the one-shot `postgres-init`, `flyway` and `minio-init` jobs, and `backend`, the only service on both networks. Docker DNS answers only for attached networks, so Nginx cannot resolve or reach a data service or the worker. A service attached only to the internal network has no egress and cannot publish a port. See [Service identities and network](#service-identities-and-network).

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

Development-only direct port exposure may be added in a local override file, not in the MVP base Compose file. Such an override must also attach the service to a non-internal network, because the internal data network cannot publish ports; the disposable test overlays add their own network for this.

## Persistent Volumes

Required named volumes:

- `postgres_data`
- `rabbitmq_data`
- `minio_data`

Redis persistence is not required for MVP because Redis is a cache/progress buffer. Durable playback progress belongs in PostgreSQL.

Issue 5.8 adds and documents a disk-backed writable volume/mount for `WORKER_TEMP_DIR` under the worker's read-only root. It must not use tmpfs; the two-step free-disk check and watchdog of ADR-010 D27 guard capacity for the raw copy plus generated HLS, with a reserve that also protects the co-located PostgreSQL and Silo data. Keep this separate from backend `TEMP_UPLOAD_DIR`, which stays in the container layer. The volume/mount wiring is future Sprint 5 work, not added by this docs change.

## MinIO Buckets

PGSTY Silo provides this contract under the existing `minio` service name; see the [ADR-004 addendum](adr/ADR-004.md#runtime-addendum). Server and initialization share one verified digest. `MINIO_BROWSER: "off"` disables the console in local and production stacks; no storage port is published. The server curl `/minio/health/live` probe is unchanged; application credentials now use the [scoped mapping and positive timeouts](#storage-identities-and-policies) below, with an authenticated backend storage readiness probe. [Backup/restore and a temporary local console](../deployment/README.md#object-storage-backup-and-restore) are documented separately.

| Bucket | Purpose | Access |
|---|---|---|
| `vod-raw` | Original uploaded files | Private |
| `vod-hls` | HLS playlists and `.ts` segments | Private, read by backend HLS proxy |
| `vod-thumbnails` | Future poster/thumbnail objects | Private, deferred until thumbnail feature |

Key layout: raw `{movieId}/{videoAssetId}.{ext}`; HLS `video-assets/{videoAssetId}/attempt-{n}/master.m3u8` and `.../segments/*.ts`. Backend derives the private attempt prefix from the READY asset's stored master key; public HLS paths are unchanged. In media keys, `{videoAssetId}` is the lowercase canonical UUID text, `{n}` is `encoding_jobs.attempt` in decimal without padding, and an attempt prefix is `video-assets/{videoAssetId}/attempt-{n}/`, including the final `/`. `master.m3u8` is a single VOD media playlist with MPEG-TS segments (ADR-010 D27). Retention/compensation follows [ADR-010](adr/ADR-010.md#hls-objects-and-cleanup-d22-d23); manual purge/orphan audit follows the [runbook](../deployment/README.md#media-purge-and-orphan-audit). Buckets remain private.

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
- `FFMPEG_PATH`, `FFPROBE_PATH`, `WORKER_TEMP_DIR`, `WORKER_CONCURRENCY`, `WORKER_PREFETCH`, `HLS_SEGMENT_SECONDS`, `HLS_TARGET_HEIGHT`, `HLS_VIDEO_MAXRATE_KBPS` and `WORKER_FFMPEG_THREADS`
- the worker media safeguards `WORKER_FFPROBE_TIMEOUT_SECONDS`, `WORKER_FFMPEG_TIMEOUT_*`, `WORKER_MAX_*`, `WORKER_OUTPUT_BUDGET_KBPS` and `WORKER_DISK_RESERVE_MB`
- `NGINX_HTTP_PORT`, `NGINX_HTTPS_PORT`, `PUBLIC_DOMAIN`, `LETSENCRYPT_EMAIL`
- production JVM options: optional `BACKEND_JAVA_TOOL_OPTIONS` and `WORKER_JAVA_TOOL_OPTIONS` overrides of the overlay's `JAVA_TOOL_OPTIONS` defaults (#124; see the [deployment preflight](../deployment/README.md#production-credential-preflight))

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

Without listing, worker exact-attempt cleanup is supported by Silo's DeleteObject extension `x-minio-force-delete: true` on `video-assets/{id}/attempt-{n}/`. The trailing `/` is required: deleting `attempt-1/` must retain sibling `attempt-10/`; broader prefixes such as `video-assets/` are denied. This is a Silo extension, not portable AWS S3 behavior. A future AWS S3 adapter would need an explicitly approved prefix-conditioned `s3:ListBucket` permission and list/delete implementation. Issue 5.7 implements this mechanism through a dedicated attempt-prefix delete that validates the prefix and sends `x-minio-force-delete: true`, never the generic object delete (which deletes nothing for a prefix). Its tests verify absence through a backend or root reader identity, because the worker cannot read or list HLS and a 204 does not prove deletion. #169 pins this policy behavior in smoke without adding ListBucket. Root operator purge/audit with listing is a separate [runbook procedure](../deployment/README.md#media-purge-and-orphan-audit).

The backend adapter accepts a caller-owned InputStream, known non-negative length and non-null content type; it never reads the whole object into an array and does not close the caller's input. The caller must close a returned GET stream to release HTTP resources. Negative length, null stream and null content type are rejected before I/O. SDK/transport failures become fixed safe IOExceptions with no cause. The SDK redacts signing credentials, but diagnostic messages retain the internal URL, bucket and object key, so the adapter drops those diagnostics.

`StorageProperties` maps `MINIO_*` through application configuration, validates an HTTP(S) origin, non-blank credentials and positive timeouts, and has no credential defaults. Compose and `.env.example` supply local placeholders. `MINIO_CONNECT_TIMEOUT_MS=10000`, `MINIO_READ_TIMEOUT_MS=60000` and `MINIO_WRITE_TIMEOUT_MS=60000` feed the named OkHttp setters. The shared application client has no total call deadline: connect/read/write timeouts bound stalls, and a steadily read GET body may continue longer than any individual timeout. The read timeout bounds a stalled read, not total download duration. The PUT backstop below prevents the SDK abort deadlock without a total-body deadline; Issue 5.2 must preserve this behavior for 500 MB–2 GB raw downloads.

`BoundedStorageDns` separately bounds each resolver wait by the connect budget (capped at 2000 ms for health). A JVM-wide daemon pool permits at most 16 actual DNS lookups, with direct handoff and no queued lookup tasks. When all workers are busy, callers wait for a handoff slot within their original DNS deadline, so a healthy cold-pool burst can drain instead of failing immediately. Execution then gets only that deadline's remaining budget; slot admission and lookup do not each receive a fresh timeout. A stuck/full pool eventually returns a fixed safe failure. Timed-out lookups are cancelled best effort; if native DNS ignores interruption, its worker remains occupied until that lookup ends, so repeated failures cannot create unlimited resolver threads. Idle workers expire after 30 seconds. Clients do not close the shared pool.

The shared client has an explicit dispatcher with 64 global/32 per-host requests and a fair internal backstop of eight concurrent PUTs. The invariant is `active PUTs < maxRequestsPerHost`, leaving headroom for SDK abort callbacks, GET and DELETE. Raising dispatcher limits alone is insufficient: minio-java 9.0.3 synchronously joins abort from a failed multipart callback before releasing that call's slot. Eight PUTs have an estimated 64–96 MiB SDK part-buffer budget; review measured about 65 MiB of additional live heap. Budget this against the effective JVM max heap rather than the full 768 MiB container limit: about 483 MiB (`Runtime.maxMemory()`) with SerialGC on 1 CPU under the production overlay's `-XX:MaxRAMPercentage=65` (#124), up from about 185 MiB with the earlier 25% default. This is an internal SDK/heap safeguard above expected MVP load; global upload rate/connection limits remain deferred by ADR-010. A caller waiting for a PUT permit gets at most `MINIO_CONNECT_TIMEOUT_MS` before the same safe storage IOException.

The shared client restores the SDK status retry policy: `Http.RETRIABLE_STATUS_CODES`, at most five attempts and 100 ms backoff. Retries execute inside the same dispatched HTTP call, so they do not consume additional dispatcher slots or change the PUT/abort headroom invariant. Persistent failures still map to the safe IOException after those attempts. Readiness disables these retries. No application retry orchestration or upload endpoint is implemented here. Issue 5.2 must carry these safeguards into the worker client.

Backend storage readiness uses a separate short-budget client/dispatcher and an authenticated GET of `video-assets/readiness-probe/attempt-0/never-written` in `MINIO_BUCKET_HLS`. Its non-UUID asset segment cannot collide with a generated real asset. The key is never written. `NoSuchKey` proves authenticated access and means UP; access/signature errors, transport failure or timeout mean DOWN with a fixed safe detail. The health client's connect/read/write/call timeouts are capped at 2000 ms and SDK retries are disabled. A dedicated single daemon worker runs at most one actual Silo GET. Concurrent callers share that probe and its original outer 2000 ms deadline; each join uses only the remaining time. Completed results are cached for one second from logical completion, limiting repeated requests to at most one new probe per cache TTL. A timed-out underlying call remains the sole in-flight operation until it actually ends, with DOWN reused meanwhile; a late UP cannot replace the deadline failure. Callers cannot accumulate probe tasks. It requires no bucket HEAD, listing or additional grant. Aggregate `/actuator/health` is the deployment readiness signal; see [OBSERVABILITY.md](OBSERVABILITY.md#health-checks).

`storage-runtime-smoke.py` runs init twice, proves privacy, scoped operations, self-service/bucket/list denials, exact-prefix force-delete behavior and secret-free logs, with provenance/SBOM checks retained. Its caller must create a fresh disposable project and trap cleanup, including failures. `storage-sdk-smoke.py` owns its fresh project and cleanup: it waits for the server, requires a successful one-shot init, then exercises the production Java configuration with generated 128 MiB objects under `-Xmx64m`. It checks GET/PUT/DELETE and IAM denials, failed multipart aborts for both scoped identities (root verifies no incomplete upload remains), and readiness UP/DOWN for correct/wrong credentials. PASS requires executed, unskipped successful surefire tests and an in-test smaller-than-object heap assertion. Redacted first-failure diagnostics survive cleanup errors. Cleanup must remove only the disposable resources created for that run. CI runs both checks and checks aggregate `/actuator/health` through Nginx after full-stack startup. Normal backend tests skip the explicitly opt-in Silo test; no-Docker regressions cover failed multipart concurrency, a steady GET longer than the stall budgets, status retries, bounded DNS bursts/stalls and readiness coalescing/cache/deadlines. A real-indicator Spring test checks HTTP 503 DOWN without details against a closed loopback endpoint.

### Service identities and network

Issue #106 extends the #169 storage split to PostgreSQL, RabbitMQ and Redis, and splits the network. Bootstrap identities stay with the servers, their one-shot initialization and explicit operator maintenance; each application receives only its own identity. Production requires every value below, rejects the published development defaults, a user name that another identity of the same service reuses, and a secret that any other identity reuses, within or across services (including the storage secrets and `JWT_SECRET`). Preflight reports variable names only. The decision is recorded in [SECURITY.md](SECURITY.md#service-identities-and-network-isolation); rotation is in the [deployment runbook](../deployment/README.md#service-identity-rotation).

| Identity | Variables | Used by | Rights |
|---|---|---|---|
| PostgreSQL bootstrap superuser | `POSTGRES_USER`, `POSTGRES_PASSWORD` | `postgres` (cluster creation), `postgres-init`, operator maintenance through the container's local socket | Superuser. Never given to Flyway or an application |
| Schema owner | `POSTGRES_OWNER_USER`, `POSTGRES_OWNER_PASSWORD` | `flyway` | Owns every relation in `public`; `USAGE, CREATE` on `public` |
| Backend role | `POSTGRES_BACKEND_USER`, `POSTGRES_BACKEND_PASSWORD` | `backend` | `SELECT, INSERT, UPDATE, DELETE` on every application table and `USAGE, SELECT` on their sequences. No DDL, `TRUNCATE`, temporary table or `flyway_schema_history` access |
| Worker role | `POSTGRES_WORKER_USER`, `POSTGRES_WORKER_PASSWORD` | `worker` | `SELECT, UPDATE` on `encoding_jobs` and `video_assets` only: the ADR-010 claim, finish and startup recovery |
| RabbitMQ bootstrap administrator | `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | broker definitions, operator maintenance | `administrator` tag, full access to vhost `/` |
| Backend broker user | `RABBITMQ_BACKEND_USERNAME`, `RABBITMQ_BACKEND_PASSWORD` | `backend` | No tag. On `/`: configure `<queue>`, `<queue>.dlx`, `<queue>.dlq`; write `amq.default`, `<queue>.dlx`, `<queue>.dlq`; read `<queue>`, `<queue>.dlx` |
| Worker broker user | `RABBITMQ_WORKER_USERNAME`, `RABBITMQ_WORKER_PASSWORD` | `worker` | As the backend user without `amq.default`, so it cannot publish jobs |
| Redis password | `REDIS_PASSWORD` | `redis` (`requirepass`), `backend` | The worker and the init jobs never receive it |

No PostgreSQL role has a superuser, role-creation, database-creation, replication or RLS-bypass attribute or a role membership. Role names are lowercase letters, digits and `_`, do not start with `pg_` and have at most 63 characters, because the scripts quote them into SQL.

`deploy/postgres-init.sh` runs as the bootstrap superuser after PostgreSQL is healthy and before Flyway. It creates or updates the three login roles and their passwords, refuses to continue while a configured role holds a membership in any other role (for example `pg_read_all_data` or a sibling application role, whose privileges the revocations below would not remove), revokes `PUBLIC` access to every connectable database and to schema `public`, grants `CONNECT` on the application database to the three roles only, and transfers the relations that the bootstrap user created before #106 to the owner; linked sequences and indexes follow their tables. Passwords reach psql through the environment (`\getenv`), the session turns statement logging off, and a failure names only its step. Flyway then migrates as the owner with `deploy/flyway/afterMigrate.sql`, a Compose-only callback that runs after every migrate, including a run with nothing pending. It revokes every table and sequence privilege from the two application roles and grants exactly the rights above, so the migrate that creates a table also grants it. Both steps are idempotent, and rerunning `postgres-init` updates the role passwords. The CI `Flyway schema migration` job migrates without the callback, so the committed schema snapshot is unchanged. The backend and worker default `SPRING_DATASOURCE_URL` to `jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}`, the expression `FLYWAY_URL` uses; production rejects an explicit URL that differs.

`deploy/rabbitmq-entrypoint.sh` renders the three users and their permissions as definitions on a tmpfs readable only by the broker user, and configures `load_definitions`. RabbitMQ imports them at every boot, which overwrites the configured users' passwords, tags and permissions, and then skips its own default-user seeding. A changed broker secret therefore needs a broker restart. Import never deletes a user; remove a retired one with `rabbitmqctl delete_user`. The permission patterns are anchored to the configured queue name, which the script validates with the `EncodingQueueTopology` rule. AMQP requires `read` on a queue declared with a dead-letter exchange and `write` on that exchange, so both users can consume or purge the source queue and route to the DLX, which ADR-010's identical declarations need. The split keeps the worker from publishing jobs and both users away from every other entity, the DLQ contents, other virtual hosts and management. DLQ inspection stays an operator task.

`deploy/redis-entrypoint.sh` passes `requirepass` to `redis-server` on stdin, so the secret never appears in argv or the Compose command. It rejects a blank password and one with whitespace or quotes, and the health check authenticates through `REDISCLI_AUTH`. The backend has no Redis client yet; the first Redis consumer must authenticate with `REDIS_PASSWORD`.

`service-identity-smoke.py` upgrades a database migrated the pre-#106 way on a fresh disposable project. It checks ownership and the exact grants, idempotent reruns, the allowed and denied operations of each role, a password rotation, Redis authentication and the network membership. With `--running-stack`, CI checks the healthy full stack: JDBC sessions use only application roles, broker connections only application users, Redis requires its password, and Nginx reaches the backend but no internal service by name or address. `rabbitmq-publisher-smoke.py` declares the topology as the worker user and runs `EncodingBrokerPermissionTests`: both users declare it, the backend publishes, the worker consumes and dead-letters, and every other probed operation is refused with 403. `production-config-smoke.sh` and `test_production_config_check.py` cover the preflight rules.

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
| `WORKER_TEMP_DIR` | `/tmp/vod-worker`; disk-backed writable volume, not tmpfs, added/documented in 5.8; two-step free-disk check; owned `job-*` children swept before listener startup |
| `WORKER_FFPROBE_TIMEOUT_SECONDS` | Positive bound, to be measured using #124 |
| `WORKER_FFMPEG_TIMEOUT_MIN_SECONDS`, `WORKER_FFMPEG_TIMEOUT_FACTOR` | Deadline `max(min, factor * duration)`; values to be measured using #124 |
| `WORKER_MAX_DURATION_SECONDS`, `WORKER_MAX_WIDTH`, `WORKER_MAX_HEIGHT`, `WORKER_MAX_STREAMS` | 10800, 1920, 1080 and 8 (owner policy, ADR-010 D26). Width and height cap the long and short side in either orientation; no 4K sources |
| `HLS_SEGMENT_SECONDS` | 6; integer 2-10; drives both `-hls_time` and the forced keyframes |
| `HLS_TARGET_HEIGHT` | 720; even integer 240-1080; output fits a box of this short side and a long side of 16/9 of it (1280×720, or 720×1280 for portrait), never upscaled |
| `HLS_VIDEO_MAXRATE_KBPS` | 4000; passed as `-maxrate:v 4000k -bufsize:v 8000k` (the `k` suffix is required; ADR-010 D27) |
| `WORKER_FFMPEG_THREADS` | 1; integer 1-16; FFmpeg `-threads` and `-filter_threads`; revisit from #124 |
| `WORKER_OUTPUT_BUDGET_KBPS`, `WORKER_DISK_RESERVE_MB` | 5000 and 5120; the output bytes budgeted per media second and the free-space reserve of the ADR-010 D27 disk checks and watchdog; #124/#180 calibrate them |

Media settings have no application defaults: the worker fails startup when one is missing or invalid. The first PR that makes a setting required writes its value into `.env.example` and both Compose files, because CI starts the stack with `--env-file /dev/null`; tests inject explicit test values. The timeouts come from #124, which runs the Issue 5.5 command line in the worker image on arm64 at the product caps with a high-entropy source (ADR-010 D28). Document bounded storage/JDBC waits per operation in Issue 5.7. DB outage at claim/finish holds delivery and retries in-process with capped backoff delays, never FAILED or DLQ; it has no retry-exhaustion shutdown path. Worker `stop_grace_period` is 30s. Before the listener starts: sweep owned temp dirs, conditionally fail orphaned PROCESSING job/asset pairs, delete their attempt prefixes, then start consumption. No backend PROCESSING reconciler or QUEUED age timeout. Recovery assumes one backend relay and one non-overlapping worker.

#74 selected the RabbitMQ 4.4 series. Use classic durable queues with DLX/DLQ (identical backend/worker declarations), AUTO acknowledgement after terminal persistence, `default-requeue-rejected=false` and prefetch 1. RabbitMQ 4.3+ classic queues evaluate no consumer timeout, so no consumer-timeout setting, policy or queue argument exists, and a DB outage at claim/finish holds the delivery for as long as it lasts (D20). Issue 5.1 verifies a long encode with a second queued job and DB-outage handling on a test broker with a 60000 ms node-wide `consumer_timeout`.

### Broker runtime image

#74 selects the RabbitMQ 4.4 series, community-supported until 2027-07-31 (https://www.rabbitmq.com/release-information). `deploy/docker-compose.yml` pins the Docker official `rabbitmq:<4.4.x>-alpine` multi-arch index digest (amd64 and arm64) with a version comment and sets `hostname: rabbitmq`: the broker stores data under its node name, which defaults to the hostname, so a recreated container without a fixed hostname starts an empty node on the same volume. The image carries SBOM and provenance attestations; container OS scanning stays with #54.

The production overlay mounts `deploy/rabbitmq/90-vod-prod.conf`, which sets `total_memory_available_override_value` to the broker's 512M memory limit, so the memory alarm is based on the container limit instead of the host RAM (owner decision, 2026-11-10). Change the two together.

Update procedure:
1. Watch rabbitmq-server GitHub releases and security advisories, and the release-information page.
2. On a 4.4 patch, a relevant advisory or an official image rebuild (checked weekly), bump the tag and index digest.
3. Run the broker smoke and the full-stack CI; record `rabbitmqctl version` and the Erlang version in the PR.
4. Before the series leaves community support, upgrade along a supported path of the upgrade guide, enabling all stable feature flags before and after.
5. Back up the `rabbitmq_data` volume before any upgrade. RabbitMQ does not support downgrades, and 4.4 classic-queue store files cannot be read by 4.3 or earlier; roll back by restoring the backup with the previous digest.

### Worker runtime image

[ADR-010 D25](adr/ADR-010.md#sprint-5-worker-media-decisions-d25-d28) selects the worker image: `debian:trixie-slim` pinned by digest, Debian `ffmpeg` and `openjdk-21-jre-headless` from trixie and trixie-security, a non-root runtime user, for amd64 and arm64. The base and packages landed first, with the #123 harness: `worker/Dockerfile` pins the trixie-slim index digest in a `runtime-base` stage, so the 5.4/5.5 command tests and the #124 arm64 measurement all run the D25 binary. On 2026-11-03 it installed ffmpeg `7:7.1.5-0+deb13u1` and openjdk-21-jre-headless `21.0.13+9-1~deb13u2`. Its `ffmpeg-test` stage adds a JDK and Maven on top of `runtime-base` for the FFmpeg-tagged tests (`deploy/tests/worker-ffmpeg-tests.sh`) and is never deployed. Issue 5.8 then adds the non-root, read-only and volume hardening, the version-floor assertion and the weekly rebuild.

Update procedure:
1. Watch debian-security-announce for ffmpeg and openjdk-21 advisories.
2. On an advisory, and weekly, bump the base digest and build with `--pull --no-cache`.
3. Raise the recorded minimum ffmpeg version when the advisory requires it. CI asserts it with `dpkg --compare-versions` and asserts a non-root `id -u`.
4. Run the #123 media tests (`sh deploy/tests/worker-ffmpeg-tests.sh`, part of the `Worker Maven build and test` CI job) on amd64 and in the #124 `arm64 worker` workflow.
5. Record the `dpkg-query -W` versions in the PR.
6. Roll back by redeploying the previous image tag.

The weekly rebuild and the version-floor assertion are new CI work for Issue 5.8. Compose `pids_limit` counts threads, so size it for the JVM threads plus the FFmpeg threads.

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

Once the body was fully sent, the SPA reports an unknown upload result for any 5xx or network error, without inferring acceptance from MovieDetail.videoStatus, unless the backend `ApiError` `code` is `UPLOAD_STORAGE_UNAVAILABLE` (503) or `UPLOAD_DATABASE_ERROR` (500). Those mean the upload was not accepted (no committed asset/job pair), so the SPA shows a failure and allows manual retry. Edge 502/503/504, `UPLOAD_OUTCOME_UNKNOWN`, `INTERNAL_ERROR` and unmapped 500s stay unknown. No automatic body resubmit: deliberate re-upload yields 409 while an asset is in-flight, or creates a newer asset (extra storage reclaimed by manual purge) if the first finished. The direct newest-asset view belongs to #168; no new endpoint is introduced here.

Under `/api/v1/`, map edge-generated 413/502/503/504 through separate named error locations (or an equivalent status-preserving map) to `application/json` `ApiError`: `timestamp=$time_iso8601`, numeric original `status`, safe fixed `message`, `requestId=$request_id`, and codes `PAYLOAD_TOO_LARGE`, `UPSTREAM_UNAVAILABLE` (502/503), `UPSTREAM_TIMEOUT` (504). Use the same request ID sent upstream as `X-Request-ID`; keep `proxy_intercept_errors off` so backend JSON is not replaced. Ensure an early body-limit error selects the JSON mapping. Do not use a single named return that flattens every status to 413.

#52 runtime evidence: small/auth routes reject >1 MiB; upload permits a valid 2 MiB request and rejects >2048 MiB; edge errors match JSON schema/status/request ID; HLS/health routing stays unchanged. The stub needs its own `client_max_body_size 0` and a marker/202 upload response (stock Nginx's 1m would reject the accepted case). Use deterministic unavailable and accept-without-response stubs for 502/504; a test-only short timeout can accelerate the 504, with an assertion that production stays 600s. Add backend-direct oversize/413, unauthorized-before-parse/no-temp-file and cancelled-upload tests. Issue 4.6/4.7 records a synthetic 2,000,000,000-byte transport/storage rehearsal in a `workflow_dispatch` job on a GitHub-hosted runner; it is not VPS evidence and proves neither encoding, ARM throughput nor VPS disk headroom. The genuine 2 GB upload on the target ARM VPS moves to #180, which blocks public deployment: client duration over WAN, saving wait after 100%, temp-spool peak, memory peak, actual VPS disk size/data root and concurrent-upload temp-disk headroom with the `limit_conn`/fallback decision, all **to be measured**. No such run is claimed here.

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
