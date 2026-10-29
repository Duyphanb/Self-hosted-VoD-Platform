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

`spring-boot-starter-parent` manages Spring and many third-party dependency versions. Spring Boot 4.1.1 manages Tomcat 11.0.24 and Jackson 3.1.5, which Trivy flags with HIGH/CRITICAL findings, so `backend/pom.xml` and `worker/pom.xml` override these two third-party versions through the parent's version properties, with the same values in both POMs. The SDK-specific Jackson 2/Guava/Snappy overrides and exclusion below are backend-only: the worker does not yet use the SDK. No Spring artifact is overridden.

| Property | Override | Managed by Spring Boot 4.1.1 | Findings in the managed version | Remove when |
|---|---|---|---|---|
| `tomcat.version` | 11.0.27 | 11.0.24 | CVE-2026-65182, CVE-2026-65905, CVE-2026-68525 (fixed in 11.0.25) | A Spring Boot 4.1.x patch release manages Tomcat 11.0.27 or newer |
| `jackson-bom.version` | 3.1.7 | 3.1.5 | CVE-2026-89407, CVE-2026-89425, CVE-2026-68497, CVE-2026-91776, CVE-2026-91777 (fixed in 3.1.7) | A Spring Boot 4.1.x patch release manages Jackson 3.1.7 or newer |
| `jackson-2-bom.version` (backend) | 2.21.7 | 2.21.5 | Jackson core: CVE-2026-89425, CVE-2026-89407; databind: CVE-2026-91777, CVE-2026-91776, CVE-2026-68497 | Boot manages Jackson 2.21.7 or newer, or the SDK no longer requires Jackson 2 |
| `guava.version` (backend) | 33.7.2-jre | Not managed; SDK requests 33.6.0-jre | CVE-2026-102554 | The selected SDK resolves Guava 33.7.2-jre or newer without this override, or no longer requires Guava |
| `snappy-java.version` (backend) | 1.1.10.9 | Not managed; SDK requests 1.1.10.8 | CVE-2026-93451, CVE-2026-108106 | The selected SDK resolves Snappy 1.1.10.9 or newer without this override, or client construction and all used paths work without Snappy |

`common/pom.xml` excludes `org.bouncycastle:bcprov-jdk18on` from minio-java: no SDK class references it, and its 1.84 version carries CVE-2026-8763/CVE-2026-13506. Remove this exclusion only if upstream stops declaring the artifact or a newly required path needs it; in the latter case, select a fixed version and repeat runtime/dependency verification first. The exclusion is not a vulnerability suppression: the artifact is absent from the packaged JAR.

`ObjectStorage` uses ordinary known-length PUT, GET and DELETE and never calls Snowball, the only SDK code path referencing `org.xerial.snappy:snappy-java`. However, excluding Snappy reproduced `NoClassDefFoundError: org/xerial/snappy/SnappyFramedOutputStream` in `MinioClient.builder()`: JVM class resolution requires its classes even when Snowball is unused. Retain the fixed managed version above rather than relying on apparent source-level unreachability.

Issue 5.2 must copy these SDK-specific protections into the separate worker reactor when adding its storage client. It must also preserve #169's correct named timeouts, bounded DNS waits/pool, explicit dispatcher/PUT bound and safe transport-exception mapping; the backend reactor cannot manage the worker's dependencies or client configuration.

When updating `spring-boot-starter-parent`, compare its managed properties with all applicable overrides above, including Jackson 2. When updating minio-java, recheck its full runtime tree, the exclusions' reachability and each artifact's GitHub advisories. Remove each override whose condition is met in the same pull request, together with its POM comment and table row; the same applies when a later Spring Boot minor release manages an equal or newer version. Add an override only for a HIGH or CRITICAL finding in a third-party library that the current Spring Boot patch release does not fix yet, and document its reason and removal condition here.

## Monitoring Ownership

The repository owner (`Duyphanb`) owns dependency monitoring. Dependabot is intentionally not used: on 2026-10-06 the owner chose owner-authored dependency updates instead of bot pull requests, and Dependabot alerts and security updates stay disabled. The alternative process is:

- **Detection.** The dependency-security workflow runs on every pull request, on every push to `main`, and weekly on schedule. When a scheduled run fails, its `Report scheduled audit failure` job opens an issue titled `Scheduled dependency audit failed on main`, assigned to the owner and labeled `type:security` and `priority:P0`, or comments on that issue if it is already open. This alert does not depend on personal notification settings; GitHub Actions failure notifications are only an optional second channel. The `Production dependency vulnerability audit` job is a required status check, so a new high or critical advisory also blocks merges until it is handled.
- **Response.** The owner triages the alert issue, opens a focused fix pull request before other work merges, and closes the alert issue once the audit passes on `main`. The [ADR-002 support model](../architecture/adr/ADR-002.md#support-model) sets the outer limit: HIGH and CRITICAL findings are fixed in the current sprint. A suppression still needs a dedicated issue with evidence that the advisory does not apply.
- **Routine updates.** The owner reviews outdated dependencies monthly and at the end of each sprint, then applies accepted updates in an owner-authored `chore(deps)` pull request. Use `npm outdated` in `frontend`; `mvn -B -ntp versions:display-parent-updates versions:display-dependency-updates versions:display-property-updates versions:display-plugin-updates` in `backend` and `worker`; and the release pages of each SHA-pinned GitHub Action and the Trivy CLI `version` input. Patch and minor `spring-boot-starter-parent` releases come first, because the parent manages most dependency and plugin versions and is the main security patch channel; under ADR-002 the backend and worker follow each Spring Boot 4.x minor release. Each parent update also rechecks the managed version overrides above. Major upgrades follow the ownership rules below and are never mixed into routine updates. Dependency pull requests are never auto-merged: repository auto-merge stays disabled, and the owner reviews and squash-merges every update, including patch updates.

Dependency Review status: on 2026-10-06 the dependency-review API was unavailable because the GitHub Dependency Graph was not enabled. The owner has since enabled the Dependency Graph (it creates no pull requests or commits), and on 2026-10-15 the dependency-graph compare API responds. The `dependency-review.yml` workflow now runs a SHA-pinned `actions/dependency-review-action` on every pull request (Issue #98). It compares the base and head dependency graphs and fails when the pull request adds a runtime dependency with a high or critical advisory. It uses read-only permissions and posts no pull request comment; the summary is in the job log. It complements, and does not replace, the full production audit above. It sees only dependency changes in the pull request, while `Production dependency vulnerability audit` checks everything on the branch. Coverage also differs by ecosystem: for npm the graph reads `package-lock.json`, so every resolved package is evaluated, but for Maven it lists only the dependencies declared directly in the POMs, without resolved versions. BOM-managed versions, transitive dependencies and version-property overrides such as `tomcat.version` are therefore not evaluated for Java; the Trivy JAR scan remains the gate there. `Dependency review` is not a required status check yet; the owner decides whether to add it to the ruleset after it has run on several pull requests.

## Follow-up ownership

- [Issue #50](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/50) recorded the supported Spring platform decision: open-source Spring Boot 4.x, maintained by the owner ([ADR-002 amendment](../architecture/adr/ADR-002.md#amendment-2026-10-18)). The complete Spring Boot 4.1 migration replaced the parent-only Boot 4 PRs #66/#68, which were closed without merging because they failed compilation and conflicted with ADR-002 as frozen at the time. A dependency-audit job that stops during packaging, before scanning, does not prove a new vulnerability finding. Future major upgrades require another ADR-002 amendment and a complete migration.
- [Issue #54](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/54) owns maintained non-MinIO images, immutable artifacts, runtime users and container OS scan coverage. The existing JAR/npm gates do not certify container OS packages, broker or object-store maintenance.
- [Issue #74](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/74) owns the RabbitMQ support/upgrade decision; [Issue #14](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/14) records the pinned Silo decision and storage verification below. Resolve these runtime decisions before public production, without treating them as authorization for a platform migration during feature work.

## Object storage runtime (#14)

Reviewed 2026-10-28: PGSTY Silo `RELEASE.2026-10-26T00-00-00Z`, source `c3ad836aed0d8a7d23c2e44a5eaa3009d08525e3`. The [ADR-004 addendum](../architecture/adr/ADR-004.md#runtime-addendum) records the index/platform/SBOM digests, exact build identity, trust boundary and update/exit policy. Both Compose storage services use this same immutable artifact. `storage-runtime-smoke.py` verifies signed SLSA provenance (source revision, exact workflow/tag, no self-hosted runners) and signed SPDX SBOMs for amd64 and arm64 before pulling/starting it. A wrong-signer verification was rejected locally. This verifies origin, not exploit resistance or a complete OS vulnerability scan (#54).

The [pinned source security policy](https://github.com/pgsty/silo/blob/c3ad836aed0d8a7d23c2e44a5eaa3009d08525e3/SECURITY.md), [release notes](https://github.com/pgsty/silo/blob/c3ad836aed0d8a7d23c2e44a5eaa3009d08525e3/CHANGELOG.md) and maintainer [advisory ledger](https://silo.pgsty.com/about/security-advisories/) were reviewed against the selected release:

| Advisory | Review result for the selected version |
|---|---|
| CVE-2025-62506 / GHSA-jjjj-jwhf-8rgr | Pinned policy records the inherited `c1a49490` session-policy fix, present since Silo's first community release. |
| CVE-2026-33322, CVE-2026-33419 | Ledger records OIDC/LDAP STS fixes retained in this release. These identity integrations are not enabled by this stack. |
| CVE-2026-34204, CVE-2026-39414 | Ledger records replication-metadata and S3 Select bounds fixes, including subsequent hardening. |
| CVE-2026-41145, CVE-2026-40344, CVE-2026-42600 | Ledger records unsigned-trailer/Snowball authentication and internode traversal fixes retained in this release. |
| SN-2026-011 through SN-2026-019 | Review includes signed-header coverage, IAM/STS revocation, retention/overwrite authorization, proxy-address trust and notification-secret redaction. Ledger places the October authorization fixes in the 20261018 baseline retained by 20261026. |
| CVE-2026-32285 | Maintainer reports misattribution to jsonparser; do not infer a fresh fix or suppress a scanner solely from that statement. |

These are source/release/maintainer-evidence assessments; the repository did not rerun each exploit regression or certify the ledger's abbreviated commit links. The local spike/runtime smoke covers the actual storage contract, not every S3/IAM API. No claim that Silo is vulnerability-free is made. Review fresh advisories and scanner findings on every bump, including reachability and fork fix evidence before granting exceptions. Root runtime (#54), registry availability and concentrated maintenance remain risks. #169 replaces shared application root credentials with scoped identities; policy and SDK integration checks are described in INFRASTRUCTURE.

## Media processing prerequisites (#103 / #54)

[ADR-010](../architecture/adr/ADR-010.md#untrusted-media-handling-d6-d10) requires a maintained FFmpeg source before Sprint 5. The owner-approved research reports a local image built from the unchanged worker Dockerfile with jammy `7:4.4.2-0ubuntu0.22.04.1` and uid 0; this is research evidence, not a CI image measurement repeated by this docs task. #54 must verify package security support or a checksum-verified upstream build, record `dpkg -s ffmpeg` where applicable, `ffmpeg -version`, FFprobe command compatibility and runtime uid in the chosen image, and document updates/OS scan limits. Debian-stable packages and a pinned upstream build are candidates, not an image decision in this change. Signature checks, protocol restrictions and capped probing do not replace decoder security fixes. #124 sets measured caps/deadlines; #74 separately settles the broker version and consumer-timeout rule.

## Local Checks

```bash
cd frontend
npm audit --package-lock-only --omit=dev --audit-level=high
```

The Trivy gate is defined in `.github/workflows/dependency-security.yml` and scans the executable Maven artifacts produced by the same Java 21 build used in CI. It runs on every pull request and push to `main`, on a weekly schedule, and on manual dispatch. There are no path filters: the check must be available on every pull request before it can safely be required by branch protection.
