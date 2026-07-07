# Sprint Execution Guide

## Purpose And Scope

This guide defines the process for using an AI coding agent (e.g. Codex) to implement one issue from `docs/requirements/BACKLOG.md` during Phase 3 (Feature Development).

It is a process companion to `AGENTS.md` and `docs/INDEX.md`, not a replacement for either. If anything in this guide conflicts with `AGENTS.md`, `AGENTS.md` wins.

This guide assumes Phase 0 (harness), Phase 1 (requirements), and Phase 2 (architecture) are already frozen and complete.

## 1. Reading Order For Every Issue

Read in this exact order before touching any file:

1. `AGENTS.md`
2. `docs/INDEX.md`
3. `docs/ai-prompts/SPRINT-EXECUTION-GUIDE.md` (this file)
4. `docs/requirements/BACKLOG.md` — the specific issue section only
5. The architecture docs listed for the current sprint in Section 3 below
6. The existing code paths the issue will touch

Do not read unrelated sprints, unrelated modules, or later-phase docs.

## 2. Batch Size Rule

One prompt equals one issue from `BACKLOG.md`, starting from Sprint 2 onward.

Sprint 1 issues that are pure scaffolding with no API surface (Issues 1.1, 1.2, 1.3) may be grouped in one batch, since they carry no risk of contract drift. From Sprint 2 onward, every issue that references an HTTP path, method, table, or schema field must be its own isolated batch, verified before starting the next one.

Follow the `Dependencies` field on each issue for execution order within a sprint; it does not always match numeric order.

## 3. Sprint To Architecture Docs Map

| Sprint | Additional docs to read beyond the issue itself |
|---|---|
| 1 — Foundation | `ERD.md`, `INFRASTRUCTURE.md`, `OBSERVABILITY.md` |
| 2 — Auth | `SECURITY.md`, `API-CONTRACT.yaml` (Auth, Users tags), `ADR-007` |
| 3 — Movie CRUD | `ERD.md` (movies, genres, people, movie_credits), `API-CONTRACT.yaml` (Movies tag) |
| 4 — Upload | `SECURITY.md` (upload validation), `API-CONTRACT.yaml` (Upload tag), `ADR-004` |
| 5 — Worker | `SEQUENCE-DIAGRAMS.md` (Worker Encoding), `ADR-005`, `ADR-006`, `SYSTEM-ARCHITECTURE.md` (Worker modules) |
| 6 — Streaming | `SEQUENCE-DIAGRAMS.md` (Playback, Progress), `SECURITY.md` (HLS access), `API-CONTRACT.yaml` (Stream, Progress tags) |
| 7 — Search | `ERD.md` (search_vector), `API-CONTRACT.yaml` (Search tag), `ADR-003` |
| 8 — User Library | `ERD.md` (watchlist, history, ratings), `API-CONTRACT.yaml` (Watchlist, History, Rating tags) |
| 9 — Admin Ops | `SEQUENCE-DIAGRAMS.md` (Admin Retry), `API-CONTRACT.yaml` (Admin tag) |
| 10 — Polish | `OBSERVABILITY.md`, all ADRs |

## 4. GitHub Issue Workflow

Create GitHub Issues one sprint ahead, not all at once. Copy the title, description, and acceptance criteria directly from `BACKLOG.md` — do not paraphrase. Reference the issue number in the agent prompt so a conventional commit can close it (`closes #N`).

If the agent's environment does not have authenticated GitHub CLI access, issue creation happens through the GitHub web UI by a human, not by the agent. Confirm the agent's actual GitHub access before relying on automatic issue closing.

## 5. Skills

Ask the agent to state which internal skills or capabilities it is using for the batch and why, as the last line of every prompt. This surfaces a wrong or missing skill choice before code is written, rather than after.

## 6. Verification Loop

After every issue:

1. The agent implements exactly the acceptance criteria for that issue.
2. Before reporting done, the agent greps the relevant frozen doc (`API-CONTRACT.yaml`, `ERD.md`, etc.) for every literal identifier it used, and includes the result in its report.
3. A human, or a second AI reviewer, checks the diff against the same frozen doc before the next issue starts.

Do not batch several issues and review them together. Errors compound and become harder to trace the more work accumulates between review points.

## 7. Reusable Prompt Template
```

Continue from the existing repository. Read AGENTS.md first, then docs/INDEX.md, then docs/ai-prompts/SPRINT-EXECUTION-GUIDE.md.
Treat the current workspace as the sole source of truth; do not use older or similarly named project paths.  
Scope: Phase 3, Sprint {N}, Issue {X.Y} only per docs/requirements/BACKLOG.md. Do not start any other issue.  
GitHub Issue: #{number} (include "closes #{number}" in the final commit if fully completed).

Read before editing:

- docs/requirements/BACKLOG.md (Issue {X.Y} section only)
- {architecture docs from Section 3 for this sprint}
- relevant existing files under backend/ or worker/ or frontend/

Rules:

- Implement exactly the acceptance criteria of Issue {X.Y}. Nothing beyond it.
- Any path, table name, column, bucket name, or env var must be copied verbatim from the frozen source doc, never recalled from memory or from root-level archival docs.
- Before reporting done, grep the relevant frozen doc for every literal identifier you used and confirm an exact match. Include this verification in your report.
- Add tests in this same batch per AGENTS.md Testing Rules.
- Do not modify docs/architecture/ files. If you believe one is wrong, stop and report instead of changing it.
- State which skills you used for this batch and why.

Report: files changed, verification grep results, assumptions, blockers, next recommended batch.

```

## 8. Worked Example — Sprint 1, Issue 1.1
```

Continue from the existing repository. Read AGENTS.md first, then docs/INDEX.md, then docs/ai-prompts/SPRINT-EXECUTION-GUIDE.md.
Treat the current workspace as the sole source of truth; do not use older or similarly named project paths.  
Scope: Phase 3, Sprint 1, Issue 1.1 only per docs/requirements/BACKLOG.md ("Initialize Backend Maven Multi-Module Structure"). Do not start Issue 1.2 or later.

Read before editing:

- docs/requirements/BACKLOG.md (Issue 1.1 section only)
- docs/architecture/SYSTEM-ARCHITECTURE.md (Backend Internal Modules list)
- docs/architecture/OBSERVABILITY.md (health check requirements)
- existing backend/Dockerfile

Rules:

- Acceptance criteria only: Maven multi-module builds, Spring Boot starts on 8080, /actuator/health returns 200, SLF4J configured, no business logic yet.
- Module names must match SYSTEM-ARCHITECTURE.md's Backend Internal Modules list exactly (auth, user, catalog, upload, encoding, playback, stream, progress, library, search, common).
- Do not modify docs/architecture/ files.
- State which skills you used for this batch and why.

Report: files changed, assumptions, blockers, next recommended batch.

```
