# Self-hosted Video-on-Demand Platform

This repository contains the foundation for a self-hosted Video-on-Demand platform built as a modular monolith plus a dedicated video-processing worker.

Current phase: Phase 0/1 foundation. The repository is intentionally documentation-first and skeleton-first. Application logic will be added in later bounded batches.

## Planned Stack

- Java 21, Spring Boot 3.x, Maven multi-module
- React 18, TypeScript, Vite, Tailwind CSS, TanStack Query
- PostgreSQL, Redis, RabbitMQ, MinIO
- FFmpeg, FFprobe, HLS, Nginx
- Docker Compose for local and Oracle Cloud Free Tier VPS deployment

## Repository Layout

```text
frontend/
backend/
worker/
deploy/
docs/
```

See `AGENTS.md` for repository rules and Codex working instructions.
