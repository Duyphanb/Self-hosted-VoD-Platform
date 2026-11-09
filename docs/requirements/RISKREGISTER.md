# Risk Register

Last reviewed: 2026-11-03, R-002 and R-018 after the Sprint 5 worker design ([ADR-010 D25-D28](../architecture/adr/ADR-010.md#sprint-5-worker-media-decisions-d25-d28)); 2026-11-01, R-008 re-check for the admin upload endpoint (#174), after the scoped storage SDK review fixes (#169), following the Sprint 4 design package ([ADR-010](../architecture/adr/ADR-010.md)); planned mitigations are not implementation evidence.

## Risk Scale

- Probability: Low, Medium, High
- Impact: Low, Medium, High
- Status: Open, Watching, Mitigated, Closed

## Risks

| ID | Risk | Probability | Impact | Trigger | Mitigation | Owner | Status |
|---|---|---|---|---|---|---|---|
| R-001 | MVP scope creep delays the core media flow. | High | High | Optional features enter before upload -> transcode -> playback works. | Enforce `OUT_OF_SCOPE.md`; review scope before each batch. | Project owner | Open |
| R-002 | FFmpeg behavior is harder than expected across local and VPS environments. | Medium | High | Encoding fails, output is not playable, or command differs on ARM VPS. | Single-quality H.264 High 8-bit yuv420p plus AAC-LC stereo MPEG-TS profile pinned in Issue 5.5 (forced keyframes, no upscaling, audio never copied, VBV cap); the worker validates playlist, segments and duration instead of trusting the exit code. #123 generates all test media at test time and runs the profile against the worker image binary in CI; no third-party media is committed (`.gitignore` media globs). #124 encodes on arm64 and sets the timeouts; ADR-010 D25 selects the FFmpeg source; generated HLS requires real playback evidence (#125). | Worker | Open |
| R-003 | Oracle VPS resources are insufficient under encoding load. | Medium | High | OOM restarts, CPU saturation, slow transcodes. | Keep worker concurrency at 1; avoid multi-quality HLS; document resource observations. | Deploy | Open |
| R-004 | API contracts drift from frontend assumptions. | Medium | High | Frontend breaks after backend endpoint changes. | Freeze OpenAPI in Phase 2; update contract with behavior changes. | Backend / Frontend | Open |
| R-005 | Database schema naming drifts during feature work. | Medium | Medium | Inconsistent table/column names or rewritten migrations. | Freeze ERD before implementation; use Flyway; never edit committed migrations. CI rejects edited, renamed or deleted migrations, validates migration naming and diffs the migrated schema against a committed snapshot (#118). | Backend | Open |
| R-006 | Uploads create storage or temp-file leaks. | Medium | High | Failed upload leaves temp/orphaned raw; failed attempts or superseded media accumulate. | ADR-010: 4.4 raw compensation for uncommitted transaction/409 loser; 5.7 attempt-prefix cleanup and per-job/startup temp sweep; retain current/newest FAILED/in-flight/archive media, manual reviewed superseded purge/orphan audit in deployment runbook. The 4.4 part is verified in the [Sprint 4 closeout](../testing/SPRINT-4-CLOSEOUT.md) (compensating raw delete, raw retained on an unknown commit, startup spool sweep); move to Watching only after the 5.7 failure-path evidence exists. | Backend (4.4) / Worker (5.7) / Deploy (manual audit) | Open |
| R-007 | Queue failures leave assets in incorrect status. | Medium | High | Publish fails, confirm/DB crash duplicates delivery, worker crashes or blocks. | 4.5 job-row outbox/relay; no QUEUED age timeout; 5.3 paired conditional claim/finish, prefetch 1, 5.7 media deadlines/startup WORKER_INTERRUPTED; one non-overlapping worker; DB outage retries with capped backoff, never FAILED/DLQ; #74 4.4 classic baseline without a consumer timeout (DB-outage hold to be verified in 5.1) and a fixed broker hostname so recreation keeps queue data; Sprint 9 newest-FAILED retry. Runbook covers wedged restart and approved exact-job republish after broker loss or a corrected DLQ cause. | Backend / Worker | Open |
| R-008 | Weak auth or RBAC creates unauthorized access. | Medium | High | User accesses admin upload or another user's data. | Add authorization tests; keep admin APIs role-protected; enforce ownership checks. Sprint 2 authorization and ownership tests are verified in the [Sprint 2 closeout](../testing/SPRINT-2-CLOSEOUT.md); admin catalog endpoints are verified in the [Sprint 3 closeout](../testing/SPRINT-3-CLOSEOUT.md) (integration tests and Newman 403/401 checks). The admin upload endpoint landed in #174 with authorization from headers before multipart parsing, the multipart route gate from PR #179 (400 `MALFORMED_REQUEST` before authentication on other operations) and a per-operation `requestId` on its error bodies, covered by HTTP tests and Newman 401/403 checks. | Backend | Watching |
| R-009 | Secrets leak into repository. | Low | High | Real `.env` or credentials are committed. | Keep `.env.example` placeholders only; review git status before commits. | All | Watching |
| R-010 | Search quality is overbuilt too early. | Medium | Medium | Work shifts to fuzzy search or Elasticsearch before MVP. | Use PostgreSQL FTS for MVP; defer `pg_trgm`, unaccent, and Elasticsearch. | Backend | Open |
| R-011 | Frontend polish consumes time before core flows work. | Medium | Medium | UI refinements delay upload/playback path. | Build functional screens first with clear states; postpone advanced visuals. | Frontend | Open |
| R-012 | Tests lag behind implementation. | High | High | Features merge without service/API coverage. | Require tests in each behavior batch; use traceability as completion gate. | All | Open |
| R-013 | Deployment docs are written too late. | Medium | Medium | Local works but VPS setup is fragile. | Create deployment docs in Phase 5 and keep `.env.example` current. | Deploy | Open |
| R-014 | HLS delivery headers are wrong. | Medium | Medium | Browser fails to play `.m3u8` or `.ts` files. | Verify MIME types and cache headers in Nginx during streaming sprint. | Deploy / Frontend | Open |
| R-015 | Resume playback durability is misunderstood. | Medium | Medium | Redis buffer loses progress and UX looks broken. | Define flush interval and fallback behavior; test Redis-to-PostgreSQL sync. | Backend | Open |
| R-016 | Portfolio claims exceed implemented behavior. | Medium | High | README or CV says optional features exist before they work. | Keep README honest; final review must compare claims to evidence. | Project owner | Open |
| R-017 | Platform or image maintenance churn blocks feature work. | High | Medium | An upstream image, SDK or platform line stops receiving updates or cannot be pulled: Silo maintenance/registry availability (#14), minio-java dependencies and failed-multipart callback bug (#169), the RabbitMQ series lifecycle (4.4 community support ends 2027-07-31, #74), unpinned images (#54); the Spring Boot 3 open-source end was resolved by moving to Spring Boot 4.1 (#50). | Weekly dependency audit; SDK version overrides/exclusions, bounded multipart regression and ObjectStorage adapter exit plan (ADR-004/#169); Silo pinned with provenance/SBOM and runtime smoke (#14); monitor its primary-maintainer bus factor and advisory ledger, keep offline backups/self-build exit plan; pin other images by digest (#54); track every platform decision in an issue and an ADR. | Project owner / Deploy | Open |
| R-018 | Hostile or malformed media compromises or stalls the worker. | Medium | High | Fake MIME/playlist, decoder defect, unbounded probe/encode or exhausted temp disk; research found an unpatched jammy FFmpeg running as root in a local image, not CI certification. | 4.4 family signature check; 5.4/5.5 forced demuxer/file protocol/typed args/closed stdin/capped diagnostics; 5.4/5.7 owner product caps, measured deadlines, two-step disk check with watchdog and process-tree kill; 5.5 output validation; 5.8 non-root/read-only/cap-drop hardening. ADR-010 D25: Debian trixie FFmpeg covered by Debian security advisories and rebuilt on each one; CVEs postponed between advisories are assessed for reachability, reviewed monthly and expire after 90 days; D26 decoder allowlist and owner product caps; D27 output bound and validation. ADR-010 accepts residual shared-worker network/credential risk; stronger invocation isolation post-MVP. | Worker / Deploy / Project owner | Open |
| R-019 | An upload during a long encode pushes free disk below the worker reserve. | Low | Medium | The backend upload spool, raw objects, worker temp and Silo share one disk on the single VPS, so the ADR-010 D27 watchdog can fail the running job as `INSUFFICIENT_DISK` although the upload succeeds (predicted in #190, not reproduced). | Accepted for the MVP (owner decision, #190, 2026-11-10): no upload contract change. The failed job is retryable and no READY asset is lost. Operator guidance: do not upload while a long encode runs. Revisit with the #180 and #137 VPS measurements; #137 keeps `WORKER_TEMP_DIR` and the Silo data on one filesystem. | Worker / Deploy / Project owner | Accepted |

## Highest Priority Risks

The first risks to actively reduce are:

1. R-001: MVP scope creep
2. R-002: FFmpeg and HLS correctness
3. R-003: VPS resource constraints
4. R-004: API contract drift
5. R-012: tests lagging behind implementation

## Review Cadence

Review this file:

- before Phase 2 architecture starts
- before each implementation sprint
- before local MVP completion
- before VPS deployment
