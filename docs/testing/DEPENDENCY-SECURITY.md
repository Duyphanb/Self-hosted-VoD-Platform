# Dependency Security

## Automated Policy

The dependency-security workflow applies two complementary production-dependency gates:

- `npm audit --package-lock-only --omit=dev --audit-level=high` fails on high or critical production npm advisories without installing packages or running lifecycle scripts.
- Trivy scans the packaged backend and worker executable JARs for high or critical library vulnerabilities. The workflow stages the same `common-0.1.0-SNAPSHOT.jar` and `queue-0.1.0-SNAPSHOT.jar` artifacts copied into the runtime images, then uses a root-filesystem scan so ignored Maven `target/` directories cannot make the gate silently fall back to manifest-only coverage.

The gates report findings without applying dependency upgrades. Any suppression requires a dedicated issue with evidence that the advisory is not applicable.

## Reviewed Baseline Advisories

As of 2026-08-29, `npm audit --omit=dev` reports two moderate advisories through `react-router-dom` 6.30.6:

- `GHSA-wrjc-x8rr-h8h6` — open redirect behavior involving backslashes.
- `GHSA-337j-9hxr-rhxg` — constructor injection in server-side hydration error deserialization.

They remain visible and tracked by GitHub Issue #56. The available npm remediation is a forced React Router 7 major upgrade, so this hardening batch does not apply it automatically. High and critical findings still fail CI.

CVE-2026-47884 / GHSA-pc63-qcmh-9cmg (`XsltView`, Issue #96) and CVE-2026-47890 / GHSA-j9f9-w8pj-32f8 (SSE view fragments, Issue #108), both critical in `spring-webmvc` 6.2.19, were reported on 2026-10-06 and 2026-10-13 respectively. They were suppressed in `.trivyignore` as not applicable because no open-source Spring Framework 6.2 fix existed. Spring Framework 7.0.9 fixes both. Since the Spring Boot 4.1 migration (Issue #50), both executable JARs resolve `spring-webmvc` 7.0.9, so both suppressions were removed. `.trivyignore` has no active entries, and no Trivy finding is suppressed.

## Managed Version Overrides

`spring-boot-starter-parent` manages Spring and third-party dependency versions. Spring Boot 4.1.1 manages Tomcat 11.0.24 and Jackson 3.1.5, which Trivy flags with HIGH/CRITICAL findings, so `backend/pom.xml` and `worker/pom.xml` override these two third-party versions through the parent's version properties, with the same values in both POMs. No Spring artifact is overridden.

| Property | Override | Managed by Spring Boot 4.1.1 | Findings in the managed version | Remove when |
|---|---|---|---|---|
| `tomcat.version` | 11.0.27 | 11.0.24 | CVE-2026-65182, CVE-2026-65905, CVE-2026-68525 (fixed in 11.0.25) | A Spring Boot 4.1.x patch release manages Tomcat 11.0.27 or newer |
| `jackson-bom.version` | 3.1.7 | 3.1.5 | CVE-2026-89407, CVE-2026-89425, CVE-2026-68497, CVE-2026-91776, CVE-2026-91777 (fixed in 3.1.7) | A Spring Boot 4.1.x patch release manages Jackson 3.1.7 or newer |

When updating `spring-boot-starter-parent`, compare the `tomcat.version` and `jackson-bom.version` properties in the new parent's `spring-boot-dependencies` POM with these overrides. Remove each override whose condition is met in the same pull request, together with its POM comment and table row; the same applies when a later Spring Boot minor release manages an equal or newer version. Add an override only for a HIGH or CRITICAL finding in a third-party library that the current Spring Boot patch release does not fix yet, and document its reason and removal condition here.

## Monitoring Ownership

The repository owner (`Duyphanb`) owns dependency monitoring. Dependabot is intentionally not used: on 2026-10-06 the owner chose owner-authored dependency updates instead of bot pull requests, and Dependabot alerts and security updates stay disabled. The alternative process is:

- **Detection.** The dependency-security workflow runs on every pull request, on every push to `main`, and weekly on schedule. When a scheduled run fails, its `Report scheduled audit failure` job opens an issue titled `Scheduled dependency audit failed on main`, assigned to the owner and labeled `type:security` and `priority:P0`, or comments on that issue if it is already open. This alert does not depend on personal notification settings; GitHub Actions failure notifications are only an optional second channel. The `Production dependency vulnerability audit` job is a required status check, so a new high or critical advisory also blocks merges until it is handled.
- **Response.** The owner triages the alert issue, opens a focused fix pull request before other work merges, and closes the alert issue once the audit passes on `main`. The [ADR-002 support model](../architecture/adr/ADR-002.md#support-model) sets the outer limit: HIGH and CRITICAL findings are fixed in the current sprint. A suppression still needs a dedicated issue with evidence that the advisory does not apply.
- **Routine updates.** The owner reviews outdated dependencies monthly and at the end of each sprint, then applies accepted updates in an owner-authored `chore(deps)` pull request. Use `npm outdated` in `frontend`; `mvn -B -ntp versions:display-parent-updates versions:display-dependency-updates versions:display-property-updates versions:display-plugin-updates` in `backend` and `worker`; and the release pages of each SHA-pinned GitHub Action and the Trivy CLI `version` input. Patch and minor `spring-boot-starter-parent` releases come first, because the parent manages most dependency and plugin versions and is the main security patch channel; under ADR-002 the backend and worker follow each Spring Boot 4.x minor release. Each parent update also rechecks the managed version overrides above. Major upgrades follow the ownership rules below and are never mixed into routine updates. Dependency pull requests are never auto-merged: repository auto-merge stays disabled, and the owner reviews and squash-merges every update, including patch updates.

Dependency Review status, verified on 2026-10-06 by the repository administrator: the dependency-review API is unavailable because the GitHub Dependency Graph is not enabled for this repository. This is a disabled feature, not an access limitation. No Dependency Review check is added. To add one later, enable the Dependency Graph (it creates no pull requests or commits), confirm the API responds, and only then add a SHA-pinned `actions/dependency-review-action` step.

## Follow-up ownership

- [Issue #50](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/50) recorded the supported Spring platform decision: open-source Spring Boot 4.x, maintained by the owner ([ADR-002 amendment](../architecture/adr/ADR-002.md#amendment-2026-10-18)). The complete Spring Boot 4.1 migration replaced the parent-only Boot 4 PRs #66/#68, which were closed without merging because they failed compilation and conflicted with ADR-002 as frozen at the time. A dependency-audit job that stops during packaging, before scanning, does not prove a new vulnerability finding. Future major upgrades require another ADR-002 amendment and a complete migration.
- [Issue #54](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/54) owns maintained non-MinIO images, immutable artifacts, runtime users and container OS scan coverage. The existing JAR/npm gates do not certify container OS packages, broker or object-store maintenance.
- [Issue #74](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/74) owns the RabbitMQ support/upgrade decision; [Issue #14](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/14) retains MinIO ownership. Resolve these runtime decisions before public production, without treating them as authorization for a platform migration during feature work.

## Local Checks

```bash
cd frontend
npm audit --package-lock-only --omit=dev --audit-level=high
```

The Trivy gate is defined in `.github/workflows/dependency-security.yml` and scans the executable Maven artifacts produced by the same Java 21 build used in CI. It runs on every pull request and push to `main`, on a weekly schedule, and on manual dispatch. There are no path filters: the check must be available on every pull request before it can safely be required by branch protection.
