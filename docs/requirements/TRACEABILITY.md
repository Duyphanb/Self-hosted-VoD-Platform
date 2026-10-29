# Traceability Matrix

## Status

Updated 2026-10-29 for [ADR-010](../architecture/adr/ADR-010.md). Rows for delivered features point to verified evidence: Sprint 2 (F-001 to F-004 and the F-023 backend) is verified in the [Sprint 2 closeout](../testing/SPRINT-2-CLOSEOUT.md), and Sprint 3 (F-005 to F-007) in the [Sprint 3 closeout](../testing/SPRINT-3-CLOSEOUT.md). Other rows list the planned test evidence until the feature is implemented; this design update supplies no runtime media evidence.

## Feature Traceability

| Feature ID | Feature | User Stories | Primary Module(s) | Planned Test Evidence |
|---|---|---|---|---|
| F-001 | Account registration | US-001 | backend, frontend | Auth service unit tests; registration API Postman tests; frontend auth flow test. Verified: [Sprint 2 closeout](../testing/SPRINT-2-CLOSEOUT.md) |
| F-002 | Login and JWT authentication | US-002 | backend, frontend | Auth service tests; security filter tests; login API Postman tests. Verified: [Sprint 2 closeout](../testing/SPRINT-2-CLOSEOUT.md) |
| F-003 | Logout session handling | US-003 | frontend, backend | Frontend logout flow test; protected route manual test. Verified: [Sprint 2 closeout](../testing/SPRINT-2-CLOSEOUT.md) |
| F-004 | RBAC | US-004 | backend, frontend | Admin endpoint authorization tests; role guard UI tests. Verified: [Sprint 2 closeout](../testing/SPRINT-2-CLOSEOUT.md) |
| F-005 | Movie browsing | US-005 | backend, frontend | Movie list service tests; pagination and visibility API tests; catalog PostgreSQL runtime smoke; catalog Newman collection; list page UI tests. Verified: [Sprint 3 closeout](../testing/SPRINT-3-CLOSEOUT.md) |
| F-006 | Movie detail | US-006 | backend, frontend | Movie detail API tests; not-found and non-published 404 API tests; catalog PostgreSQL runtime smoke; catalog Newman collection; detail page UI test. Verified: [Sprint 3 closeout](../testing/SPRINT-3-CLOSEOUT.md) |
| F-007 | Movie metadata management | US-007 | backend, frontend | Movie, genre, and people service unit tests; admin authorization tests; catalog PostgreSQL runtime smoke; catalog Newman collection (admin CRUD); admin form tests. Related video asset metadata is delivered with F-008 and F-020. Verified: [Sprint 3 closeout](../testing/SPRINT-3-CLOSEOUT.md) |
| F-008 | Admin video upload | US-008 | backend, frontend, MinIO | Upload validation tests; MinIO integration test or manual evidence; admin upload flow test |
| F-009 | Queue publishing | US-008 | backend, RabbitMQ | Queue publisher unit test; API-to-queue integration evidence |
| F-010 | Encoding worker | US-009 | worker, RabbitMQ, MinIO | Worker command construction tests; job status transition tests; failed-job test |
| F-011 | FFprobe metadata extraction | US-009 | worker | FFprobe parser unit tests; sample media manual verification |
| F-012 | FFmpeg HLS transcoding | US-009, US-010 | worker, MinIO | HLS output smoke test; generated playlist and segments verification |
| F-013 | HLS playback | US-010 | backend, frontend, nginx | Playback API Postman test; browser playback smoke test; HLS MIME check |
| F-014 | Playback progress and resume | US-011 | backend, frontend, Redis, PostgreSQL | Progress API tests; Redis flush test; resume manual E2E evidence |
| F-015 | Continue watching | US-011 | backend, frontend | Continue-watching API tests; UI list test |
| F-016 | Search and suggestions | US-012 | backend, frontend, PostgreSQL | Search service tests; FTS seed data tests; search UI tests |
| F-017 | Watchlist | US-013 | backend, frontend | Watchlist API tests; duplicate prevention test; UI mutation test |
| F-018 | Watch history | US-014 | backend, frontend | Watch history service tests; ownership authorization test |
| F-019 | Rating | US-015 | backend, frontend | Rating validation tests; aggregate rating test; UI mutation test |
| F-020 | Encoding status and retry | US-016 | backend, worker, frontend, RabbitMQ | Retry API authorization test; failed-to-queued transition test; admin dashboard test |
| F-021 | Local Docker runtime | US-017 | deploy, backend, worker, frontend | Docker Compose smoke test; health check evidence |
| F-022 | VPS demo deployment | US-018 | deploy, docs/deployment | Deployment runbook; HTTPS smoke test; backup/rollback checklist |
| F-023 | Profile update | US-019 | backend, frontend | Profile update API tests; current-user ownership test; profile UI mutation test. Backend verified: [Sprint 2 closeout](../testing/SPRINT-2-CLOSEOUT.md); profile UI tracked in #131 |

## NFR Traceability

| NFR | Related Features | Evidence |
|---|---|---|
| NFR-001 | F-021 | Docker Compose smoke test |
| NFR-002 | F-021, F-022 | VPS resource observation during demo smoke test |
| NFR-003 | F-008 | `.env.example` size/MIME allowlist configuration and backend rejection outside those limits; declared MIME/signature family, equal MiB ceilings including multipart overhead, route-limit JSON errors and real 2 GB prod-overlay timings/temp/memory (Issues 4.4/4.6/4.7) |
| NFR-004 | F-010 | One non-overlapping worker, prefetch/concurrency 1, startup recovery, caps/deadlines and long-job/second-queued-job test (5.1/5.3/5.7/#124) |
| NFR-005 | F-012, F-013 | Browser playback smoke test |
| NFR-006 | F-014, F-015 | Redis-to-PostgreSQL flush test |
| NFR-007 | F-001, F-002, F-004, F-020 | Auth and authorization tests |
| NFR-008 | All runtime features | Secret scan/manual review; `.env.example` review |
| NFR-009 | F-008, F-012 | MinIO storage integration evidence |
| NFR-010 | F-016 | Search seed-data test |
| NFR-011 | F-010, F-021, F-022 | Health endpoint and lifecycle log review |
| NFR-012 | All features | Documentation review before milestone completion |
| NFR-013 | All MVP features | This traceability matrix plus test artifacts |
| NFR-014 | F-021, F-022 | Compose service-name verification |
| NFR-015 | Backend API features | Contract ApiError tests, edge 413/502/503/504 JSON/status/requestId and SPA unknown-result/no-auto-resubmit handling even while videoStatus stays READY |
| NFR-016 | Frontend features | UI state tests or manual checklist |

## Sprint 4 design-to-test plan

All rows below are **planned**, not passed tests. Copy them into implementation issues; #125 requires actual upload -> transcode -> HLS browser playback.

| Decisions / feature | Planned evidence and owner |
|---|---|
| D1-D5, F-008 | 4.4/4.6: header auth before multipart parse/no temp, known-length spool/store, edge/backend oversize, cancel cleanup, deterministic slow/unavailable upstream; 4.7: XHR pre-check/progress/saving/no replay/status 0/504; manual genuine 2 GB prod-overlay metrics |
| D6-D9, F-011/F-012 | #54 patched-binary/user evidence; #123 negative media fixtures; #124 target CPU measurement; 5.4/5.5/5.7/5.8 command restrictions, metadata caps, process-tree kill, disk check and container hardening |
| D11-D15/D17, F-008/F-013/F-020 | 4.3/4.6 PostgreSQL partial index/concurrent 409 compensation; catalog READY fallback/tie ordering; 6.1/6.2 current-only playback/HLS/visibility-first 404; 9.2/9.3 newest-only retry/double retry/upload race |
| D16/D19, F-009 | 4.2/4.5 golden fixture, confirms/returns, commit-before-publish, relay grace/crash/publish failure and fast-worker no-regression |
| D18/D20/D21, F-010 | 5.1/5.3/5.7 terminal redelivery, stale attempt, poison DLQ, DB outage, orphan restart and long job with second queued; #74 version-dependent ack timeout |
| D22/D23, F-012/F-022, R-006 | 4.4 compensating raw delete/failed compensation; 5.6/5.7 attempt isolation, failed/lost-finish/orphan prefix delete, trailing-slash safety and temp sweep; Phase 5 manual dry-run purge/orphan audit protects current/newest FAILED/in-flight/archive |
| D10/D24 | DTO bounds remain separate follow-up; one ADR/docs package, V5/counts implemented later in 4.3; independent AI review when a PR exists, before owner merge |

## Evidence Storage Plan

| Evidence Type | Future Location |
|---|---|
| Automated backend tests | `backend/**/src/test/` |
| Automated worker tests | `worker/**/src/test/` |
| Frontend tests | `frontend/src/**/__tests__/` or chosen test folder |
| Postman collection | `docs/testing/postman/` or root collection if later selected |
| Manual E2E checklist | `docs/testing/` |
| Deployment proof and runbook | `docs/deployment/` |
| OpenAPI contract | `docs/architecture/API-CONTRACT.yaml` in Phase 2 |
