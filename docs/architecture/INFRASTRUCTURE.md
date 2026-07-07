# Infrastructure Architecture

Status: Frozen for MVP implementation

## Deployment Model

The MVP uses Docker Compose for both local development and the Oracle Cloud Free Tier VPS demo. Kubernetes is out of scope.

Nginx is the only public edge in both local Compose and VPS runtime.

## Runtime Components

| Component | Runtime | Purpose | Public? |
|---|---|---|---|
| `nginx` | Nginx | Frontend static serving, API proxy, HLS proxy, HTTPS on VPS | Yes |
| `frontend` | React build artifact | Browser UI built by Vite and served by Nginx | No direct container exposure |
| `backend` | Java 21 Spring Boot | Core API, auth, upload, HLS proxy, progress, admin APIs | Internal behind Nginx |
| `worker` | Java 21 Spring Boot with FFmpeg/FFprobe in `PATH` | Video processing | Internal only |
| `postgres` | PostgreSQL | Source-of-truth database | Internal only |
| `redis` | Redis | Cache and playback-progress buffer | Internal only |
| `rabbitmq` | RabbitMQ | Encoding job queue | Internal only |
| `minio` | MinIO | Private object storage | Internal only |

## Public Routing

Nginx routes public browser traffic:

| Public Path | Upstream |
|---|---|
| `/` | frontend static build |
| `/api/v1/*` | backend |
| `/hls/*` | backend HLS proxy |
| `/actuator/health` | backend health endpoint, exposed only if deployment needs it |

`/hls/*` through Nginx to the backend HLS proxy is the only MVP browser playback path; MinIO is never public.

## Local Network

Compose service names are stable DNS names:

- `nginx`
- `backend`
- `worker`
- `postgres`
- `redis`
- `rabbitmq`
- `minio`

Application configuration must use service names instead of hardcoded IP addresses.

## Port Baseline

| Service | Internal Port | Public Local Port | VPS Exposure |
|---|---:|---|---|
| Nginx HTTP | 80 | `localhost:80` | 80 |
| Nginx HTTPS | 443 | not required locally | 443 |
| Backend | 8080 | none | none |
| PostgreSQL | 5432 | none | none |
| Redis | 6379 | none | none |
| RabbitMQ | 5672 | none | none |
| RabbitMQ management | 15672 | none by default | none |
| MinIO API | 9000 | none | none |
| MinIO console | 9001 | none by default | none |

Development-only direct port exposure may be added in a local override file, not in the MVP base Compose file.

## Persistent Volumes

Required named volumes:

- `postgres_data`
- `rabbitmq_data`
- `minio_data`

Redis persistence is not required for MVP because Redis is a cache/progress buffer. Durable playback progress belongs in PostgreSQL.

## MinIO Buckets

| Bucket | Purpose | Access |
|---|---|---|
| `vod-raw` | Original uploaded files | Private |
| `vod-hls` | HLS playlists and `.ts` segments | Private, read by backend HLS proxy |
| `vod-thumbnails` | Future poster/thumbnail objects | Private, deferred until thumbnail feature |

## Environment Configuration

All runtime configuration comes from environment variables. `.env.example` is the safe template and must not contain real secrets.

Required configuration groups:

- `APP_*` and public URL
- `SERVER_PORT`
- `SPRING_*`
- `JWT_*`
- `POSTGRES_*`
- `REDIS_*`
- `RABBITMQ_*`
- `MINIO_*`
- upload size and MIME allowlist
- `FFMPEG_PATH`, `FFPROBE_PATH`, worker temp path, and worker concurrency
- `NGINX_HTTP_PORT`, `NGINX_HTTPS_PORT`, `PUBLIC_DOMAIN`, `LETSENCRYPT_EMAIL`

Frontend public runtime values should point at Nginx paths:

- API base: `/api/v1`
- HLS base: `/hls`

## Nginx Responsibilities

Nginx must:

- serve the frontend build
- proxy `/api/v1/*` to backend
- proxy `/hls/*` to backend HLS proxy
- forward `Authorization` and `X-Request-ID` headers to backend
- terminate HTTPS on VPS
- apply basic security headers
- enforce upload body size at least as strictly as backend configuration
- return correct HLS MIME types when applicable

HLS MIME baseline:

- `.m3u8`: `application/vnd.apple.mpegurl`
- `.ts`: `video/mp2t`

## VPS Deployment Baseline

Target:

- Oracle Cloud Free Tier Ampere A1
- 2 OCPU
- 12 GB RAM
- Ubuntu Linux
- Docker and Docker Compose
- Nginx container in the Compose stack
- HTTPS through Let's Encrypt

Deployment must preserve named volumes across releases.

## CI/CD Direction

Future CI must:

- run backend tests
- run worker tests
- run frontend lint/build/tests
- validate Docker builds for frontend, backend, and worker
- avoid publishing images unless tests pass

Future CD must:

- connect to VPS by SSH
- pull images
- run `docker compose up -d`
- preserve volumes
- run smoke checks

## Deferred Infrastructure

Deferred until MVP is stable:

- Kubernetes
- managed AWS production stack
- S3/CloudFront migration
- Prometheus/Grafana/Loki stack
- autoscaling
- multi-node deployment
