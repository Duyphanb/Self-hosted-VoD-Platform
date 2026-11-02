# Sprint 4 Upload closeout

Audit date: 2026-11-02. Implementation baseline: `e164c39` on `main`, after [PR #183](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/183).

Sprint 4 Upload has its Definition of Done verified: all thirteen DoD items pass on the merged implementation, within the boundaries stated at the end. Under the project plan, the sprint is complete once its milestone has no open issues; the non-blocking issues still in the milestone (#118, #119, #121, #167) await the owner's decision to move them. Each item maps to the backlog issues below. This is verification evidence, not a replacement for the frozen contracts ([ADR-010](../architecture/adr/ADR-010.md) and the documents it amends).

Sprint 4 proves upload, raw storage and queue handoff only. It does not establish encoding, HLS playback, VPS capacity or public-production readiness.

## Acceptance criteria

| Backlog item | GitHub | Verified behavior and evidence |
|---|---|---|
| 4.1 Storage client | [#169](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/169) / [PR #176](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/176) | A streaming Silo client with known-length PUT, bounded timeouts and storage readiness. `minio-init` creates least-privilege backend and worker identities scoped to their buckets, with an explicit Deny on self-service account and password operations. The CI storage runtime smoke and the SDK smoke check private buckets, put and get, anonymous denial and secret-free logs. |
| 4.3 Video asset persistence | [#171](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/171) / [PR #177](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/177) | Flyway V5 adds `published_at` and the partial unique index `ux_video_assets_movie_in_flight`, behind a guard that aborts on pre-existing duplicates. Entities, repositories and the catalog `videoStatus` lookup (READY first, then the newest asset; null without assets). `VideoAssetPostgresTest` (13 tests) runs in the required Flyway job with zero skipped, next to the schema-count assertions. |
| 4.2 RabbitMQ publisher | [#170](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/170) / [PR #178](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/178) | A version-1 IDs-only persistent message (`schemaVersion`, `encodingJobId`, `videoAssetId`, `attempt`), pinned by the golden fixture `docs/testing/fixtures/encoding-job-v1.json`. A durable queue with a DLX and DLQ. Publisher confirms and mandatory returns map to explicit outcomes. The broker smoke runs in the required Skeleton job. |
| 4.4 Upload service and 4.5 job publishing | [#172](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/172), [#173](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/173) / [PR #179](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/179) | **Validation and storage:** the declared-MIME allowlist plus a signature-family check; a Tomcat spool in `TEMP_UPLOAD_DIR`, swept non-recursively before the connector starts; a known-length stream to `vod-raw`. **Commit:** the asset and its attempt-1 job commit together as QUEUED. A certain failure deletes the raw object (best effort); an unconfirmed commit keeps it. **Publishing:** happens after the commit, with a conditional `published_at`. A scheduled relay republishes pending jobs (grace period, bounded batch, stops at the first failure, never overwrites a faster worker). **Route gate:** multipart is rejected outside the upload operation. **Tests:** `UploadTransactionTests`, `UploadSpoolTest`, and `UploadPostgresTests` (14 tests, in the required Flyway job). |
| 4.6 Admin upload endpoint | [#174](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/174) / [PR #181](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/181), [PR #182](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/182) | **Endpoint (#181):** `POST /api/v1/admin/movies/{movieId}/video` with `ROLE_ADMIN` checked before parsing and exactly one `file` part. It returns 202 with the QUEUED snapshot, also while publication is pending. **Failures:** the owner-approved mapping (503 `UPLOAD_STORAGE_UNAVAILABLE`; 500 `UPLOAD_DATABASE_ERROR`, `UPLOAD_OUTCOME_UNKNOWN` and `INTERNAL_ERROR`), with a `requestId` on every error of the operation. **Evidence (#181):** `MultipartLifecycleTests` (real Tomcat), `VideoUploadApiIntegrationTests`, `UploadEndpointPostgresTests`, and the Newman Sprint 4 upload collection. **Edge (#182, closes #52):** a global 1 MiB limit; a nested upload location with 2048 MiB, buffering off and a 600 s read timeout; `ApiError` JSON for edge 413/502/503/504. The same PR adds the size-equality preflight, the SPA limit constant and the synthetic 2 GB rehearsal. |
| 4.7 Admin upload UI | [#175](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/175) / [PR #183](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/183) | `ApiClient.upload()` on XMLHttpRequest, with no 401 refresh or replay. The page runs pre-checks against the guarded 2048 MiB constant and the four-format allowlist, and sends a fresh movie GET on every click. It shows percent progress, Cancel only while sending, and "Saving to storage…" after 100%. Outcomes are code-based: accepted, not accepted with manual retry, or unknown. A leave guard protects the page while an upload is pending. The list shows an "Upload video" link for every movie status. Tests: page, hook, client, API and pure tables, mutation-checked; the manual pass is below. |

**Supporting issues:**
- [#14](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/14) / [PR #163](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/163): the verified PGSTY Silo image.
- [PR #166](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/166): ADR-010 and the frozen-doc package; it closes [#100](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/100), [#101](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/101) and [#102](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/102).
- [#52](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/52) and [#116](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/116): the route-scoped edge and the 2 GB transport specification.
- [#164](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/164) / [PR #165](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/165): the admin form test stability.

## Definition of Done evidence

The thirteen checklist entries in [BACKLOG.md](../requirements/BACKLOG.md#sprint-4-definition-of-done) map to the table above:

| DoD item | Evidence |
|---|---|
| Admin can upload video files through API and UI | 4.6: Newman 202 through Nginx and Silo in CI. 4.7: the manual UI pass below. |
| Upload validates declared MIME/signature family and size from config | 4.4 service tests. 4.6: Newman 400 (type) and 413 (`MAX_UPLOAD_SIZE_MB=1` for that CI step). The edge smoke checks the route limits. 4.7 pre-checks the same allowlist and the guarded size constant. |
| Raw video is stored in MinIO `vod-raw` bucket | 4.1 and 4.4. The rehearsal verified an exact 2,000,000,000-byte raw object. In the manual pass, the stored keys matched the movie and asset, including a `.mkv` key for an empty-type file. |
| Asset and attempt-1 job commit together as QUEUED; UPLOADED reserved; V5 and schema counts verified on PostgreSQL | 4.3: `VideoAssetPostgresTest` and the Flyway job's schema counts. 4.4: `UploadTransactionTests` and `UploadPostgresTests`. |
| Version-1 IDs-only message, topology, confirms/returns and relay verified; broker outage keeps 202 QUEUED | 4.2: the golden fixture, publisher tests and the broker smoke. 4.5: the relay tests (`relayHonorsGraceRecoversCrashAndNeverOverwritesFastWorker` and the bounded-batch test). 4.6: `acceptedForEveryMovieStatusEvenWhilePublicationIsPending`. |
| Conditional writes prevent late publish or status regression; parallel uploads prove the in-flight index, 409 and raw compensation | 4.5: the conditional `markPublished` tests. 4.3/4.6: `UploadPostgresTests` and `UploadEndpointPostgresTests` (one 202 and one 409; the loser's key is deleted exactly once). |
| Upload endpoint enforces `ROLE_ADMIN` | 4.6: authorization before parsing, with no spool (`MultipartLifecycleTests`), and Newman 401/403. |
| XHR progress, saving after 100%, send-only Cancel, pre-checks, no 401 replay and explicit ambiguous-error recovery verified | 4.7: the tests and the manual pass. No-401-replay is verified by unit tests only; the browser pass did not exercise it. |
| Unit and integration tests cover upload and queue publish | Backend: 1,129 tests run in CI, with 0 failures and 33 opt-in skips (shared 112, auth 80, user 17, catalog 330, encoding 112, upload 54, common 424). PostgreSQL suites in the Flyway job: 13 + 14 + 1, with 0 skipped. Worker: 10 tests, with 1 opt-in skip. Frontend: 788 tests in 38 files. |
| Postman/Newman covers upload 202/400/401/403/404/409/413; edge smoke covers ApiError 413/502/503/504, route limits and unchanged HLS/health | 4.6: the [Sprint 4 upload collection](postman/README.md#sprint-4-upload-collection) in the CI full-stack step. 4.6 (#182): `deploy/tests/nginx-edge-smoke.py` in the required Skeleton job, with mutation checks during review. |
| `.env.example` documents size/MIME/temp, confirm/relay settings and the edge/SPA consistency rule; spool, abort and startup cleanup verified | `.env.example`: `MAX_UPLOAD_SIZE_MB` with the consistency rule, `ALLOWED_VIDEO_MIME_TYPES`, `TEMP_UPLOAD_DIR`, `ENCODING_PUBLISH_CONFIRM_TIMEOUT_MS`, `ENCODING_RELAY_INTERVAL_SECONDS`. Cleanup: `UploadSpoolTest` (startup sweep) and `MultipartLifecycleTests` (abort and request cleanup). |
| Synthetic 2,000,000,000 B rehearsal on a GitHub-hosted runner finishes without 504, labelled non-VPS evidence | [Run 41405634232](https://github.com/Duyphanb/Self-hosted-VoD-Platform/actions/runs/41405634232) on `060f8fb`. The figures are in the [#174 closeout](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/174#issuecomment-6571719287): 202 with no 504, 19.06 s send, 36.14 s saving wait, 55.20 s total, about 3.93 GB of transient disk, and the analytic headroom estimate for #180. |
| This sprint proves upload, storage and queue only; upload → transcode → HLS remains #125 | Stated here and in every Sprint 4 closeout. The worker has no consumer yet, so assets stay QUEUED. |

### Global Definition of Done

| Global DoD item | Status for Sprint 4 |
|---|---|
| AGENTS.md rules (constructor injection, DTOs, no exposed entities) | Met. There is no `@Autowired` in backend or worker main sources. Upload responses are DTO records, validated against the contract. |
| Unit tests cover service logic | Met. Upload, encoding-job, publisher, relay, storage and spool unit tests run in the upload, encoding and common modules. |
| Integration tests cover API endpoints | Met. MockMvc and real-Tomcat tests cover the upload operation. PostgreSQL behaviour that H2 cannot show (the partial index, concurrent 409 and created-at ordering) runs in the three PostgreSQL suites. |
| SLF4J logging, no `System.out` | Met. There is no `System.out`, `System.err` or `printStackTrace` in backend or worker main sources. Rejections log IDs and codes only. |
| Secrets externalised; none committed | Met. Storage, broker and database credentials come from the environment. CI and the rehearsal generate run-only values, and the rehearsal artifact contains no credentials. |
| `API-CONTRACT.yaml` updated or confirmed; responses validated in tests | Met. The upload operation, its failure codes and the edge responses are in the contract (#166, #181). One stale `videoStatus` sentence was removed with owner authorization (#175 D18). |
| Frontend loading, error, empty and success states | Met for the upload page (tests and the manual pass). |
| Protected and admin routes enforced | Met. `AdminRoute` guards the upload page, and the backend checks `ROLE_ADMIN` before parsing. |
| Docs updated with behaviour, config and contracts | Met. ADR-010 and its addenda, INFRASTRUCTURE "Upload transport", SECURITY, the testing README, `UPLOAD-REHEARSAL.md`, the Postman README and this closeout. |
| Conventional commits; git status reviewed before staging | Met. Every Sprint 4 pull request (#163, #165, #166, #176-#179, #181-#183) is squash-merged with a conventional title. |

### Manual UI pass

Done on 2026-11-02 on a disposable Compose stack built from PR #183 (Nginx on loopback, a generated test admin, seeded empty movies, and the worker not started), in a Chromium browser:

1. **List:** an "Upload video" link with an accessible name on every row; Enter opens the page.
2. **Pre-checks:** `.txt`, a 0-byte file, a file over 2048 MiB, `.mp4` declared `text/plain` and a dotless name are each rejected with a specific message and no request. Exactly 2048 MiB is allowed.
3. **Happy path (300 MB, keyboard):** Checking movie… → percent sent with "Cancel upload" → "Saving to storage…" with no Cancel → "Upload accepted. Video asset status: QUEUED.", with the re-upload guidance. Focus moved from the status region to the outcome and never fell to the page body.
4. **Second upload to the same movie:** 409 "already in progress".
5. **Cancel at 30%:** "cancelled"; the file was kept. A later upload to the same movie got 202, so the cancel committed nothing.
6. **Leave guard:**
   - While sending: "Stay on page" and "Cancel upload and leave".
   - While saving: "Stay on page" only.
   - A response arriving while the dialog was open closed it and focused the outcome.
7. **Storage paused:** 503 → "Upload not accepted (no committed asset/job pair)… retry manually".
8. **Backend stopped while saving:** edge 502 → "Upload result unknown".
9. **`.mkv` with an empty file type:** accepted.
10. **Network log:** one POST per click, with no automatic replay, and a fresh movie GET before each POST.
11. **Database:** one QUEUED asset/job pair per accepted upload, and none for the rejected and unknown cases.

## Verification

- **CI on the final pull-request head** (tree identical to `e164c39`): [CI](https://github.com/Duyphanb/Self-hosted-VoD-Platform/actions/runs/41469092125), with every required check green, including the PostgreSQL suites, the edge smoke and the Newman upload collection; CodeQL green.
- **Rehearsal:** [synthetic 2 GB rehearsal](https://github.com/Duyphanb/Self-hosted-VoD-Platform/actions/runs/41405634232) on `main`.
- **Reviews:** every Sprint 4 implementation pull request carries an independent AI review with an author response, plus a closeout comment on its issue.

## Remaining work and boundaries

| Classification | Tracking | Boundary |
|---|---|---|
| Pre-production gate | [#180](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/180) | The genuine 2 GB run on the target VPS: WAN time, saving wait, the real disk and data root, spool and memory peaks, concurrent-upload headroom, and the `limit_conn`/fallback decision. It blocks public deployment. **To be measured in #180.** |
| Sprint 5 | [#103](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/103), [#117](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/117) | FFprobe/FFmpeg sandboxing and caps. The worker-side cleanup rules; only the upload-side compensating delete is delivered. |
| Sprint 6 | [#125](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/125) | The upload → transcode → HLS browser smoke. |
| Sprint 9 | [#168](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/168) | A direct newest-asset view (current, superseded, retry-eligible). Until then the UI says "Upload result unknown" rather than inferring from `videoStatus`. |
| Open in the Sprint 4 milestone | [#118](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/118), [#119](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/119), [#121](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/121), [#167](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/167) | Non-blocking: migration immutability in CI; moving the remaining H2 integration tests to PostgreSQL (three upload suites already run there); the frontend dev loop; login and refresh length bounds. Their new milestone is the owner's decision. |
| Not exercised in the browser | — | Firefox, and the upload 401 path (unit-tested). |
| UI overhaul (owner-approved, two phases) | — | Basic UI by decision until then. Phase 1 (design system and admin pages) runs alongside Sprint 5; phase 2 (viewer pages) comes after Sprint 6. |
