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

## Automated Update Visibility

Dependabot checks backend Maven, worker Maven, frontend npm, and GitHub Actions weekly. Each ecosystem is limited to one open version-update pull request, and no auto-merge is configured.

Dependabot updates the SHA-pinned action references, but it does not own the explicit Trivy CLI `version` input. Review that scanner version separately whenever the Trivy action or the scheduled security baseline is updated.

The Dependency Review action is intentionally absent until a repository administrator enables and verifies the GitHub Dependency Graph and its dependency-review API. Dependabot alerts and security updates are separate recommended repository settings, not technical prerequisites for the action. Adding a review action before the graph/API prerequisite is available would create a misleading or permanently failing check.

## Local Checks

```bash
cd frontend
npm audit --package-lock-only --omit=dev --audit-level=high
```

The Trivy gate is defined in `.github/workflows/dependency-security.yml` and scans the executable Maven artifacts produced by the same Java 21 build used in CI. It runs when dependency manifests or security automation change and on a weekly schedule, so unrelated source-only pull requests do not repeat external advisory downloads.
