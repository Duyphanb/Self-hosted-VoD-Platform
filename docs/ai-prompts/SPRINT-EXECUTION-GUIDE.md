# Sprint Execution Guide

Use for implementing a selected issue from [BACKLOG.md](../requirements/BACKLOG.md). This is optional task guidance; [AGENTS.md](../../AGENTS.md) owns repository boundaries and completion.

## Scope and dependencies

- A selected issue is the default unit of work. Implement its acceptance criteria and necessary supporting changes; do not start the next issue unless the user includes it in the task.
- Check the issue's dependencies against current implementation and verification evidence. Numeric order and planning status alone do not establish readiness. Preserve the backlog's dependency/approval boundaries; report a missing prerequisite and continue work that does not depend on it.
- Select the relevant contract sections and follow code dependencies using [docs/INDEX.md](../INDEX.md) as needed. Sprint labels do not require reading every architecture document or prohibit inspecting another module.

## Acceptance and verification

- Confirm affected contract details in context: API methods, payloads, status codes, schema constraints, storage names, and access rules as applicable. Search helps locate definitions; matching identifier text alone does not prove correct behavior.
- Implement and verify the issue's acceptance criteria with meaningful tests for changed behavior. For runtime acceptance criteria, run the relevant flow against established local test resources and inspect the result.
- Fix failures caused by the change and rerun affected checks. Review the diff for omissions, contract drift, and unintended changes before reporting completion. Avoid unrelated or repeated checks with no new reason.
- If a frozen decision prevents completion, describe the mismatch and proposed decision before changing the contract. Continue independent authorized work. Ordinary local fixes do not require an interim human or second-agent review gate.
- Separate implemented, verified, and blocked criteria in the handoff. Missing credentials, tools, services, or prerequisite features are verification limits, not passing results. Full pipeline claims require upload -> transcode -> HLS playback evidence; an individual issue can finish against its own acceptance criteria.

## GitHub handoff

Use the actual GitHub issue number when available; backlog labels such as `2.1` are not GitHub issue numbers. When asked to create issues, preserve backlog titles, descriptions, and acceptance criteria and use the one-sprint-ahead planning cadence. Check available authenticated tools rather than assuming a specific CLI or manual UI workflow.

Prepare a scoped diff and verification summary before authorized commit/push or PR publication. Use issue closing keywords only when the issue is fully complete. Do not close an issue or mark the backlog complete based on a first implementation with outstanding required checks.

## Prompt example

```text
Implement backlog Issue {X.Y}, GitHub #{number if known}, in the current checkout.
Use its acceptance criteria and dependencies in docs/requirements/BACKLOG.md.
Keep scope to this issue and preserve the frozen contracts.
Continue through implementation, relevant local verification, diff review, and
fixing failures caused by the changes. Report acceptance evidence and any
remaining blocker. Follow AGENTS.md for authorization boundaries.
```
