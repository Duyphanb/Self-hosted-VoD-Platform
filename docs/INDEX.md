# Documentation Index

Use this map when the task needs context. Select relevant sections and follow dependencies as needed; phase labels describe project organization, not reading restrictions. There is no mandatory document stack for every edit.

## Source of truth

Frozen requirements and architecture govern implementation. Planning/status documents describe intended work, not proof of completion. `docs/reports/` contains archival plans and may use obsolete names.

| Task or decision | Relevant source |
|---|---|
| MVP scope and exclusions | [VISION.md](requirements/VISION.md), [OUT_OF_SCOPE.md](requirements/OUT_OF_SCOPE.md) |
| Acceptance criteria, constraints, terminology | [USERSTORIES.md](requirements/USERSTORIES.md), [NFR.md](requirements/NFR.md), [DOMAINGLOSSARY.md](requirements/DOMAINGLOSSARY.md) |
| Backlog issue, dependencies, planned next step | Specific issue in [BACKLOG.md](requirements/BACKLOG.md), [PROJECTPLAN.md](requirements/PROJECTPLAN.md); verify status against current code/GitHub when relevant |
| Requirement coverage and risks | [TRACEABILITY.md](requirements/TRACEABILITY.md), [RISKREGISTER.md](requirements/RISKREGISTER.md) |
| Runtime/module boundaries | [SYSTEM-ARCHITECTURE.md](architecture/SYSTEM-ARCHITECTURE.md), relevant [ADR](architecture/adr/) |
| Tables, fields, constraints, migrations | [ERD.md](architecture/ERD.md); use frozen names such as `people`, `movie_credits`, `watchlist_items` |
| HTTP methods, paths, payloads, status codes | [API-CONTRACT.yaml](architecture/API-CONTRACT.yaml), affected flow in [SEQUENCE-DIAGRAMS.md](architecture/SEQUENCE-DIAGRAMS.md) |
| Authentication, authorization, uploads, HLS access | [SECURITY.md](architecture/SECURITY.md) plus the affected API/sequence |
| Compose, network exposure, storage, environment configuration | [INFRASTRUCTURE.md](architecture/INFRASTRUCTURE.md); private buckets are `vod-raw`, `vod-hls`, `vod-thumbnails` |
| Logs, health, metrics | [OBSERVABILITY.md](architecture/OBSERVABILITY.md) |

## Implementation and verification

- [Implementation conventions](../Codex_Usage_Notes.md#implementation-conventions): backend, frontend, and worker rules; read the applicable section when changing code.
- Current code and tests live in `backend/`, `frontend/`, `worker/`, and `deploy/`. Inspect the paths needed to understand and verify the change.
- [README.md](../README.md): local stack commands and runtime limitations. Check destinations and data persistence before running commands that mutate state.
- [CI workflow](../.github/workflows/ci.yml), module POMs, and [frontend scripts](../frontend/package.json): current build/test commands. Select relevant checks rather than treating the entire CI workflow as a per-edit checklist.
- [Testing docs](testing/README.md): test evidence and hardening guides. [Dependency security](testing/DEPENDENCY-SECURITY.md) covers dependency scans.
- [Deployment docs](deployment/README.md): deployment and production preflight guidance. Reading a runbook does not authorize production operations.

## Optional Codex guidance

- [Ghi chú review kỹ thuật](reports/ENGINEERING-REVIEW-NOTES.md): đọc khi chọn ưu tiên hardening, thiết kế upload/worker/playback hoặc cân nhắc sau MVP. Tổng hợp trao đổi của người dùng; các đề xuất chưa thay thế frozen contracts hay trạng thái GitHub hiện tại.
- [Batch prompt](ai-prompts/BATCH-RUNNER.md): specify a bounded outcome and completion evidence when preparing a task.
- [Sprint guide](ai-prompts/SPRINT-EXECUTION-GUIDE.md): backlog dependencies and issue acceptance checks when implementing a sprint issue.
- [Instruction maintenance](../Codex_Usage_Notes.md#instruction-maintenance): where guidance belongs and when a skill is justified.

Repository-wide boundaries and completion criteria live in [AGENTS.md](../AGENTS.md). These optional guides add context without imposing another reading sequence or overriding frozen contracts.
