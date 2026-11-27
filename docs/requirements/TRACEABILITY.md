# Traceability Matrix

## Status

Updated 2026-11-26 for the owner's scope decisions ([#258](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/258)): F-016 and NFR-010 re-planned and rows F-024 to F-036 added, as planned evidence only; nothing in them is verified yet. Updated 2026-11-26 for the Sprint 6 closeout, 2026-11-14 for the Sprint 6 streaming design ([ADR-011](../architecture/adr/ADR-011.md)) and 2026-11-13 for the Sprint 5 closeout; the upload and worker design follows [ADR-010](../architecture/adr/ADR-010.md). Rows for delivered features point to verified evidence: Sprint 2 (F-001 to F-004 and the F-023 backend) is verified in the [Sprint 2 closeout](../testing/SPRINT-2-CLOSEOUT.md), Sprint 3 (F-005 to F-007) in the [Sprint 3 closeout](../testing/SPRINT-3-CLOSEOUT.md), Sprint 4 (F-008 and F-009) in the [Sprint 4 closeout](../testing/SPRINT-4-CLOSEOUT.md), Sprint 5 (F-010 to F-012) in the [Sprint 5 closeout](../testing/SPRINT-5-CLOSEOUT.md), and Sprint 6 (F-013 to F-015, with the playback part of F-012) in the [Sprint 6 closeout](../testing/SPRINT-6-CLOSEOUT.md). Other rows list the planned test evidence until the feature is implemented; no row has VPS evidence yet.

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
| F-008 | Admin video upload | US-008 | backend, frontend, MinIO | Upload validation tests; MinIO integration test or manual evidence; admin upload flow test. Verified: [Sprint 4 closeout](../testing/SPRINT-4-CLOSEOUT.md) |
| F-009 | Queue publishing | US-008 | backend, RabbitMQ | Queue publisher unit test; API-to-queue integration evidence. Verified: [Sprint 4 closeout](../testing/SPRINT-4-CLOSEOUT.md) |
| F-010 | Encoding worker | US-009 | worker, RabbitMQ, MinIO | Worker command construction tests; job status transition tests; failed-job test. Verified: [Sprint 5 closeout](../testing/SPRINT-5-CLOSEOUT.md) |
| F-011 | FFprobe metadata extraction | US-009 | worker | FFprobe parser unit tests; #123 generated-fixture probe tests with the worker image binary. Verified: [Sprint 5 closeout](../testing/SPRINT-5-CLOSEOUT.md) |
| F-012 | FFmpeg HLS transcoding | US-009, US-010 | worker, MinIO | HLS output smoke test; generated playlist and segments verification; #123 generated fixtures through the worker image binary with output validation. Encoding to `vod-hls` verified in the [Sprint 5 closeout](../testing/SPRINT-5-CLOSEOUT.md); browser playback of the encoded output (US-010) verified in the [Sprint 6 closeout](../testing/SPRINT-6-CLOSEOUT.md) |
| F-013 | HLS playback | US-010 | backend, frontend, nginx | Playback API tests and Postman test; HLS proxy tests (MIME, `no-store`, current-only 404, query-token 401); player unit tests (token refresh, error mapping, hls.js-only path); one manual row per verified browser (current Chrome or Edge, and Firefox); browser playback smoke test (#125). Verified: [Sprint 6 closeout](../testing/SPRINT-6-CLOSEOUT.md) (Chrome playback smoke on `main`; Edge, Chromium and Chrome rows by hand and a scripted Firefox row on #242; the owner accepted the scripted Firefox row as a recorded deviation from ADR-011 D5); a Sprint 6 Newman collection is still missing (#254) |
| F-014 | Playback progress and resume | US-011 | backend, frontend, PostgreSQL | Progress API tests on PostgreSQL (forward-only upsert, clamp, 90% completion, history write, ownership); player save-trigger test; resume manual E2E evidence. Verified: [Sprint 6 closeout](../testing/SPRINT-6-CLOSEOUT.md) (save and reload resume also in the Chrome playback smoke); Newman coverage missing (#254) |
| F-015 | Continue watching | US-011 | backend, frontend | Continue-watching API tests (unfinished only, PUBLISHED only, order, limit); UI list test. Verified: [Sprint 6 closeout](../testing/SPRINT-6-CLOSEOUT.md) |
| F-016 | Search and suggestions | US-012 | backend, frontend, PostgreSQL | Planned (ADR-015): normalizer unit tests and PostgreSQL rebuild/race tests (Issue 7.4, #261); relevance fixture with Vietnamese, Japanese and romaji cases, visibility and total-order tests, `EXPLAIN` record (Issue 7.5, #263); suggestion and search-page tests (Issue 7.6, #264); 500-title timings (Issue 7.7, #129) |
| F-017 | Watchlist | US-013 | backend, frontend | Watchlist API tests; duplicate prevention test; UI mutation test |
| F-018 | Watch history | US-014 | backend, frontend | Watch history service tests; ownership authorization test |
| F-019 | Rating | US-015 | backend, frontend | Rating validation tests; aggregate rating test; UI mutation test |
| F-020 | Encoding status and retry | US-016 | backend, worker, frontend, RabbitMQ | Retry API authorization test; failed-to-queued transition test; admin dashboard test |
| F-021 | Local Docker runtime | US-017 | deploy, backend, worker, frontend | Docker Compose smoke test; health check evidence |
| F-022 | VPS demo deployment | US-018 | deploy, docs/deployment | Deployment runbook; HTTPS smoke test; backup/rollback checklist |
| F-023 | Profile update | US-019 | backend, frontend | Profile update API tests; current-user ownership test; profile UI mutation test. Backend verified: [Sprint 2 closeout](../testing/SPRINT-2-CLOSEOUT.md); profile UI tracked in #131 |
| F-024 | Content model: franchises, titles, episodes | US-005, US-006, US-007 | backend, frontend, PostgreSQL | Planned (ADR-013): populated upgrade smoke and unchanged suites after the rename (Issue 7.1, #259); populated upgrade with an `EXCEPT` bijection, concurrent-upload race tests and the Chrome smoke on episode routes (Issue 7.2, #260); three-level visibility tests (Issue 7.3, #262); episode-grid tests (Issue 8.12) |
| F-025 | Activation and Admin Preview | US-008, US-016 | backend, frontend | Planned (ADR-013, ADR-019): activation race tests, zero progress and history requests in preview including page exit; on `/hls/admin/**` 403 for a signed-in non-admin or demoted admin and 401 for a signed-out or revoked session, both `no-store`; 404 for a candidate on the viewer HLS routes (Issue 9.6) |
| F-026 | Custom player controls | US-010 | frontend | Planned (ADR-018): hotkey and menu unit tests, Chrome run on the real stack, axe scan, manual keyboard-only and screen-reader rows per browser (Issue 8.8); picture-in-picture rows (Issue 10.9) |
| F-027 | Subtitles | US-010 | backend, frontend, worker | Planned (ADR-019): parser and storage tests (Issue 8.9, #247), Chrome and Firefox sync and recovery tests (Issue 8.10), extraction fixtures (Issue 9.11), renderer spike (Issue 10.11, #248) |
| F-028 | Timed markers, skip and chapters | US-010 | backend, frontend, worker | Planned (ADR-019): marker validation and carry tests (Issue 9.7), skip-region browser tests (Issue 9.8) |
| F-029 | Multi-audio | US-009, US-010 | worker, backend, frontend | Planned (ADR-016, ADR-018): playlist and route tests, Chrome track switch and seek (Issue 9.9), optional-track recovery tests (Issue 9.10) |
| F-030 | Rule-based recommendations | US-005, US-006 | backend, frontend | Planned (ADR-015): exclusion and ordering fixtures, two-user isolation, no-seed and no-store tests (Issues 8.13, 8.14) |
| F-031 | Collections | US-005 | backend, frontend | Planned (ADR-021): draft/archive/restore and visibility tests (Issue 8.15), rule validation and query plans (Issue 9.17) |
| F-032 | Catalogue images | US-005, US-006, US-007 | backend, frontend, worker | Planned (ADR-020): visibility and Blob-lifecycle tests (Issue 8.19), frame fixtures (Issue 8.20), upload rejection and sandbox tests (Issue 9.15), crop tests (Issue 9.16) |
| F-033 | Metadata import | US-007 | backend, frontend | Planned (ADR-014): provenance, revision and withdrawal tests with a fake clock (Issue MU.1), zero outbound calls for non-admins and provider-failure tests (Issues MU.2-MU.5, #245, #246) |
| F-034 | Resumable upload | US-008 | backend, frontend, infra | Planned (ADR-017): resume, lost-response and orphan tests (Issue MU.6), real-Chrome resume (Issue MU.7), 2 GB and 8 GiB target-VM rows (#180) |
| F-035 | Backup and recovery | US-018 | backend, infra | Planned (ADR-016): capture-consistency and receipt tests (Issue 9.12), release-disabled and reconcile tests (Issue 9.13), restore drill and disaster-recovery document (Pre-production gate, #136) |
| F-036 | Media ingest selection and the 1080p default | US-009 | worker | Planned (ADR-016): generated stream fixtures and memory peak (Issue 8.18), sandbox negatives (Issue 8.17), target-VM gate G1-G6 (G1/G2 #124, G3 the owner's visual review, G4/G6 #233, G5 #137 with #190) and 1080p browser playback |

## NFR Traceability

| NFR | Related Features | Evidence |
|---|---|---|
| NFR-001 | F-021 | Docker Compose smoke test |
| NFR-002 | F-021, F-022 | VPS resource observation during demo smoke test |
| NFR-003 | F-008 | `.env.example` size/MIME allowlist configuration and backend rejection outside those limits; declared MIME/signature family, equal MiB ceilings including multipart overhead, route-limit JSON errors and a synthetic non-VPS 2 GB runner rehearsal (Issues 4.4/4.6/4.7); genuine 2 GB VPS timings/temp/memory/disk and concurrent-upload headroom (#180, before public deployment) |
| NFR-004 | F-010 | One non-overlapping worker, prefetch/concurrency 1, startup recovery, owner product caps checked in 5.4 (ADR-010 D26), deadlines measured in #124, and a long-job/second-queued-job test (5.1/5.3/5.7) |
| NFR-005 | F-012, F-013 | Browser playback smoke test (#125) and one manual 60-second playback row per verified browser: current desktop Chrome or Edge, and Firefox, with versions recorded (ADR-011 D5). Chrome or Edge verified in the [Sprint 6 closeout](../testing/SPRINT-6-CLOSEOUT.md): the Chrome 158 smoke on `main` (97 s of playback across a token expiry) and a hand-run Edge 158 row, with Chromium 155 and Chrome 156 rows, on the #242 branch stack. Firefox: a scripted Firefox 159 row (Playwright, real Gecko and decoding) on that stack; the owner accepted it as a recorded deviation from the hand-run row of ADR-011 D5 (see the Sprint 6 closeout). No A1/VPS playback yet (#124, #233) |
| NFR-006 | F-014, F-015 | PostgreSQL progress test: an accepted update is committed before the response and a read right after it returns it (ADR-011 D9). Verified in the [Sprint 6 closeout](../testing/SPRINT-6-CLOSEOUT.md) (`ProgressApiIntegrationTests` compares a `PUT` response with a `GET` right after it; the Chrome smoke resumes within 3 s of the saved position after a reload) |
| NFR-007 | F-001, F-002, F-004, F-020 | Auth and authorization tests |
| NFR-008 | All runtime features | Secret scan/manual review; `.env.example` review |
| NFR-009 | F-008, F-012 | MinIO storage integration evidence |
| NFR-010 | F-016 | Planned: Issue 7.5 relevance fixture and Issue 7.7 (#129) 500-title timings |
| NFR-011 | F-010, F-021, F-022 | Health endpoint and lifecycle log review |
| NFR-012 | All features | Documentation review before milestone completion |
| NFR-013 | All MVP features | This traceability matrix plus test artifacts |
| NFR-014 | F-021, F-022 | Compose service-name verification |
| NFR-015 | Backend API features | Contract ApiError tests, edge 413/502/503/504 JSON/status/requestId, backend upload 503 `AUTH_STATE_UNAVAILABLE`/`UPLOAD_STORAGE_UNAVAILABLE` and 500 `UPLOAD_DATABASE_ERROR`/`UPLOAD_OUTCOME_UNKNOWN`/`INTERNAL_ERROR` with requestId, and SPA not-accepted versus unknown-result/no-auto-resubmit handling even while videoStatus stays READY |
| NFR-016 | Frontend features | UI state tests or manual checklist |
| NFR-017 | Frontend features | Planned (NFR-017 is added to `NFR.md` by the UI brief pull request under #200): UI-BRIEF §13 evidence per UI change (token contrast test, axe scan, keyboard walk-through, reduced-motion/forced-colours, manual screen-reader rows) |

## Sprint 4 design-to-test plan

The Sprint 4 (4.x) parts of these rows are verified in the [Sprint 4 closeout](../testing/SPRINT-4-CLOSEOUT.md), the Sprint 5 parts (with #74 and #123) in the [Sprint 5 closeout](../testing/SPRINT-5-CLOSEOUT.md), and the Sprint 6 parts in the [Sprint 6 closeout](../testing/SPRINT-6-CLOSEOUT.md), including the upload -> transcode -> HLS browser playback that #125 required. The parts owned by Sprint 9, Phase 5, #54, #124 and #180 remain **planned**, not passed tests.

| Decisions / feature | Planned evidence and owner |
|---|---|
| D1-D5, F-008 | 4.4/4.6: header auth before multipart parse/no temp, known-length spool/store, edge/backend oversize, cancel cleanup, deterministic slow/unavailable upstream; 4.7: XHR pre-check/progress/saving/no replay, status 0 by body-sent phase, code-based not-accepted 503 AUTH_STATE_UNAVAILABLE / 503 UPLOAD_STORAGE_UNAVAILABLE / 500 UPLOAD_DATABASE_ERROR versus unknown post-send network errors and other 5xx, even while videoStatus stays READY; synthetic non-VPS 2 GB runner rehearsal; #180: genuine 2 GB VPS metrics and concurrent-upload headroom/limit_conn decision before public deployment |
| D6-D9, F-011/F-012 | #54 patched-binary/user evidence; #123 negative media fixtures; #124 target CPU measurement; 5.4/5.5/5.7/5.8 command restrictions, metadata caps, process-tree kill, disk check and container hardening |
| D11-D15/D17, F-008/F-013/F-020 | 4.3/4.6 PostgreSQL partial index/concurrent 409 compensation; catalog READY fallback/tie ordering; 6.1/6.2 current-only playback/HLS/visibility-first 404; 9.2/9.3 newest-only retry/double retry/upload race |
| D16/D19, F-009 | 4.2/4.5 golden fixture, confirms/returns, commit-before-publish, relay grace/crash/publish failure and fast-worker no-regression |
| D18/D20/D21, F-010 | 5.1/5.3/5.7 terminal redelivery, stale attempt, poison DLQ, DB outage, orphan restart and long job with second queued; #74 4.4 classic baseline: no ack timeout (5.1 long-job/DB-outage evidence) |
| D22/D23, F-012/F-022, R-006 | 4.4 compensating raw delete/failed compensation; 5.6/5.7 attempt isolation, failed/lost-finish/orphan prefix delete, trailing-slash safety and temp sweep; Phase 5 manual dry-run purge/orphan audit protects current/newest FAILED/in-flight/archive |
| D10/D24 | DTO bounds remain separate follow-up; one ADR/docs package, V5/counts implemented later in 4.3; independent AI review when a PR exists, before owner merge |

## Sprint 6 design-to-test plan

These rows were the planned evidence for [ADR-011](../architecture/adr/ADR-011.md). They are verified in the [Sprint 6 closeout](../testing/SPRINT-6-CLOSEOUT.md), which maps each Sprint 6 issue to its tests, the CI run, the Chrome playback smoke on `main` and the manual browser rows. Firefox in D5-D8 rests on a scripted row, accepted by the owner as a recorded deviation from the hand-run row. Two parts remain open: the Chrome smoke as a pull-request check (#255), and the admin projection part of D15-D18, owned by Sprint 9 (9.1).

| Decisions / feature | Planned evidence and owner |
|---|---|
| D1-D3, F-013, R-014 | 6.6: hls.js-only path when `canPlayType` answers positively, `video.src` never set to the manifest, unsupported state; token read per request, one shared refresh and one retry on 401, session expiry on the second 401, resume after a long pause; 6.2: a valid token only in `?access_token=` returns 401 on HLS and API paths |
| D4, F-013 | 6.2: MIME types, `Cache-Control: no-store`, `Range` ignored, current-only 404 `VIDEO_ASSET_NOT_FOUND` for superseded, non-READY, hidden, malformed ID and unsafe segment name, 503 `HLS_STORAGE_UNAVAILABLE`; Nginx forwards `Authorization` |
| D5-D8, F-013, NFR-005 | 6.6/6.7: one manual row per verified browser (60 seconds, seek, pause, reload and resume) with versions; keyboard-only pass; error-mapping unit tests for non-fatal, fatal media, 401 and 404; start position and rejected `play()`; #125: the Playwright Chrome smoke (real upload and encode, at least 60 seconds of advancing media time, playback across an access-token expiry, seek, progress save, reload and resume), run locally and by `workflow_dispatch`, one recorded run at the closeout |
| D9-D12, F-014, NFR-006, R-015 | 6.3 on PostgreSQL: a read right after a save, concurrent writes keep the newest, older write returns the stored row, clamp to the current asset, 90% and `ended` completion, rewatch clears `finished`, history insert/advance/`completed_at` once, visibility 404, 409 without a current asset, 409 `VIDEO_ASSET_NOT_CURRENT` for a replaced asset stores nothing, another user's rows untouched; 6.1: `resumeSeconds` is the saved position for a movie finished at 90%, 0 after `ended`, and 0 when a shorter replacement ends before the stored position |
| D11, F-015 | 6.5: unfinished and started only, normalized position below the current duration, PUBLISHED with a current asset only, filters before the 50-row limit, order by `last_played_at` then movie, items carry the normalized progress; 6.8: list without pagination |
| D13, F-014 | 6.6: fake-timer test for the 10-second interval, pause, seek, ended, page-hide and unmount saves, no save before first play, a save skipped only when position and `finished` both equal the last successful save, a pause followed by `ended` within the same second still stores `finished`, one save in flight with a coalesced pending snapshot, `keepalive` at unload, saving stops after 404 or 409, refetch after 409 `VIDEO_ASSET_NOT_CURRENT` |
| D15-D18, F-020 | Contract validation of every new status in 6.1-6.3; Sprint 9 (9.1): projection fields computed movie-wide, total order, combined filters, code-to-message mapping and unknown-value fallback |

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
