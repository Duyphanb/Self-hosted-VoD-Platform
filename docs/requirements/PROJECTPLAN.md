# Project Plan

## Delivery Model

This project is delivered in small, reviewable batches. Phase 1 defines requirements only. Architecture and implementation are intentionally deferred to later phases.

## Phase Plan

| Phase | Focus | Required Outputs | Exit Criteria |
|---|---|---|---|
| Phase 0 | Project harness and AI infrastructure | Repo skeleton, `AGENTS.md`, environment baseline, prompt notes | Repo structure is stable and Codex rules are clear |
| Phase 1 | Requirements and planning | Requirement documents under `docs/requirements/` | MVP scope, stories, NFRs, risks, glossary, and traceability are defined |
| Phase 2 | Architecture and design | System architecture, ERD, API contract, sequences, infrastructure, security, observability, ADRs | Architecture is reviewed before implementation |
| Phase 3 | Feature development | Backend, frontend, worker, tests, docs updates | MVP features implemented and tested |
| Phase 4 | Testing and hardening | Postman, Newman plan, manual E2E checklist, known limitations | End-to-end flow is stable |
| Phase 5 | Deployment and operations | Production compose, Nginx, deployment docs, backup/restore, rollback, runbook | VPS demo is healthy over HTTPS |
| Phase 6 | Portfolio finalization | Polished README, demo video, architecture image, CV-aligned claims | Project is interview-ready |

## MVP Sprint Plan

See `BACKLOG.md` for detailed sprint backlog with issues, acceptance criteria, dependencies, and definition of done per sprint.

| Sprint | Goal | Deliverables |
|---|---|---|
| 1 | Foundation | Maven multi-module structure, frontend scaffold, worker scaffold, Compose baseline, health endpoint, Flyway V1 |
| 2 | Auth | Register, login, logout, JWT, RBAC, route guards |
| 3 | Movie CRUD | Movie domain model, metadata APIs, admin UI, pagination |
| 4 | Upload | Validation, raw upload to MinIO, video asset status, RabbitMQ publish |
| 5 | Worker | Queue consumer, FFprobe, FFmpeg HLS, status transitions, failure handling baseline |
| 6 | Streaming | Playback API, HLS delivery, player integration, progress save/resume |
| 7 | Search | PostgreSQL FTS, search suggestions, search UI |
| 8 | User features | Watchlist, watch history, rating, profile |
| 9 | Admin operations | Encoding dashboard, failed-job retry, status visibility |
| 10 | Polish | Error handling, docs, Swagger/OpenAPI, Postman, VPS preparation |

## Implementation Principles

- Build local end-to-end before deployment polish.
- Keep worker concurrency at 1 until the encoding flow is reliable.
- Freeze ERD and API contract before broad feature implementation.
- Add tests with behavior changes.
- Update docs when behavior, configuration, or contracts change.
- Do not promote optional features until the MVP path is stable.

## Milestone Gates

### Gate 1 - Requirements Ready

- `VISION.md` describes product direction.
- `USERSTORIES.md` covers major MVP features with acceptance criteria.
- `NFR.md` defines measurable constraints.
- `OUT_OF_SCOPE.md` prevents scope creep.
- `TRACEABILITY.md` maps features to planned evidence.
- `DOMAINGLOSSARY.md` stabilizes shared vocabulary.
- `RISKREGISTER.md` lists execution risks and mitigations.

### Gate 2 - Architecture Ready

- ERD reviewed.
- OpenAPI baseline reviewed.
- Upload, encoding, playback, and progress flows documented.
- Security boundaries documented.
- Deployment topology documented.

### Gate 3 - Local MVP Ready

- Admin upload works.
- Worker produces HLS.
- User playback works.
- Progress/resume works.
- Search and user library features use real data.
- Local Compose stack is documented and repeatable.

### Gate 4 - Demo Ready

- VPS deployment works over HTTPS.
- README and docs match reality.
- Smoke tests and known limitations are documented.
- CV claims are limited to working functionality.

## GitHub Task Workflow

- Each task should map to a GitHub Issue when possible.
- Commit and PR messages should use conventional commits.
- If a task fully completes an issue, include a closing keyword such as `closes #5`.
- If a task only contributes to an issue, reference the issue without closing it.

## Current Next Step

Continue Phase 0 / Batch 4 by creating the baseline container and deployment skeleton only:

- backend, worker, and frontend Dockerfiles
- local and production Docker Compose skeletons
- Nginx routing skeleton for frontend, API, and HLS paths
- CI skeleton that validates builds and configuration as far as possible

Do not add business logic in this batch. Phase 2 architecture is already frozen for MVP implementation.
