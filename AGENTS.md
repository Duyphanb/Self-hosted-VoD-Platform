# Repository Instructions for Codex

## Project Direction

This repository is a self-hosted Video-on-Demand platform built as a modular monolith plus a dedicated worker. It is not a microservices project.

Keep the MVP tight. Prefer documentation, skeletons, contracts, and tests before business feature implementation. Do not add production features unless a prompt explicitly asks for them.

## Source Of Truth

Treat the frozen docs under `docs/requirements/` and `docs/architecture/` as the source of truth for implementation.

Root-level planning artifacts such as `VoD_Platform_Final.docx`, `VoD_SDLC_Master_Plan_Codex_Final_Reviewed.docx`, and `VoD_SDLC_Master_Plan_Codex_Final_Reviewed.md` are archival inputs only. Do not use their older schema or storage names when they conflict with frozen docs. In particular, prefer `docs/architecture/ERD.md` for table names and `docs/architecture/INFRASTRUCTURE.md` for MinIO bucket names.

## Stack

- Backend: Java 21, Spring Boot 3.x, Maven multi-module
- Frontend: React 18, TypeScript, Vite, Tailwind CSS, TanStack Query
- Database and storage: PostgreSQL, Flyway, MinIO
- Cache and queue: Redis, RabbitMQ
- Media processing: FFmpeg, FFprobe, HLS
- Reverse proxy and delivery: Nginx
- Runtime and deployment: Docker Compose locally, Oracle Cloud Free Tier VPS for demo deployment
- Optional later: AWS S3 and CloudFront through a storage adapter after the VPS deployment is stable

## Repo Layout

```text
/
+-- frontend/
+-- backend/
+-- worker/
+-- deploy/
+-- docs/
|   +-- requirements/
|   +-- architecture/
|   |   +-- adr/
|   +-- testing/
|   +-- deployment/
|   +-- ai-prompts/
+-- .github/workflows/
+-- .husky/
+-- AGENTS.md
+-- README.md
+-- .env.example
```

Use these names consistently. Do not create ad hoc top-level folders without an explicit architecture decision.

## Backend Rules

- Use Java 21 and Spring Boot 3.x.
- Keep the backend as the core API in the modular monolith.
- Use constructor injection only.
- Keep controllers thin; business logic belongs in services.
- Use DTOs for all request and response payloads.
- Never expose JPA entities directly through API responses.
- Use Spring Validation for request validation.
- Use `@RestControllerAdvice` for centralized exception handling.
- Use Flyway for all schema changes.
- Never edit an existing committed migration; create a new migration instead.
- Use SLF4J logging, not `System.out.println`.
- Externalize all runtime configuration through environment variables.
- Keep OpenAPI/Swagger contracts synchronized with API behavior.

## Frontend Rules

- Use React 18, TypeScript, Vite, and Tailwind CSS.
- Use functional components only.
- Use TanStack Query for server state.
- Use a shared API client with JWT handling; do not scatter raw fetch calls across components.
- Use feature-based folders once implementation begins.
- Every user-facing request state must handle loading, error, empty, and success states.
- Protected routes and admin-only routes must use explicit role guards.
- Do not build marketing pages in place of the actual app experience.

## Worker Rules

- The worker is a separate runtime for video processing, not a separate business service.
- Keep worker responsibilities focused on queue consumption, media probing, transcoding, artifact upload, and status updates.
- Consume RabbitMQ encoding jobs from the worker, not from the API request thread.
- Keep worker concurrency at 1 for MVP stability unless a later prompt changes this.
- FFmpeg and FFprobe must be available in `PATH` in the worker runtime.
- Use `ffmpeg` and `ffprobe` command paths from environment variables when provided.
- Persist processing lifecycle states clearly: `UPLOADED`, `QUEUED`, `PROCESSING`, `READY`, `FAILED`.
- Do not implement multi-quality encoding until the single-quality MVP pipeline is stable.

## Testing Rules

- Add tests in the same batch as behavior changes.
- Backend tests should use JUnit 5 and Mockito by default.
- Use integration tests for persistence, queue, and storage boundaries when those modules are introduced.
- Frontend tests should cover important user flows and state handling once the UI exists.
- Worker tests must cover command construction, status transitions, and failure handling before production hardening.
- Do not claim a flow is complete until the upload -> transcode -> HLS playback path is verified.

## Security Rules

- Never commit secrets, tokens, private keys, or real credentials.
- Keep `.env.example` safe with placeholder values only.
- Passwords must be hashed with BCrypt.
- JWT secrets must come from environment variables.
- Upload endpoints must validate file type and size.
- Buckets should be private by default.
- Admin upload and retry operations must require `ROLE_ADMIN`.
- User playback and profile operations must enforce ownership or access checks.
- Avoid logging secrets, JWTs, raw passwords, or sensitive request bodies.

## Git Rules

- Use conventional commits.
- Prefer branch names like `feature/{issue-number}-{short-desc}` when an issue exists.
- When a completed task maps to a GitHub Issue, include a GitHub closing keyword in the commit or PR title/body, for example `feat: init spring boot structure, closes #5`.
- Use closing keywords only when the change fully completes the issue; otherwise reference the issue without closing it.
- Do not push directly to `main` for major changes.
- Keep changes scoped to the prompt and batch.
- Before staging, inspect `git status` and avoid staging unrelated work.
- Do not rewrite history or reset user changes unless explicitly requested.

## Forbidden Patterns

- Do not turn the project into microservices.
- Do not add Kubernetes, Kafka, Elasticsearch, DRM, payments, live streaming, or recommendation-engine scope during MVP.
- Do not implement business features during documentation or skeleton batches.
- Do not hardcode secrets or environment-specific URLs.
- Do not put business logic in controllers or UI components.
- Do not bypass RabbitMQ for the encoding workflow.
- Do not use local filesystem media storage as the final architecture; use MinIO for MVP object storage.
- Do not add broad abstractions before the simple MVP flow exists.

## How Codex Should Be Used

- Start every task by reading this file.
- Then read `docs/INDEX.md` to select the minimum relevant docs for the current phase or batch.
- Work in small, bounded batches.
- List assumptions before editing files.
- Modify only the files requested by the current batch.
- Read only the docs and code paths relevant to the current task; do not inspect unrelated later-phase docs or unrelated modules.
- Prefer docs, contracts, skeletons, and tests before implementation.
- When the user sends work from GitHub tasks, ask for or infer the issue number and keep the final commit/PR message ready to close that issue automatically after merge.
- After each batch, report files created or changed, assumptions, blockers, and the recommended next batch.
- If a change affects architecture, API contracts, deployment, or security, update the relevant docs in the same batch only when the prompt allows it.
- If user-level tooling instructions are active, such as command proxy requirements, follow them without committing machine-specific configuration.

## Documentation Routing

- Use `docs/INDEX.md` as the map for which docs to read.
- Phase 1 tasks read `docs/requirements/` only.
- Phase 2 tasks read `docs/requirements/` plus the relevant files under `docs/architecture/`.
- Phase 3 implementation tasks read relevant requirements, relevant architecture docs, and only the named code paths.
- Phase 3 implementation tasks must also read `docs/ai-prompts/SPRINT-EXECUTION-GUIDE.md` before starting any sprint issue.
- Phase 4 testing tasks read `docs/testing/` plus relevant implementation and source-of-truth docs.
- Phase 5 deployment tasks read `docs/deployment/` plus relevant architecture and implementation files.
- If a task does not name a phase, infer the smallest current phase scope and avoid later-phase files.
