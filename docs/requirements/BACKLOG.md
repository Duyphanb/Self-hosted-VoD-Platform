# Sprint Backlog

Status: Planning baseline for MVP implementation

## Purpose

This document provides the detailed sprint backlog for implementing the self-hosted VoD platform MVP. Each sprint contains issues with titles, descriptions, acceptance criteria, dependencies, and definition of done.

## How To Use This Backlog

- Sprint order follows the dependency chain and risk-mitigation strategy from PROJECTPLAN.md.
- Issue titles follow GitHub Issue format; descriptions are starter prompts for implementation.
- Each sprint maps to user stories and NFRs from the requirements folder.
- Do not skip sprints or implement features out of order without architecture approval.
- Update sprint status as work progresses; keep this doc synchronized with actual GitHub Issues.

## Sprint Dependency Flow

```text
Sprint 1 (Foundation)
  ↓
Sprint 2 (Auth)
  ↓
Sprint 3 (Catalog)
  ↓
Sprint 4 (Upload)
  ↓
Sprint 5 (Worker)
  ↓
Sprint 6 (Streaming)
  ↓
Sprint 7 (Content Model and Search)
  ↓
Sprint 8 (Library, Player Core and Viewer UI)
  ↓
Sprint 9 (Admin Operations and Media)
  ↓
Metadata and Upload + Sprint 10 (Polish) [may overlap]
  ↓
Pre-production gate (target-VM evidence and switches; local work never waits for it)
```

## Sprint 1 - Foundation

**Goal:** Establish the runtime baseline with health checks, database migrations, and Docker Compose orchestration.

**Related Stories:** US-017  
**Related NFRs:** NFR-001, NFR-002, NFR-014


### Issue 1.1: Initialize Backend Maven Multi-Module Structure

**Description:**  
Create the backend as a Maven multi-module project with Spring Boot 4.x, Java 21. Include parent POM, common module, and placeholder modules for auth, catalog, upload, encoding, playback, stream, progress, library, search. Add Spring Boot Actuator health endpoint.

**Acceptance Criteria:**
- Maven multi-module builds successfully
- Spring Boot application starts on port 8080
- `/actuator/health` returns 200 OK
- SLF4J logging is configured
- No business logic implemented yet

**Dependencies:** None

---

### Issue 1.2: Initialize Worker Spring Boot Structure

**Description:**  
Create the worker as a standalone Spring Boot 4.x application with Java 21. Include placeholder modules for queue, probe, transcode, storage, status, cleanup. Worker should start cleanly but not consume queues yet.

**Acceptance Criteria:**
- Worker Spring Boot application builds successfully
- Worker starts on a different port or as a non-web application
- Health endpoint or startup log confirms successful initialization
- No queue consumption logic yet

**Dependencies:** None

---

### Issue 1.3: Initialize Frontend React + TypeScript + Vite Scaffold

**Description:**  
Create the frontend application using Vite, React 18, TypeScript, and Tailwind CSS. Include routing placeholder, basic layout component, and build configuration. No auth or API integration yet.

**Acceptance Criteria:**
- `npm install` and `npm run build` succeed
- Dev server runs on port 5173 or configured port
- Browser displays placeholder homepage
- TypeScript strict mode is enabled
- Tailwind CSS is configured

**Dependencies:** None

---

### Issue 1.4: Create Flyway V1 Schema Migration

**Description:**  
Create the baseline Flyway migration `V1__init_schema.sql` following ERD.md. Include users, roles, user_roles, refresh_tokens, movies, genres, movie_genres, people, movie_credits, video_assets, encoding_jobs, playback_progress, watchlist_items, watch_history, ratings tables with indexes and constraints.

**Acceptance Criteria:**
- Flyway migration runs successfully against PostgreSQL
- All tables, indexes, and foreign keys match ERD.md
- Check constraints enforce status values
- No data seeded yet (data migrations come later)

**Dependencies:** Issue 1.1

---

### Issue 1.5: Docker Compose Local Stack

**Description:**  
Create `docker-compose.yml` for local development with PostgreSQL, Redis, RabbitMQ, MinIO, backend, worker, frontend, Nginx. Use service names from INFRASTRUCTURE.md. Add health checks where applicable. Backend and worker should wait for dependent services.

**Acceptance Criteria:**
- `docker compose up` starts all services
- PostgreSQL, Redis, RabbitMQ, MinIO are healthy
- Backend health endpoint returns 200
- Frontend is accessible through Nginx on port 80
- MinIO buckets (`vod-raw`, `vod-hls`, `vod-thumbnails`) are created via init container
- `.env.example` documents all required environment variables

**Dependencies:** Issues 1.1, 1.2, 1.3, 1.4

---

### Issue 1.6: Docker Compose Production Baseline

**Description:**  
Create `docker-compose.prod.yml` with resource limits, restart policies, named volumes, and production-oriented settings for the Oracle VPS target (2 OCPU / 12 GB RAM). Keep service structure aligned with local Compose.

**Acceptance Criteria:**
- Production Compose defines resource limits per service
- Named volumes are used for PostgreSQL, Redis, MinIO data
- Restart policy is `unless-stopped` or `always`
- Nginx is configured for HTTPS placeholder (Let's Encrypt setup deferred to Phase 5)
- File aligns with NFR-002 resource constraints

**Dependencies:** Issue 1.5

---

### Sprint 1 Definition of Done

- [x] Backend, worker, and frontend build and start successfully
- [x] Flyway migration creates all ERD tables in PostgreSQL
- [x] Docker Compose local stack starts all services with health checks passing
- [x] Nginx routes frontend, backend health endpoint, and placeholder API paths
- [x] `.env.example` is complete with placeholders
- [x] Production Compose includes resource limits and named volumes
- [x] README documents how to run the local stack
- [x] No secrets are committed

---

## Sprint 2 - Auth

**Goal:** Implement user registration, login, logout, JWT-based authentication, refresh tokens, and RBAC.

**Related Stories:** US-001, US-002, US-003, US-004, US-019  
**Related NFRs:** NFR-007, NFR-008, NFR-015

### Issue 2.1: Backend Auth Service - Registration

**Description:**  
Implement user registration endpoint `POST /api/v1/auth/register` with request fields `email`, `password`, and `displayName`. Map `displayName` to `users.display_name`. Hash passwords with BCrypt, create the user with `ACTIVE` status, and assign `ROLE_USER` by default. Return `201 Created` with the existing `UserProfile` DTO. Registration does not issue access or refresh tokens; token issuance remains scoped to Issue 2.2.

**Acceptance Criteria:**
- Registration validates email format, password strength, and display name length
- Duplicate email returns 409 Conflict
- Password is stored as BCrypt hash
- New user has `ACTIVE` status
- New user has `ROLE_USER` in `user_roles` table
- Successful registration returns 201 Created with the existing `UserProfile` DTO
- Response DTO excludes password hash, access token, and refresh token
- Unit tests cover validation and service logic

**Dependencies:** Sprint 1 complete

---

### Issue 2.2: Backend Auth Service - Login and JWT

**Description:**  
Implement login endpoint `POST /api/v1/auth/login` returning access token (JWT) and refresh token (opaque, hashed in DB). JWT includes user ID, email, roles. Expiration configured via environment. Invalid credentials return generic 401.

**Acceptance Criteria:**
- Login validates credentials against BCrypt hash
- Access token is signed JWT with configurable expiration
- Refresh token is random opaque value stored hashed in `refresh_tokens` table
- Login response includes both tokens in JSON body
- Invalid credentials return 401 without revealing which field was wrong
- JWT secret comes from environment variable
- Unit and integration tests cover happy path and failure cases

**Dependencies:** Issue 2.1

---

### Issue 2.3: Backend Auth Service - Refresh Token

**Description:**  
Implement refresh endpoint `POST /api/v1/auth/refresh` that accepts a refresh token and returns a new access token and new refresh token. Old refresh token is revoked.

**Acceptance Criteria:**
- Endpoint validates refresh token against hashed storage
- Expired or revoked refresh tokens return 401
- New access token and refresh token are generated
- Old refresh token is marked revoked
- Unit tests cover token rotation and expiration

**Dependencies:** Issue 2.2

---

### Issue 2.4: Backend Auth Service - Logout

**Description:**  
Implement logout endpoint `POST /api/v1/auth/logout` that revokes the submitted refresh token if present. Frontend clears local auth state.

**Acceptance Criteria:**
- Endpoint marks refresh token as revoked if provided
- Endpoint returns 204 No Content regardless of token presence (idempotent)
- Unit tests verify revocation behavior

**Dependencies:** Issue 2.3

---

### Issue 2.5: Backend Security Filter - JWT Authentication

**Description:**  
Create Spring Security filter chain that validates JWT bearer tokens on protected endpoints. Populate SecurityContext with authenticated user and roles. Public endpoints: register, login, refresh.

**Acceptance Criteria:**
- Protected endpoints require valid JWT
- Missing or invalid JWT returns 401
- SecurityContext includes user ID, email, and roles
- Public endpoints bypass JWT validation
- Integration tests cover authenticated and anonymous requests

**Dependencies:** Issue 2.2

---

### Issue 2.6: Backend RBAC - Admin Authorization

**Description:**  
Add role-based access control annotations/configuration for admin endpoints. Admin endpoints require `ROLE_ADMIN`. Regular users receive 403 Forbidden.

**Acceptance Criteria:**
- Admin endpoints are annotated with `@PreAuthorize("hasRole('ADMIN')")`
- User with `ROLE_USER` receives 403 for admin endpoints
- User with `ROLE_ADMIN` can access admin endpoints
- Unit tests cover role enforcement

**Dependencies:** Issue 2.5

---

### Issue 2.7: Backend User Profile Endpoints

**Description:**  
Implement user profile endpoints per API-CONTRACT.yaml (Users tag): `GET /api/v1/users/me` to return current user profile and `PUT /api/v1/users/me` to update display name. Users can only update their own profile.

**Acceptance Criteria:**
- GET returns user ID, email, display name, roles
- PUT accepts display name only
- PUT validates display name length
- Users cannot update email, password, or roles through this endpoint
- Response uses DTO
- Unit and integration tests cover ownership enforcement

**Dependencies:** Issue 2.5

---

### Issue 2.8: Frontend Auth State Management

**Description:**  
Create auth context/store for managing access token, refresh token, and current user state. Store tokens securely (localStorage or httpOnly cookie strategy defined). Implement register, login, logout flows.

**Acceptance Criteria:**
- Auth context provides login, register, logout, and token refresh methods
- Tokens are stored and retrieved consistently
- Logout clears local auth state
- Failed auth requests clear state and redirect to login
- Loading and error states are handled

**Dependencies:** Issues 2.1, 2.2, 2.3, 2.4

---

### Issue 2.9: Frontend Auth UI - Login and Register

**Description:**  
Create login and register pages with form validation. Display loading, success, and error states. Redirect to home or intended route after successful login.

**Acceptance Criteria:**
- Login form accepts email and password
- Register form accepts email, password, display name
- Client-side validation matches backend rules
- Loading spinner during API call
- Error messages display validation or server errors
- Successful login redirects to home or protected route

**Dependencies:** Issue 2.8

---

### Issue 2.10: Frontend Protected Route Guards

**Description:**  
Implement route guards that redirect unauthenticated users to login. Add admin route guard that checks for `ROLE_ADMIN`.

**Acceptance Criteria:**
- Protected routes redirect to login if not authenticated
- Admin routes redirect to home or forbidden page if user lacks `ROLE_ADMIN`
- Route guards check auth state before rendering protected components
- Manual test confirms redirect behavior

**Dependencies:** Issue 2.8

---

### Sprint 2 Definition of Done

- [x] User can register, login, logout through API and UI
- [x] JWT authentication works on protected backend endpoints
- [x] RBAC enforces admin-only endpoints
- [x] Frontend auth state persists and refreshes correctly
- [x] Protected routes redirect unauthenticated users
- [x] Admin routes are hidden/blocked for regular users
- [x] User profile endpoints work with ownership checks
- [x] Unit and integration tests cover auth service, security filter, and RBAC
- [x] Postman collection includes auth endpoints
- [x] No passwords or secrets logged or exposed in responses

Verified against merged implementation `b6ad022507108079d182b2d9941cdc1c0e38625e` on 2026-09-18; see [Sprint 2 closeout evidence](../testing/SPRINT-2-CLOSEOUT.md) for the acceptance mapping, CI/runtime/browser results, and limits. RBAC is verified through real Spring method security with a test-only admin operation; later production admin endpoints still require their own enforcement tests. Auth token responses follow the frozen API contract.

---

## Sprint 3 - Movie CRUD

**Goal:** Implement movie catalog domain model, admin movie metadata CRUD, genre/people management, and user-facing browse and detail endpoints.

**Related Stories:** US-005, US-006, US-007  
**Related NFRs:** NFR-010, NFR-015, NFR-016

### Issue 3.1: Backend Movie Entity and Repository

**GitHub:** [#84](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/84), delivered by [PR #148](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/148)

**Description:**  
Create JPA entities for movies, genres, people, movie_genres, movie_credits. Implement repositories with pagination and basic queries. Do not expose entities directly; use DTOs.

**Acceptance Criteria:**
- JPA entities match ERD schema
- Repositories support pagination
- Constructor injection used
- No entities exposed in API responses
- Unit tests cover repository methods

**Dependencies:** Sprint 2 complete

---

### Issue 3.2: Backend Movie Service - CRUD Operations

**GitHub:** [#85](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/85), delivered by [PR #149](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/149)

**Description:**  
Implement movie service with create, update, read, list (paginated), and archive operations. Validate required fields. Leave `search_vector` null; Issue 7.2 rebuilds and backfills it. (Superseded 2026-11-26: Issue 7.4 drops the never-written column and builds the search tables instead.)

**Acceptance Criteria:**
- Create movie accepts title, slug, description, release_year, maturity_rating, status
- Slug is validated, never generated by the backend; a duplicate slug returns 409 `MOVIE_SLUG_CONFLICT`
- Update movie validates required fields and fully replaces genres and credits
- Archive sets status to `ARCHIVED` (soft delete) and is idempotent
- Service returns DTOs only
- Unit tests cover validation and business logic

**Dependencies:** Issue 3.1

---

### Issue 3.3: Backend Genre and People Management

**GitHub:** [#86](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/86), delivered by [PR #150](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/150)

**Description:**  
Implement genre and people endpoints for admin (`/api/v1/admin/genres`, `/api/v1/admin/people`; no delete). Support adding genres and people to movies through movie_genres and movie_credits junction tables, using the matching rules in ADR-009.

**Acceptance Criteria:**
- Admin can create, list, update genres
- Admin can create, list, update people
- Admin can assign genres and credits (actor, director) to movies
- Genres match by slug; credits use `personId` or an unambiguous name
- Duplicate prevention on junction tables
- Service and repository tests cover relationships

**Dependencies:** Issue 3.2

---

### Issue 3.4: Backend Movie List and Detail APIs

**GitHub:** [#87](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/87), delivered by [PR #152](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/152)

**Description:**  
Implement `GET /api/v1/movies` (paginated, authenticated users, `PUBLISHED` only for every role), `GET /api/v1/admin/movies` (any status, `ROLE_ADMIN`), and `GET /api/v1/movies/{id}` (detail with genres, credits, rating summary, video status). Apply the movie visibility rule in `SECURITY.md`.

**Acceptance Criteria:**
- List endpoint supports pagination (page, size)
- List returns published movies for regular users
- Detail endpoint includes movie metadata, genres, people, aggregate rating, video availability
- Missing movie ID returns 404; a non-admin requesting a non-published movie gets the same 404
- Admin can see all statuses through `GET /api/v1/admin/movies` and movie detail; users see published only
- Integration tests cover pagination and visibility

**Dependencies:** Issue 3.2

---

### Issue 3.5: Backend Admin Movie CRUD Endpoints

**GitHub:** [#88](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/88), delivered by [PR #155](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/155)

**Description:**  
Implement admin-only movie CRUD endpoints per API-CONTRACT.yaml (Movies + Admin tags): `POST /api/v1/movies` (create), `PUT /api/v1/movies/{movieId}` (update), `DELETE /api/v1/movies/{movieId}` (archive). Require `ROLE_ADMIN`.

**Acceptance Criteria:**
- POST /api/v1/movies creates movie
- PUT /api/v1/movies/{movieId} updates movie
- DELETE /api/v1/movies/{movieId} archives movie (soft delete, sets status ARCHIVED)
- All endpoints require `ROLE_ADMIN`; `ROLE_USER` gets 403 even with an invalid body
- Validation errors return 400 with details; slug conflicts return 409
- Integration tests cover admin authorization

**Dependencies:** Issue 3.2, Issue 2.6

---

### Issue 3.6: Frontend Movie List Page

**GitHub:** [#89](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/89), delivered by [PR #157](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/157)

**Description:**  
Create movie list page with pagination. Display movie cards with title, poster placeholder, release year, genres. Handle loading, empty, and error states.

**Acceptance Criteria:**
- List page fetches movies using TanStack Query
- Pagination controls (next, previous, page number)
- Loading spinner during fetch
- Empty state message when no movies
- Error message on fetch failure
- Movie cards link to detail page

**Dependencies:** Issue 3.4

---

### Issue 3.7: Frontend Movie Detail Page

**GitHub:** [#90](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/90), delivered by [PR #158](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/158)

**Description:**  
Create movie detail page showing title, description, release year, genres, cast, director, rating, video availability status. Display "Watch" button if video is ready.

**Acceptance Criteria:**
- Detail page fetches movie by ID
- Shows all metadata from API
- Video status displayed (e.g., "Ready", "Processing", "Not Available")
- Watch button visible only if status is `READY`
- Loading, error, and not-found states handled
- Admin-only controls hidden for regular users

**Dependencies:** Issue 3.4

---

### Issue 3.8: Frontend Admin Movie CRUD UI

**GitHub:** [#91](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/91), delivered by [PR #159](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/159)

**Description:**  
Create admin pages for movie listing, create, edit, archive, and restore. Include genre and people assignment. Forms validate required fields.

**Acceptance Criteria:**
- Admin movie list page uses `GET /api/v1/admin/movies` with a status filter and links to edit
- Admin movie create form includes title, slug, description, release_year, maturity_rating, status
- Slug is suggested from the title and remains editable
- Admin movie edit form pre-fills existing data
- Admin can assign genres and credits
- Admin can archive movie (soft delete) and restore an archived movie by setting status DRAFT or PUBLISHED
- Credit assignment searches people through `GET /api/v1/admin/people` and sends `personId` for an existing person
- Form validation matches backend rules
- Success and error feedback displayed
- Admin routes are protected with `ROLE_ADMIN` guard

**Dependencies:** Issue 3.3, Issue 3.4, Issue 3.5, Issue 2.10

---

### Sprint 3 Definition of Done

- [x] Backend movie CRUD APIs work with pagination and DTOs
- [x] Genre and people management works for admin
- [x] Movie list and detail APIs return correct data with status filtering
- [x] Admin movie CRUD endpoints enforce `ROLE_ADMIN`
- [x] Frontend movie list page displays paginated movies
- [x] Frontend movie detail page shows full metadata and video status
- [x] Admin UI for movie CRUD is functional and protected
- [x] Unit and integration tests cover movie service and APIs
- [x] Postman collection includes movie endpoints
- [x] No JPA entities exposed in API responses

Verified against `main` at `7e9668e` on 2026-10-28; see the [Sprint 3 closeout evidence](../testing/SPRINT-3-CLOSEOUT.md) for the mapping of each item to its issue, tests, Newman requests, the manual UI pass and the limits. Upload, encoding and playback were out of scope for that closeout. #14 is now merged at `24873be`; [ADR-010](../architecture/adr/ADR-010.md) records the owner-approved 2026-10-29 Sprint 4 design package. The Sprint 4 criteria below were delivered and verified in the [Sprint 4 closeout](../testing/SPRINT-4-CLOSEOUT.md).

---

## Sprint 4 - Upload

**Goal:** Implement admin video upload with validation, MinIO storage, video asset tracking, and RabbitMQ job publishing.

**Related Stories:** US-008  
**Related NFRs:** NFR-003, NFR-008, NFR-009, NFR-015

**Design authority:** [ADR-010](../architecture/adr/ADR-010.md), API-CONTRACT, ERD, SECURITY and INFRASTRUCTURE. Create the implementation issues with these criteria after the docs merge. Order: 4.1; 4.3 (V5); 4.2; 4.4/4.5; 4.6 with #52; 4.7; the synthetic non-VPS 2 GB rehearsal (the genuine VPS measurement is #180, before public deployment). PostgreSQL race evidence uses #119 or an isolated Compose smoke, not H2. Do not introduce Testcontainers as an assumed existing dependency.

### Issue 4.1: Backend MinIO Client Configuration

**GitHub:** [#169](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/169), delivered by [PR #176](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/176)

**Description:**  
Configure a MinIO-compatible Silo client with environment credentials. Select/document minio-java or AWS SDK v2 in this issue; stream known-length uploads and reads. Keep bucket creation/privacy in the existing init path and provide scoped backend/worker service credentials.

**Acceptance Criteria:**
- MinIO client connects using environment credentials
- Buckets are private by default
- Upload and get object methods work
- Known-length upload streams use bounded heap; no whole-file byte array
- Least-privilege backend permissions cover raw PUT/compensation DELETE and HLS reads; worker permissions cover raw reads and attempt HLS PUT/DELETE; neither uses storage root credentials
- Integration evidence covers private buckets, streamed PUT/GET/DELETE, and denied out-of-scope access; final policy details are documented with the selected SDK

**Dependencies:** Sprint 3 complete

---

### Issue 4.2: Backend RabbitMQ Publisher Configuration

**GitHub:** [#170](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/170), delivered by [PR #178](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/178)

**Description:**  
Configure RabbitMQ and the ADR-010 topology from `RABBITMQ_ENCODING_QUEUE`. Publish only versioned IDs-only messages, with persistent delivery, correlated confirms and mandatory returns.

**Acceptance Criteria:**
- RabbitMQ connection uses environment credentials
- Durable classic source queue defaults to `video-encoding-queue`; direct durable DLX `<queue>.dlx` and durable classic DLQ `<queue>.dlq`, source dead-letter routing key and DLQ binding `<queue>.dlq`; identical backend/worker declarations, no quorum delivery-limit or automatic DLQ redrive
- JSON is `{schemaVersion:1, encodingJobId, videoAssetId, attempt}`; UUID IDs, attempt >=1, content type application/json, persistent, message_id=encodingJobId, optional x-request-id header; no object keys, Java serialization or __TypeId__ coupling
- One golden `encoding-job-v1.json` fixture is parsed by backend and worker tests; ignore unknown fields, reject unsupported versions and ID/attempt mismatches
- Confirms plus mandatory/returns distinguish ack-with-route, returned/unroutable, nack and timeout; default `ENCODING_PUBLISH_CONFIRM_TIMEOUT_MS=5000`, env-driven
- Isolated broker evidence proves declarations, persistence, confirmed publication, return/nack/timeout handling and DLQ routing

**Dependencies:** Sprint 3 complete, Issue 4.3

---

### Issue 4.3: Backend Video Asset Entity and Repository

**GitHub:** [#171](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/171), delivered by [PR #177](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/177)

**Description:**  
Map `video_assets` and `encoding_jobs` including nullable `published_at`. Add V5 from the [ADR-010 specification](../architecture/adr/ADR-010.md#v5-migration-specification-d11-d16-d17-d24); never edit V1-V4. Repository supports newest/current queries and the per-movie transaction serialization needed by upload/retry.

**Acceptance Criteria:**
- Entity matches ERD schema
- V5 adds only `encoding_jobs.published_at timestamptz NULL` (no default/backfill) and `ux_video_assets_movie_in_flight ON video_assets(movie_id) WHERE status IN ('UPLOADED','QUEUED','PROCESSING')`
- Preflight detects pre-existing duplicate in-flight movies and stops for explicit repair; no silent data deletion. Existing QUEUED rows stay unpublished/relay eligible
- CI named-index count changes 31 -> 32; tables 15, constraints 53, FKs 17 and CHECKs 9 stay unchanged; no current pointer/optional READY CHECK/extra outbox or job index
- Repository includes findByMovieId, findByStatus, newest by `(created_at,id)` and `findCurrentReadyByMovieId` (greatest READY tuple)
- Asset `created_at` is assigned inside the asset/job insert transaction after raw PUT and per-movie serialization, never alongside pre-PUT ID generation or at request start
- Catalog list/detail derive videoStatus in set-based SQL or through a catalog-defined port implemented by encoding, without N+1 or a Maven dependency cycle
- Tests cover no assets, one READY, pending/failed replacement with older READY, tie ordering, newest-only retry selection; PostgreSQL proves the partial unique index and mapping by its name (also exercised through upload in 4.6)

**Dependencies:** Issue 3.1

---

### Issue 4.4: Backend Upload Service - Validation and Storage

**GitHub:** [#172](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/172), delivered by [PR #179](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/179)

**Description:**  
Validate the spooled multipart file, stream raw to Silo with known length, then coordinate with 4.5 to commit a new QUEUED asset/job pair. Generate the asset ID before PUT; never upsert a video asset. Configure/create the dedicated temp spool before the connector starts.

**Acceptance Criteria:**
- Check declared part MIME against the configured allowlist; require compatible family: `ftyp` at offset 4 for mp4/mov or EBML `1A45DFA3` for mkv/webm. Reject text, renamed HLS/ffconcat, family mismatch and legacy MOV without ftyp with 400 `UNSUPPORTED_VIDEO_TYPE`; empty file remains 400 validation error
- Extension is mp4/mov/mkv/webm from validated declared type within its family, never filename; MP4 declared video/quicktime is accepted as family-compatible
- Set Spring file/request limits to `${MAX_UPLOAD_SIZE_MB:2048}MB`, spool `${TEMP_UPLOAD_DIR:/tmp/vod-uploads}`, threshold 0B. Validate directory and sweep only stale regular Tomcat `upload_*.tmp` files non-recursively before connector start, no symlink traversal/general temp sweep/ApplicationRunner
- Stream `getInputStream()` with `getSize()` to private `vod-raw` at `{movieId}/{videoAssetId}.{ext}`, never `getBytes()`; release request temp files on completion/abort
- Advisory in-flight pre-check before PUT saves storage writes; authoritative V5 partial index maps concurrent loser to 409 `VIDEO_UPLOAD_IN_PROGRESS`
- Temp-disk headroom for concurrent uploads to different movies (each may spool up to the configured ceiling) is measured on the target VPS in #180, which blocks public deployment; until then it is not measured and no limit_conn is set. If insufficient, #180 documents a measured fallback proposal such as upload-location Nginx limit_conn 1; adopt no limit without measurement and a recorded decision
- Raw PUT precedes the new asset/job transaction; if it does not commit (including 409 loser), best-effort delete only this request's raw key, WARN with IDs on cleanup failure. Read back uncertain commits by generated asset/job IDs; retain raw while outcome is unknown. Never delete raw on a post-commit publish failure
- Assign asset `created_at` only inside that insert transaction, after PUT and per-movie serialization. Test overlapping PUTs: X starts first, Y commits and reaches READY before X's PUT ends, then X inserts; X must rank newest, Y stays current until X reaches READY, then X becomes current
- Return the committed acceptance DTO (QUEUED), with no internal keys/credentials; UPLOADED stays reserved
- Tests cover all MIME/signature cases, size/empty rejection, known-length streaming, DB rollback and concurrent 409 compensation, ambiguous commit retaining raw, failed-delete logging, temp cleanup and cancelled body parsing; use private isolated storage/PostgreSQL evidence

**Dependencies:** Issues 4.1, 4.3

---

### Issue 4.5: Backend Encoding Job Service - Queue Publishing

**GitHub:** [#173](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/173), delivered by [PR #179](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/179)

**Description:**  
Commit a new asset and attempt-1 job as QUEUED in one transaction, then eagerly publish. Use the job's nullable published_at as outbox state and a single-backend scheduled relay; a local DB transaction does not atomically commit RabbitMQ.

**Acceptance Criteria:**
- Asset and attempt-1 job are QUEUED together before publication; job published_at is NULL, UPLOADED is never committed
- Eager publish happens only after commit using 4.2's version-1 IDs-only message; confirmed, non-returned publication sets published_at only if NULL and never rewrites lifecycle status
- Return 202 QUEUED on publish failure/timeout; retain raw and pending outbox row. Zero-row conditional writes mean already advanced, not permission to reset state
- Relay defaults `ENCODING_RELAY_INTERVAL_SECONDS=10`, oldest QUEUED/published_at NULL first, queued_at at least twice confirm timeout old; both interval and confirm timeout are environment-driven
- Every lifecycle transition uses expected-state predicates; upload/retry serialize per movie and commit paired changes. No QUEUED age timeout and no generic outbox table
- Tests: DB rollback publishes nothing; crash after commit/before publish recovered by relay; returned/nacked/timed-out publish; confirm-before-published_at crash duplicates safely; fresh job skipped by relay grace; fast worker PROCESSING/FAILED/READY is never overwritten by a late confirm

**Dependencies:** Issues 4.2, 4.3

---

### Issue 4.6: Backend Admin Upload Endpoint

**GitHub:** [#174](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/174), delivered by [PR #181](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/181) and [PR #182](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/182)

**Description:**  
Implement `POST /api/v1/admin/movies/{movieId}/video` endpoint for admin video upload. Require `ROLE_ADMIN`. Accept multipart file. Call upload service and encoding job service. Return 202 Accepted with video asset status.

**Acceptance Criteria:**
- Endpoint requires `ROLE_ADMIN`
- Accepts multipart file
- Validates movie exists
- Calls upload service and encoding job service
- Returns 202 after raw storage and DB commit, even if broker publication is pending; validation 400, missing movie 404, anonymous 401, non-admin 403, in-flight 409 `VIDEO_UPLOAD_IN_PROGRESS`, 503 `AUTH_STATE_UNAVAILABLE` (Issue 6.10, before multipart parsing) / 503 `UPLOAD_STORAGE_UNAVAILABLE` / 500 `UPLOAD_DATABASE_ERROR` / 500 `UPLOAD_OUTCOME_UNKNOWN` / 500 `INTERNAL_ERROR`, each ApiError with requestId
- Backend-direct oversize raises `MaxUploadSizeExceededException` -> 413 `PAYLOAD_TOO_LARGE` ApiError; multipart request ceiling includes overhead
- Actual HTTP multipart tests prove 401/403 before parse and no upload temp files (including invalid body/missing movie); PostgreSQL parallel uploads produce one accepted transaction and one mapped 409 with compensating delete
- PostgreSQL overlap test pauses X's PUT while Y commits/reaches READY, then lets X finish PUT and insert; assert asset created_at is assigned after serialization, newest/current follow insert order rather than request start, and no timestamp was captured at pre-PUT ID generation
- Implement #52/#116 Nginx change only with/after this route: global 1m, nested upload-only regex with own URI-less proxy_pass, 2048m, buffering off, read timeout 600s, inherited headers/HTTP1.1, 60s body/send idle defaults, edge ApiError 413/502/503/504 and proxy_intercept_errors off per INFRASTRUCTURE
- Smoke/preflight asserts edge literal and SPA constant equal MAX_UPLOAD_SIZE_MB; changing that value without coordinated limits fails closed; document the consistency rule in `.env.example`
- #52 runtime tests: >1 MiB ordinary/auth body rejected; valid 2 MiB upload accepted by a custom stub with client_max_body_size 0; oversized upload edge 413 JSON; deterministic unavailable/slow stub 502/504 JSON with status/requestId; HLS/health unchanged. A short test timeout may accelerate 504 but verify production 600s
- Test cancelled sending releases temp files; record a synthetic 2,000,000,000 B transport/storage rehearsal with no 504 in a `workflow_dispatch` job on a GitHub-hosted runner, labelled non-VPS evidence (it does not prove encoding, ARM throughput or VPS disk headroom). The genuine 2 GB run on the target VPS and its measurements move to #180, which blocks public deployment. Upload endpoints/storage/broker use only isolated test resources

**Dependencies:** Issues 4.4, 4.5, Issue 2.6

---

### Issue 4.7: Frontend Admin Upload UI

**GitHub:** [#175](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/175), delivered by [PR #183](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/183)

**Description:**  
Create protected admin upload UI and shared `ApiClient.upload()` on XMLHttpRequest. Distinguish body transfer, storage-saving wait, committed acceptance and recoverable errors without automatic body replay.

**Acceptance Criteria:**
- File picker accepts video files
- Upload button disabled until valid selection and during its own active request; no parallel duplicate submission
- Pre-check file size against the guarded 2048 MiB limit constant, allowlisted extension and MIME; fill empty File.type from extension. GET movie detail through the existing refresh path before upload; do not add unconditional refresh or pull Sprint 9 admin listing forward
- videoStatus may stay READY during replacement; it is availability, not a complete in-flight pre-check. Server 409 remains authoritative
- XHR percent progress; Cancel only during body transfer. At 100%, “Saving to storage…” with Cancel hidden/disabled
- Success message shows video asset status (QUEUED)
- Map 400/413 clearly, 409 as upload already in progress, 401 as session expired (never automatically resend body), status 0 as file too large or connection lost. Once the body was fully sent, show “Upload result unknown” for any 5xx or network error, unless the ApiError `code` is `AUTH_STATE_UNAVAILABLE` (503, Issue 6.10), `UPLOAD_STORAGE_UNAVAILABLE` (503) or `UPLOAD_DATABASE_ERROR` (500), per the amended ADR-010 D1: then show that the upload was not accepted (no committed asset/job pair) and allow manual retry. Edge 502/503/504, `UPLOAD_OUTCOME_UNKNOWN`, `INTERNAL_ERROR` and unmapped 500s stay unknown; never infer outcome from MovieDetail.videoStatus, which may stay READY throughout a replacement
- Explain deliberate re-upload is safe: while the first asset is UPLOADED/QUEUED/PROCESSING the server returns 409 VIDEO_UPLOAD_IN_PROGRESS; if it already finished, re-upload creates a newer asset with extra storage cost, reclaimable by the manual purge runbook, without corrupting existing assets. Never auto-resubmit, including after ambiguous disconnect/cancel; #168 supplies a direct newest-asset view later, with no new endpoint in this issue
- Tests cover pre-check failure, empty MIME fallback, own-request disable, progress/saving switch, cancel availability and no automatic body replay; lost replacement-upload response while videoStatus stays READY, unknown-result messaging for post-send network error and edge 502/503/504, code-based not-accepted 503/500 (`AUTH_STATE_UNAVAILABLE` from Issue 6.10, `UPLOAD_STORAGE_UNAVAILABLE`, `UPLOAD_DATABASE_ERROR`) versus unknown 500 (`UPLOAD_OUTCOME_UNKNOWN`, `INTERNAL_ERROR`, unmapped), deliberate re-upload 409 while in-flight, and a new asset/extra-storage notice after completion
- Admin upload page is protected with `ROLE_ADMIN` guard

**Dependencies:** Issue 4.6, Issue 2.10

---

### Sprint 4 Definition of Done

- [x] Admin can upload video files through API and UI
- [x] Upload validates declared MIME/signature family and size from config
- [x] Raw video is stored in MinIO `vod-raw` bucket
- [x] New asset and attempt-1 job commit together as QUEUED; UPLOADED is reserved; V5 and schema-count changes verified on PostgreSQL
- [x] Version-1 IDs-only persistent RabbitMQ message, topology, confirms/returns and unpublished-job relay verified; broker outage keeps 202 QUEUED
- [x] Conditional writes prevent late publish/status regression; parallel uploads prove one-in-flight index/409 and raw compensation
- [x] Upload endpoint enforces `ROLE_ADMIN`
- [x] XHR progress, saving after 100%, send-only Cancel, pre-checks, no 401 replay and explicit ambiguous-error recovery verified
- [x] Unit and integration tests cover upload and queue publish
- [x] Postman/Newman collection covers upload 202/400/401/403/404/409/413; edge smoke covers ApiError 413/502/503/504, route limits and unchanged HLS/health
- [x] `.env.example` documents size/MIME/temp, confirm/relay settings and edge/SPA consistency rule; backend spool/abort/startup cleanup verified
- [x] Synthetic 2,000,000,000 B transport/storage rehearsal on a GitHub-hosted runner finishes without 504, labelled non-VPS evidence; it closes Sprint 4. The genuine VPS run (client time over WAN, post-100% saving wait, temp-disk peak, docker stats memory, actual VPS disk size/data root) and concurrent-upload headroom are tracked by #180 before public deployment, **to be measured in #180**. Demo target remains 500 MB-1 GB. If saving approaches 600s or disk is insufficient, #180 proposes a separate ADR
- [x] This sprint proves upload/storage/queue only; upload -> transcode -> HLS playback remains #125

Verified against `main` at `e164c39` on 2026-11-02; see the [Sprint 4 closeout evidence](../testing/SPRINT-4-CLOSEOUT.md) for the mapping of each item to its issue, tests, Newman requests, the edge smoke, the synthetic rehearsal, the manual UI pass and the limits. The VPS measurements remain **to be measured in #180** before public deployment, and upload -> transcode -> HLS playback remains #125.

---

## Sprint 5 - Worker

**Goal:** Implement worker queue consumer, FFprobe metadata extraction, FFmpeg HLS transcoding, HLS upload to MinIO, and status transitions.

**Related Stories:** US-009  
**Related NFRs:** NFR-004, NFR-005, NFR-009, NFR-011

**Design authority:** [ADR-010](../architecture/adr/ADR-010.md) D6-D10 and D18-D28, SECURITY Media Processing Safeguards, and INFRASTRUCTURE Upload and encoding settings and Worker runtime image. Create the Sprint 5 GitHub issues after this design merges. Test media is generated by #123; no third-party media is committed.

### Issue 5.1: Worker RabbitMQ Consumer Configuration

**GitHub:** [#191](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/191), delivered by [PR #208](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/208) and [PR #213](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/213)

**Description:**  
Configure worker to consume from `video-encoding-queue`. Set concurrency to 1. Implement message listener that receives encoding job messages and delegates to processing service.

**Acceptance Criteria:**
- Worker connects to RabbitMQ using environment credentials
- Consumer declares the same env-named classic durable queue/DLX/DLQ topology as 4.2; concurrency and `WORKER_PREFETCH` are 1
- Listener `autoStartup=false`; startup sweep/recovery in 5.7 must finish before listener starts. Exactly one worker replica, no overlapping recreate
- Parse golden version-1 IDs-only JSON without Java type headers; load trusted storage keys from DB, validate job/asset/attempt; unknown fields ignored, bad version/malformed/mismatched IDs rejected without requeue to DLQ
- Spring AMQP AUTO ack only after persisted READY/FAILED or a safe duplicate/stale skip; `default-requeue-rejected=false`, poison rejection uses `AmqpRejectAndDontRequeueException`
- DB outage at claim/finish holds delivery with capped in-process backoff, never media FAILED or DLQ; no hot requeue loop or encode replay during final-write retries
- Tests: redelivery after READY, stale attempt, poison payload, DB down and restart mid-PROCESSING. Inspect DLQ using rabbitmqctl without exposing management UI
- #74 baseline (RabbitMQ 4.4, classic queues) evaluates no consumer timeout, so none is configured. On a test broker with a 60000 ms node-wide `consumer_timeout`, a delivery held at least 3 minutes (long job, then a DB outage at claim/finish) keeps its channel and consumer and is not redelivered, and the second queued job is delivered only after the first is acknowledged; no application retry-exhaustion path

**Dependencies:** Sprint 4 complete, #74; startup recovery from Issue 5.7 must be wired before enabling consumption

---

### Issue 5.2: Worker MinIO Client and PostgreSQL Configuration

**GitHub:** [#192](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/192), delivered by [PR #209](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/209) and [PR #213](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/213)

**Description:**  
Configure worker MinIO client for reading from `vod-raw` and writing to `vod-hls`. Configure JDBC or JPA for direct status updates to PostgreSQL (worker does not use full ORM for status writes).

**Acceptance Criteria:**
- Worker MinIO client uses environment credentials
- Worker can read from `vod-raw` bucket
- Worker can write to `vod-hls` bucket
- Worker has direct JDBC connection to PostgreSQL for the short status transactions
- Storage/JDBC connect/read/statement waits are bounded and documented; free-space planning uses raw object size from storage metadata, without a new schema size column
- Environment variables include MinIO and PostgreSQL connection details
- Manual verification confirms connectivity

**Dependencies:** Issue 5.1

---

### Issue 5.3: Worker Status Update Service

**GitHub:** [#193](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/193), delivered by [PR #210](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/210) and [PR #213](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/213)

**Description:**  
Implement service that updates `video_assets.status` and `encoding_jobs.status` using direct JDBC writes. Support transitions: `QUEUED` → `PROCESSING`, `PROCESSING` → `READY`, `PROCESSING` → `FAILED`. Store failure reason for failed jobs.

**Acceptance Criteria:**
- Status update method accepts video asset ID, job ID, new status, optional failure reason
- Claim validates latest attempt and conditionally changes both QUEUED rows to PROCESSING in one short JDBC transaction; roll back both if either precondition fails. After an unknown claim COMMIT outcome, read back the pair when DB reconnects, not merely retry the conditional claim: matching latest PROCESSING for encodingJobId/attempt is our own claim under one non-overlapping worker, so continue probe/encode without re-claim; matching QUEUED retries claim. Terminal pair, different attempt or newer job is duplicate/stale and is acknowledged without media work; unresolved readback is held, never acknowledged from non-QUEUED status alone. No claim token/schema change; V5 stays minimal
- Finish conditionally changes both PROCESSING rows to READY (including HLS keys/metadata) or FAILED (safe reason) in one transaction; lost expected state never overwrites a newer status. Read back ambiguous commits: same-attempt READY with the expected keys is success and retains HLS; only a genuinely lost finish cleans an unreferenced prefix
- No transaction/DB connection is held during probe/encode/upload; DB unavailability retries final persistence without re-running media work
- Timestamps are updated (started_at, finished_at)
- Failure reason is stored if status is `FAILED`
- Tests cover expected-state/zero-row semantics, atomic pair rollback, two claims, late finish, lost finish commit response/readback preserving READY HLS, and startup recovery; conditional update races require PostgreSQL evidence
- PostgreSQL fault-injection test: claim COMMIT succeeds but connection drops before response; worker reconnects, reads matching latest PROCESSING pair, encodes to READY and does not leave the pair stuck PROCESSING or ack it as a duplicate

**Dependencies:** Issue 5.2

---

### Issue 5.4: Worker FFprobe Service

**GitHub:** [#194](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/194), delivered by [PR #203](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/203)

**Description:**  
Implement FFprobe service that executes `ffprobe` command to extract duration, width, height, codec, bitrate from raw video. Parse JSON output. Return a metadata DTO; Issue 5.7 persists it in the conditional finish transaction.

**Acceptance Criteria:**
- FFprobe command execution uses configurable path from environment
- Force mov or matroska demuxer from trusted key extension through the fixed ADR-010 table (an unknown extension fails before any tool runs), whitelist file protocol and absolute file: input; typed ProcessBuilder arguments, close stdin, clear environment except PATH/LANG/HOME; do not pass FFmpeg-only -nostdin
- Pass `-max_streams` (`WORKER_MAX_STREAMS`) and the ADR-010 D26 `-codec_whitelist`; a stream whose decoder is not listed fails as UNSUPPORTED_MEDIA
- Select the first video stream with `disposition.attached_pic=0`; use the selected stream's duration, or the format duration when the stream has none (WebM often reports `N/A`), as the one media duration for caps, deadline, disk budget and validation; require that duration and the rotation-corrected dimensions (Display Matrix side data, `tags.rotate` as the fallback) to be finite and positive; round the rotation to whole degrees, normalize it modulo 360 (FFprobe reports [-180, 180], so -90 means 270) and accept only 0/90/180/270; ignore unknown JSON keys; round duration up to whole seconds; store bitrate as null outside the int range
- Extracts duration, width, height, codec, bitrate
- Parses JSON output safely
- Drain capped stderr and bound JSON output; enforce `WORKER_FFPROBE_TIMEOUT_SECONDS` (**to be measured using #124**) and the D26 product caps orientation-neutrally (long side <= `WORKER_MAX_WIDTH`, short side <= `WORKER_MAX_HEIGHT`, duration <= `WORKER_MAX_DURATION_SECONDS`)
- Deadline kills the process tree; codes PROBE_FAILED/PROBE_TIMEOUT/UNSUPPORTED_MEDIA/MEDIA_LIMIT_EXCEEDED from the ADR-010 D28 enum; no fps allowlist
- Returns metadata DTO
- Unit tests cover parsing logic
- Generated #123 fixtures test valid families, negative and positive 90/180-degree rotation tags, key/content family mismatch, truncated file with moov at the end, audio-only, cover-art-only, a decoder outside the allowlist, disguised playlist, timeout and caps; command integration test uses the ADR-010 D25 binary and pins the decoder names

**Dependencies:** Issue 5.2, #123, #124 measurement record

**Implementation note:** this issue (with #123) switches `worker/Dockerfile` to the ADR-010 D25 base (digest-pinned `debian:trixie-slim`, Debian `ffmpeg` and `openjdk-21-jre-headless`) before its FFprobe tests, so 5.4/5.5 and the #124 measurement run the D25 binary; Issue 5.8 adds the hardening

---

### Issue 5.5: Worker FFmpeg HLS Transcoding Service

**GitHub:** [#195](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/195), delivered by [PR #207](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/207)

**Description:**  
Implement the FFmpeg service that turns the probed raw copy into single-quality VOD HLS in the job temp directory, with the ADR-010 D27 profile (#122): H.264 High 8-bit 4:2:0 video, optional AAC-LC stereo audio, MPEG-TS segments and one media playlist named `master.m3u8` with relative `segments/` URIs.

**Acceptance Criteria:**
- FFmpeg command execution uses configurable path from environment
- Force trusted mov/matroska input, -protocol_whitelist file, absolute file: path and -nostdin; typed argument list without shell, cleared environment except PATH/LANG/HOME, capped stderr
- Video: libx264, `-profile:v high`, `-pix_fmt yuv420p`, preset `medium`, CRF 23, x264-chosen level, `-maxrate:v {HLS_VIDEO_MAXRATE_KBPS}k -bufsize:v {2 x HLS_VIDEO_MAXRATE_KBPS}k` (the `k` suffix is required: FFmpeg takes bits per second and bits; the command test asserts `4000k` and `8000k`)
- Geometry: autorotation kept; scale into a box of `HLS_TARGET_HEIGHT` (short side) by 16/9 of it (long side) in display orientation, 1280x720 or 720x1280; never upscale; both dimensions even
- Frame rate: source rate with `-fpsmax 60`; keyframes forced with `-force_key_frames expr:gte(t,n_forced*HLS_SEGMENT_SECONDS)`
- Streams: `-map 0:V:0 -map 0:a:0?`, `-map_metadata -1`, `-map_chapters -1`; audio `-c:a aac -profile:a aac_low -b:a 128k -ac 2 -ar 48000`, never copied; a silent source yields video-only HLS
- HDR and 10-bit sources become 8-bit without tone mapping and the output is tagged BT.709: after scaling, the frames are retagged with `setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709`, and the matching output options `-color_primaries bt709 -color_trc bt709 -colorspace bt709` are kept (on FFmpeg 7.1 the output options alone leave the source's PQ/BT.2020 tags; reproduced in #123). FFmpeg-tagged tests probe the output and assert all three fields are `bt709`; washed-out colours are an owner-accepted known limitation
- HLS: `-hls_time HLS_SEGMENT_SECONDS`, `-hls_playlist_type vod`, `-hls_list_size 0`, `-hls_segment_type mpegts`, `-hls_flags independent_segments`, `-hls_base_url segments/`, segment files `segments/seg_%05d.ts`; the worker creates `hls/segments/` first and rejects relative job paths or paths containing `%`
- Process: `-xerror -nostats -loglevel error -progress pipe:1 -stats_period 1`; `-threads` and `-filter_threads` from `WORKER_FFMPEG_THREADS`; stdout and stderr drained concurrently with bounded readers; the progress parser keeps only the last `out_time_us` and tolerates N/A or negative values
- Exit 0 is not success: run the SECURITY output validation (VOD, ENDLIST, segments present and non-empty, no extra files, EXTINF sum within 1 s of the 5.4 media duration); any failure or non-zero exit is ENCODE_FAILED. FFmpeg deadline `max(WORKER_FFMPEG_TIMEOUT_MIN_SECONDS, WORKER_FFMPEG_TIMEOUT_FACTOR * durationSeconds)` kills the process tree and yields ENCODE_TIMEOUT; values **to be measured using #124**
- Tests: a command-construction test asserts the exact argument list for both demuxers and rejects invalid settings; validator and progress-parser unit tests
- FFmpeg-tagged tests on #123 fixtures with the worker image binary: mp4 h264/aac 1080p30, webm vp9/opus, mkv High10, HEVC Main10 HDR with AC-3 5.1, portrait 720x1280 and 1080x1920, rotation-tagged (check orientation, not only dimensions), silent, odd 641x359, 853x480, 60/120/29.97 fps and scene cuts; each output passes validation, has even dimensions within the box, no upscaling and segment durations near `HLS_SEGMENT_SECONDS`
- Negative tests: a faststart file truncated to 30% fails validation; cover-art-only input fails; the deadline kill uses a tiny test timeout. The #123 valid corpus shows that neither `-xerror` nor the 1-second tolerance rejects a valid file. Keep CI clips short and low-resolution

**Dependencies:** Issue 5.2, #122, #123, #124 measurement record

---

### Issue 5.6: Worker HLS Upload Service

**GitHub:** [#196](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/196), delivered by [PR #211](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/211)

**Description:**  
Upload HLS artifacts to private `vod-hls` under attempt-scoped keys: `video-assets/{videoAssetId}/attempt-{n}/master.m3u8` and sibling `segments/*.ts`. Public API HLS paths remain unchanged and are resolved through the stored master key.

**Acceptance Criteria:**
- Uploads all HLS files from temp directory to `vod-hls` bucket
- Attempt prefixes cannot overwrite another attempt; relative segment URIs match uploaded files
- Return storage locations to the orchestrator; write `hls_bucket`/`hls_master_object_key` only in the successful conditional READY transaction
- Unit tests cover object key generation: lowercase canonical UUID, decimal attempt without padding, segment names matching the API `segmentName` pattern, and an attempt prefix equal to the runbook SQL derivation
- Integration test or manual verification confirms upload

**Dependencies:** Issue 5.5

---

### Issue 5.7: Worker Job Processing Orchestration

**GitHub:** [#197](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/197), delivered by [PR #213](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/213) and [PR #215](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/215)

**Description:**  
Implement main processing orchestrator that coordinates: mark status `PROCESSING` → download raw video → run FFprobe → run FFmpeg → upload HLS → mark status `READY` or `FAILED`. Handle failures at each step.

**Acceptance Criteria:**
- Conditionally claim latest QUEUED asset/job pair through 5.3; after ambiguous claim COMMIT, read back and continue this delivery's matching latest PROCESSING claim without re-claiming. Ack without media work only for confirmed terminal/stale/different-attempt/newer-job outcomes, never just from a failed conditional re-claim
- Test successful claim COMMIT with lost response: reconnect/readback owns PROCESSING, probe/encode/finish to READY, no stuck PROCESSING; DB outage at claim/finish remains never FAILED and never DLQ
- Check free disk twice per ADR-010 D27 (before download: raw size plus `WORKER_DISK_RESERVE_MB`; after probe: reserve plus max(B, 2B - R) with B from `WORKER_OUTPUT_BUDGET_KBPS`) and run a one-second free-space watchdog during encoding and upload; INSUFFICIENT_DISK is a safe failure code, not a new API quota response
- Bounded download into owned `job-*` directory; no symlink/path escape
- Runs FFprobe and keeps its metadata for the finish transaction
- Runs FFmpeg and generates HLS output
- Uploads HLS artifacts to MinIO
- Delete downloaded raw copy when FFmpeg exits, before HLS upload; original raw object stays retained
- Conditionally finish asset/job READY with metadata/HLS keys in one transaction; on media/storage/timeout failure, persist paired FAILED with safe code/timestamps before AUTO ack
- Best-effort delete only the attempt prefix ending `/` after FAILED, lost conditional finish or startup orphan recovery; warn on delete failure without undoing terminal state. Use a dedicated attempt-prefix delete that validates `^video-assets/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/attempt-[1-9][0-9]*/$` and sends Silo `x-minio-force-delete: true` under the existing worker policy, never the generic `ObjectStorage.delete` (a silent no-op for a prefix). A 2xx is logged as delete requested, not confirmed. Crash-orphaned multipart uploads fall under the runbook orphan audit
- Clean owned temp files on every exit. Before listener startup, sweep validated WORKER_TEMP_DIR job-* dirs, log bytes freed, conditionally fail all orphaned PROCESSING pairs as WORKER_INTERRUPTED, delete their attempt prefixes, then enable listener. Startup fails closed if DB recovery cannot finish; assume one non-overlapping worker
- No QUEUED timeout/backend PROCESSING reconciler/heartbeat columns; unpublished QUEUED belongs to backend relay. Manual wedged-JVM restart and confirmed-message-loss procedure follow deployment runbook
- Bounded probe/encode/storage/JDBC waits per operation; DB outage during claim/finish retries in-process with capped backoff delays without FAILED/DLQ or re-encoding. No DB-retry exhaustion, listener/worker shutdown or startup recovery is triggered by the outage alone; the #74 RabbitMQ 4.4 classic baseline evaluates no consumer timeout. Failure codes come from the ADR-010 D28 enum: STORAGE_ERROR for a raw object that is missing or unreadable or an HLS upload that fails after bounded retries, ENCODE_FAILED for output-validation failures, INTERNAL_ERROR for unexpected errors
- Tests cover happy path, truncated/disguised media, process-tree timeout, insufficient disk, raw-copy removal order, failed-prefix deletion/failure, attempt-1/ vs attempt-10/ isolation, lost finish, DB outage, crash/restart/orphan cleanup and redelivery after READY; also truncated-faststart output rejection, cover-art/audio-only rejection, a fake-binary grandchild process-tree kill, and failed-prefix tests against the pinned Silo that assert absence through a backend or root reader while `attempt-10/` and another asset's `attempt-1/` remain

**Dependencies:** Issues 5.3, 5.4, 5.5, 5.6

---

### Issue 5.8: Worker Dockerfile and Environment Config

**GitHub:** [#198](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/198), delivered by [PR #214](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/214)

**Description:**  
Harden the worker Dockerfile that Issue 5.4 moved to the ADR-010 D25 base (FFmpeg and FFprobe from Debian). Document required environment variables in `.env.example`: RabbitMQ credentials, MinIO credentials and bucket names, PostgreSQL connection, FFmpeg/FFprobe paths.

**Acceptance Criteria:**
- Keep the ADR-010 D25 base introduced in Issue 5.4 (digest-pinned `debian:trixie-slim`, Debian `ffmpeg` and `openjdk-21-jre-headless`); CI asserts a non-root `id -u` and the recorded minimum ffmpeg version with `dpkg --compare-versions`, logs the installed versions, and adds the weekly rebuild; the #124 arm64 job builds the same Dockerfile; container OS scan limits stay with #54
- Non-root, read_only root, a disk-backed writable volume (not tmpfs) for WORKER_TEMP_DIR guarded by the raw-size free-disk check, cap_drop ALL, no-new-privileges, pids_limit validated in this issue and sized for JVM plus FFmpeg threads (it counts threads); stop_grace_period 30s; test the real mount layout (disk-backed WORKER_TEMP_DIR and a writable java.io.tmpdir), not tmpfs
- Add the worker temp volume/mount to Compose and document it in INFRASTRUCTURE Persistent Volumes; this does not move the backend TEMP_UPLOAD_DIR out of its container layer
- Worker image builds successfully
- `.env.example`/Compose bind prefetch/concurrency, temp, probe timeout, duration/width/height/stream caps, min/factor encode deadline, `HLS_SEGMENT_SECONDS`, `HLS_TARGET_HEIGHT`, `HLS_VIDEO_MAXRATE_KBPS`, `WORKER_FFMPEG_THREADS`, `WORKER_OUTPUT_BUDGET_KBPS`, `WORKER_DISK_RESERVE_MB` and bounded storage/JDBC waits; policy values from ADR-010 D26-D28, timeouts from #124, never invented placeholders; a setting is already bound by the first PR that required it (D28)
- Worker starts in Docker Compose with correct config
- Manual verification confirms worker can process a job end-to-end

**Dependencies:** Issue 5.7, #74, #124

---

### Sprint 5 Definition of Done

- [x] Worker consumes encoding jobs from RabbitMQ queue
- [x] Worker concurrency is 1 (sequential processing)
- [x] One non-overlapping replica, prefetch 1, startup sweep/orphan failure before listener, 30s stop grace and long-job consumer-timeout evidence
- [x] Worker downloads raw video from MinIO
- [x] FFprobe extracts metadata, persisted with the paired READY/FAILED finish
- [x] FFmpeg generates single-quality HLS output with the ADR-010 D27 profile, validated before READY
- [x] Worker uploads HLS artifacts to MinIO `vod-hls` bucket
- [x] Video asset status transitions: `QUEUED` → `PROCESSING` → `READY` or `FAILED`
- [x] Encoding job status matches video asset status
- [x] Failed jobs store safe diagnostic information
- [x] Worker cleans up temp files after processing
- [x] Restricted media commands, metadata caps, bounded diagnostics, process-tree deadlines/disk check and hardened container verified against #123/#124 fixtures
- [x] Redelivery/poison/DB-outage/lost-finish/crash recovery and failed-attempt-prefix cleanup tested; R-006 remains Open until the 5.7 failure-path evidence exists (4.4 is verified in the Sprint 4 closeout)
- [x] Worker logs job lifecycle events
- [x] Unit and integration tests cover worker modules
- [x] End-to-end test: upload → queue → worker → HLS output verified
- [x] Worker Dockerfile includes FFmpeg/FFprobe
- [x] `.env.example` documents worker environment variables

Verified against `main` at `f83f576` on 2026-11-13, plus the job lifecycle logs added with the closeout; see the [Sprint 5 closeout evidence](../testing/SPRINT-5-CLOSEOUT.md) for the mapping of each item to its issue, tests, CI smokes and limits. The real-worker fault flows and the long-hold broker test are local-only evidence; production deadline values and A1 capacity remain with #124, and upload -> transcode -> HLS playback remains #125 (Sprint 6).

---

## Sprint 6 - Streaming

**Goal:** Implement HLS playback API, authenticated HLS proxy through backend, frontend video player, and playback progress tracking with resume.

**Related Stories:** US-010, US-011  
**Related NFRs:** NFR-005, NFR-006, NFR-016

**Design authority:** [ADR-011](../architecture/adr/ADR-011.md) (owner-approved 2026-11-14 for #104, #105, #107 and #126) together with ADR-010 D14/D22/D27 and the frozen API contract. The issues below summarize it; the ADR governs when they differ.

### Issue 6.1: Backend Playback API

**GitHub:** [#219](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/219), delivered by [PR #226](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/226)

**Description:**  
Implement `GET /api/v1/movies/{movieId}/playback` per API-CONTRACT: authenticate, apply visibility 404 first, then select current READY asset by greatest `(created_at,id)` and return manifest, duration and current user's per-movie resume position.

**Acceptance Criteria:**
- Endpoint requires valid JWT
- Uses the catalog visibility check (ADR-009): a missing, DRAFT or ARCHIVED movie returns 404 `MOVIE_NOT_FOUND` for ROLE_USER and ROLE_ADMIN alike; a `movieId` that is not a UUID returns 400 `VALIDATION_ERROR`
- Uses `findCurrentReadyByMovieId`; pending/failed replacement preserves playback on the previous current asset
- Returns HLS manifest path per API-CONTRACT format: `/hls/video-assets/{videoAssetId}/master.m3u8`
- Returns video duration from `video_assets` metadata of the current asset
- Returns `resumeSeconds` per ADR-011 D11: 0 without progress, or when the stored position clamped to the current asset's duration equals that duration (played to the end, or a shorter replacement); otherwise the clamped position, also for a movie finished at 90%
- Visible movie with no current asset returns 409 VIDEO_NOT_READY; missing/hidden movie is 404 for every playback caller
- Tests cover null/no READY, replacement pending/failed/succeeded, tie ordering, visibility-first 404, and the resume rules including a shorter replacement
- Responses are validated against the frozen contract

**Dependencies:** Sprint 5 complete

---

### Issue 6.2: Backend HLS Proxy Endpoint

**GitHub:** [#220](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/220), delivered by [PR #227](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/227)

**Description:**  
Implement authenticated HLS proxy endpoints per API-CONTRACT.yaml (Stream tag): `GET /hls/video-assets/{videoAssetId}/master.m3u8` (playlist) and `GET /hls/video-assets/{videoAssetId}/segments/{segmentName}` (segments). Validate JWT, check video access, read HLS objects from MinIO, stream to client. Nginx forwards `/hls/*` to backend. Response rules follow ADR-011 D3-D4.

**Acceptance Criteria:**
- Endpoints match API-CONTRACT paths: `/hls/video-assets/{videoAssetId}/master.m3u8` and `/hls/video-assets/{videoAssetId}/segments/{segmentName}`
- Validates JWT from the `Authorization` header only; a valid token sent only as `?access_token=` returns 401 on HLS and API paths (test)
- Validates published movie and requested asset is current READY on every playlist/segment request; non-current/hidden/non-ready/missing is 404, including after a mid-stream switch
- Derives the attempt prefix from stored hls_master_object_key, validates segmentName and streams only those objects from private vod-hls; public paths unchanged
- Streams the whole object with correct Content-Type (`application/vnd.apple.mpegurl` for `.m3u8`, `video/mp2t` for `.ts`), `Cache-Control: no-store`, and `Content-Length` when storage reports the size; a `Range` header is ignored
- Missing or invalid JWT returns 401
- Every not-found case returns 404 `VIDEO_ASSET_NOT_FOUND`: missing object, missing/superseded/non-READY asset, hidden movie, a `videoAssetId` that is not a UUID and an unsafe segment name; the application's own validation never answers 400 on HLS paths, and error responses also carry `Cache-Control: no-store`
- The `ObjectStorage` port distinguishes a missing object (404) from unavailable storage (503) instead of one `IOException`, closes the storage stream when the client disconnects, and a missing object of a current READY asset is logged at WARN as an integrity problem
- A storage read failure before the response starts returns 503 `HLS_STORAGE_UNAVAILABLE`
- HLS authorization failures are logged with request ID and asset ID, never the token
- Integration tests cover current-only playlist/segments, superseded 404, attempt-prefix resolution, unsafe segment names, visibility, the headers and the query-token rejection
- Nginx config forwards `/hls/*` with `Authorization` header and does not cache it

**Dependencies:** Issue 6.1

---

### Issue 6.3: Backend Progress Save and Read API

**GitHub:** [#221](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/221), delivered by [PR #229](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/229)

**Description:**  
Implement the progress endpoints per API-CONTRACT.yaml (Progress tag): `PUT /api/v1/movies/{movieId}/progress` saves the current playback position and `GET /api/v1/movies/{movieId}/progress` returns it. Each accepted update is committed to PostgreSQL before the response; there is no Redis buffer (ADR-011 D9-D12). Require authentication.

**Acceptance Criteria:**
- `PUT` (not POST) accepts the required `videoAssetId`, `currentSeconds` and `finished`, and the optional `durationSeconds` (ignored), per the ProgressRequest schema, and returns 200 with the stored ProgressResponse
- A `videoAssetId` that is not the current asset returns 409 `VIDEO_ASSET_NOT_CURRENT` and stores nothing
- Uses the catalog visibility check (ADR-009); integration tests assert 404 for DRAFT, ARCHIVED and missing movies for ROLE_USER and ROLE_ADMIN
- A visible movie without a current READY asset returns 409 `VIDEO_NOT_READY` on `PUT`
- One transaction: forward-only upsert of `playback_progress` by `last_played_at` (server `Clock`), then the `watch_history` write; an older concurrent write returns the stored newer row and leaves history unchanged
- The server clamps `currentSeconds` to the current asset's duration, stores that duration, and derives `finished` (the request flag, which also stores the position as the duration, or the position at 90% of the duration); a later update below the threshold clears `finished`
- `watch_history`: inserted on the first accepted update, `last_watched_at` advanced afterwards, `completed_at` set once on the first finished update
- `GET` returns the row normalized against the current asset (ADR-011 D11), or `currentSeconds: 0`, `finished: false` and null `durationSeconds`/`lastPlayedAt` without one; it does not require a current asset
- User can only read and update their own progress
- No Redis client, buffer, TTL or scheduled flush is added
- PostgreSQL integration tests cover the transaction, the forward-only guard under concurrent writes, clamping, completion, history and ownership; responses are validated against the frozen contract

**Dependencies:** Issue 6.1

---

### Issue 6.4: Backend Progress Flush Service (withdrawn)

Withdrawn by ADR-011 D9 (owner decision, 2026-11-14): progress is written directly to PostgreSQL in Issue 6.3, so there is no Redis buffer to flush and no scheduled flush service. The number is kept so that existing references stay valid; no GitHub issue is created for it.

---

### Issue 6.5: Backend Continue Watching API

**GitHub:** [#222](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/222), delivered by [PR #235](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/235)

**Description:**  
Implement continue watching endpoint per API-CONTRACT.yaml (History + Progress tags): `GET /api/v1/me/continue-watching` returns movies user started but hasn't finished, ordered by last_played_at descending. Require authentication.

**Acceptance Criteria:**
- Endpoint path is `/api/v1/me/continue-watching` per API-CONTRACT
- Endpoint requires valid JWT
- Returns the caller's movies from `playback_progress` that are not finished and whose position, normalized against the current asset, is above 0 and below its duration; movies without a current READY asset are excluded; these conditions apply before ordering and the limit, and each item carries the normalized progress
- Uses the catalog visibility check (ADR-009): the list excludes non-published movies for every role; integration tests cover DRAFT and ARCHIVED movies for ROLE_USER and ROLE_ADMIN
- Results ordered by `last_played_at` descending, then `movie_id`, limited to the 50 most recent (ADR-011 D11)
- Includes movie metadata and resume position per ContinueWatchingItem schema, without N+1 queries
- No pagination per API-CONTRACT (returns array, not page)
- Tests cover the query logic, ordering, the limit and ownership

**Dependencies:** Issue 6.3

---

### Issue 6.6: Frontend Video Player Component

**GitHub:** [#223](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/223), delivered by [PR #241](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/241), with the manual rows on [PR #242](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/242)

**Description:**  
Create the video player component with hls.js (ADR-011 D1-D8, D13). The player accepts the HLS manifest URL and resume position, authenticates every HLS request, sends progress updates, and handles loading, buffering and error states.

**Acceptance Criteria:**
- Player uses hls.js only, pinned to an exact version (1.5.0 or later): checks `Hls.isSupported()` first, never assigns the manifest to `video.src` or uses `canPlayType`, and shows an unsupported-browser state otherwise
- Player accepts manifest URL and start position props; it loads paused at the resume position through hls.js `startPosition` and never calls `play()` on its own; a rejected `play()` stays paused and is not an error (planned exception, owner decision 2026-11-26: an explicit "Tập tiếp theo" click starts the next episode once, Issue 8.12)
- Every HLS request reads the current access token and sends it in the `Authorization` header; a 401 runs the shared `ApiClient` authentication recovery once (retry with a newer current token, or join the single-flight refresh) and retries that request once; the load-policy retry and the fatal handler share that one budget; only a 401 on the retry, or a refresh rejected with 401, ends the session; the player is destroyed when the signed-in identity changes, and an old identity's callback never ends a newer session
- `ApiClient` exposes that recovery and the authentication snapshot for the player; the player reuses Issue 6.9's refresh-failure classification (a refresh that fails by a network error, a timeout or a 5xx keeps the session; only a 401 ends it), with tests on the HLS path; no token is ever put in a URL and `sendBeacon` is not used
- Player sends progress every 10 seconds while playing, and on pause, seek, `ended` (with `finished: true`), page hide, `pagehide` and unmount; whole seconds; no save before the first play; a save is skipped only when its whole payload (position and `finished`) equals the last successful save, so the `ended` save is always sent; each save carries the `videoAssetId`; at most one save is in flight, later triggers replace one pending snapshot; unload saves use `fetch` with `keepalive` and the latest snapshot; a failed save never interrupts playback; saving stops after 404 or 409, and 409 `VIDEO_ASSET_NOT_CURRENT` also makes the page fetch the playback info again
- Native `<video controls playsinline>` with `aria-label` set to the movie title; no custom control bar
- Error mapping per ADR-011 D7 and its 2026-11-22 addendum: only fatal errors leave the playing state, except a load error that stops loading at once (a 404, a 401 on the retry, a failed recovery), because hls.js 1.7.4 still retries such a fragment of a single-level stream; one `recoverMediaError()` for a fatal media error; fatal network errors mapped by status (401 recovery rule, 404 reloads playback info once and a failed refetch shows Retry, others show Retry); `role="status"` for the initial load, `role="alert"` for fatal states, an `aria-hidden` rebuffering spinner
- Unit tests: non-fatal error ignored; fatal media error recovered once, then the error state; 401 recover-once-retry-once and session expiry; a late 401 of an old token retries without a refresh; a refresh 503 keeps the session; an account change during a refresh; one recovery budget shared with the fatal handler; 404 with and without a new current asset; unsupported state; hls.js path when `canPlayType` answers positively; `video.src` never set to the manifest; start position; rejected `play()`; a fake-timer test of the save triggers, including a pause followed by `ended` within the same second
- Manual evidence: one row per verified browser (current desktop Chrome or Edge, and Firefox, versions recorded) with 60 seconds of playback, a seek, pause and resume, and a reload that resumes; a keyboard-only pass (reach the player, play, pause, seek, volume, fullscreen)
- The automated Chrome playback smoke of ADR-011 (Playwright, part of #125) runs against this player once Issue 6.7 exists

**Dependencies:** Issue 6.2, Issue 6.3, Issue 6.9

---

### Issue 6.7: Frontend Watch Page

**GitHub:** [#224](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/224), delivered by [PR #242](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/242)

**Description:**  
Create watch page that fetches playback info and displays video player. Show movie title, description, and player. Handle not-ready video status.

**Acceptance Criteria:**
- Watch page fetches playback info for movie
- Displays player with HLS manifest and resume position
- Shows movie title and description
- Handles loading state during fetch
- 409 `VIDEO_NOT_READY` shows the viewer-facing availability label ("Processing" or "Not available"); failure codes and messages are never shown to viewers
- 404 `MOVIE_NOT_FOUND` shows the not-found state; other errors show the error state with Retry
- When the player reports that the asset is no longer current, the page fetches playback info again once and reloads the player on a new asset (ADR-011 D7)
- The movie detail page links to the watch page only when the movie can be watched
- Tests cover the loading, ready, not-ready, not-found and error states; the manual rows of Issue 6.6 are run on this page

**Dependencies:** Issue 6.6, Issue 6.1

---

### Issue 6.8: Frontend Continue Watching Page

**GitHub:** [#225](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/225), delivered by [PR #243](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/243)

**Description:**  
Create continue watching page that displays movies user started but hasn't finished. Link to watch page for each movie.

**Acceptance Criteria:**
- Page fetches continue watching list
- Displays movie cards with resume position indicator
- Cards link to watch page
- Loading, empty, error states handled
- Shows the whole array without pagination, per API-CONTRACT, and says that it lists the 50 most recently watched movies

**Dependencies:** Issue 6.5

---

### Issue 6.9: Browser Session Cookie and In-Memory Access Token

**GitHub:** [#230](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/230), delivered by [PR #237](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/237) and [PR #240](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/240)

**Description:**
Implement ADR-012 D1-D3, D7 and the 6.9 part of D9: move the refresh token from JSON bodies and `localStorage` into the HttpOnly `vod_refresh` cookie scoped to `/api/v1/auth`, keep the access token in memory only, protect the auth endpoints with `SameSite=Strict`, an exact `Origin` check and `Sec-Fetch-Site`, and move the SPA's refresh-failure classification here from Issue 6.6. Backend, frontend, API contract, smoke scripts and Postman collections change in one pull request, because the auth contract change is breaking. Must land before Issue 6.6.

**Acceptance Criteria:**
- Login and refresh set `vod_refresh` with `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`, no `Domain`, `Max-Age` equal to the refresh token's remaining lifetime; no response body contains a refresh token; refresh and logout take no body
- Refresh returns 401 and clears the cookie for a missing, unknown, expired, revoked, over-long, duplicated or replayed cookie; a failure to read the token state is 503, not 401; logout is idempotent 204 and always clears the cookie; login revokes a valid refresh token presented with it
- `refresh` and `logout` require an allowed `Origin`; `login` and `register` reject a present, disallowed `Origin`; `Sec-Fetch-Site: cross-site` is rejected on all four with 403 `ORIGIN_NOT_ALLOWED`; tests cover each case, including two `vod_refresh` cookies
- `Secure` can be disabled only with loopback-only allowed origins; startup fails otherwise; `CORS_ALLOWED_ORIGINS` defaults include the Compose edge origin
- `API-CONTRACT.yaml` updated per ADR-012 D2 in the same pull request; every auth response validated against it; cookie attributes tested directly, since the contract validator does not check them
- Login, refresh (including bootstrap) and logout each run inside the cross-tab Web Lock from request to response handling; timeouts abort inside the lock; the "revoke the unused refresh token" clean-up is removed; browser-level tests with controlled response order check both the real cookie and the memory state
- A non-secret `localStorage` marker holds the generation and the signed-in user id (never a token); requests check it before sending and before committing; tabs reconcile on `pageshow`, `visibilitychange` and focus; a token-free `BroadcastChannel` only wakes tabs; tests delay the channel and simulate a restored tab
- The SPA keeps the access token in memory only, bootstraps with one refresh call, and deletes the legacy `localStorage` session key; no token is written to web storage (tests)
- Only a refresh rejected with 401 ends the session; a network error, a timeout or a 5xx keeps it with a retryable error, for JSON requests and bootstrap (tests; a change of the Sprint 2 behaviour)
- A logout the server has not confirmed leaves a non-secret "logout pending" flag; bootstrap retries the logout instead of refreshing until it returns 204 or the user signs in; the UI shows the unconfirmed sign-out with a retry (tests)
- A sign-in as another user in one tab replaces the session in every tab (tests)
- Newman Sprint 2 auth collection and every smoke that refreshes or logs out use a cookie jar and send `Origin`
- Manual check in Chrome and Firefox on the local Compose stack: sign in, reload, two tabs, sign out, sign out while offline

**Dependencies:** #94 (forwarded host and port, before the edge verification)

---

### Issue 6.10: Server-Side Sessions and Bounded Revocation

**GitHub:** [#231](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/231), delivered by [PR #251](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/251)

**Description:**
Implement ADR-012 D4-D6, D8 and the 6.10 part of D9: an `auth_sessions` table with an absolute lifetime, refresh tokens bound to a session, replay revoking the whole session, `users.auth_epoch`, access tokens with `sid` and `ver`, a 5-second JWT clock skew, a per-request session check with a 30-second cache aged from load start, direct database checks for refresh and admin operations, 503 `AUTH_STATE_UNAVAILABLE` when the state cannot be read, a 10-session cap per user and daily pruning. May land after Issue 6.6; must land before public deployment and before the final #125 run.

**Acceptance Criteria:**
- One new Flyway migration (no existing migration edited) creates `auth_sessions`, adds nullable `refresh_tokens.session_id` with a cascading foreign key and an index, and `users.auth_epoch integer NOT NULL DEFAULT 0`; legacy tokens without a session are rejected at refresh; the schema snapshot is updated
- Login creates a session with `expires_at = now + JWT_REFRESH_TOKEN_TTL_DAYS`; rotation stays in the session and never extends it; replaying a rotated token revokes the session; login revokes the session of a presented cookie (PostgreSQL tests, including concurrent rotation)
- Access tokens last `JWT_ACCESS_TOKEN_TTL_MINUTES` (default 5, allowed 1-15; session 1-30 days), are validated with a 5-second clock skew, and carry `sid` and `ver`; a token without them, for a revoked or expired session, a non-active user or an outdated epoch is 401; `.env.example`, Compose defaults and the deployment README's upgrade steps change the TTL from 60 to 5
- The session check uses a Caffeine cache whose entries authorize only while less than 30 seconds have passed since their load started, shares one load per key, and discards a load invalidated while it ran (neither cached nor used for the waiting requests); backend-made changes invalidate after commit (tests with a controllable clock, including a load that is slower than an operator's SQL revocation)
- Refresh and admin operations (`/api/v1/admin/**` and the admin movie writes) read PostgreSQL on every request; a demoted admin is refused on the next admin request (test)
- A failure to read the auth state returns 503 `AUTH_STATE_UNAVAILABLE`, is not turned into 401 by the access-token converter, and clears no cookie; the API contract declares this 503 on every bearer-authenticated operation (tests)
- A user has at most 10 active sessions; the 11th login revokes the oldest (test)
- A daily job deletes sessions ended more than 30 days ago and legacy session-less tokens in bounded batches (test)
- The deployment runbook's disable and role-change steps increment `auth_epoch` and revoke the user's sessions in one transaction; SECURITY's personal-data inventory lists the session tables
- HLS and playback keep their per-request visibility and current-asset checks (existing HLS tests stay green)

**Dependencies:** Issue 6.9

---

### Sprint 6 Definition of Done

- [x] Authenticated users can fetch playback info for ready videos
- [x] Backend HLS proxy streams playlists and segments with JWT validation
- [x] Frontend video player plays HLS content in browser
- [x] Player starts at resume position if available
- [x] Player sends progress updates every 5-10 seconds
- [x] Backend commits each accepted progress update to PostgreSQL before responding (ADR-011 D9)
- [x] Continue watching API returns user's unfinished movies
- [x] Frontend watch page displays player and handles not-ready videos
- [x] Frontend continue watching page shows resumable movies
- [x] Unit and integration tests cover playback and progress APIs
- [x] End-to-end manual test: watch video, close, resume from same position
- [ ] Postman collection includes playback and progress endpoints (not met at the closeout; tracked in [#254](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/254))
- [x] Player handles loading, buffering, and error states
- [x] The refresh token lives only in the HttpOnly cookie and the access token only in memory; logout, account disabling and role changes take effect within 30 seconds (ADR-012)

Verified against `main` at `5c2c863` on 2026-11-26, except the manual close-and-resume item, whose hand-run rows ran on branch stacks (the Chrome smoke automates that path on `main`); see the [Sprint 6 closeout evidence](../testing/SPRINT-6-CLOSEOUT.md) for the mapping of each item to its issue, tests, CI run, the Chrome playback smoke and the manual browser rows, and for the limits. Thirteen items are met. The Postman item is not: no Newman collection covers playback, progress or continue watching yet (#254), in the meantime the CI stack smoke calls playback and HLS through Nginx, the Chrome smoke saves progress and resumes after a reload, and the PostgreSQL integration tests cover progress and Continue Watching. The manual browser rows predate the #251 session model and the Firefox row was scripted, not run by hand (accepted by the owner as a deviation from ADR-011 D5), the Chrome smoke is not yet a pull-request check (#255), and A1/VPS playback evidence remains with #124, #180 and #233.

---

## Sprint 7 - Content Model and Search

**Goal:** Move the catalogue to the approved content model (optional franchise → title → episode → video asset; a feature film is a title with one episode), build search on titles, and start the UI overhaul with the design system and admin pages.

**Related Stories:** US-005, US-006, US-007, US-012
**Related NFRs:** NFR-010, NFR-013, NFR-016

**Design authority:** ADR-013 (content model, planned under [#244](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/244)) and ADR-015 (search and discovery, planned under [#128](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/128)), both written for the owner's decisions of 2026-11-25/26 ([#258](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/258)). Until they merge, the frozen ADRs and API contract govern; the issues below summarize the plan and the ADRs govern when they differ.

Also in this sprint, outside the numbered items: the decision record [#258](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/258), the UI brief and UI overhaul phase 1 ([#200](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/200)), the frontend dev proxy ([#121](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/121)), the code health audit ([#239](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/239)), the Sprint 6 Newman collection ([#254](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/254)) and the Chrome playback smoke on pull requests ([#255](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/255)).

**UI decisions for #200 (owner, 2026-11-25/26):** a pink-and-black theme; cinematic density on Home and title pages, compact on browse, search, library and admin; the episode list beside the player on wide screens and below it on narrow ones; Vietnamese everywhere, admin included, with the display title first and the original or romaji name on a second line; navigation "Trang chủ · Phim lẻ · Phim bộ · Anime · Bộ sưu tập" with search and the account menu on the right ("Bộ sưu tập" once collections ship); the provisional name "Yuumé" as a text wordmark, checked for trademark and domain before #250 buys a domain, while the repository and technical identifiers keep their names. The detailed tokens and screens belong to `docs/design/UI-BRIEF.md`, written in its own design pull request under #200.

### Issue 7.1: Rename Movies to Titles Without Behaviour Change

**GitHub:** [#259](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/259)

**Description:**  
A new Flyway migration renames the movie catalogue tables and their `movie_id` columns to titles, with no route, request or response change, so every later slice works on the final names.

**Acceptance Criteria:**
- `movies`, `movie_genres`, `movie_credits` and the `movie_id` columns of those tables, `ratings` and `watchlist_items` become `titles`, `title_genres`, `title_credits` and `title_id`, with their constraint and index names; no committed migration is edited
- `video_assets`, `playback_progress` and `watch_history` keep their columns for Issue 7.2
- Entities, native SQL, constraint constants, fixtures, the schema snapshot and the purge SQL use the new names; backend, Vitest, Newman and the worker stack smoke pass unchanged
- A populated V6 database upgrades cleanly, including runtime-role grants; the slug and in-flight upload 409s still map on PostgreSQL

**Dependencies:** ADR-013 merged (#244)

---

### Issue 7.2: Episode Cutover in One Deployable Step

**GitHub:** [#260](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/260)

**Description:**  
Introduce episodes and re-key media, progress and history from the movie to the episode. Backend, Nginx and SPA routes change in one deployable step, or behind a tested bridge that keeps both route families working until the SPA switch lands.

**Acceptance Criteria:**
- One migration transaction adds `episodes` (one backfilled PUBLISHED episode per title), `titles.content_type` and `part_label`, and re-keys `video_assets`, `playback_progress` and `watch_history`; the in-flight index is per episode
- Episode playback, progress and upload endpoints; the Nginx upload location, multipart filter and `ADMIN_ENDPOINTS` cover the episode upload route
- SPA: `/watch/:episodeId`, the title route, a redirect from `/movies/:id/watch`, Continue Watching with one card per title: the latest interaction on a visible episode is chosen first; a finished one advances only to the immediately following episode when it is visible, playable and unfinished; if the selected unfinished episode loses its playable asset, the card is dropped and no older episode resurfaces; a next-episode card starts from that episode's own saved position; tests cover each of these cases, an older resumable episode and a hidden or unready successor
- The fenced deployment of ADR-013 D13: new uploads stopped and in-flight ones drained, the edge and backend stopped, the worker drained before the migration; a test runs the previous SPA build's upload against the deployed configuration and confirms that no request body reaches a removed route (the same evidence when a bridge is used)
- Populated upgrade smoke with an `EXCEPT` bijection on every carried column; a job queued before the migration finishes; PostgreSQL race tests for concurrent uploads; the real-Chrome smoke passes on the new routes
- Rollback boundary documented: the restore decision is taken at the fenced migration checkpoint, before the new backend or worker starts; once either has started, roll forward only (restoring PostgreSQL alone cannot rewind the broker or object store)

**Dependencies:** Issue 7.1

---

### Issue 7.3: Franchises and Series Administration

**GitHub:** [#262](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/262)

**Description:**  
The admin groups titles into an optional franchise and manages episodes. A DRAFT or ARCHIVED franchise hides only its own page, strip, references and search name; its PUBLISHED titles stay watchable.

**Acceptance Criteria:**
- `franchises` table and admin CRUD; `titles.franchise_id`/`franchise_order`; episode CRUD, ordering and per-episode publish; admin UI for parts and episodes
- Visibility tests at three levels; counts exclude hidden items before paging; concurrent reorder tests; fixed statement count per page
- Franchise names join the search tables: a rename rewrites one row and a hidden franchise's name row is never eligible (index-level tests here); for each endpoint, the published and hidden franchise tests belong to the later of that endpoint's issue (7.5 for search, 7.6 for suggestions) and this one
- Until Issue 8.12 ships the viewer pages, publishing a title that is not a MOVIE returns 409 `TITLE_PUBLISH_NOT_AVAILABLE` (ADR-013 D4): before then the title page can open only a film's single episode; admins can still create and prepare a title that is not a MOVIE, and upload and publish its episodes, while the title stays hidden; a test covers the refusal and Issue 8.12 flips it

**Dependencies:** Issue 7.2, Issue 7.4

---

### Issue 7.4: Search Foundation: Normalizer, Search Tables, Indexer and Aliases

**GitHub:** [#261](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/261)

**Description:**  
A versioned Java text normalizer (exact and accent-folded keys, Vietnamese đ/Đ included, Japanese kana kept) replaces the `unaccent` extension, which the Compose Flyway role cannot create. Search-owned name and document tables stay in step with every catalog write; the admin manages aliases. Provider descriptions are never indexed; eligible AniList name aliases are, once imported; other TMDB-derived values stay out of the index unless TMDB's permission is recorded in ADR-014.

**Acceptance Criteria:**
- Migration: `title_aliases`, `title_search_names`, `title_search_documents`; the never-written `search_vector` and its index dropped; no extension required
- Every catalog write path rebuilds the affected rows in its transaction; one advisory lock per write attempt; a startup reconciler fills missing or old-version rows
- `GET`/`PUT /api/v1/admin/titles/{titleId}/aliases` (at most 20 aliases)
- Normalizer unit tests (diacritics, đ/Đ, Turkish I, NFD vs precomposed, half-width kana, ガ vs カ, sequel markers); PostgreSQL race tests end equal to a full rebuild

**Dependencies:** ADR-015 merged (#128), Issue 7.1

---

### Issue 7.5: Backend Search API

**GitHub:** [#263](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/263)

**Description:**  
Implement `GET /api/v1/search` on titles per ADR-015: ranked tiers, one card per PUBLISHED title (a title with nothing playable yet stays, labelled like the catalog list), a deterministic total order and stable paging. Filters use only the admin's own data; TMDB-imported genres, cast or year join a filter only after ADR-014 records that TMDB's terms allow that use.

**Acceptance Criteria:**
- Endpoint requires a valid access token; `q` trimmed, at most 100 code points, 400 when blank or too long; `genre`, `year` and `type` filters; visibility applied through joins before rank and count
- Relevance fixture created through the admin API: Vietnamese with and without diacritics, Japanese exact and partial, romaji aliases, special characters; a typo set decides whether a `pg_trgm` fallback ships, and then only when the strict tiers find no visible title under the same filters
- Results contain only PUBLISHED titles for every role, with 200 and an empty result when nothing visible matches; tests cover DRAFT and ARCHIVED titles for ROLE_USER and ROLE_ADMIN; hidden titles never appear in items, counts or `matchedName`; tied scores keep the total order across pages
- Issue 7.3 owns the franchise-name index rows; franchise-name matching in this endpoint and its published and hidden tests belong to whichever of this issue and Issue 7.3 lands later, so this issue ships independently after Issue 7.2
- `EXPLAIN (ANALYZE, BUFFERS)` recorded on the fixture; query strings absent from the Nginx logs

**Dependencies:** Issue 7.4, Issue 7.2

---

### Issue 7.6: Search Suggestions and Search Page

**GitHub:** [#264](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/264)

**Description:**  
Title suggestions (`GET /api/v1/search/suggestions`) and the search page with a suggestion combobox, in the new design system and Vietnamese strings.

**Acceptance Criteria:**
- Endpoint requires a valid access token and applies the `q` rules of Issue 7.5; at most 5 suggestions, each a visible title, with `matchedName` only when an alias or a visible franchise name won the match, from the name tiers only; no person or genre suggestions and no fuzzy matches
- Only PUBLISHED titles for every role, with 200 and an empty result when nothing visible matches; tests cover DRAFT and ARCHIVED titles for ROLE_USER and ROLE_ADMIN; franchise-name matching and its tests belong to whichever of this issue and Issue 7.3 lands later
- No request while an IME composes; 250-350 ms debounce; stale responses dropped
- Combobox keyboard support with `aria-activedescendant`; loading, empty, error and approximate-results states

**Dependencies:** Issue 7.5, UI overhaul phase 1 (#200)

---

### Issue 7.7: Seed Script and Search Timings

**GitHub:** [#129](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/129)

**Description:**  
An idempotent seed script through the admin API creates titles with episodes, aliases, and Vietnamese and Japanese names, including a 500-title option; list, search and suggestion timings are recorded for NFR-010.

**Acceptance Criteria:**
- The seed recreates a catalogue on an empty stack; no demo data in Flyway
- A 500-title run completes; cold and warm p50/p95 HTTP timings of list, search and suggestions recorded with the commit and machine; list and search are checked against their existing targets in `NFR.md`; suggestion timings are recorded as evidence only (there is no suggestion target)

**Dependencies:** Issue 7.5 for the search row, Issue 7.6 for the suggestion row (the seed script can start earlier)

---

### Sprint 7 Definition of Done

- [ ] ADR-013 and ADR-015 merged, and the requirements record (#258) merged
- [ ] Titles and episodes live: a populated database upgraded with the bijection check, old watch links redirect, the Chrome smoke passes on the new routes
- [ ] Franchise and episode administration works with visibility tests at three levels
- [ ] Search on titles: accent-insensitive, aliases, title suggestions, hidden titles never leaked, total order across pages
- [ ] Search page and suggestions work with loading, empty and error states
- [ ] Seeded 500-title timings recorded (#129)
- [ ] UI overhaul phase 1 (#200) and the dev proxy (#121) delivered
- [ ] Unit, PostgreSQL integration and browser tests cover the above; Postman includes the new endpoints

---

## Sprint 8 - Library, Player Core and Viewer UI

**Goal:** Implement watchlist, watch history and rating on titles; the own player control bar and subtitles; the viewer pages; the first recommendation rows and editorial collections; and the media, image, player and collection designs with the disk admission and media sandbox their consumers need.

**Related Stories:** US-005, US-006, US-010, US-012, US-013, US-014, US-015
**Related NFRs:** NFR-004, NFR-005, NFR-007, NFR-016

**Design authority:** ADR-013 for the title and episode level of Issues 8.1-8.7 (watchlist and rating per title, history per episode; their paths follow the contract as ADR-013 amends it); ADR-016 and ADR-017 (media, backup and upload), ADR-020 (images, [#199](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/199)), ADR-021 (collections) and ADR-018/ADR-019 (player controls, subtitles, markers), all planned and written in this sprint before the items that need them. Existing issues in this sprint: [#130](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/130) (library contract preparation for Issues 8.1-8.7), [#131](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/131) (profile edit UI), #249 (Issue 8.8), #247 (Issue 8.9), #190 (Issue 8.16) and #199 (the image design). GitHub issues for the other items are created at the sprint start.

### Issue 8.1: Backend Watchlist Service and API

**Level (ADR-013, planned):** per title. "Movie" in the criteria below means the title, and `{movieId}` becomes the title ID in the path ADR-013 sets.

**Description:**  
Implement watchlist service with add, remove, list operations per API-CONTRACT.yaml (Watchlist tag): `PUT /api/v1/me/watchlist/{movieId}` (add), `DELETE /api/v1/me/watchlist/{movieId}` (remove), `GET /api/v1/me/watchlist` (list). Require authentication. Enforce ownership.

**Acceptance Criteria:**
- PUT /api/v1/me/watchlist/{movieId} adds movie to watchlist (not POST)
- DELETE /api/v1/me/watchlist/{movieId} removes movie from watchlist
- GET /api/v1/me/watchlist lists user's watchlist (returns array, not paginated per API-CONTRACT; #130 decides whether it becomes server-paginated)
- Duplicate prevention on add
- Watchlist scoped to authenticated user
- Uses the catalog visibility check (ADR-009); integration tests assert 404 for DRAFT, ARCHIVED and missing movies for ROLE_USER and ROLE_ADMIN, and lists exclude non-published movies
- Unit tests cover service logic
- Integration tests cover ownership enforcement

**Dependencies:** Issue 7.2 (the title level; no longer in parallel with Sprint 7)

---

### Issue 8.2: Backend Watch History Service and API

**Level (ADR-013, planned):** history rows per episode, listed with their title; visibility follows the episode and its title.

**Description:**  
Implement the watch history read API per API-CONTRACT.yaml (History tag): `GET /api/v1/me/history` returns paginated watch history. The rows are written by the progress transaction of Issue 6.3 (ADR-011 D12); this issue adds no second writer. Require authentication. Enforce ownership.

**Acceptance Criteria:**
- Endpoint path is `/api/v1/me/history` (not `/api/v1/users/me/watch-history`) per API-CONTRACT
- Watch history rows come from Issue 6.3: created on the first accepted progress update, `completed_at` set on the first finished update
- User can list their own watch history ordered by last_watched_at descending
- Watch history scoped to authenticated user
- Uses the catalog visibility check (ADR-009): results contain only PUBLISHED movies for every role, with 200 and an empty result when nothing matches; integration tests cover DRAFT and ARCHIVED movies for ROLE_USER and ROLE_ADMIN
- Pagination supported per HistoryPageResponse schema
- Unit tests cover service logic
- Integration tests cover ownership enforcement
- At the episode level (ADR-013): a hidden episode under a published title, and every episode of a hidden title, is excluded from the list and the count; two episodes of one title keep separate rows

**Dependencies:** Issue 6.3 (progress tracking triggers history), Issue 7.2

---

### Issue 8.3: Backend Rating Service and API

**Level (ADR-013, planned):** per title. "Movie" in the criteria below means the title.

**Description:**  
Implement rating service with create/update operation per API-CONTRACT.yaml (Rating tag): `PUT /api/v1/movies/{movieId}/rating` creates or updates user's rating. Require authentication. Enforce ownership.

**Acceptance Criteria:**
- Endpoint is PUT /api/v1/movies/{movieId}/rating (singular "rating", not "ratings") per API-CONTRACT, with the path as the contract stands after Issue 7.2 (ADR-013)
- User can create or update their own rating for a movie
- Rating value validated per RatingRequest schema (1-5 stars)
- Uses the catalog visibility check (ADR-009); integration tests assert 404 for DRAFT, ARCHIVED and missing movies for ROLE_USER and ROLE_ADMIN
- Users cannot rate on behalf of another user
- Aggregate rating calculation available (mean, count) returned in RatingResponse
- Unit tests cover validation and service logic
- Integration tests cover ownership enforcement
- Note: GET endpoint for retrieving user's own rating is NOT in API-CONTRACT; MovieDetail.userRating provides this

**Dependencies:** Issue 7.2

---

### Issue 8.4: Backend Aggregate Rating in Movie APIs

**Level (ADR-013, planned):** aggregate rating per title, in the title APIs.

**Description:**  
Update movie detail and list APIs to include aggregate rating (average, count). Optionally cache in Redis for performance.

**Acceptance Criteria:**
- Movie detail API includes aggregate rating (average, count)
- Movie list API includes aggregate rating per movie
- Rating calculation uses `ratings` table
- Optional Redis caching for aggregate rating
- Unit tests cover rating aggregation logic

**Dependencies:** Issue 8.3

---

### Issue 8.5: Frontend Watchlist UI

**Level (ADR-013, planned):** titles, as Issue 8.1.

**Description:**  
Create watchlist page showing user's saved movies. Add "Add to Watchlist" / "Remove from Watchlist" button on movie detail page.

**Acceptance Criteria:**
- Watchlist page fetches and displays user's watchlist
- Movie cards link to detail page
- Add/remove button on movie detail page
- Button state reflects current watchlist status
- Loading, empty, error states handled
- Pagination if list is long

**Dependencies:** Issue 8.1

---

### Issue 8.6: Frontend Watch History UI

**Level (ADR-013, planned):** episodes with their title, as Issue 8.2.

**Description:**  
Create the watch history page showing the user's recently watched episodes with their title. Display the last watched timestamp.

**Acceptance Criteria:**
- Watch history page fetches and displays user's history
- Episodes ordered by last watched descending
- Each card names the title and the episode and links to the title page or to the episode's watch page
- Loading, empty, error states handled
- Pagination if list is long

**Dependencies:** Issue 8.2

---

### Issue 8.7: Frontend Rating UI

**Level (ADR-013, planned):** titles, as Issue 8.3.

**Description:**  
Add rating component on movie detail page. User can submit or update their rating. Display aggregate rating (stars, count).

**Acceptance Criteria:**
- Rating component shows star selector (1-5 stars)
- Component pre-fills user's existing rating if available
- User can submit or update rating
- Aggregate rating displayed (e.g., "4.2 stars from 15 ratings")
- Loading and error states handled
- Success feedback on rating submission

**Dependencies:** Issue 8.3, Issue 8.4

---

### Issue 8.8: Player Controls Foundation

**GitHub:** [#249](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/249) (core part; moved from Sprint 10, owner decision 2026-11-26)

**Description:**  
An own React control bar on the current hls.js player: play and pause, ±10 s, volume, time, fullscreen, focus-scoped hotkeys with click-to-focus, auto-hide, status announcements and the accessible menu-button pattern the subtitle and audio menus reuse. A temporary build-time switch returns to native controls until the keyboard and screen-reader checks pass.

**Acceptance Criteria:**
- Hotkeys ignore text inputs, IME composition and modifiers; auto-hide never hides a focused control; one `Hls` instance; request counts of the ADR-011 2026-11-22 addendum unchanged
- Chrome run on the real stack: 60 s playback, keyboard seek and fullscreen; axe scan; manual keyboard-only and screen-reader rows in Chrome or Edge and Firefox

**Dependencies:** ADR-018 merged, Chrome smoke on pull requests (#255)

---

### Issue 8.9: Subtitle Tracks Backend

**GitHub:** [#247](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/247) (backend part)

**Description:**  
Admin upload of WebVTT and SRT (converted to WebVTT by a Java parser), stored privately per asset, served through an authenticated viewer route and listed in PlaybackInfo.

**Acceptance Criteria:**
- Admin-only before the multipart parse; 413 at the edge; malformed, non-UTF-8, oversized or over-long files rejected with storage untouched; markup reduced to an allowlist
- Tracks are served only for the asset a viewer may play: until Issue 9.6 ships, the visible episode's current READY asset; afterwards, the activated asset. Hidden, DRAFT, ARCHIVED or any other asset returns 404 in both stages; signed-out 401; `no-store` and `nosniff` on every response

**Dependencies:** Issue 7.2, ADR-019 merged

---

### Issue 8.10: Subtitle Menu and Renderer

**Description:**  
Subtitle menu on the Issue 8.8 bar; tracks fetched with the bounded authenticated body reader, a hidden text track and an overlay above the bar. Each viewer's subtitle and audio language is remembered on the device; with no preference, Vietnamese subtitles turn on when the audio is not Vietnamese; forced tracks always show.

**Acceptance Criteria:**
- Sync after seek, pause, speed and reload in Chrome and Firefox; a subtitle 404 keeps the video playing; 401 recovery; no request after sign-out

**Dependencies:** Issue 8.8, Issue 8.9, Issue 8.19 (body reader)

---

### Issue 8.11: UI Overhaul Phase 2: Viewer Pages

**Description:**  
Home, browse ("Phim lẻ", "Phim bộ", "Anime"), the title header and the Continue Watching page in the new design system and Vietnamese strings; cinematic density on Home and title pages, compact on browse and library.

**Acceptance Criteria:**
- Screens and states at 1280, 1440 and 1920 px; layout-shift and first-viewport image budgets; keyboard rows and axe; no fake data (no match scores or view counts)

**Dependencies:** UI overhaul phase 1 (#200), Issue 7.2

---

### Issue 8.12: Franchise, Part and Episode Pages

**Description:**  
Franchise page with the part strip, title page with the episode grid and part switcher (beside the player on wide screens, below on narrow ones), previous and next episode, Continue Watching per title, and the "Tập tiếp theo" button that starts the next episode once (never on reload or a shared link). Lifts the Issue 7.3 guard, so titles that are not a MOVIE can be published; a test publishes a series title and navigates its episodes.

**Acceptance Criteria:**
- Two-episode tests: finished last episode with a next one; hidden or unready next episode
- "Tập tiếp theo" starts once; reload, back and forward, Retry and a deep link stay paused

**Dependencies:** Issue 7.3, Issue 8.11, Issue 8.8

---

### Issue 8.13: Similar Titles

**Description:**  
"Tương tự" on the title page, from shared genres and people of the admin's own data (TMDB-imported genres or people only after ADR-014 records that use as allowed); reasons shown as text, no score.

**Acceptance Criteria:**
- A candidate shares at least one genre with the title (a person-only match is rejected by a fixture); the title itself, its visible franchise, unplayable and hidden titles excluded; deterministic order, one per franchise; fixed statement count

**Dependencies:** Issue 7.2, Issue 7.4

---

### Issue 8.14: "Vì bạn đã xem X" Home Row

**Description:**  
One personal Home row from one seed: a visible title the viewer rated 4-5, else, when the viewer has not rated it 1-3, the visible title with the latest completed episode. When the seed belongs to a visible franchise and the viewer has finished the seed title's last visible episode, the row starts with the next visible, playable part in franchise order that the viewer has not started; the seed's similar titles follow, by the same rule as Issue 8.13. Titles the viewer has started or rated never appear in the row. With no eligible seed there is no personal row; the other eligible Home rows (Continue Watching, pinned collections, "Mới thêm") stay.

**Acceptance Criteria:**
- Two users isolated; ratings 1-2 never recommended; a row below 3 items is hidden unless it holds a next part; Admin Preview creates no seed; `Cache-Control: no-store`

**Dependencies:** Issue 7.3, Issue 8.3, Issue 8.13

---

### Issue 8.15: Editorial Collections

**Description:**  
Admin-curated collections with a Collections page, title chips and up to three pinned Home rows (hidden below four visible titles); covers are a collage of visible members. "Bộ sưu tập" appears in the navigation once this ships.

**Acceptance Criteria:**
- Draft, archive and restore tests; hidden members excluded before counts; fixed query counts

**Dependencies:** ADR-021 merged, Issue 7.3, Issue 8.11

---

### Issue 8.16: Shared Upload and Worker Disk Admission

**GitHub:** [#190](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/190) (local implementation; its target-VM run stays in the Pre-production gate)

**Description:**  
One disk ledger for the one-shot upload spool, resumable upload sessions, encode downloads, the post-encode stage and image jobs, with a pause and drain switch for backups and a free-disk pre-check.

**Acceptance Criteria:**
- Refusal before the multipart body is parsed; a running encode's reservation is never reclaimed by time alone; crash and ambiguous-commit tests

**Dependencies:** ADR-016 merged

---

### Issue 8.17: Media Sandbox

**Description:**  
A separate container for decoding untrusted inputs (admin images now, VC-1 later): no network, read-only root, dropped capabilities, CPU, memory and PID limits, job-scoped scratch, inside the worker's single admission slot.

**Acceptance Criteria:**
- Network egress and writes outside scratch refused; limits enforced; a restart reclaims a job once with no overlapping run

**Dependencies:** ADR-016 merged

---

### Issue 8.18: Stream Selection and Wider Audio Support

**Description:**  
A two-stage probe (header inventory, then the unchanged strict probe on a verified selected copy), caps of 32 input streams and 4 audio tracks, DTS and TrueHD decoded to AAC stereo, PGS kept as a sidecar file, dropped tracks visible to the admin.

**Acceptance Criteria:**
- Generated fixtures: web release, fansub with many streams and fonts, DTS, Blu-ray-like remux, MP4 with cover art, no usable audio fails; cap plus one overflow; memory peak with many attachments measured

**Dependencies:** ADR-016 merged, Issue 8.17

---

### Issue 8.19: Protected Image Serving

**Description:**  
Images served by an authenticated GET to a Blob URL with `no-store`, following the catalog visibility rule, with the generated text poster as the 2:3 fallback. Builds the bounded authenticated body reader that subtitles reuse.

**Acceptance Criteria:**
- DRAFT or hidden image 404, signed-out 401; no Blob URL survives sign-out or an identity switch

**Dependencies:** ADR-020 merged (#199), Issue 7.2

---

### Issue 8.20: Worker Frames and the Post-Encode Stage

**Description:**  
A post-encode stage with its own timeout, inside the encode's disk reservation, that never fails READY; WebP frames under the attempt prefix; frames used only on 16:9 surfaces; seek thumbnails reuse the stage later.

**Acceptance Criteria:**
- Generated fixtures incl. black first frames; a stage failure keeps READY; cost per media hour measured; backups include the frames

**Dependencies:** Issue 8.19, Issue 8.16

---

### Sprint 8 Definition of Done

- [ ] User can add and remove movies from watchlist
- [ ] User can view their watchlist with pagination (server or client side, as #130 decides)
- [ ] Watch history records when user starts watching
- [ ] User can view their watch history ordered by recent
- [ ] User can rate movies (1-5 stars)
- [ ] User can update their own rating
- [ ] Aggregate rating is displayed on movie detail
- [ ] Frontend watchlist, history, rating UIs work with loading/error/empty states
- [ ] All library APIs enforce user ownership
- [ ] Unit and integration tests cover library features
- [ ] Postman collection includes watchlist, history, rating endpoints
- [ ] Manual test: add to watchlist, watch, rate, verify in UI
- [ ] Library features work at the title level (watchlist, rating) and episode level (history), per ADR-013
- [ ] Own control bar with keyboard and screen-reader rows passed; subtitles with the language preference in Chrome and Firefox
- [ ] Viewer pages, franchise and episode pages, "Tương tự", the personal Home row and editorial collections on real data
- [ ] Disk admission (#190) and the media sandbox in place before their consumers; stream selection with DTS/TrueHD; protected images and worker frames
- [ ] ADR-016, ADR-017, ADR-018, ADR-019, ADR-020 and ADR-021 merged

---

## Sprint 9 - Admin Operations and Media

**Goal:** Implement the admin encoding dashboard and retry per episode, activation of encoded assets with Admin Preview, the Marker Editor with skip and chapters, multi-audio packages, backup receipts and source-file tracking, admin images and smart collections; write the metadata design.

**Related Stories:** US-008, US-010, US-016
**Related NFRs:** NFR-004, NFR-007, NFR-009, NFR-011, NFR-016

**Design authority:** ADR-013 (assets per episode, `active`/`candidate`, activation), ADR-016, ADR-019, ADR-020 and ADR-021 (planned). Issues 9.1-9.5 work per episode as ADR-013 amends ADR-010; their paths follow the amended contract. GitHub issues for Issues 9.6-9.18 are created at the sprint start; the metadata design (ADR-014, [#245](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/245)/[#246](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/246)) is written in this sprint.

### Issue 9.1: Backend Video Asset List API for Admin

**GitHub:** [#168](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/168)

**Level (ADR-013, planned):** assets per episode, with title context. Rewritten 2026-11-26 for the episode level; the path and field names follow the contract as ADR-013 amends it.

**Description:**
Implement `GET /api/v1/admin/video-assets`: all video assets with status, metadata and failure reason, filterable by status, episode and title. Require `ROLE_ADMIN`.

**Acceptance Criteria:**
- Endpoint requires `ROLE_ADMIN`
- Each row carries its episode and title context, status, metadata and the ADR-011 D17 projection: `failureCode`, the fixed safe `failureReason` (ADR-011 D18; an unknown stored value maps to `INTERNAL_ERROR`), `queuedAt`/`startedAt`/`finishedAt` of the latest attempt, `retryEligible` and `superseded`
- Filters by status, episode and title combine; an invalid filter or page parameter returns 400 `VALIDATION_ERROR`; total order `createdAt` descending, then `id` descending; pagination supported
- Every state is computed per episode from the whole episode, never from a paginated subset; two episodes of one title have independent states
- Before Issue 9.6 ships, current, newest and superseded follow the ADR-010 rule (current = newest READY); after it, rows also carry `active`, `candidate` and `retired` from the activation model, and current means the activated asset. Historical READY is not a playback promise and purged historical keys may dangle
- Integration tests cover admin authorization and both stages

**Dependencies:** Sprint 8 complete; the activation fields after Issue 9.6

---

### Issue 9.2: Backend Encoding Retry Service

**Level (ADR-013, planned):** retry the newest asset of its episode, serialized on the episode row in the ADR-013 lock order; "movie" below means the episode.

**Description:**  
Retry only the newest `(created_at,id)` asset of its movie when FAILED. Serialize per movie with the same movie-row lock order as upload commit, conditionally claim FAILED -> QUEUED, clear failure, insert next attempt/published_at NULL and commit before eager publish/relay.

**Acceptance Criteria:**
- Retry service accepts video asset ID
- Non-newest/non-FAILED/lost concurrent claim returns 409 VIDEO_ASSET_NOT_RETRYABLE; missing asset 404
- Asset transition and next job insert are atomic; attempt increases per asset, source raw retained, expected-state predicates on every write
- After commit publish the same version-1 envelope with confirms/returns; publish failure remains accepted QUEUED for relay, never deletes raw
- PostgreSQL tests: double retry yields one 202/one 409, retry versus new upload preserves one-in-flight, rollback publishes nothing, late worker/status writes cannot regress state
- At the episode level (ADR-013): a retry on one episode neither blocks nor is blocked by an upload or retry on another episode of the same title
- Before Issue 9.6 ships, a retried asset that reaches READY becomes current by the ADR-010 rule; after it, the asset becomes a candidate and is not live until activated

**Dependencies:** Issue 9.1

---

### Issue 9.3: Backend Encoding Retry API

**Level (ADR-013, planned):** episode level, as Issue 9.2.

**Description:**  
Implement `POST /api/v1/admin/video-assets/{videoAssetId}/retry` per API-CONTRACT. Require ROLE_ADMIN and call the newest-FAILED retry service.

**Acceptance Criteria:**
- Endpoint requires `ROLE_ADMIN`
- Calls retry service for specified video asset
- Returns 202 Accepted with QUEUED DTO, including pending publication
- Returns 409 VIDEO_ASSET_NOT_RETRYABLE when not newest FAILED or another retry won; 401 anonymous, 403 non-admin, 404 missing
- Integration tests cover admin authorization and retry flow

**Dependencies:** Issue 9.2

---

### Issue 9.4: Frontend Admin Encoding Dashboard

**Level (ADR-013, planned):** episode level with title context, as Issue 9.1.

**Description:**  
Create admin dashboard page showing video assets with status. Display movie title, upload date, status, duration, failure reason. Support filtering by status.

**Acceptance Criteria:**
- Dashboard fetches video assets from admin API
- Table displays the title and the episode label, status, metadata, the safe failure message, and the queued/started/finished times; a two-episode case confirms the retry action targets the intended asset
- Filter dropdown for status (All, Uploaded, Queued, Processing, Ready, Failed)
- Loading, empty, error states handled
- Pagination supported
- Retry button enabled only when `retryEligible` is true (newest FAILED); label rows with `superseded` as history and handle 409 refresh. A historical READY row may have manually purged objects and is not current playback
- Admin route protected with `ROLE_ADMIN` guard

**Dependencies:** Issue 9.1

---

### Issue 9.5: Frontend Admin Retry UI

**Level (ADR-013, planned):** episode level, as Issue 9.2.

**Description:**  
Add retry button to encoding dashboard for failed assets. Clicking retry calls retry API and updates dashboard.

**Acceptance Criteria:**
- Retry button visible only for failed assets
- Clicking retry calls admin retry API
- Loading state during retry
- Success message updates asset status to `QUEUED`
- Error message on retry failure
- Dashboard refreshes after successful retry

**Dependencies:** Issue 9.3, Issue 9.4

---

### Issue 9.6: Activation and Admin Preview Route

**Description:**  
READY means encoded; viewers get only the asset the admin activated. At activation the admin chooses "same cut" (resume, markers and subtitles kept) or "different cut" (resume from 0, history kept). The migration activates only today's playback winner of each episode, so what viewers play does not change. Every other READY row is retired: its objects may already be purged, so it can never be activated or previewed. Candidates are never-activated READY assets that are not retired; ADR-013 defines the states. A different-cut activation resets resume to 0 and keeps history and `finished`. Admin Preview accepts any READY asset of the episode that is not retired (active, candidate, or a replaced asset that can still be re-activated), also under a DRAFT title, through its separate route only. Ships as one release boundary with the separate admin-only preview route (audited, `no-store`, through live admin authorization), a minimal preview player, the activation dialog, progress writes that serialize with activation, and the viewer reload rule for a different cut.

**Acceptance Criteria:**
- A replacement stays invisible to viewers until activated; progress inserts and updates take the episode lock that activation takes and carry the activation generation; race tests for a first save and a stale A→B→A save against activation
- Preview writes no progress or history rows or requests, including on page exit; viewers and demoted admins get 404 or 403 with `no-store` on every preview path
- The admin asset list (Issue 9.1) offers the Preview and activation entry points, so a first upload can be activated after leaving the upload page
- The purge handoff ships in this release: purge spares active and candidate assets and sets `retired_at` before deleting a selected asset's objects; a test shows an older active asset is never purged as "not newest"
- Upgrade test: a historical READY row whose objects were purged is retired and can be neither activated nor previewed

**Dependencies:** Issue 7.2, Issue 9.1 (#168)

---

### Issue 9.7: Marker Editor and Admin Preview Tools

**Description:**  
The preview player on the Issue 8.8 controls, the Marker Editor (intro, ending and chapter markers with frame-accurate time capture), chapter capture by the worker, timing import, the track inventory and subtitle candidates.

**Acceptance Criteria:**
- Marker validation and revision conflicts; markers survive a "same cut" activation and are dropped by a "different cut"; an audit row per preview open

**Dependencies:** Issue 9.6, Issue 8.8, ADR-019 merged

---

### Issue 9.8: Skip Buttons and Chapters

**Description:**  
Skip buttons for confirmed intro and ending markers and a chapter menu; no automatic skipping.

**Acceptance Criteria:**
- Before, at, inside and after a region; paused stays paused; no markers, no skip control; a different-cut activation drops markers

**Dependencies:** Issue 9.7

---

### Issue 9.9: Multi-Audio Packages

**Description:**  
Every usable audio track (up to four) packaged with the video in a second layout, the master playlist rendered from database rows, typed HLS routes and an admin track API. Readers, validation and Preview parity ship first and on their own, tested with isolated V2 fixtures. Production encodes stay V1 until Issues 9.6 and 9.10 have both shipped: before activation a newer READY asset becomes current automatically, and before Issue 9.10 a first upload in V2 would have no playable asset. Output dimensions are recorded at READY.

**Acceptance Criteria:**
- Playlist escaping and route negatives; crash and failure cleanup; real Chrome plays, switches tracks and seeks; existing single-audio assets unchanged
- While the viewer switch is off, production encodes stay V1 and the activation endpoint refuses a multi-audio asset server-side; tests cover a first upload and a replacement in that state, and the viewers' existing asset stays active

**Dependencies:** Issue 8.18, Issue 7.2, Issue 8.16; Issues 9.6 and 9.10 for production V2 encodes

---

### Issue 9.10: Audio Menu and Optional-Track Errors

**Description:**  
Audio menu on the Issue 8.8 bar; a missing or disabled optional audio or subtitle track no longer ends playback.

**Acceptance Criteria:**
- Switching keeps the position; a track disabled mid-play falls back once; single-audio assets show no menu

**Dependencies:** Issue 8.8, Issue 9.9 (readers)

---

### Issue 9.11: Embedded Text Subtitles

**Description:**  
The worker extracts embedded SRT, mov_text and WebVTT subtitles (ASS kept, with an optional WebVTT fallback) as candidates the admin accepts in Admin Preview.

**Acceptance Criteria:**
- Extraction failure keeps READY; offsets correct for a late-starting source

**Dependencies:** Issue 8.9, Issue 8.18, Issue 9.7

---

### Issue 9.12: Backup Manifest, Receipts and Coverage

**Description:**  
A capture manifest (database dump, package IDs, sizes and checksums, including subtitles, fonts, PGS files, frames and thumbnails) taken while writers are drained; verification of the off-VM copy of the dump and small files; automatic restore checks of the dump; an admin banner when the newest valid receipt is older than 8 days or the last run failed; an admin download of one title's HLS package with checksums.

**Acceptance Criteria:**
- A capture during an encode, a finalizing upload and an activation still matches the dump; tampered receipts rejected; the download verified by the PC script

**Dependencies:** ADR-016 merged, Issue 8.16

---

### Issue 9.13: Source-File Tracking with Release Disabled

**Description:**  
SHA-256 and size recorded at upload, a source-file state, hash attestation of the off-VM copy (never revealing the server hash), a dry-run release list, the release API with an operation fence, a reconciler and an audit trail. Originals are archived long-term on the owner's PC or NAS, not on the VM. Deletion stays disabled server-side until the runbook exists and the R2 volume-restore drill of #136 has passed: a volume backup taken with writers drained (Issue 9.12, #190) is restored into an isolated stack, where sign-in, playback of a single-audio and a multi-audio package and a track switch succeed. The automatic dump check of Issue 9.12 does not count as that drill.

**Acceptance Criteria:**
- With deletion disabled every release call is refused
- Each release rechecks at execution time the exact source identity and SHA-256, that the asset is activated, that its current HLS package is in a verified backup set, and the owner's confirmation for that exact object set; missing or changed coverage refuses the release
- Ineligible states refused (in flight, newest FAILED, not activated, hash mismatch); a crash between delete and commit reconciles

**Dependencies:** Issue 9.6, Issue 9.12

---

### Issue 9.14: Remux Spike

**Description:**  
Research only: copy the source video into the package when a probe shows it is eligible, with a per-segment decode check and one encode fallback in the same job. Measured on the CI runner first; the target-VM comparison is a Pre-production gate row.

**Acceptance Criteria:**
- Eligible fixtures play in Chrome; ineligible ones fall back; adopted only if the target VM shows a gain

**Dependencies:** Issue 8.18

---

### Issue 9.15: Admin Image Upload

**Description:**  
Admin poster and backdrop uploads, staged and decoded only in the media sandbox through the single admission slot, never in the API process; the separate image lane is built behind a switch that stays off.

**Acceptance Criteria:**
- Fake, oversized and malformed images rejected; DRAFT images never leak; encode speed unchanged with the switch off

**Dependencies:** Issue 8.19, Issue 8.17, Issue 8.16, UI overhaul phase 1 (#200)

---

### Issue 9.16: "Chụp khung này" Crop Tool

**Description:**  
In Admin Preview the admin captures a frame and crops it to a poster or backdrop, saved through the Issue 9.15 pipeline.

**Acceptance Criteria:**
- Card-size preview, explicit save, variant warning; no decode in the API process

**Dependencies:** Issue 9.7, Issue 9.15

---

### Issue 9.17: Smart Collections

**Description:**  
Collections defined by a closed, versioned rule over the admin's own data (audio or subtitle language, type and genre, year), with preview and sort modes.

**Acceptance Criteria:**
- Rule validation; query plans recorded before any caching; no provider-imported values used

**Dependencies:** Issue 8.15; language rules after Issue 9.9 and Issue 8.9

---

### Issue 9.18: Bounded Internal Metrics

**GitHub:** [#232](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/232) (local implementation; its target-VM use is a row of #233)

**Description:**  
Bounded internal metrics, including the encode real-time factor and image-job duration that the image-lane measurement needs.

**Acceptance Criteria:**
- Per #232; label cardinality bounded; no query strings or identifiers in labels

**Dependencies:** none

---

### Sprint 9 Definition of Done

- [ ] Admin can view all video assets with status and metadata
- [ ] Admin can filter video assets by status
- [ ] Admin can see failure reasons for failed assets
- [ ] Admin can retry failed encoding jobs
- [ ] Retry increments attempt number and republishes queue message
- [ ] Retry updates asset status to `QUEUED`
- [ ] Frontend admin dashboard displays encoding status
- [ ] Frontend retry button works for failed assets
- [ ] All admin endpoints enforce `ROLE_ADMIN`
- [ ] Unit and integration tests cover retry logic and admin APIs
- [ ] Postman collection includes admin video asset and retry endpoints
- [ ] Manual test: fail a job, view in dashboard, retry successfully
- [ ] Encoding dashboard and retry work per episode with `active` and `candidate` asset states
- [ ] Activation with Admin Preview, the Marker Editor, skip and chapters; preview writes no viewer state
- [ ] Multi-audio packages with the audio menu; embedded text subtitles as candidates
- [ ] Backup receipts and coverage; source-file tracking with release still disabled
- [ ] Admin images through the media sandbox; smart collections; ADR-014 merged

---

## Metadata and Upload

**Goal:** Import metadata from TMDB and AniList with review before apply, offer resumable upload at the current size limit, and show the admin which worldwide trending titles are missing from the library.

**Related Stories:** US-007, US-008
**Related NFRs:** NFR-003, NFR-007, NFR-008

**Design authority:** ADR-014 (metadata, planned under [#245](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/245)/[#246](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/246)) and ADR-017 (resumable upload, planned). The display title and slug stay the admin's own; the admin's own description always wins, provider text is used only when the admin has none and never overwrites admin-edited content; provider names are stored as aliases with their provenance, indexed only under the search rules of Issue 7.4. This milestone runs after Sprint 9; Sprint 10 can overlap it. GitHub issues are created at its start.

### Issue MU.1: Metadata Catalog Foundation

**Description:**  
External IDs per title, episode and person; provider texts and field provenance stored apart from the admin's own fields; a title revision; the description becomes optional; a withdrawal job, fail-closed reads of expired provider text and a "refresh due" list of the titles whose TMDB data expires within 30 days: refreshed values that did not change get a new deadline, changed values wait for the admin's review, and values never refreshed are removed before the six-month limit.

**Acceptance Criteria:**
- A manual edit removes provenance; stale revisions return 409; with the withdrawal job stopped past a deadline no provider text is served while the catalogue still works

**Dependencies:** ADR-014 merged, Issue 7.2, Issue 7.4

---

### Issue MU.2: TMDB Movie Import

**GitHub:** [#245](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/245)

**Description:**  
Admin search, preview and apply for TMDB movies, with attribution and the refresh flow; no viewer ever calls TMDB.

**Acceptance Criteria:**
- Per #245; non-admins get 401/403 with zero outbound calls; provider failures return 503 and never sign the admin out

**Dependencies:** Issue MU.1

---

### Issue MU.3: TMDB TV Seasons and Episodes

**Description:**  
Season links (whole season or part of a split season), episode mapping, provider episode texts, opt-in DRAFT episodes and renumbering detection.

**Acceptance Criteria:**
- Two local titles referencing one TMDB season; duplicate episode claims return 409; an unknown season change needs re-identification and applies nothing

**Dependencies:** Issue MU.2, Issue 7.3

---

### Issue MU.4: AniList Title Import

**GitHub:** [#246](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/246)

**Description:**  
One title at a time: names, description, year and season, genres, a type suggestion and franchise suggestions from relations; data kept until refreshed or unlinked, with "last fetched" shown.

**Acceptance Criteria:**
- Per #246; rate budget with `Retry-After`; partial data never clears a field; no viewer call

**Dependencies:** Issue MU.2 (shared pipeline)

---

### Issue MU.5: Trending Titles Missing from the Library

**Description:**  
An admin-only list of worldwide trending titles (AniList and TMDB charts) that the library does not have, fetched on demand and held in memory only; each provider is enabled only after its use is cleared in ADR-014.

**Acceptance Criteria:**
- Nothing written to the database; zero calls for non-admins; a provider without a cleared use is not called

**Dependencies:** Issue MU.2, Issue MU.4

---

### Issue MU.6: Resumable Upload Sessions

**Description:**  
A restricted tus 1.0 profile on object-storage multipart: sessions with a #190 reservation at creation, an episode lock, a declared SHA-256 checked by the worker, a sweeper and an Nginx location with a fixed chunk ceiling. The whole-file limit lives only in backend configuration. The deployed limit stays 2048 MiB; #180 runs the decimal 2 GB baseline and a separate, test-only 8 GiB resumable qualification on isolated resources, and only after that row passes does the Pre-production gate raise the deployed limit.

**Acceptance Criteria:**
- Resume from a partially committed chunk, a lost completion response and an orphan multipart upload; no automatic replay of an ambiguous upload; 64-bit sizes end to end

**Dependencies:** ADR-017 merged, Issue 8.16, Issue 7.2

---

### Issue MU.7: Resumable Upload in the Admin SPA

**Description:**  
The admin upload page uses a tus client with a declared digest and resumes after a reload or a network loss, at the current size limit.

**Acceptance Criteria:**
- Real-Chrome resume after reload and network loss; the exit guard kept; closes at the 2048 MiB limit; the one-shot route stays until the Pre-production gate retires it

**Dependencies:** Issue MU.6, UI overhaul phase 1 (#200)

---

### Metadata and Upload Definition of Done

- [ ] TMDB movies and TV and AniList titles import with review before apply, provenance and withdrawal
- [ ] The admin's own text is never overwritten automatically
- [ ] Resumable upload works at the current size limit in real Chrome
- [ ] The trending admin list stores nothing

---

## Sprint 10 - Polish

**Goal:** Finalize error handling, documentation, OpenAPI/Swagger, Postman collection, and VPS deployment preparation; polish the player (picture-in-picture, Media Session, shortcut list, seek thumbnails) and answer the subtitle-rendering spike.

**Related Stories:** US-017, US-018  
**Related NFRs:** NFR-011, NFR-012, NFR-015

### Issue 10.1: Backend Centralized Exception Handling

**Description:**  
Implement `@RestControllerAdvice` for centralized exception handling. Return consistent error response DTOs with status code, message, timestamp, optional validation errors.

**Acceptance Criteria:**
- Global exception handler catches common exceptions
- Error response DTO includes status, message, timestamp, optional errors field
- Validation errors include field names and messages
- 404, 400, 401, 403, 500 responses are consistent
- Stack traces not exposed in production responses
- Unit tests cover error response format

**Dependencies:** Sprint 9 complete

---

### Issue 10.2: Backend OpenAPI/Swagger Documentation

**Description:**  
Serve the frozen `docs/architecture/API-CONTRACT.yaml` through Swagger UI. The contract stays the only API document (contract-first, Issue #120): do not generate a second, code-first document or add OpenAPI annotations to controllers. Review the contract against the implemented endpoints and close any gap through a reviewed contract update.

**Acceptance Criteria:**
- Swagger UI accessible at `/swagger-ui.html` or similar, showing `API-CONTRACT.yaml` unchanged
- No code-first OpenAPI document or controller annotations
- Request/response schemas and auth requirements come from the contract
- API contract matches actual endpoint behavior; responses are validated in tests against `API-CONTRACT.yaml`
- Swagger UI exposure follows `SECURITY.md` and `INFRASTRUCTURE.md`
- Manual review confirms completeness

**Dependencies:** Issue 10.1

---

### Issue 10.3: Postman Collection

**Description:**  
Create comprehensive Postman collection covering all MVP endpoints. Include environment variables, auth setup, example requests.

**Acceptance Criteria:**
- Collection includes all auth, movie, upload, playback, search, library, admin endpoints
- Environment variables for base URL, tokens
- Pre-request scripts for token management
- Example requests with valid payloads
- Collection stored in `docs/testing/postman/` or root
- README documents how to import and use collection

**Dependencies:** Issue 10.2

---

### Issue 10.4: Frontend Error Boundaries and Global Error Handling

**Description:**  
Add React error boundaries to catch rendering errors. Implement global error handling for API failures. Display user-friendly error messages.

**Acceptance Criteria:**
- Error boundary wraps app or major routes
- Fallback UI displays on rendering errors
- Global API error handler shows toast/alert for network failures
- 401 errors follow the shared recovery policy of ADR-012 (one refresh and retry; sign-out only when the session has ended)
- 403 errors show forbidden message
- Error messages are user-friendly, not raw stack traces

**Dependencies:** Issue 10.1

---

### Issue 10.5: Documentation Review and Update

**Description:**  
Review and update README, API contract, deployment docs, `.env.example` to match actual implementation. Ensure all environment variables are documented.

**Acceptance Criteria:**
- README includes quick start, local setup, Docker Compose instructions
- README describes MVP features and architecture
- `.env.example` documents all required variables with descriptions
- API contract (OpenAPI) matches implementation
- No outdated information in docs
- Manual review confirms accuracy

**Dependencies:** Issue 10.3

---

### Issue 10.6: VPS Deployment Preparation

**Description:**  
Document VPS deployment steps for Oracle Cloud Free Tier. Include SSH setup, Docker installation, Compose deployment, HTTPS placeholder, backup/restore procedures.

**Acceptance Criteria:**
- Deployment doc includes VPS setup steps (SSH, Docker, Compose)
- Doc includes environment variable setup for production
- Doc includes HTTPS placeholder (Let's Encrypt setup deferred to Phase 5)
- Doc includes backup and restore procedures for PostgreSQL and MinIO
- Doc includes rollback procedure
- Doc includes known limitations and troubleshooting
- Exercise the media purge and orphan audit of the deployment README on isolated fixtures (synthetic DB rows, a disposable Silo): candidate and attempt queries, `mc rm --dry-run` with the trailing `/` (without it, `attempt-1` also lists `attempt-10/`), and protected current/newest-FAILED/in-flight/archive objects still present afterwards; record the evidence; this is not a production deletion
- Doc stored in `docs/deployment/`

**Dependencies:** Issue 10.5

---

### Issue 10.7: Local Smoke Test and Documentation

**Description:**  
Create and document local smoke test procedure. Test: start stack → register → login → admin upload → wait for encoding → playback → search → watchlist → rate. Document expected behavior and known limitations.

**Acceptance Criteria:**
- Smoke test procedure documented in `docs/testing/` or README
- Smoke test covers end-to-end flow: upload → encode → playback
- Smoke test includes search, library features, admin operations
- Known limitations documented
- Smoke test can be run by developer or reviewer
- Manual execution confirms all steps pass

**Dependencies:** Issue 10.6

---

### Issue 10.8: CI/CD Pipeline Enhancement

**Description:**  
Review and enhance GitHub Actions CI pipeline. Ensure backend, frontend, worker build and test in CI. Add Docker image build validation if needed.

**Acceptance Criteria:**
- CI pipeline builds backend, frontend, worker
- CI runs unit tests for backend and worker
- CI runs frontend build and lint
- CI validates Docker Compose file syntax
- CI fails on test failures or build errors
- CI badge added to README if desired

**Dependencies:** Issue 10.7

---

### Issue 10.9: Player Polish

**GitHub:** a new child issue created at the Sprint 10 start; #249 keeps the Sprint 8 core (Issue 8.8)

**Description:**  
Picture-in-picture, Media Session actions, the speed menu and the keyboard shortcut list on the Issue 8.8 bar.

**Acceptance Criteria:**
- Picture-in-picture with captions as a manual row per browser; Media Session respects the no-autoplay rule

**Dependencies:** Issue 8.8

---

### Issue 10.10: Seek Thumbnails

**Description:**  
Keyframe-based thumbnail sprites produced in the post-encode stage and shown on the seek bar.

**Acceptance Criteria:**
- Cost and bytes per media hour measured; a stage failure keeps READY; backups include the thumbnails

**Dependencies:** Issue 8.20, Issue 8.8

---

### Issue 10.11: Styled Subtitle and PGS Rendering Spike

**GitHub:** [#248](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/248)

**Description:**  
Spikes for styled ASS (JASSUB), PGS (libpgs-js) and Document picture-in-picture: performance, sync after seek and resize, teardown and licences. If the spike passes, the owner accepted the LGPL and FreeType obligations: the notices, the location of the corresponding LGPL source and a documented way to replace or relink the LGPL code; the repository stays MIT.

**Acceptance Criteria:**
- Per #248; a recommendation per renderer; production rendering gets its own issue only if adopted

**Dependencies:** Issue 8.10

---

### Sprint 10 Definition of Done

- [ ] Backend has centralized exception handling with consistent error responses
- [ ] OpenAPI/Swagger documentation is complete and matches implementation
- [ ] Postman collection covers all MVP endpoints
- [ ] Frontend has error boundaries and global error handling
- [ ] README and docs are accurate and up-to-date
- [ ] `.env.example` documents all required variables
- [ ] VPS deployment docs include setup, HTTPS placeholder, backup, rollback
- [ ] Local smoke test procedure is documented and passes
- [ ] CI pipeline builds and tests all modules
- [ ] No secrets committed; all secrets via environment variables
- [ ] Known limitations documented
- [ ] Project is ready for the local demo; the VPS demo follows the Pre-production gate and does not hold this sprint
- [ ] Player polish, seek thumbnails and the subtitle-rendering spike delivered or answered

---

## Pre-production gate

**Goal:** Measure on the target VM and switch on what waits for that evidence; prepare deployment. Local feature work never waits for this milestone.

The measurement owners stay as they are: #124 (encode calibration, gate G1/G2 with a test-only 1080p candidate profile), #233 (mixed load, gate G4/G6 and the image-lane test), #180 (upload: the 2 GB baseline and the 8 GiB resumable row), #137 with #190 (disk layout and storage fit, gate G5), and the owner's visual review of #124 outputs (gate G3). Items created when their gate is near:

- **1080p default for new encodes:** after G1-G6 pass (G1/G2 #124, G3 the owner's visual review, G4/G6 #233, G5 #137 with #190) and 1080p plays in the real browser; needs Issue 9.9; no upscaling, existing 720p assets unchanged
- **Release of source files enabled:** the admin release button with its dry-run list and a warning whenever only one archive copy exists, and the owner's authorization to turn on the server-side switch, after the runbook exists and the R2 volume-restore drill (#136; single-audio and multi-audio playback with a track switch, on a backup taken with writers drained) has passed; every release still rechecks the archive SHA-256, activation, the verified backup coverage of the current package and the owner's confirmation for the exact object set (Issue 9.13 ships it disabled)
- **Volume backups and the disaster-recovery document** (child of #136): automatic rotation within the free limit that deletes an older backup only after the new one is AVAILABLE; immediate failure notification and the admin banner; the free R2 drill on a 50 GB data volume before the volume grows; no step that costs money or deletes the source volume without the owner's consent
- **Upload limit promotion and retirement of the one-shot route:** Issue MU.7 closes at 2048 MiB, #180 runs the separate test-only 8 GiB qualification, and only when both are done does this item raise the deployed limit and retire the one-shot route; if the 8 GiB row fails, the 2048 MiB limit and the one-shot route stay
- **Image lane switched on:** after #233's lane qualification passes: #232 instrumentation, sustained and overlapping load, restart recovery, at most 5 % encode slowdown and the full playback thresholds; the sequential single-slot path stays the fallback
- **Remux adoption:** only if Issue 9.14 shows a gain on the target VM
- HTTPS preparation (#250), the deployment children of #136, and the security gates already in this milestone (for example #51, #54, #72, #138, #139 and #141-#143) before the VPS demo; the local release does not wait for the target VM; public access remains a separate owner decision

Conditional items with no issue until their trigger:

| Item | Trigger |
|---|---|
| MPEG-2 sources | real samples and a deinterlace policy |
| VC-1 sources | the Debian fix for the open FFmpeg CVE, plus the media sandbox |
| HDR-to-SDR conversion | HDR sources exist |
| An extra 720p rendition with adaptive switching | #233 shows viewers need it |
| Styled ASS or PGS rendering in production | Issue 10.11 recommends adoption; the adopting pull request ships the notices, the LGPL source location and the replace or relink route (ADR-019, #143) |
| Studio and creator pages | enough titles carry that data |
| Personal named lists | the owner asks after using the watchlist |
| A viewer row of trending titles | provider terms allow it and a two-week pilot shows at least four playable matches on most days; once enabled, the row hides whenever fewer than four playable titles remain |
| An `ffprobe` inventory of the real library | the owner has a film library; tests keep generated lavfi fixtures |
| Automatic TMDB refresh | the owner asks |
| AniList staff, characters and studios | a follow-up issue after #246 |

---

## Global Definition of Done (All Sprints)

Every sprint must satisfy:

- [ ] Code follows AGENTS.md rules (constructor injection, DTOs, no exposed entities, etc.)
- [ ] Unit tests cover service/business logic
- [ ] Integration tests cover API endpoints where applicable
- [ ] Backend uses SLF4J logging, not System.out.println
- [ ] Secrets externalized via environment variables
- [ ] No secrets committed to repository
- [ ] `API-CONTRACT.yaml` updated or confirmed; responses validated in tests
- [ ] Frontend handles loading, error, empty, success states
- [ ] Protected routes enforce authentication; admin routes enforce `ROLE_ADMIN`
- [ ] Docs updated if behavior, config, or contracts change
- [ ] Conventional commits used
- [ ] Git status reviewed before staging to avoid unrelated changes

---

## Risk Mitigation Through Sprint Order

The sprint order intentionally addresses high-priority risks from RISKREGISTER.md:

- **R-001 (Scope creep):** Sprint 1 establishes foundation without optional features; strict adherence to MVP scope throughout.
- **R-002 (FFmpeg behavior):** Issue 5.5 pins the HLS output profile and the output validation (ADR-010 D27); #123 verifies them against generated fixtures with the worker image binary, and #124 encodes on arm64, before dependent features.
- **R-003 (VPS resources):** Sprint 1 production Compose includes resource limits; NFR-002 validated before VPS deployment.
- **R-004 (API contract drift):** Sprint 10 reviews OpenAPI vs. implementation; Postman collection serves as contract test.
- **R-006 (Storage leaks):** 4.4 compensates uncommitted raw PUTs; 5.7 cleans failed/orphan/lost-finish prefixes and temp; deployment runbook owns manual superseded purge and orphan audit. Watching since the [Sprint 5 closeout](../testing/SPRINT-5-CLOSEOUT.md) verified the 5.7 failure-path tests.
- **R-007 (Queue failures):** 4.5 commits job-row outbox before publish and relays pending publication; 5.3/5.7 conditionally claim/finish and recover startup orphans with deadlines; Sprint 9 retries only newest FAILED. Never time out QUEUED by age.
- **R-008 (Auth/RBAC):** Sprint 2 completed before catalog and upload features; authorization tests required.
- **R-012 (Tests lag):** Definition of Done includes tests for every sprint; traceability matrix tracks coverage.
- **R-014 (HLS delivery and player authentication):** ADR-011 pins the proxy headers, the hls.js-only player and token refresh during playback; Sprint 6 verified them with proxy tests, player unit tests, hand-run rows for Chrome or Edge, a scripted Firefox row (accepted by the owner in place of the hand-run row of ADR-011 D5) and the #125 Chrome smoke (see the Sprint 6 closeout).
- **R-015 (Progress durability):** ADR-011 writes each accepted progress update directly to PostgreSQL with a forward-only upsert; PostgreSQL integration tests cover a read right after a save, concurrency and completion; the Chrome playback smoke (#125) covers a player reload that resumes near the last save.

---

## Assumptions

1. Backend and worker use Spring Boot 4.x with Java 21 (ADR-002, amended 2026-10-18); frontend uses React 18 with TypeScript.
2. Docker Compose is used for local and VPS deployment; no Kubernetes.
3. One video quality per asset: 720p today, a 1080p default for new encodes after the target-VM gate (#124, #233); several audio tracks per asset are in scope (ADR-016, planned); an extra rendition only on #233 evidence.
4. PostgreSQL full-text search with a Java accent-folding normalizer and aliases is sufficient (ADR-015, planned); Elasticsearch only if a relevance fixture shows otherwise.
5. MinIO buckets are private; HLS delivered through authenticated backend proxy.
6. Worker concurrency is 1 for MVP stability.
7. Redis is used for cache only and holds no playback progress (ADR-011 D9); PostgreSQL is source of truth.
8. Authentication follows ADR-012, delivered in Sprint 6: an in-memory access token, an HttpOnly refresh cookie and server-side sessions with bounded revocation; no token in `localStorage`.
9. GitHub Issues will be created based on this backlog; issue numbers referenced in commits.
10. Test media is generated at test time with FFmpeg lavfi (#123); no third-party sample media is committed. Demo media stays local to the owner.

---

## Blockers and Dependencies

**External Dependencies:**
- FFmpeg and FFprobe must be available in worker Docker image
- Oracle Cloud Free Tier VPS account for demo deployment (Phase 5)
- Let's Encrypt for HTTPS (deferred to Phase 5 deployment)
- A free TMDB API key before the Metadata and Upload milestone; TMDB and AniList terms re-read at implementation

**Internal Dependencies:**
- Auth (Sprint 2) must complete before catalog, upload, streaming features
- Upload (Sprint 4) must complete before worker (Sprint 5)
- Worker (Sprint 5) must complete before streaming (Sprint 6)
- The content model and search (Sprint 7) come before the library (Sprint 8), which keys on titles and episodes
- Measurements and switches that need the target VM wait in the Pre-production gate; local work does not
- Admin Operations (Sprint 9) depends on upload and worker completion

**Known Risks:**
- FFmpeg command may differ on ARM vs x86 architectures (mitigate with early worker testing)
- VPS resource constraints may require tuning (mitigate with resource limits and monitoring)
- HLS playback may require MIME type and CORS configuration (mitigate with early browser testing)

---

## Next Steps After Sprint 10

After Sprint 10 and the Metadata and Upload milestone, and as the Pre-production gate's measurements allow, the project enters **Phase 4 - Testing and Hardening** and **Phase 5 - Deployment and Operations**:

1. Execute comprehensive Postman/Newman tests
2. Create manual E2E test checklist and execute
3. Set up the Oracle A1 VM as an isolated qualification environment (no public access) for the Pre-production gate measurements and the R2 restore drill
4. Test backup, restore and rollback and write the operations runbook and the disaster-recovery document
5. Close the security gates of the Pre-production gate (for example #51, #54, #72, #138, #139 and #141-#143) and configure HTTPS with Let's Encrypt (#250)
6. Deploy the VPS demo over HTTPS only after the #124, #180, #190 and #233 evidence, the applicable security gates, the #136 children, tested recovery and HTTPS are in place, and verify its stability; if the optional 8 GiB promotion failed, the demo runs with the 2048 MiB limit; public access stays a separate owner decision, and the site is never indexed by search engines
7. Keep the deployment children of #136 current
8. Enter Phase 6 - Portfolio finalization (README polish, demo video, architecture diagram)

---

## Revision History

| Date | Version | Change |
|---|---|---|
| 2026-07-05 | 1.0 | Initial backlog created from PROJECTPLAN.md, USERSTORIES.md, NFR.md, TRACEABILITY.md |
| 2026-08-08 | 1.0 | Sprint 1 Definition of Done marked complete |
| 2026-08-11 | 1.0 | Registration contract aligned with the Sprint 2 scope |
| 2026-09-18 | 1.0 | Verified Sprint 2 auth closeout recorded |
| 2026-10-06 | 1.0 | Sprint 3 catalog contract frozen (#95): Issues 3.2-3.5, 3.8 and 7.2 updated |
| 2026-10-18 | 1.1 | Platform statements in Issues 1.1 and 1.2 and Assumption 1 changed from Spring Boot 3.x to Spring Boot 4.x after the ADR-002 amendment (Issue #50) |
| 2026-11-03 | 1.3 | Sprint 5 worker design (ADR-010 D25-D28, #122): design authority line, Issues 5.4-5.8 criteria and tests, the Sprint 5 DoD wording, the Issue 10.6 purge rehearsal, the R-002 mitigation and Assumption 10 |
| 2026-11-14 | 1.4 | Sprint 6 streaming design (ADR-011, #104/#105/#107/#126): design authority line, Issues 6.1-6.3 and 6.5-6.8 criteria and tests, Issue 6.4 withdrawn, the Sprint 6 DoD progress line, the catalog visibility criteria in Issues 6.3, 6.5, 7.3, 7.4, 8.1, 8.2 and 8.3 (404 for operations that take a `movieId`, filtered results for lists), the Issue 8.2 history writer, the Issue 9.1/9.4 admin projection, R-014/R-015 and Assumption 7 |
| 2026-11-19 | 1.5 | Browser session design (ADR-012, #72): Issues 6.9 and 6.10 added to Sprint 6, Issue 6.6 depends on Issue 6.9, a Sprint 6 DoD line for the session model |
| 2026-11-22 | 1.6 | Issue 6.6 follows the ADR-011 addendum of 2026-11-22: the D2 budget per hls.js load and terminal HLS load errors in hls.js 1.7.4 (#223) |
| 2026-10-26 | 1.2 | Contract-first API documentation (Issue #120, owner decision 2026-10-18): the Global Definition of Done requires `API-CONTRACT.yaml` updated or confirmed with responses validated in tests instead of OpenAPI annotations, and Issue 10.2 serves the frozen contract in Swagger UI instead of generating a code-first document |
| 2026-11-26 | 1.7 | Sprint 6 closeout (#256): `**GitHub:**` lines under Issues 6.1-6.3 and 6.5-6.10, and the Sprint 6 DoD ticked except the Postman item (#254), with a verified note |
| 2026-11-26 | 1.8 | Owner scope decisions of 2026-11-25/26 (#258): Sprint 7 rewritten on the content model and search (Issues 7.1-7.7, GitHub #259-#264 and #129); Sprint 8 renamed, its items keyed on titles and episodes, Issues 8.8-8.20 added; Sprint 9 renamed, Issues 9.6-9.18 added; the Metadata and Upload milestone (Issues MU.1-MU.7); Issues 10.9-10.11; the Pre-production gate section with conditional items; the dependency flow, Assumptions 3 and 4, external and internal dependencies |

---

**End of Backlog**
