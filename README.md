# Self-hosted Video-on-Demand Platform

This repository contains the foundation for a self-hosted Video-on-Demand platform built as a modular monolith plus a dedicated video-processing worker.

Current status: Phase 3 implementation. The Sprint 1 foundation provides buildable backend, worker, and frontend skeletons, a Flyway baseline schema, and Docker Compose orchestration. Business features are implemented in later bounded sprint issues.

## Stack

- Java 21, Spring Boot 3.x, Maven multi-module
- React 18, TypeScript, Vite, Tailwind CSS, TanStack Query
- PostgreSQL, Redis, RabbitMQ, MinIO
- FFmpeg, FFprobe, HLS, Nginx
- Docker Compose for local development and the Oracle Cloud Free Tier VPS demo baseline

## Repository Layout

```text
frontend/
backend/
worker/
deploy/
docs/
```

See `AGENTS.md` for repository rules and `docs/INDEX.md` for the source-of-truth documentation map.

## Run the Local Stack

### Prerequisites

- Docker Engine with Docker Compose V2
- Enough local resources to build and run the ten Compose services
- On Windows, WSL2 with Docker access enabled

Run all commands from the repository root.

### 1. Create the local environment file

PowerShell:

```powershell
Copy-Item .env.example .env
```

Bash:

```bash
cp .env.example .env
```

The committed `.env.example` contains placeholders only. Before sharing the stack or using persistent data, replace at least `JWT_SECRET`, `POSTGRES_PASSWORD`, `RABBITMQ_PASSWORD`, and `MINIO_SECRET_KEY` in the ignored `.env` file.

Do not commit `.env`. Credentials stored in existing named volumes do not change automatically when `.env` changes.

### 2. Build and start

```bash
docker compose --env-file .env -f deploy/docker-compose.yml up --build -d
```

The first start builds the backend, worker, and frontend images; applies the Flyway migration; creates the three private MinIO buckets; and starts the long-lived services.

### 3. Check service status

```bash
docker compose --env-file .env -f deploy/docker-compose.yml ps -a
```

Expected result:

- `postgres`, `redis`, `rabbitmq`, `minio`, `backend`, `worker`, `frontend`, and `nginx` are running and healthy.
- `flyway` and `minio-init` have exited successfully with status code 0.

### 4. Verify the public routes

```bash
curl --fail http://localhost/
curl --fail http://localhost/api/v1/health
```

On Windows PowerShell, use `curl.exe` instead of the `curl` alias. The API health response should report `{"status":"UP"}`.

## Local Ports and Routes

Nginx is the only service published to the host by the local Compose file.

| Host URL or port | Destination | Purpose |
|---|---|---|
| `http://localhost/` | frontend:80 | Frontend placeholder |
| `http://localhost/api/v1/*` | backend:8080 | Public API route |
| `http://localhost/hls/*` | backend:8080 | Authenticated HLS proxy route |
| `${NGINX_HTTP_PORT:-80}` | nginx:80 | Configurable host HTTP port |

These service ports are available only inside the `vod_internal` Docker network:

| Service | Internal port |
|---|---:|
| PostgreSQL | 5432 |
| Redis | 6379 |
| RabbitMQ | 5672 |
| MinIO S3 API | 9000 |
| MinIO console | 9001 |
| Backend | 8080 |
| Frontend | 80 |

## Logs and Shutdown

Follow all logs:

```bash
docker compose --env-file .env -f deploy/docker-compose.yml logs -f
```

Follow one service:

```bash
docker compose --env-file .env -f deploy/docker-compose.yml logs -f backend
```

Stop the stack while retaining named-volume data:

```bash
docker compose --env-file .env -f deploy/docker-compose.yml down
```

To deliberately delete all local PostgreSQL, RabbitMQ, and MinIO data and start clean:

```bash
docker compose --env-file .env -f deploy/docker-compose.yml down --volumes
```

The last command is destructive and cannot recover data from the deleted Docker volumes.

## Current Scope

The current worker is a runtime skeleton. A healthy local stack does not yet prove the complete upload -> RabbitMQ -> transcode -> HLS playback flow. That flow is implemented and verified in later sprint issues.

The production Compose overlay is a baseline for the later VPS deployment phase. HTTPS certificate provisioning, backups, rollback, and public deployment remain deferred to Phase 5.
