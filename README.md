# Self-hosted Video-on-Demand Platform

This repository contains the foundation for a self-hosted Video-on-Demand platform built as a modular monolith plus a dedicated video-processing worker.

Current status: Phase 3 implementation. Sprint 1 (foundation) and Sprint 2 (authentication: registration, login, refresh-token rotation, logout, role-based access and profile endpoints, with the frontend auth pages) are delivered; see the [Sprint 2 closeout evidence](docs/testing/SPRINT-2-CLOSEOUT.md) and the `v0.1.0` and `v0.2.0` releases. Sprint 3 (movie catalog: admin movie, genre and people management, user-facing list and detail pages) is delivered; see the [Sprint 3 closeout evidence](docs/testing/SPRINT-3-CLOSEOUT.md). #14 is merged; [ADR-010](docs/architecture/adr/ADR-010.md) records the owner-approved Sprint 4 upload/asset/handoff/cleanup design and [ADR index](docs/architecture/README.md#adr-index) maps the decisions. Sprint 4 (upload: admin upload endpoint and UI, raw storage in Silo, a QUEUED encoding job with RabbitMQ handoff, and the guarded Nginx edge) is delivered; see the [Sprint 4 closeout evidence](docs/testing/SPRINT-4-CLOSEOUT.md). Sprint 5 (worker: RabbitMQ consumer, FFprobe and FFmpeg single-quality HLS encoding into the private `vod-hls` bucket, READY/FAILED status, startup recovery and a hardened non-root container) is delivered; see the [Sprint 5 closeout evidence](docs/testing/SPRINT-5-CLOSEOUT.md). Sprint 6 (streaming: playback info, the authenticated backend HLS proxy, the hls.js player and watch page, progress saved directly to PostgreSQL with resume, Continue Watching, and browser sessions with an HttpOnly refresh cookie and bounded server-side revocation) is delivered; see the [Sprint 6 closeout evidence](docs/testing/SPRINT-6-CLOSEOUT.md). A real Chrome smoke verifies upload -> transcode -> HLS playback across an access-token expiry on the Compose stack. The A1/VPS measurements (production encode deadlines, upload path and playback capacity) are tracked in #124, #180 and #233.

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

The committed `.env.example` contains placeholders only. Before sharing the stack or using persistent data, replace at least `JWT_SECRET`, `REDIS_PASSWORD`, every `POSTGRES_*_PASSWORD` and `RABBITMQ_*_PASSWORD`, `MINIO_SECRET_KEY`, and the independent `MINIO_BACKEND_*`/`MINIO_WORKER_*` credentials in the ignored `.env` file, each with its own value. Storage root keys are reserved for bootstrap and explicit operator maintenance; [scoped application policies](docs/architecture/INFRASTRUCTURE.md#storage-identities-and-policies) are provisioned by minio-init.

Each service receives only its own identity (#106): `POSTGRES_USER` and `RABBITMQ_USERNAME` are the bootstrap superuser and broker administrator, `postgres-init` provisions the `POSTGRES_OWNER_*` role for Flyway and the `POSTGRES_BACKEND_*`/`POSTGRES_WORKER_*` roles for the applications, the broker loads the `RABBITMQ_BACKEND_*`/`RABBITMQ_WORKER_*` users, and only the backend receives `REDIS_PASSWORD`. Leave `SPRING_DATASOURCE_URL` unset; the backend and worker derive it from `POSTGRES_HOST`, `POSTGRES_PORT` and `POSTGRES_DB`, as Flyway does. See [Service identities and network](docs/architecture/INFRASTRUCTURE.md#service-identities-and-network).

Do not commit `.env`. Rerunning `minio-init` overwrites and re-enables the configured scoped storage users, including their secrets; changing an access key leaves the old user valid until explicitly removed. PostgreSQL, RabbitMQ and Redis credentials follow the [service identity rotation](docs/deployment/README.md#service-identity-rotation) procedure. Follow the [storage identity rotation and revocation runbook](docs/deployment/README.md#storage-identity-rotation-and-revocation) for existing volumes.

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
- `postgres-init`, `flyway` and `minio-init` have exited successfully with status code 0.

### 4. Verify the public routes

The commands below assume the default `NGINX_HTTP_PORT=127.0.0.1:80`, which publishes the edge on host port 80 of the loopback interface only: `http://localhost/` works from the same machine, but other devices cannot reach it (see [LAN access for a demo](#lan-access-for-a-demo)). If you configure another host port, keep the loopback address and include the port in each URL; for example, `NGINX_HTTP_PORT=127.0.0.1:8088` serves `http://localhost:8088/`. Nginx forwards the browser-visible host, port and scheme. Auth also checks an explicit Origin allowlist (ADR-012): add every browser-facing origin, including a non-default port, to `CORS_ALLOWED_ORIGINS` (default `http://localhost,http://localhost:5173`). For the example use `http://localhost:8088,http://localhost:5173`. This auth check is separate from CORS; no CORS credentials are enabled.

To use admin features locally, register an account and promote it with the [first administrator procedure](docs/deployment/README.md#first-administrator).

```bash
curl --fail http://localhost/
curl --fail http://localhost/api/v1/health
curl --fail http://localhost/actuator/health
```

On Windows PowerShell, use `curl.exe` instead of the `curl` alias. The API health response should report `{"status":"UP"}`. Use aggregate `/actuator/health` for deployment readiness, including storage and the other integrated dependencies; the availability-only `/actuator/health/readiness` group is not that readiness signal. Health responses expose no dependency details.

## Local Ports and Routes

Nginx is the only service published to the host by the local Compose file, and by default only on the loopback interface (`127.0.0.1`).

| Host URL or port | Destination | Purpose |
|---|---|---|
| `http://localhost/` | frontend:80 | Frontend single-page app |
| `http://localhost/api/v1/*` | backend:8080 | Public API route |
| `http://localhost/actuator/health` | backend:8080 | Aggregate deployment readiness |
| `http://localhost/hls/*` | backend:8080 | Authenticated HLS proxy route |
| `${NGINX_HTTP_PORT:-127.0.0.1:80}` | nginx:80 | Configurable host HTTP address and port, loopback only by default |

These service ports are not published to the host. Nginx and the frontend share `vod_edge`; only Nginx and the backend share `vod_proxy`. The backend also joins the `internal: true` `vod_internal` network with the data stores and worker. The frontend cannot reach the backend directly. The proxy link defaults to `10.94.250.0/29`, with Nginx `.2` and backend `.3`; override `VOD_PROXY_SUBNET`, `VOD_PROXY_NGINX_IP` and `VOD_PROXY_BACKEND_IP` together if it overlaps another network (see [proxy network configuration](docs/deployment/README.md#proxy-network-configuration)):

| Service | Internal port |
|---|---:|
| PostgreSQL | 5432 |
| Redis | 6379 |
| RabbitMQ | 5672 |
| Silo S3 API (`minio`) | 9000 |
| Backend | 8080 |
| Worker | 8081 |
| Frontend | 80 |

### Browser authentication

Login and refresh return the access token and profile in JSON and set an HttpOnly, Secure, SameSite=Strict `vod_refresh` cookie. Refresh and logout send no body and require an allowed `Origin`. The SPA keeps access tokens only in memory and restores them once through the cookie after reload. It removes the old token storage on startup. An unsuccessful sign-out remains pending with a retry action, including after reload. Blocked storage and browsers without Web Locks display a single-tab limitation notice.

If your local `.env` predates Issue 6.9, explicitly update it to `CORS_ALLOWED_ORIGINS=http://localhost,http://localhost:5173`, adding any non-default edge origin. Keeping only `http://localhost:5173` makes login from the Compose edge return 403 `ORIGIN_NOT_ALLOWED`; see the [upgrade steps](docs/deployment/README.md#upgrade-from-before-issue-69). Production lists its public HTTPS origin.

### Frontend development

Start the [local Compose stack](#run-the-local-stack) with its Nginx edge. From `frontend/`, use Node 22.22.2 or a
later Node 22 release (`nvm use` with a compatible version manager), then run:

```bash
npm ci
npm run dev
```

Open `http://localhost:5173` for hot reload. Vite proxies `/api/v1` and `/hls` to the Compose edge at
`http://localhost` by default. Leave `VITE_API_BASE_URL` unset so browser requests use relative URLs.
`VOD_DEV_PROXY_TARGET` is a server-only environment variable set in the shell that starts Vite.

For an edge on port 8088, set these values in the Compose `.env` and recreate the affected services:

```dotenv
NGINX_HTTP_PORT=127.0.0.1:8088
CORS_ALLOWED_ORIGINS=http://localhost:8088,http://localhost:5173
```

Then run `VOD_DEV_PROXY_TARGET=http://localhost:8088 npm run dev` in Bash, or use PowerShell:

```powershell
$env:VOD_DEV_PROXY_TARGET = 'http://localhost:8088'
npm run dev
```

Vite forwards the browser's `Origin` through Nginx. ADR-012 requires an exact allowlist entry for
`http://localhost:5173` (included by default). Add any other browser-facing origin you use, including
its scheme and port. For example, browsing on `http://127.0.0.1:5173` requires its own entry.

To verify the setup, sign in, reload and sign out at `http://localhost:5173`. In browser DevTools,
check that login and the bootstrap refresh return 200, logout returns 204 and clears `vod_refresh`,
and reloading after logout leaves you signed out. Login and refresh set a host-only cookie with
`Path=/api/v1/auth`, `HttpOnly`, `Secure`, and `SameSite=Strict` under the default configuration.
Open an authenticated page or play a ready title and check that protected API and HLS requests use
the same Vite origin and carry the access bearer.

Use one UI origin per browser profile. The Compose UI (`http://localhost`) and the Vite UI share the `vod_refresh`
cookie, because cookies ignore ports, but not their refresh locks. Refreshing in both at once can trip ADR-012's
refresh-replay protection and sign you out everywhere.

Dev and `npm run preview` bind to loopback by default. Preview serves the built files on port 4173.
`npm run dev:lan` and `npm run preview:lan` explicitly bind to all interfaces. `dev:lan` makes the API
and HLS routes reachable from the network through the proxy, even while Nginx stays on loopback. Before
you run it, replace the published `JWT_SECRET` and the other placeholders as in step 1 of
[LAN access for a demo](#lan-access-for-a-demo), recreate the backend, and use it only on a trusted
network. Signing in from other devices still needs HTTPS, as that section explains.

From the repository root, `python deploy/tests/frontend-build-context-smoke.py` checks that a
synthetic `.env` marker is absent from both the Docker context and the built frontend bundle.

### LAN access for a demo

**Plain HTTP over a LAN address cannot sign in.** The refresh cookie requires `Secure`; disabling it is allowed only when every allowed origin is loopback. The steps below expose public routes only. Use HTTPS (#136) and allow its public origin for an authenticated demo on other devices.

The edge stays on loopback by default because the local `JWT_SECRET` in `.env.example` is a public placeholder: anyone who can reach the edge could sign their own access tokens for any user, including administrators. To let other devices on a trusted network reach the stack:

1. In `.env`, replace `JWT_SECRET` with a random value of at least 32 bytes that is not the placeholder, for example the output of `openssl rand -base64 48` (available in Git Bash and WSL). Also replace the other placeholders listed in [step 1](#1-create-the-local-environment-file).
2. In `.env`, publish the edge on the network: `NGINX_HTTP_PORT=80` listens on every interface, and `NGINX_HTTP_PORT=192.168.1.20:80` (your machine's LAN address) listens on that address only. If you choose a host port other than 80, include it in the browser URL as in [step 4](#4-verify-the-public-routes).
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

The complete upload -> RabbitMQ -> transcode -> HLS playback flow is implemented. The required CI stack smoke encodes an upload to READY HLS and fetches it through Nginx, and the Chrome playback smoke (`workflow_dispatch`, see [docs/testing/README.md](docs/testing/README.md)) plays it in Google Chrome. A healthy stack alone does not prove that flow; those smokes do.

The production Compose overlay is a baseline for the later VPS deployment phase. Object-storage backup/restore and digest rollback are documented; HTTPS provisioning and public deployment remain deferred to Phase 5.
