# Documentation Index

## How To Use These Docs

Read documents in this order:

1. `AGENTS.md`
2. `docs/INDEX.md`
3. The docs for the current phase only
4. The current GitHub issue, task, or batch instructions

Do not read later-phase docs unless the task explicitly asks for them.

## Source Of Truth Warning

Implementation must follow the frozen files under `docs/requirements/` and `docs/architecture/`.

Root-level master-plan artifacts are archival context only and may contain pre-freeze names. If they conflict with frozen docs, use:

- `docs/architecture/ERD.md` for schema/table names such as `people`, `movie_credits`, and `watchlist_items`
- `docs/architecture/INFRASTRUCTURE.md` for bucket names such as `vod-raw`, `vod-hls`, and `vod-thumbnails`
- `docs/requirements/PROJECTPLAN.md` for current phase and next-batch guidance

## Phase Map

### Phase 0 - Project Harness

Read:

- `AGENTS.md`
- `README.md`
- `.env.example`
- `docs/ai-prompts/` when prompt templates are needed

Purpose:

- repo skeleton
- workflow rules
- environment baseline
- Codex usage rules

### Phase 1 - Requirements And Planning

Read:

- `docs/requirements/README.md`
- `docs/requirements/VISION.md`
- `docs/requirements/USERSTORIES.md`
- `docs/requirements/NFR.md`
- `docs/requirements/OUT_OF_SCOPE.md`
- `docs/requirements/TRACEABILITY.md`
- `docs/requirements/DOMAINGLOSSARY.md`
- `docs/requirements/PROJECTPLAN.md`
- `docs/requirements/RISKREGISTER.md`

Purpose:

- freeze MVP scope
- define stories and acceptance criteria
- define measurable constraints
- define traceability and risks

### Phase 2 - Architecture Design

Read:

- `docs/architecture/README.md`
- `docs/architecture/SYSTEM-ARCHITECTURE.md`
- `docs/architecture/ERD.md`
- `docs/architecture/API-CONTRACT.yaml`
- `docs/architecture/SEQUENCE-DIAGRAMS.md`
- `docs/architecture/INFRASTRUCTURE.md`
- `docs/architecture/SECURITY.md`
- `docs/architecture/OBSERVABILITY.md`
- `docs/architecture/adr/`

Purpose:

- freeze domain model
- freeze API contract
- define runtime flows
- define infrastructure, security, observability, and ADR decisions

### Phase 3 - Implementation

Read:

- `backend/`
- `frontend/`
- `worker/`
- `deploy/`
- only the relevant Phase 1 and Phase 2 docs for the task

Purpose:

- implement features
- keep code aligned with frozen requirements and architecture
- add tests with behavior changes

### Phase 4 - Testing And Hardening

Read:

- `docs/testing/README.md`
- `docs/testing/`
- relevant Phase 1 and Phase 2 docs
- relevant implementation files

Purpose:

- add test evidence
- prepare Postman/Newman/manual E2E checks
- harden reliability

### Phase 5 - Deployment And Operations

Read:

- `docs/deployment/README.md`
- `docs/deployment/`
- relevant architecture docs
- relevant implementation files

Purpose:

- Oracle VPS deployment
- HTTPS setup
- backup and restore
- rollback
- runbook

## Default Rule

If a task does not mention a phase explicitly, assume the current phase only and ignore later phases.

## File Selection Rule

Read the minimum set of files required for the current task.

If a file is not relevant to the current batch, do not open it.

## Examples

### Requirements Task

Read:

- `AGENTS.md`
- `docs/INDEX.md`
- `docs/requirements/README.md`
- relevant files under `docs/requirements/`

Do not read:

- `docs/architecture/`
- `backend/`
- `frontend/`
- `worker/`

### Architecture Task

Read:

- `AGENTS.md`
- `docs/INDEX.md`
- `docs/requirements/`
- relevant current files under `docs/architecture/`

Do not read:

- implementation code unless the task explicitly asks to compare against it

### Backend Feature Task

Read:

- `AGENTS.md`
- `docs/INDEX.md`
- relevant requirements docs
- relevant architecture docs
- backend files needed for the task

Do not read:

- unrelated frontend, worker, deployment, or docs files
