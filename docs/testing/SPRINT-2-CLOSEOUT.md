# Sprint 2 Auth closeout

Audit date: 2026-09-18. Implementation baseline: `b6ad022507108079d182b2d9941cdc1c0e38625e`, merged by [PR #70](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/70).

Sprint 2 Auth is closed: all acceptance criteria and ten DoD items pass on the merged implementation. This is verification evidence, not a replacement for frozen contracts. Sprint 3 has not been started. Auth completion does not establish media-pipeline or public-production readiness.

## Acceptance criteria

| Backlog item | Verified behavior and evidence |
|---|---|
| 2.1 Registration | DTO validation covers email, password and display name; service/persistence tests cover ACTIVE, ROLE_USER and BCrypt storage. Real PostgreSQL HTTP checks prove 201 profile-only DTO, 409 duplicates, concurrent exact/case duplicates and direct database uniqueness. Dots and plus tags remain distinct under approved ADR-008. |
| 2.2 Login/JWT | Login service/controller/integration tests cover BCrypt verification, legacy upgrades, generic invalid credentials, signed JWT claims/expiry and hashed random refresh tokens. Configuration rejects missing/short signing keys and nonpositive lifetimes. Real HTTP checks compare missing-user and wrong-password errors and reject tampered JWTs. Generic errors and hash work are verified; constant-time network behavior is not claimed. |
| 2.3 Refresh | Unit/integration tests and PostgreSQL HTTP checks verify hashed lookup, new token pair, revocation, replay rejection, expiry and concurrent rotation with exactly one success. A real row lock held across expiry confirms time is checked after lock acquisition. |
| 2.4 Logout | Service/integration tests and Newman cover revocation, repeated logout, unknown/revoked tokens and omitted body returning 204. UI and cross-tab checks confirm local state is cleared. |
| 2.5 JWT filter | `JwtSecurityIntegrationTests` uses the actual Spring Security chain for valid, anonymous, malformed, expired and invalid-claim requests; runtime checks exercise protected profile access and public Auth routes. |
| 2.6 RBAC | `@EnableMethodSecurity` and `AdminAuthorizationIntegrationTests` verify real JWT/method security: USER gets 403, ADMIN 200, anonymous 401 for an annotated test-only admin operation. Production catalog/upload admin endpoints belong to later sprints and must apply the same authorization boundary; no production test probe was added. |
| 2.7 Profile | Service/validation/integration tests cover DTOs, display-name validation and authenticated-user ownership. Runtime PUT attempts to change ID/email/roles leave those fields unchanged. Disabled-user profile access is rejected. |
| 2.8 Auth state | Context/API/storage tests cover persistence, refresh, failure handling, loading/errors, logout and stale responses. Chrome verifies reload, coordinated two-tab refresh, cross-tab logout and preservation of a new login after an older refresh fails. |
| 2.9 Auth UI | Form/page tests cover contract-aligned validation, pending/error/success states and intended-route redirects. Chrome verifies registration, case-variant login and logout through the built frontend and Nginx. |
| 2.10 Guards | Route/component tests and Chrome verify anonymous account redirects, hidden admin navigation and direct `/admin` denial for ROLE_USER before protected content is rendered. |

## Definition of Done evidence

All ten Sprint 2 checklist entries in [BACKLOG.md](../requirements/BACKLOG.md#sprint-2-definition-of-done) map to the acceptance table above. Additional evidence:

- The checked-in [Postman collection](postman/README.md) covers 11 ordered requests; Newman runs it against a disposable PostgreSQL-backed Auth stack in CI and propagates failures.
- Passwords/hashes and configuration secrets are excluded from profile/error DTOs. Tokens are returned only where the Auth contract requires them. Inspection found no Auth request-body/credential logging; Newman suppresses token-bearing assertion diagnostics. This is scoped code/diff/runtime evidence, not a forensic certification of all repository history.
- `CorsSecurityIntegrationTests` covers allowed/denied origins and public/protected routes; runtime denies an untrusted origin.
- Production configuration smoke rejects missing, empty and published placeholder credentials. It does not deploy to production.

## Defects corrected in this audit

1. Exact-string email lookup/uniqueness allowed case variants to become different identities. The owner approved case-insensitive identity; PostgreSQL `lower(email)` now governs both lookup and a new unique index. V4 aborts atomically on existing collisions, preserving both accounts. Frozen API/ERD/security docs and [ADR-008](../architecture/adr/ADR-008.md) record the decision.
2. Legacy direct BCrypt could accept an unauthenticated suffix and upgrade that ambiguous input. Oversized legacy inputs are rejected; current versioned hashing still verifies the full UTF-8 password. Regression failed before the fix and passes afterward.
3. Refresh expiry was sampled before waiting for a pessimistic row lock. Time is now sampled after acquiring the lock. The PostgreSQL regression reproduced the old behavior and passed with the fix.
4. Late pre-logout responses could clear a newer frontend login. Generation-aware cleanup and superseded-operation handling preserve the newer session. Two new unit regressions failed before the fix; real two-tab Chrome checks passed afterward.
5. Main did not require existing CI checks; the security workflow also used path filters. Main now requires all six always-available checks, bound to GitHub Actions app `15368`, with strict up-to-date checks and no bypass actors. Existing PR/deletion/non-fast-forward rules remain in force.
6. The Sprint 2 status and unchecked DoD disagreed. The checklist is updated only after verification of the merged implementation.

## Verification

- Backend `mvn -B -ntp verify`: 96 tests, no failures/errors/skips; worker verification: 1 test passed.
- Frontend: 88 tests across 9 files and production build passed.
- Dockerfiles build backend, worker and frontend; worker FFmpeg/FFprobe and Nginx route smoke pass.
- Flyway: 15 application tables, 31 named indexes, 53 constraints including 17 foreign keys and 9 checks. Collision abort/recovery is tested on PostgreSQL, not inferred from H2.
- Real PostgreSQL HTTP regression and pinned Newman collection pass; fixture teardown removes only that run's resources.
- Chrome local checks pass for the built frontend, including real storage events and Web Locks; fallback stale-response checks also pass with Web Locks disabled. These browser checks were performed locally, not by the CI unit runner.
- Production dependency gates pass: npm HIGH/CRITICAL and Trivy HIGH/CRITICAL against the actual packaged backend/worker JARs. This does not cover all container OS or object-store vulnerabilities.
- PR verification: [CI](https://github.com/Duyphanb/Self-hosted-VoD-Platform/actions/runs/35360519863), [dependency security](https://github.com/Duyphanb/Self-hosted-VoD-Platform/actions/runs/35360519939).
- Merged implementation verification passed on the baseline SHA above: [CI](https://github.com/Duyphanb/Self-hosted-VoD-Platform/actions/runs/35360897575), [dependency security](https://github.com/Duyphanb/Self-hosted-VoD-Platform/actions/runs/35360897476). The runtime tree is identical to the browser-verified PR head; this evidence update changes documentation only.

## Remaining work and boundaries

| Classification | Tracking | Boundary |
|---|---|---|
| Blocker before Sprint 3 | None found in the verified Auth/catalog prerequisites | No Sprint 3 implementation is included here. Future admin endpoints still need their own authorization tests. |
| Before public production | [#14](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/14) | Object-store availability/maintenance decision. A fresh CI runner could not pull `minio/minio`; cached local success does not prove clean-machine full-stack startup. Resolve before depending on a fresh full media stack. The Auth CI fixture deliberately does not exercise storage. |
| Before public production | [#50](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/50), [#51](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/51), [#52](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/52), [#54](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/54) | Supported Spring baseline, login abuse controls, route-specific body limits, container identity/immutable artifacts. Their decisions remain separate from Auth closeout. |
| Before public production | [#57](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/57) | Nginx upstream re-resolution after backend recreation; local workaround is restarting Nginx with the backend. |
| Non-blocking tracked debt | [#53](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/53), [#56](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/56) | Stronger reserved-route assertions and reviewed moderate React Router advisories; revisit applicability before public deployment. No scanner threshold was weakened. |

Additional Auth deployment-decision notes were retained locally for the owner; publishing their dedicated follow-up issue requires the owner's pending publication approval. This pending tracking action does not change the tested Sprint 2 behavior or authorize a different security model.

Issue [#55](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/55) is delivered by PR #70. Google/OAuth, MFA, email verification, password-reset features, Boot 4 and media-pipeline implementation remain outside this audit.
