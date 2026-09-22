# Repository Instructions for Codex

Trao đổi và báo cáo với người dùng bằng tiếng Việt; giữ nguyên tên kỹ thuật, định danh và quy ước commit/PR của dự án.

## Scope and source of truth

- Work from the current checkout. Keep changes within the requested outcome; documentation or skeleton tasks do not authorize business features or the next backlog issue.
- Preserve the MVP: a modular monolith plus a dedicated encoding worker, not microservices. Keep the existing stack and layout; no new top-level areas or out-of-scope product features without an explicit architecture decision.
- Frozen requirements and architecture under `docs/requirements/` and `docs/architecture/` govern implementation. Use [docs/INDEX.md](docs/INDEX.md) when locating relevant contracts; it is a map, not a required reading sequence. `docs/reports/` contains archival inputs, not implementation authority.
- Read the relevant frozen contract before changing behavior governed by it. A request to implement a feature does not authorize changing frozen architecture, API, schema, or security decisions. If a conflict requires such a change, explain the conflict and proposed decision, ask for authorization, and continue independent work. Make authorized contract changes with the affected docs/ADR in the same task.

## Repository invariants

- Encoding runs in the worker through RabbitMQ, never in the API request thread. MVP worker concurrency is 1 with single-quality encoding. MinIO is final media storage; Nginx is the only public edge. Preserve the authenticated backend HLS proxy and private buckets.
- Use Flyway for schema changes. Never edit an existing committed migration; add a new one consistent with the frozen schema or an explicitly authorized schema change.
- Preserve BCrypt passwords, environment-supplied JWT secrets, `ROLE_ADMIN` for upload/retry, upload type/size validation, and ownership/access checks for playback and profile operations. Consult `docs/architecture/SECURITY.md` for the affected flow.
- Keep runtime configuration environment-driven. Never commit or expose secrets, tokens, private keys, real credentials, or sensitive request bodies in logs, reports, or tool output. `.env.example` must contain safe placeholders only.

## Local autonomy and boundaries

- Inspect the branch and worktree before edits. Preserve unrelated changes and untracked files; never reset, overwrite, or clean up user work to make a task easier.
- Choose the relevant docs, code, dependencies, callers, and tests yourself. Follow evidence across modules or phase folders when needed. File lists are starting points unless the user explicitly makes them an edit boundary.
- Routine local inspection, scoped editing, builds, tests, and fixes caused by the requested change are authorized. Resolve reversible implementation details and continue without an approval checkpoint for each step; surface material assumptions when they affect the result.
- Before running a check that writes data or starts services, establish that it targets local test resources. Do not assume Compose volumes or configured databases are disposable. Prefer isolated fixtures and clean up only resources created for the task.
- Obtain explicit authorization for destructive data operations, discarding user work, history rewrites/force pushes, production mutations/deployments, and privileged or system-wide changes. Explain the target, impact, and recovery plan first. Existing authorization within its stated scope remains valid.
- Commit, push, publish, merge, or modify remote issues only when authorized by the user. Prepare the diff and verification evidence first; stage only task files. Use conventional commits and `feature/{issue-number}-{short-desc}` or an appropriate `fix/`, `hotfix/`, or `release/` branch. Do not push major changes directly to `main`. Use issue closing keywords only for fully completed issues; otherwise reference them without closing.

## Completion

- Continue through the requested implementation or document changes, relevant verification, diff review, and fixing failures caused by the change. A first pass or a plan is not completion unless that is the requested deliverable.
- For code changes, use the applicable conventions in [Codex_Usage_Notes.md](Codex_Usage_Notes.md#implementation-conventions). Add meaningful tests with behavior changes and check affected contracts. Select checks from the changed behavior and existing project commands; do not run unrelated suites or repeatedly rerun passing checks without a reason.
- For instruction/documentation-only changes, check consistency, references, and the diff; application builds are unnecessary unless executable content or behavior changed.
- Only claim upload -> transcode -> HLS playback is complete after verifying that path. A successful build or health check proves only what it exercised.
- Finish when the requested outcome and relevant checks are satisfied, or a concrete blocker needs user input or an external change. Complete unaffected work before handing back. Report the result, changed files, verification and its limits, and any remaining blocker; do not label partial or unverified work complete.
