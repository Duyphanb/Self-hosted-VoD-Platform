# Non-Functional Requirements

## Operating Assumption

The MVP targets a single Oracle Cloud Free Tier VPS, expected baseline: Ampere A1 ARM, 2 OCPU, 12 GB RAM, Ubuntu Linux, Docker Compose, Nginx, PostgreSQL, Redis, RabbitMQ, MinIO, backend, worker, and frontend.

## Requirements

| ID | Category | Requirement | Measure / Acceptance |
|---|---|---|---|
| NFR-001 | Deployability | The full stack must run through Docker Compose locally before VPS deployment. | A documented command starts all required containers and health checks pass. |
| NFR-002 | Resource fit | MVP runtime must fit on a 2 OCPU / 12 GB RAM VPS under demo load. | Baseline containers start with stable memory use and no repeated OOM restarts during smoke testing. |
| NFR-003 | Upload limits | Maximum upload size and allowed MIME types must be configurable. | `.env.example` exposes `MAX_UPLOAD_SIZE_MB` and an allowlist for MP4, MKV, WebM, and MOV; backend rejects files above the configured limit or outside the allowlist. |
| NFR-004 | Encoding stability | Worker concurrency must default to 1 for MVP. | One encoding job is processed at a time unless configuration explicitly changes it. |
| NFR-005 | Playback readiness | A ready video must be playable in a modern desktop browser. | A smoke test plays generated HLS for at least 60 seconds without manual file access. |
| NFR-006 | Progress durability | Playback progress may be buffered but must not remain only in memory. | Redis-buffered progress is flushed to PostgreSQL on a scheduled interval. |
| NFR-007 | API security | Protected APIs must reject unauthenticated requests and enforce roles. | Auth and RBAC tests cover user-only, admin-only, and anonymous access. |
| NFR-008 | Secret handling | Secrets must not be committed. | `.env.example` uses placeholders; real secrets are supplied through environment variables. |
| NFR-009 | Data storage | Raw and processed media must be stored in object storage for MVP. | MinIO buckets store raw uploads and HLS artifacts; app containers do not rely on permanent local media files. |
| NFR-010 | Search quality | Search must use real database-backed metadata. | Search tests seed real movies and verify title, description, genre, actor, and director matches. |
| NFR-011 | Observability | Each runtime must expose enough diagnostics for demo operations. | Backend has health endpoint and structured logs; worker logs job lifecycle and failures. |
| NFR-012 | Documentation accuracy | Docs must match implemented behavior. | README, OpenAPI, Postman, and deployment docs are reviewed before each release milestone. |
| NFR-013 | Testability | Business rules must be covered by automated or documented manual tests. | Traceability file maps each MVP feature to planned test evidence before implementation. |
| NFR-014 | Portability | Local and VPS deployment must use the same service names where practical. | Compose env vars use stable service aliases: `postgres`, `redis`, `rabbitmq`, `minio`, `backend`, `worker`, `frontend`, `nginx`. |
| NFR-015 | Error handling | API failures must be predictable and machine-readable. | Backend uses centralized exception handling with consistent error response DTOs. |
| NFR-016 | Browser UX | User-facing async states must be explicit. | Frontend screens for major flows include loading, error, empty, and success states. |

## MVP Performance Targets

These are demo-grade targets, not production SLA commitments.

| Area | Target |
|---|---|
| Login API | Responds within 1 second under local demo conditions. |
| Movie list API | Responds within 1 second for up to 500 seeded movies. |
| Search API | Responds within 1 second for up to 500 seeded movies using PostgreSQL FTS. |
| Playback progress save | Client sends updates every 5 to 10 seconds; backend accepts without visible playback interruption. |
| Encoding | Completion time depends on source size and VPS resources; correctness is prioritized over speed. |
| Recovery | Failed encoding jobs can be inspected and retried by admin. |

## Documentation Requirements

- Requirement IDs must be stable after implementation begins.
- Architecture docs must not contradict these NFRs.
- Any NFR change must update `TRACEABILITY.md` and the relevant implementation plan.
