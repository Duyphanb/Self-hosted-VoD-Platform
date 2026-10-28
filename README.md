# Self-hosted Video-on-Demand Platform

This repository contains the foundation for a self-hosted Video-on-Demand platform built as a modular monolith plus a dedicated video-processing worker.

Current status: Phase 3 implementation. Sprint 1 (foundation) and Sprint 2 (authentication: registration, login, refresh-token rotation, logout, role-based access and profile endpoints, with the frontend auth pages) are delivered; see the [Sprint 2 closeout evidence](docs/testing/SPRINT-2-CLOSEOUT.md) and the `v0.1.0` and `v0.2.0` releases. Sprint 3 (movie catalog: admin movie, genre and people management, user-facing list and detail pages) is delivered; see the [Sprint 3 closeout evidence](docs/testing/SPRINT-3-CLOSEOUT.md). Sprint 4 (upload) is next and starts with the object-storage decision in #14. Upload, encoding and playback are not implemented yet.

## Stack

- Java 21, Spring Boot 4.x, Maven multi-module
- React 18, TypeScript, Vite, Tailwind CSS, TanStack Query
- PostgreSQL, Redis, RabbitMQ, PGSTY Silo (MinIO-compatible object storage)
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

Object storage uses digest-pinned PGSTY Silo for both server and initialization, with its admin console disabled. See the [ADR-004 addendum](docs/architecture/adr/ADR-004.md#runtime-addendum) and [backup/restore runbook](docs/deployment/README.md#object-storage-backup-and-restore). The full stack can pull storage on a clean machine; this does not implement the media pipeline.

### 1. Create the local environment file

PowerShell:

```powershell
Copy-Item .env.example .env
```

Bash:

```bash
cp .env.example .env
```

The committed `.env.example` contains placeholders only. Before sharing the stack or using persistent data, replace at least `JWT_SECRET`, `POSTGRES_PASSWORD`, `SPRING_DATASOURCE_PASSWORD`, `RABBITMQ_PASSWORD`, and `MINIO_SECRET_KEY` in the ignored `.env` file.

For the default local Compose setup, `SPRING_DATASOURCE_PASSWORD` must match `POSTGRES_PASSWORD`. If you also change the PostgreSQL database name or username, keep `SPRING_DATASOURCE_URL` and `SPRING_DATASOURCE_USERNAME` aligned with the corresponding `POSTGRES_*` values.

Do not commit `.env`. Credentials stored in existing named volumes do not change automatically when `.env` changes.

### 2. Build and start

```bash
docker compose --env-file .env -f deploy/docker-compose.yml up --build -d
```

The first start builds the backend, worker, and frontend images; applies the Flyway migration; creates the three private Silo buckets; and starts the long-lived services.

### 3. Check service status

```bash
docker compose --env-file .env -f deploy/docker-compose.yml ps -a
```

Expected result for the full stack:

- `postgres`, `redis`, `rabbitmq`, `minio`, `backend`, `worker`, `frontend`, and `nginx` are running and healthy.
- `flyway` and `minio-init` have exited successfully with status code 0.

### 4. Verify the public routes

The commands below assume the default `NGINX_HTTP_PORT=127.0.0.1:80`, which publishes the edge on host port 80 of the loopback interface only: `http://localhost/` works from the same machine, but other devices cannot reach it (see [LAN access for a demo](#lan-access-for-a-demo)). If you configure another host port, keep the loopback address and include the port in each URL; for example, `NGINX_HTTP_PORT=127.0.0.1:8088` serves `http://localhost:8088/`. Also add that origin to `CORS_ALLOWED_ORIGINS` (for example `CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:8088`): the backend does not yet apply forwarded host and port headers, so it treats same-origin browser `POST`, `PUT`, and `DELETE` requests on a non-80 edge port as cross-origin and rejects them with 403.

To use admin features locally, register an account and promote it with the [first administrator procedure](docs/deployment/README.md#first-administrator).

```bash
curl --fail http://localhost/
curl --fail http://localhost/api/v1/health
```

On Windows PowerShell, use `curl.exe` instead of the `curl` alias. The API health response should report `{"status":"UP"}`.

## Local Ports and Routes

Nginx is the only service published to the host by the local Compose file, and by default only on the loopback interface (`127.0.0.1`).

| Host URL or port | Destination | Purpose |
|---|---|---|
| `http://localhost/` | frontend:80 | Frontend single-page app |
| `http://localhost/api/v1/*` | backend:8080 | Public API route |
| `http://localhost/hls/*` | backend:8080 | Authenticated HLS proxy route |
| `${NGINX_HTTP_PORT:-127.0.0.1:80}` | nginx:80 | Configurable host HTTP address and port, loopback only by default |

These service ports are available only inside the `vod_internal` Docker network:

| Service | Internal port |
|---|---:|
| PostgreSQL | 5432 |
| Redis | 6379 |
| RabbitMQ | 5672 |
| Silo S3 API (`minio`) | 9000 |
| Backend | 8080 |
| Worker | 8081 |
| Frontend | 80 |

### LAN access for a demo

The edge stays on loopback by default because the local `JWT_SECRET` in `.env.example` is a public placeholder: anyone who can reach the edge could sign their own access tokens for any user, including administrators. To let other devices on a trusted network reach the stack:

1. In `.env`, replace `JWT_SECRET` with a random value of at least 32 bytes that is not the placeholder, for example the output of `openssl rand -base64 48` (available in Git Bash and WSL). Also replace the other placeholders listed in [step 1](#1-create-the-local-environment-file).
2. In `.env`, publish the edge on the network: `NGINX_HTTP_PORT=80` listens on every interface, and `NGINX_HTTP_PORT=192.168.1.20:80` (your machine's LAN address) listens on that address only. If you choose a host port other than 80, apply the `CORS_ALLOWED_ORIGINS` note from [step 4](#4-verify-the-public-routes) to that origin.
3. Recreate the affected containers. Tokens signed with the previous secret stop working, so sign in again.

   ```bash
   docker compose --env-file .env -f deploy/docker-compose.yml up -d
   ```

4. Open `http://<your-LAN-address>/` from the other device. The host firewall may need to allow inbound connections on that port.

Set `NGINX_HTTP_PORT` back to `127.0.0.1:80` when the demo ends. Production sets `NGINX_HTTP_PORT=80` explicitly; see [Edge Port Binding](docs/deployment/README.md#edge-port-binding).

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

The production Compose overlay is a baseline for the later VPS deployment phase. Object-storage backup/restore and digest rollback are documented; HTTPS provisioning and public deployment remain deferred to Phase 5.
