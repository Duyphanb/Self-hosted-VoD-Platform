# Risk Register

Last reviewed: 2026-10-29, Sprint 4 design package ([ADR-010](../architecture/adr/ADR-010.md)); planned mitigations are not implementation evidence.

## Risk Scale

- Probability: Low, Medium, High
- Impact: Low, Medium, High
- Status: Open, Watching, Mitigated, Closed

## Risks

| ID | Risk | Probability | Impact | Trigger | Mitigation | Owner | Status |
|---|---|---|---|---|---|---|---|
| R-001 | MVP scope creep delays the core media flow. | High | High | Optional features enter before upload -> transcode -> playback works. | Enforce `OUT_OF_SCOPE.md`; review scope before each batch. | Project owner | Open |
| R-002 | FFmpeg behavior is harder than expected across local and VPS environments. | Medium | High | Encoding fails, output is not playable, or command differs on ARM VPS. | Single-quality output; #123 fixtures and #124 target-CPU measurements set caps/deadlines; #54 verifies maintained FFmpeg binaries before Sprint 5; generated HLS requires real playback evidence (#125). | Worker | Open |
| R-003 | Oracle VPS resources are insufficient under encoding load. | Medium | High | OOM restarts, CPU saturation, slow transcodes. | Keep worker concurrency at 1; avoid multi-quality HLS; document resource observations. | Deploy | Open |
| R-004 | API contracts drift from frontend assumptions. | Medium | High | Frontend breaks after backend endpoint changes. | Freeze OpenAPI in Phase 2; update contract with behavior changes. | Backend / Frontend | Open |
| R-005 | Database schema naming drifts during feature work. | Medium | Medium | Inconsistent table/column names or rewritten migrations. | Freeze ERD before implementation; use Flyway; never edit committed migrations. | Backend | Open |
| R-006 | Uploads create storage or temp-file leaks. | Medium | High | Failed upload leaves temp/orphaned raw; failed attempts or superseded media accumulate. | ADR-010: 4.4 raw compensation for uncommitted transaction/409 loser; 5.7 attempt-prefix cleanup and per-job/startup temp sweep; retain current/newest FAILED/in-flight/archive media, manual reviewed superseded purge/orphan audit in deployment runbook. Move to Watching only after 4.4/5.7 failure-path evidence exists. | Backend (4.4) / Worker (5.7) / Deploy (manual audit) | Open |
| R-007 | Queue failures leave assets in incorrect status. | Medium | High | Publish fails, confirm/DB crash duplicates delivery, worker crashes or blocks. | 4.5 job-row outbox/relay; no QUEUED age timeout; 5.3 paired conditional claim/finish, prefetch 1, 5.7 media deadlines/startup WORKER_INTERRUPTED; one non-overlapping worker; DB outage retries with capped backoff, never FAILED/DLQ; #74 consumer-timeout sizing/DB-outage handling; Sprint 9 newest-FAILED retry. Runbook covers wedged restart and approved exact-job republish after broker loss or a corrected DLQ cause. | Backend / Worker | Open |
| R-008 | Weak auth or RBAC creates unauthorized access. | Medium | High | User accesses admin upload or another user's data. | Add authorization tests; keep admin APIs role-protected; enforce ownership checks. Sprint 2 authorization and ownership tests are verified in the [Sprint 2 closeout](../testing/SPRINT-2-CLOSEOUT.md); admin catalog endpoints are verified in the [Sprint 3 closeout](../testing/SPRINT-3-CLOSEOUT.md) (integration tests and Newman 403/401 checks); re-check when upload endpoints land. | Backend | Watching |
| R-009 | Secrets leak into repository. | Low | High | Real `.env` or credentials are committed. | Keep `.env.example` placeholders only; review git status before commits. | All | Watching |
| R-010 | Search quality is overbuilt too early. | Medium | Medium | Work shifts to fuzzy search or Elasticsearch before MVP. | Use PostgreSQL FTS for MVP; defer `pg_trgm`, unaccent, and Elasticsearch. | Backend | Open |
| R-011 | Frontend polish consumes time before core flows work. | Medium | Medium | UI refinements delay upload/playback path. | Build functional screens first with clear states; postpone advanced visuals. | Frontend | Open |
| R-012 | Tests lag behind implementation. | High | High | Features merge without service/API coverage. | Require tests in each behavior batch; use traceability as completion gate. | All | Open |
| R-013 | Deployment docs are written too late. | Medium | Medium | Local works but VPS setup is fragile. | Create deployment docs in Phase 5 and keep `.env.example` current. | Deploy | Open |
| R-014 | HLS delivery headers are wrong. | Medium | Medium | Browser fails to play `.m3u8` or `.ts` files. | Verify MIME types and cache headers in Nginx during streaming sprint. | Deploy / Frontend | Open |
| R-015 | Resume playback durability is misunderstood. | Medium | Medium | Redis buffer loses progress and UX looks broken. | Define flush interval and fallback behavior; test Redis-to-PostgreSQL sync. | Backend | Open |
| R-016 | Portfolio claims exceed implemented behavior. | Medium | High | README or CV says optional features exist before they work. | Keep README honest; final review must compare claims to evidence. | Project owner | Open |
| R-017 | Platform or image maintenance churn blocks feature work. | High | Medium | An upstream image or platform line stops receiving updates or cannot be pulled: Silo maintenance/registry availability (#14), RabbitMQ 3.13 (#74), unpinned images (#54); the Spring Boot 3 open-source end was resolved by moving to Spring Boot 4.1 (#50). | Weekly dependency audit; Silo pinned with provenance/SBOM and runtime smoke (#14); monitor its primary-maintainer bus factor and advisory ledger, keep offline backups/self-build exit plan; pin other images by digest (#54); track every platform decision in an issue and an ADR. | Project owner / Deploy | Open |
| R-018 | Hostile or malformed media compromises or stalls the worker. | Medium | High | Fake MIME/playlist, decoder defect, unbounded probe/encode or exhausted temp disk; research found an unpatched jammy FFmpeg running as root in a local image, not CI certification. | 4.4 family signature check; 5.4/5.5 forced demuxer/file protocol/typed args/closed stdin/capped diagnostics; 5.7 measured caps/deadlines/disk check/process-tree kill; 5.8 non-root/read-only/cap-drop hardening. #54 must verify patched decoder source before Sprint 5. ADR-010 accepts residual shared-worker network/credential risk; stronger invocation isolation post-MVP. | Worker / Deploy / Project owner | Open |

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
