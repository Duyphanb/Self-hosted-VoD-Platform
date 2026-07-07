# Traceability Matrix

## Status

This is the planned Phase 1 traceability baseline. Test evidence names are expected evidence targets until implementation exists.

## Feature Traceability

| Feature ID | Feature | User Stories | Primary Module(s) | Planned Test Evidence |
|---|---|---|---|---|
| F-001 | Account registration | US-001 | backend, frontend | Auth service unit tests; registration API Postman tests; frontend auth flow test |
| F-002 | Login and JWT authentication | US-002 | backend, frontend | Auth service tests; security filter tests; login API Postman tests |
| F-003 | Logout session handling | US-003 | frontend, backend | Frontend logout flow test; protected route manual test |
| F-004 | RBAC | US-004 | backend, frontend | Admin endpoint authorization tests; role guard UI tests |
| F-005 | Movie browsing | US-005 | backend, frontend | Movie list service tests; pagination API tests; list page UI tests |
| F-006 | Movie detail | US-006 | backend, frontend | Movie detail API tests; not-found API test; detail page UI test |
| F-007 | Movie metadata management | US-007 | backend, frontend | Movie service unit tests; admin CRUD Postman tests; admin form tests |
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
| F-023 | Profile update | US-019 | backend, frontend | Profile update API tests; current-user ownership test; profile UI mutation test |

## NFR Traceability

| NFR | Related Features | Evidence |
|---|---|---|
| NFR-001 | F-021 | Docker Compose smoke test |
| NFR-002 | F-021, F-022 | VPS resource observation during demo smoke test |
| NFR-003 | F-008 | Upload size and MIME allowlist validation test |
| NFR-004 | F-010 | Worker concurrency config test or documented startup evidence |
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
| NFR-015 | Backend API features | Error response tests |
| NFR-016 | Frontend features | UI state tests or manual checklist |

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
