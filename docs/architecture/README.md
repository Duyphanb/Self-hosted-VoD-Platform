# Architecture Docs

Status: Frozen for MVP implementation

Use this folder for Phase 2 architecture and design.

## Read Order

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

Do not add implementation code here. If architecture changes after implementation starts, update the relevant ADR and affected docs in the same bounded task.
