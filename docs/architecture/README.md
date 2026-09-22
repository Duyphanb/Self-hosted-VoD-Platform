# Architecture Docs

Status: Frozen for MVP implementation

Use this folder for Phase 2 architecture and design.

## Document Map

Choose the contract sections relevant to the task; this list is not a required reading order. Use [the documentation index](../INDEX.md) for task-based routing.

1. `SYSTEM-ARCHITECTURE.md`
2. `ERD.md`
3. `API-CONTRACT.yaml`
4. `SEQUENCE-DIAGRAMS.md`
5. `INFRASTRUCTURE.md`
6. `SECURITY.md`
7. `OBSERVABILITY.md`
8. `adr/`

## Purpose

- define the modular monolith plus worker architecture
- freeze the MVP domain model
- define the baseline API contract
- document key runtime flows
- document infrastructure, security, and observability decisions
- capture trade-offs in ADRs

## Boundary

Do not add implementation code here. Changes to frozen decisions require explicit authorization; a feature request alone is not authorization to redesign its contract. For an authorized architecture change, update the relevant ADR and affected docs in the same bounded task.
