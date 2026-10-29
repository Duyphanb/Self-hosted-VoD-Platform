# Observability Architecture

Status: Frozen for MVP implementation

## Goals

The MVP uses lightweight observability suitable for local Docker Compose and one Oracle VPS. Prometheus, Grafana, Loki, and distributed tracing are deferred.

Required visibility:

- service health
- API failures
- upload lifecycle
- encoding lifecycle
- HLS access failures
- playback-progress flush
- deployment smoke status

## Health Checks

| Component | Health Signal |
|---|---|
| nginx | HTTP 200 for `/` and proxy reachability for `/api/v1/health` |
| backend | `/api/v1/health` for public smoke checks and `/actuator/health` internally |
| worker | Spring Boot Actuator health plus startup log confirming FFmpeg and FFprobe availability |
| PostgreSQL | Compose health check using database readiness |
| Redis | `PING` health check |
| RabbitMQ | broker health check |
| MinIO | MinIO health endpoint |

Backend readiness must verify PostgreSQL, Redis, RabbitMQ, and MinIO after those integrations exist.

## Logging

Logging requirements:

- use SLF4J in backend and worker
- no `System.out.println`
- include timestamp, level, logger, message, and `requestId` where present
- do not log JWTs, refresh tokens, passwords, raw secrets, or sensitive request bodies
- include `videoAssetId` and `encodingJobId` in worker lifecycle logs

Important backend events:

- registration success/failure category
- login success/failure category
- admin upload accepted/rejected
- raw object stored
- encoding job published
- publication confirm/return/timeout and relay republish with job/asset IDs; distinguish committed QUEUED pending publication from a rejected upload
- raw compensation success/failure (WARN on failure), without secret-bearing diagnostics
- HLS authorization failure
- progress accepted
- progress flush success/failure
- admin retry requested

Important worker events:

- job received
- status transition to `PROCESSING`
- FFprobe success/failure
- FFmpeg success/failure
- HLS upload success/failure
- status transition to `READY` or `FAILED`
- temp cleanup success/failure
- duplicate/stale attempt skipped and poison message dead-lettered
- startup orphan marked FAILED `WORKER_INTERRUPTED`, attempt cleanup outcome and bytes freed from temp sweep
- one event per attempt-prefix/manual object deletion, with IDs and safe outcome
- bounded DB-outage retry/backoff and process deadline/resource-cap failure

## Metrics

MVP metrics come from Spring Boot Actuator plus simple application counters when practical.

Required metric categories:

- HTTP request count and latency
- auth failure count
- upload accepted/rejected count
- encoding job queued count
- encoding job `READY`/`FAILED` count
- encoding duration
- worker active job count
- progress flush count and failure count
- search latency

Metrics do not require Prometheus in MVP.

Metric categories are targets; implementation priority follows feature priority.

## Failure Visibility

Encoding failures must be visible to admin users through the admin video asset view.

Required failure fields:

- video asset ID
- current status
- latest job attempt
- safe failure reason
- queued, started, and finished timestamps
- retry eligibility: newest `(created_at, id)` asset of its movie and FAILED only; superseded rows are historical, may reference purged objects, and must be labelled accordingly in the admin view

Failure messages shown in UI must be safe summaries. Stack traces stay in server logs only.

Issue #107 finalizes the safe-code mapping; reserve `PROBE_FAILED`, `PROBE_TIMEOUT`, `ENCODE_TIMEOUT`, `MEDIA_LIMIT_EXCEEDED`, `INSUFFICIENT_DISK` and `WORKER_INTERRUPTED` for the approved safeguards. A transient DB outage is not a media failure or DLQ reason. QUEUED age alone is not evidence of a stuck job. `published_at NULL` on QUEUED means relay pending; queued/started/finished times and publication outcomes support diagnosis. Do not log uncapped FFmpeg output, tokens, storage credentials or sensitive request bodies.

## Correlation

MVP uses `X-Request-ID`.

- Nginx accepts or generates `X-Request-ID`.
- Nginx forwards `X-Request-ID` to backend.
- Backend includes `requestId` in logs and error responses.
- Worker logs include `encodingJobId` and `videoAssetId`.
- Queue payloads include `encodingJobId` and `videoAssetId`.
- The versioned IDs-only envelope is `{schemaVersion: 1, encodingJobId, videoAssetId, attempt}`; optional `x-request-id` is an AMQP header, not a Java class/type header. [ADR-010](adr/ADR-010.md#message-consumer-and-recovery-d18-d21) is the schema/claim/ack authority; backend and worker parse the same golden fixture in implementation tests.

## Dashboards

No Grafana dashboard is required for MVP.

Admin UI must still show:

- uploaded assets
- encoding status
- failure reason
- retry action when eligible

## Deferred Observability

Deferred until MVP is stable:

- Prometheus
- Grafana
- Loki
- distributed tracing
- alerting rules
- long-term log aggregation

## Smoke Evidence

Before demo readiness, collect evidence for:

- Nginx serves frontend
- Nginx proxies `/api/v1/health`
- backend health is healthy
- worker starts and confirms FFmpeg/FFprobe availability
- RabbitMQ is reachable
- MinIO buckets exist
- PostgreSQL migrations applied
- HLS manifest and segment requests use correct MIME types
