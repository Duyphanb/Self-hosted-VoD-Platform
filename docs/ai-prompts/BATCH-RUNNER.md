# VoD Platform Batch Runner Prompt

Use this prompt when starting a bounded implementation, documentation, testing, or deployment batch for this repository.

```text
Continue work in the current workspace as the source of truth.

Do not use older or similarly named project paths.

Start by reading:

1. AGENTS.md
2. docs/INDEX.md

Then infer the smallest applicable phase and read only the minimum relevant docs and code paths for the current batch.
    For Phase 3 sprint issues specifically, also read `docs/ai-prompts/SPRINT-EXECUTION-GUIDE.md` and follow its one-issue-per-batch and grep-based verification rules.

Current batch:

- Batch name:
- Phase:
- Issue number, if any:
- Requested files or areas:
- Explicit non-goals:

Before editing files:

- List assumptions.
- Confirm which docs and source paths are relevant.
- Keep the batch small and scoped.

Repository constraints:

- Keep the project as a modular monolith plus a dedicated worker.
- Do not introduce microservices.
- Do not introduce Kubernetes, Kafka, Elasticsearch, DRM, payments, live streaming, or recommendation-engine scope during MVP.
- Prefer docs, contracts, skeletons, and tests before business feature implementation.
- Do not add production business features unless explicitly requested.
- Do not hardcode secrets or environment-specific URLs.
- Keep runtime configuration environment-driven.
- Add tests in the same batch as behavior changes.

For Docker skeleton batches:

- Align with docs/architecture/INFRASTRUCTURE.md.
- Keep Nginx as the only public edge.
- Keep PostgreSQL, Redis, RabbitMQ, MinIO, backend, and worker internal by default.
- Use stable Compose service names for internal DNS.
- Keep MinIO buckets private.
- Keep worker concurrency at 1 for MVP unless explicitly changed.
- Ensure FFmpeg and FFprobe paths can come from environment variables.
- Use a local override file for development-only direct port exposure.
- Do not expose RabbitMQ management or MinIO console in the base MVP Compose file.

Future workflow upgrade:

- After several batches have been completed and the batch workflow is stable, proactively propose upgrading this prompt into a repo-scoped Codex skill at `.agents/skills/vod-platform-batch-runner/SKILL.md`.
- If the user approves that upgrade, update the repository layout guidance as needed before creating `.agents/`, then move the durable batch workflow into the skill without duplicating all of `AGENTS.md`.

Expected response after the batch:

- Files created or changed.
- Assumptions used.
- Verification performed.
- Blockers, if any.
- Recommended next batch.
- Skills or capabilities used, and why.
```
