# VoD Batch Prompt

Optional template for starting a bounded task. Fill in the outcome and acceptance evidence; omit fields that add no useful context. Repository boundaries and completion are defined in [AGENTS.md](../../AGENTS.md).

```text
Work in the current checkout.

Outcome: {requested result}
Issue or batch: {identifier, if applicable}
Acceptance evidence: {what must work or be verified}
Starting points: {known files or areas, if helpful}
Non-goals or explicit edit boundaries: {if any}

Choose the relevant docs, code, and checks; use docs/INDEX.md if needed to
locate the governing contracts. Follow dependencies needed for this outcome.
Continue through the changes, relevant verification, diff review, and fixes
for failures caused by this work. Respect frozen decisions and the local/remote
authorization boundaries in AGENTS.md. If blocked, complete independent work
and identify the decision or resource needed.

Report the result, changed files, checks and their limits, and remaining blockers.
```

For a backlog issue, the [sprint guide](SPRINT-EXECUTION-GUIDE.md) explains dependency and acceptance checks. A docs-only or skeleton outcome remains limited to that outcome. Starting-point paths do not prevent inspecting a relevant caller, test, or contract.
