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

As of 2026-10-06, Trivy reports CVE-2026-47884 / GHSA-pc63-qcmh-9cmg (critical) in `spring-webmvc` 6.2.19 in both executable JARs. The advisory requires `XsltView` with a `/**` mapping that renders a view without an explicit view name. This repository renders no views: there is no `XsltView`, view resolver, `ModelAndView`, or template engine, and every web endpoint is a `@RestController`. No open-source fix exists on the Spring Framework 6.2 line, and the 7.0.9 fix depends on the platform decision in Issue #50. The advisory is suppressed in `.trivyignore` until 2026-12-31 under Issue #96. Revisit it at that date, or earlier if any view rendering, XSLT, or template engine is introduced. No other finding is suppressed.

## Monitoring Ownership

The repository owner (`Duyphanb`) owns dependency monitoring. Dependabot is intentionally not used: on 2026-10-06 the owner chose owner-authored dependency updates instead of bot pull requests, and Dependabot alerts and security updates stay disabled. The alternative process is:

- **Detection.** The dependency-security workflow runs on every pull request, on every push to `main`, and weekly on schedule. A scheduled failure notifies the owner, who last changed the workflow's `cron` line. Its `Production dependency vulnerability audit` job is a required status check, so a new high or critical advisory blocks merges until it is handled.
- **Response.** When a scheduled scan fails, the owner opens a prioritized issue (P0 when the required check is red on `main`) and a focused fix pull request before other work merges. A suppression still needs a dedicated issue with evidence that the advisory does not apply.
- **Routine updates.** The owner reviews outdated dependencies monthly and at the end of each sprint, then applies accepted updates in an owner-authored `chore(deps)` pull request. Use `npm outdated` in `frontend`; `mvn -B -ntp versions:display-parent-updates versions:display-dependency-updates versions:display-property-updates versions:display-plugin-updates` in `backend` and `worker`; and the release pages of each SHA-pinned GitHub Action and the Trivy CLI `version` input. Patch and minor `spring-boot-starter-parent` releases come first, because the parent manages most dependency and plugin versions and is the main security patch channel. Major upgrades follow the ownership rules below and are never mixed into routine updates. Dependency pull requests are never auto-merged: repository auto-merge stays disabled, and the owner reviews and squash-merges every update, including patch updates.

Dependency Review status, verified on 2026-10-06 by the repository administrator: the dependency-review API is unavailable because the GitHub Dependency Graph is not enabled for this repository. This is a disabled feature, not an access limitation. No Dependency Review check is added. To add one later, enable the Dependency Graph (it creates no pull requests or commits), confirm the API responds, and only then add a SHA-pinned `actions/dependency-review-action` step.

## Follow-up ownership

- [Issue #50](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/50) owns the supported Spring platform decision. The parent-only Boot 4 PRs #66/#68 were closed without merging: they fail compilation and conflict with frozen ADR-002. Their dependency-audit jobs stop during packaging, before scanning; a red job in that case does not prove a new vulnerability finding. Future major upgrades require the explicit platform decision and a complete migration.
- [Issue #54](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/54) owns maintained non-MinIO images, immutable artifacts, runtime users and container OS scan coverage. The existing JAR/npm gates do not certify container OS packages, broker or object-store maintenance.
- [Issue #74](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/74) owns the RabbitMQ support/upgrade decision; [Issue #14](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/14) retains MinIO ownership. Resolve these runtime decisions before public production, without treating them as authorization for a platform migration during feature work.

## Local Checks

```bash
cd frontend
npm audit --package-lock-only --omit=dev --audit-level=high
```

The Trivy gate is defined in `.github/workflows/dependency-security.yml` and scans the executable Maven artifacts produced by the same Java 21 build used in CI. It runs on every pull request and push to `main`, on a weekly schedule, and on manual dispatch. There are no path filters: the check must be available on every pull request before it can safely be required by branch protection.
