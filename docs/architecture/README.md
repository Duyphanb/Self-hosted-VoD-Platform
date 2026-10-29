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

## ADR Index

| ADR | Decision |
|---|---|
| [ADR-001](adr/ADR-001.md) | Modular monolith plus worker |
| [ADR-002](adr/ADR-002.md) | Java 21 / Spring Boot 4.x / Maven |
| [ADR-003](adr/ADR-003.md) | PostgreSQL and Flyway |
| [ADR-004](adr/ADR-004.md) | MinIO-compatible storage / PGSTY Silo |
| [ADR-005](adr/ADR-005.md) | RabbitMQ encoding queue |
| [ADR-006](adr/ADR-006.md) | FFprobe / FFmpeg / HLS |
| [ADR-007](adr/ADR-007.md) | Body-based refresh tokens |
| [ADR-008](adr/ADR-008.md) | Case-insensitive email identity |
| [ADR-009](adr/ADR-009.md) | Catalog visibility, archive retention and slugs |
| [ADR-010](adr/ADR-010.md) | Upload transport, video asset lifecycle, encoding handoff and cleanup |

## Purpose

- define the modular monolith plus worker architecture
- freeze the MVP domain model
- define the baseline API contract
- document key runtime flows
- document infrastructure, security, and observability decisions
- capture trade-offs in ADRs

## Boundary

Do not add implementation code here. Changes to frozen decisions require explicit authorization; a feature request alone is not authorization to redesign its contract. For an authorized architecture change, update the relevant ADR and affected docs in the same bounded task.
