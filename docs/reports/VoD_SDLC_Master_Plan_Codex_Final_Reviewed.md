# VoD Platform — Solo Dev SDLC Master Plan (Codex Final, Reviewed)

> Archival note: This root-level master plan is pre-freeze planning context. Implementation must follow `AGENTS.md`, `docs/INDEX.md`, `docs/requirements/`, and `docs/architecture/` when names differ. In particular, use `docs/architecture/ERD.md` for schema names and `docs/architecture/INFRASTRUCTURE.md` for bucket names.

> Version: final reviewed merge of the original master plan and the VoD specification document  
> Primary mode: solo development with Codex assistance  
> Goal: a professional, structured, portfolio-grade project that can be implemented with strong engineering discipline

---

## 1. Executive Direction

This document is an archival planning input for the self-hosted Video-on-Demand platform. Frozen implementation decisions now live under `docs/requirements/` and `docs/architecture/`.

The project is designed to demonstrate professional capability across:
- Backend engineering
- Database design
- Asynchronous processing
- Media storage and streaming
- Frontend integration
- Testing discipline
- DevOps and deployment
- Technical documentation

This is not a toy CRUD project. It is a constrained, production-style portfolio system with a clear MVP boundary.

---

## 2. Project Objective

Build a self-hosted Video-on-Demand platform that supports:
- admin upload of video files
- asynchronous transcoding to HLS via FFmpeg
- playback in the browser with resume progress
- movie metadata management
- search
- watchlist / watch history / rating
- JWT authentication and RBAC
- Dockerized local and VPS deployment

The MVP must run end-to-end on local Docker Compose first, then be deployed to Oracle Cloud Free Tier VPS with a public demo link.

---

## 3. Core Success Criteria

The project is considered successful only if all of the following are true:

1. A video can be uploaded by admin and automatically processed into playable HLS.
2. A logged-in user can watch that video in browser without manual intervention.
3. Continue-watching works reliably enough for demo use.
4. Search works for real movie metadata, not mocked content.
5. The full stack runs through Docker Compose locally.
6. The system is deployable to Oracle Cloud Free Tier VPS with HTTPS and a stable public demo.
7. README, Swagger/OpenAPI, Postman collection, architecture docs, and tests all support the implementation.
8. CV claims match what is actually implemented and deployed.

---

## 4. Scope Baseline

### 4.1 Must-have MVP

Merged from the specification and master plan, the MVP includes:
- React frontend
- Register / login / logout
- JWT auth
- RBAC with `ROLE_USER` and `ROLE_ADMIN`
- Movie CRUD
- Admin upload video
- Upload validation
- Raw video storage in MinIO
- RabbitMQ job publishing for encoding
- Worker consumes encoding jobs
- FFmpeg transcoding to HLS
- HLS playback in React player
- PostgreSQL movie metadata management
- PostgreSQL full-text search
- Continue watching
- Watch history
- Watchlist
- Rating
- Redis cache for homepage and progress buffering
- Docker Compose for entire stack
- Swagger/OpenAPI docs
- Global exception handling
- JUnit + Mockito tests
- Postman collection
- VPS deployment on Oracle Cloud Free Tier

### 4.2 Strongly recommended after MVP-local works

These are not day-one MVP, but they should be planned early because they strengthen the final portfolio quality:
- GitHub Actions CI
- Spring Boot Actuator health check
- basic production logs
- retry handling for failed encoding jobs
- admin retry action
- thumbnail generation
- subtitle upload via WebVTT

### 4.3 Optional advanced scope

Do only if the entire MVP is green:
- multi-quality HLS (360p / 720p / 1080p)
- CloudFront + S3 variant
- fuzzy search via `pg_trgm`
- unaccent search
- DLQ + retry queue
- Testcontainers
- Prometheus / Grafana / Loki
- real-time encoding status via WebSocket or SSE
- presigned upload
- resumable upload

### 4.4 Explicit out-of-scope

Not part of this project baseline:
- Kubernetes
- Kafka
- Elasticsearch
- fully managed AWS production stack (ECS, RDS, ALB, etc.)
- live streaming
- DRM
- payments/subscriptions
- recommendation engine with real production ranking
- mobile app
- complex microservices split

---

## 5. Target Architecture

### 5.1 System model

This project is a **multi-container modular system**, not a complex microservices platform.

Services:
- `frontend` — React web client
- `backend` — Spring Boot core API
- `worker` — Spring Boot worker for video processing
- `postgres` — primary relational database
- `redis` — cache and temporary playback progress buffer
- `rabbitmq` — encoding queue
- `minio` — S3-compatible object storage
- `nginx` — reverse proxy and HLS delivery layer

### 5.2 Architecture principle

Each runtime is isolated by responsibility, but the project stays operationally simple enough for a solo developer. That balance is exactly what makes it interview-strong.

### 5.3 Deployment principle

- local development: Docker Compose
- production demo: Docker Compose on Oracle Cloud Free Tier VPS
- optional extension: S3/CloudFront via storage adapter, without changing core business logic

---

## 6. Technology Stack

### 6.1 Frontend

- React
- TypeScript
- Vite
- Tailwind CSS
- React Router
- Axios
- TanStack Query
- HLS.js or Video.js

### 6.2 Backend

- Java 21
- Spring Boot 3.x
- Spring Web
- Spring Data JPA
- Hibernate
- Spring Security
- JWT
- Spring Validation
- Springdoc OpenAPI

### 6.3 Database and storage

- PostgreSQL
- Flyway
- Full-Text Search
- GIN Index
- JSONB
- MinIO

### 6.4 Queue and cache

- Redis
- RabbitMQ

### 6.5 Media processing

- FFmpeg
- FFprobe
- HLS
- Java `ProcessBuilder`

### 6.6 DevOps and testing

- Docker
- Docker Compose
- GitHub Actions
- JUnit 5
- Mockito
- Postman
- Newman (recommended)

---

## 7. Monorepo Structure

```text
/
├── frontend/
├── backend/
├── worker/
├── deploy/
│   ├── docker-compose.yml
│   ├── docker-compose.prod.yml
│   └── nginx/nginx.conf
├── docs/
│   ├── requirements/
│   ├── architecture/
│   │   └── adr/
│   ├── testing/
│   ├── deployment/
│   └── ai-prompts/
├── .github/workflows/
├── .husky/
├── AGENTS.md
├── README.md
├── .env.example
└── package.json
```

This repo structure is mandatory. Avoid ad hoc folder placement.

---

## 8. Codex Working Standard

### 8.1 Core rule

Codex is used as an assistant under explicit repository instructions. It is not allowed to drive architecture by itself.

### 8.2 Required repository instruction file

Use `AGENTS.md` instead of `CLAUDE.md`.

`AGENTS.md` must define:
- stack
- repo layout
- backend rules
- frontend rules
- testing rules
- security rules
- git rules
- forbidden patterns

### 8.3 Session discipline

Every Codex task should follow this workflow:
1. tell Codex to read `AGENTS.md`
2. give one bounded task
3. state files likely to change
4. state constraints
5. review output manually
6. run lint/test
7. update docs if architecture or contract changed

### 8.4 Prompt library

Use reusable prompts in `docs/ai-prompts/` for:
- CRUD generation
- test generation
- React feature generation
- ADR writing
- code review
- refactoring review
- API contract update review

### 8.5 Hard reviewer note

The new Codex version is much better than the Claude-oriented draft, but it becomes truly professional only if `AGENTS.md` is kept synchronized with the actual codebase. If `AGENTS.md` drifts, Codex becomes a source of inconsistency.

---

## 9. Engineering Rules

### 9.1 Backend rules

Mandatory:
- constructor injection only
- DTOs for all request/response payloads
- never expose JPA entities directly via API
- service layer owns business logic
- `@RestControllerAdvice` for centralized exception handling
- Flyway for every schema change
- never modify existing migrations after commit
- log through SLF4J
- public service methods require tests

Forbidden:
- `System.out.println`
- business logic inside controllers
- transactional boundaries in controllers
- hardcoded secrets
- raw SQL strings where JPA / parameterized approach is safer

### 9.2 Frontend rules

Mandatory:
- functional components only
- TanStack Query for server state
- shared Axios instance with JWT interceptor
- loading/error/empty states handled
- feature-based folder structure
- protected routes + role-based route guard

Forbidden:
- direct fetch logic scattered across components
- missing refresh behavior after mutations
- ad hoc auth handling inside random UI components

### 9.3 Git rules

- branch name: `feature/{issue-number}-{short-desc}`
- conventional commits
- self-review before merge
- no direct push to main for major changes

---

## 10. Functional Modules

### 10.1 Frontend modules

#### Web UI Layout
Pages required:
- Home page
- Movie detail page
- Watch page
- Search result page
- Login/Register page
- User profile page
- Admin dashboard page

#### Video Player
MVP:
- HLS playback from `.m3u8`
- loading / buffering / error state
- seek support
- resume playback from saved position

Optional later:
- quality selection
- subtitle support

#### Search UI
Required:
- search bar
- debounce around 300ms
- suggestions dropdown
- search by title, genre, actor, director
- filter by genre and year if implemented in API

#### Authentication UI
Required:
- register
- login
- logout
- protected routes
- role-based route guards

#### User features
MVP:
- browse movies
- view detail
- watch video
- continue watching
- watch history
- watchlist
- rating

Optional later:
- favorite
- comment

#### Admin dashboard UI
MVP:
- upload video
- CRUD movie metadata
- view encoding status
- retry failed video
- list uploaded movies

Optional later:
- usage stats
- storage stats
- trending charts

### 10.2 Backend modules

#### Authentication module
Required:
- register
- login
- logout
- JWT auth
- BCrypt password hashing
- role-based access control

Optional later:
- email verification OTP
- forgot password OTP

#### User module
Required:
- get profile
- update profile
- change password

Optional later:
- admin browse users

#### Movie metadata module
Required entities:
- User
- Role
- Movie
- Genre
- Actor
- Director
- VideoAsset

Required operations:
- create movie
- update movie
- delete movie
- get movie detail
- list movies
- filter by genre / year
- sort by latest / view count if implemented
- pagination

#### Video upload API
Required:
- admin upload video
- validate file type
- validate file size
- store raw file in MinIO
- create video record in PostgreSQL
- publish encoding job to RabbitMQ
- return upload status

MVP upload strategy:
- Spring Boot receives `MultipartFile`
- save temporary local file if needed
- upload raw to MinIO
- create encoding job

Optional later:
- presigned URL
- resumable upload
- frontend upload progress refinement

#### Streaming API
Required:
- return HLS playlist URL
- return playback info
- check access permission
- return subtitle URL if available

Note:
- HLS is the main streaming path
- MP4 fallback is optional, not primary MVP architecture

#### Search API
Required:
- search by title, description, genre, actor, director
- PostgreSQL full-text search
- GIN index
- suggestions API

Optional later:
- `pg_trgm`
- unaccent search
- result ranking refinement

#### Playback progress API
Required:
- frontend sends current time every 5–10 seconds
- backend buffers progress in Redis
- scheduler syncs to PostgreSQL periodically
- resume from previous position
- continue watching list

#### Watchlist / history / rating API
Required:
- add watchlist item
- remove watchlist item
- get watchlist
- save watch history
- rate movie
- get recently watched

#### Common backend components
Required:
- DTO validation
- global exception handler
- standard API response model
- Swagger / OpenAPI docs
- basic logging
- CORS config

Recommended later:
- rate limiting via Bucket4j for login/search/upload
- trace ID via MDC

### 10.3 Worker modules

#### Queue consumer
Required:
- consume `video-encoding-queue`
- receive job with movie/video ID and source path
- mark status as `PROCESSING`
- log processing lifecycle
- keep worker concurrency at 1 for MVP stability

#### FFprobe metadata extraction
Required:
- read duration
- read resolution
- read codec
- read bitrate
- persist useful metadata

#### FFmpeg HLS transcoding
MVP:
- generate `master.m3u8`
- generate `.ts` segments
- upload HLS output to MinIO
- update status to `READY`
- prioritize 720p only for first stable MVP if needed

Optional later:
- multi-quality HLS
- adaptive bitrate refinement
- CRF/preset tuning

#### Thumbnail generation
Strongly recommended after base MVP works:
- extract poster frame
- upload thumbnail
- store URL in movie metadata

#### Subtitle processing
Optional but useful:
- upload `.vtt`
- store in MinIO
- player loads subtitle track

#### Encoding status lifecycle
Minimum statuses:
- `UPLOADED`
- `QUEUED`
- `PROCESSING`
- `READY`
- `FAILED`

Admin must be able to inspect and retry failed processing.

---

## 11. Database Design Baseline

### 11.1 Main tables

Minimum tables from merged source:
- `users`
- `roles`
- `user_roles`
- `movies`
- `genres`
- `movie_genres`
- `actors`
- `movie_actors`
- `directors`
- `movie_directors`
- `video_assets`
- `encoding_jobs`
- `playback_progress`
- `watch_history`
- `watchlist`
- `ratings`
- `refresh_tokens` (optional depending on implementation style)

### 11.2 Search design

Required:
- `tsvector` for movie search
- GIN index
- search fields include title, description, actor, director, genre if modeled that way

Optional later:
- `pg_trgm`
- `unaccent`

### 11.3 Playback progress design

Persist at minimum:
- user ID
- movie ID
- current time
- duration
- updated at

Optional JSONB enrichment:
- device
- volume
- selected quality

### 11.4 Migration discipline

Recommended migration sequence:
- `V1__init_schema.sql`
- `V2__add_auth_tables.sql`
- `V3__add_movie_schema.sql`
- `V4__add_fulltext_search.sql`
- `V5__add_playback_progress.sql`

Professional rule: never edit old migrations after they are committed to shared history.

---

## 12. Storage Design

### 12.1 Buckets

Minimum bucket strategy:
- `raw-videos`
- `hls-videos`
- `thumbnails`
- `subtitles`

### 12.2 Object structure

Example layout:
- `movies/{movieId}/raw/source.mp4`
- `movies/{movieId}/hls/master.m3u8`
- `movies/{movieId}/hls/720p/index.m3u8`
- `movies/{movieId}/hls/720p/segment001.ts`
- `movies/{movieId}/thumbnail/poster.jpg`
- `movies/{movieId}/subtitles/vi.vtt`

### 12.3 Access control

Rules:
- bucket private by default
- upload restricted to admin workflows
- validation on file type and size required
- optional presigned upload only after base flow is stable

---

## 13. Cache and Queue Design

### 13.1 Redis

MVP uses Redis for:
- homepage cache
- movie detail cache if useful
- search suggestion cache if useful
- temporary playback progress buffering

### 13.2 Progress sync strategy

Use scheduler-based periodic flush from Redis to PostgreSQL.

Professional note: this is a smart trade-off for the project. It is simple, explainable, and aligned with demo-level durability needs.

### 13.3 RabbitMQ

Required:
- primary queue for encoding jobs
- producer in backend
- consumer in worker
- failed state reflected in database

Recommended later:
- retry queue
- DLQ
- retry limit

---

## 14. Nginx and HLS Delivery

### 14.1 Reverse proxy

Nginx responsibilities:
- proxy API requests to backend
- serve or proxy frontend build
- proxy or serve HLS assets
- apply basic CORS handling if needed by architecture

### 14.2 HLS delivery requirements

Required:
- correct MIME types
- support `.m3u8`
- support `.ts`
- correct cache-control headers

MIME baseline:
- `.m3u8` → `application/vnd.apple.mpegurl`
- `.ts` → `video/mp2t`

---

## 15. DevOps and Deployment

### 15.1 Environment baseline

Required config areas:
- database config
- JWT secret
- Redis config
- RabbitMQ config
- MinIO config
- upload size config
- optional mail config

All must be externalized through environment variables.

### 15.2 CI/CD direction

Expected flow:
- push code to GitHub
- GitHub Actions runs tests and build steps
- images built for frontend, backend, worker
- images pushed to GHCR or Docker Hub
- CD deploys to Oracle VPS via SSH
- `docker compose pull` + `docker compose up -d`
- named volumes preserved across deployments

### 15.3 Oracle Cloud VPS target

Production demo target:
- Oracle Cloud Free Tier Ampere A1 ARM
- 2 OCPU
- 12 GB RAM
- Ubuntu Linux
- custom domain
- HTTPS via Let's Encrypt + Nginx

### 15.4 Optional AWS extension

Only after stable VPS deployment:
- storage adapter allows MinIO → S3 by config
- CloudFront in front of HLS assets
- S3 lifecycle policy
- AWS Budget Alert

### 15.5 Missing but required operational docs

To be truly professional, add:
- `docs/deployment/DEPLOYMENT.md`
- `docs/deployment/ROLLBACK.md`
- `docs/deployment/BACKUP-RESTORE.md`
- `docs/deployment/RUNBOOK.md`

Without these, the project looks good technically but not operationally mature.

---

## 16. Testing and Documentation

### 16.1 Testing MVP

Minimum testing baseline:
- unit test service layer
- auth service tests
- movie service tests
- upload validation tests
- Postman API test collection

### 16.2 Recommended testing extensions

When stable:
- Newman in CI
- integration tests
- Testcontainers for PostgreSQL / Redis / RabbitMQ

### 16.3 API documentation

Required:
- Swagger UI
- OpenAPI 3.0
- auth API docs
- movie API docs
- upload API docs
- playback API docs
- error response documentation

### 16.4 Monitoring baseline

Minimum:
- Spring Boot Actuator
- `/actuator/health`
- basic application logs
- optional Docker health checks

Advanced later:
- Prometheus
- Grafana
- Loki

---

## 17. Phase Plan

### Phase 0 — Project Harness / AI Infrastructure

Outputs:
- monorepo structure
- `AGENTS.md`
- Husky hooks
- CI skeleton
- reusable Codex prompts
- GitHub board setup

Exit criteria:
- repo structure stable
- hooks working
- CI green
- prompt templates committed

### Phase 1 — Requirements and Planning

Outputs:
- `VISION.md`
- `USERSTORIES.md`
- `NFR.md`
- `RISKREGISTER.md`
- `DOMAINGLOSSARY.md`
- `PROJECTPLAN.md`
- `OUT_OF_SCOPE.md`
- `TRACEABILITY.md`

Exit criteria:
- all MVP features mapped to stories
- measurable NFRs
- risks and mitigation defined

### Phase 2 — Architecture and Design

Outputs:
- `SYSTEM-ARCHITECTURE.md`
- `ERD.md`
- `API-CONTRACT.yaml`
- `SEQUENCE-DIAGRAMS.md`
- `INFRASTRUCTURE.md`
- `SECURITY.md`
- `OBSERVABILITY.md`
- ADR set

Exit criteria:
- architecture validated
- ERD and OpenAPI reviewed
- core runtime flows documented

### Phase 3 — Feature Development

Outputs:
- sprint-based implementation across foundation, auth, movie CRUD, upload, worker, streaming, search, user features, admin, polish

Exit criteria:
- all MVP features implemented
- tests passing
- docs updated

### Phase 4 — Testing and Hardening

Outputs:
- Postman collection
- Newman plan
- manual E2E checklist
- known limitations document

Exit criteria:
- end-to-end flow stable
- known limitations explicitly documented

### Phase 5 — Deployment and Operations

Outputs:
- production compose config
- nginx prod config
- deployment docs
- backup/restore
- rollback
- runbook

Exit criteria:
- healthy VPS deployment
- HTTPS working
- public demo reachable

### Phase 6 — Portfolio Finalization

Outputs:
- polished README
- architecture image
- demo video
- repo profile optimization
- CV bullet alignment

Exit criteria:
- project is recruiter-ready and interview-ready

---

## 18. Sprint Breakdown

| Sprint | Goal | Deliverables |
|---|---|---|
| 1 | Foundation | multi-module setup, Compose stack, health endpoint, Flyway V1, CI |
| 2 | Auth | register, login, refresh, logout, route guards, security config |
| 3 | Movie CRUD | movie/genre entities, admin UI, pagination |
| 4 | Upload | file validation, MinIO upload, queue publish |
| 5 | Worker | FFprobe, FFmpeg HLS, status update, retry handling baseline |
| 6 | Streaming | HLS delivery, player, progress save/resume |
| 7 | Search | FTS, suggestions, search UI |
| 8 | User features | watchlist, history, rating, profile |
| 9 | Admin | upload dashboard, failed-job retry, status visibility |
| 10 | Polish | error handling, docs, Swagger, Postman, production prep |

---

## 19. Artifact Map

| Phase | Files / Assets | Mandatory |
|---|---|---|
| 0 | `AGENTS.md`, hooks, CI, prompt templates | yes |
| 1 | requirements docs | yes |
| 2 | architecture docs + ADRs | yes |
| 3 | implementation code + tests | yes |
| 4 | Postman + QA docs | yes |
| 5 | deploy docs + runbooks | yes |
| 6 | README + demo + portfolio assets | yes |

---

## 20. Strict Reviewer Assessment

This section reviews the Codex version as a tough reviewer.

### 20.1 What is already strong

- The project choice is excellent for a backend/full-stack portfolio.
- The architecture is ambitious enough to stand out, but still reasonable if scope is controlled.
- The move from Claude-specific workflow to Codex-oriented repository instructions is correct.
- The merged plan now has much better structural clarity than the earlier version.
- The inclusion of phase outputs and artifact mapping is a strong professional improvement.

### 20.2 What was weak before this merge

Before this merged final version, the plan had several issues:
- too much dependency on Claude-specific operational wording
- not enough direct merge from the spec into the implementation plan
- insufficient explicit operational maturity
- incomplete traceability between feature scope and deliverables
- some features existed in the spec but were not surfaced clearly in the plan as first-class deliverables

### 20.3 Remaining risks even after this final version

These are not documentation flaws anymore; they are execution risks:
- overscoping frontend polish too early
- spending too long on optional features before stable upload → transcode → play flow
- underestimating FFmpeg and VPS operational issues
- inconsistent schema naming if ERD is not frozen early
- drifting API contracts if Swagger is not updated continuously
- weak test discipline once development speed increases

### 20.4 Professional recommendation

The correct implementation order is:
1. freeze MVP scope
2. freeze ERD + API contract baseline
3. build local end-to-end first
4. stabilize tests and logs
5. deploy early to VPS
6. only then add optional polish and cloud extension

That sequence is what separates an experienced engineering approach from a student project that grows messy.

### 20.5 Final reviewer verdict

This merged Codex final version is now **good enough to serve as the real master plan** for the project.

However, to be “professional engineer level” in execution, three disciplines are still non-negotiable:
- never let optional scope enter before MVP is green
- keep docs synchronized with implementation every sprint
- treat deployment and operational docs as part of the product, not as afterthoughts

If those three rules are followed, the structure is solid enough to start implementation with confidence.

---

## 21. Start Order Recommendation

To begin safely, do the next steps in this exact order:

1. finalize `AGENTS.md`
2. create `VISION.md`, `USERSTORIES.md`, `NFR.md`, `OUT_OF_SCOPE.md`, `TRACEABILITY.md`
3. freeze ERD and main tables
4. define OpenAPI baseline
5. set up repo skeleton and Docker Compose
6. implement Sprint 1 only
7. do not touch advanced features before the upload pipeline works

---

## 22. Completion Standard

This plan should be considered execution-ready only when:
- repo structure exists
- Phase 1 and Phase 2 documents exist physically in `docs/`
- `AGENTS.md` is written
- sprint backlog is created
- MVP scope is frozen
- no advanced features are mixed into Sprint 1 or Sprint 2

At that point, implementation can begin with a strong, professional structure.
