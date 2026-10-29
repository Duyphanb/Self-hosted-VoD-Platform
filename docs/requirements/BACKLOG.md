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
Sprint 7 (Search) + Sprint 8 (Library) [parallel]
  ↓
Sprint 9 (Admin Ops) 
  ↓
Sprint 10 (Polish)
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
Implement movie service with create, update, read, list (paginated), and archive operations. Validate required fields. Leave `search_vector` null; Issue 7.2 rebuilds and backfills it.

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

Verified against `main` at `7e9668e` on 2026-10-28; see the [Sprint 3 closeout evidence](../testing/SPRINT-3-CLOSEOUT.md) for the mapping of each item to its issue, tests, Newman requests, the manual UI pass and the limits. Upload, encoding and playback were out of scope for that closeout. #14 is now merged at `24873be`; [ADR-010](../architecture/adr/ADR-010.md) records the owner-approved 2026-10-29 Sprint 4 design package. The following criteria are planned implementation, not completed work.

---

## Sprint 4 - Upload

**Goal:** Implement admin video upload with validation, MinIO storage, video asset tracking, and RabbitMQ job publishing.

**Related Stories:** US-008  
**Related NFRs:** NFR-003, NFR-008, NFR-009, NFR-015

**Design authority:** [ADR-010](../architecture/adr/ADR-010.md), API-CONTRACT, ERD, SECURITY and INFRASTRUCTURE. Create the implementation issues with these criteria after the docs merge. Order: 4.1; 4.3 (V5); 4.2; 4.4/4.5; 4.6 with #52; 4.7; measured prod-overlay upload. PostgreSQL race evidence uses #119 or an isolated Compose smoke, not H2. Do not introduce Testcontainers as an assumed existing dependency.

### Issue 4.1: Backend MinIO Client Configuration

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

**Description:**  
Validate the spooled multipart file, stream raw to Silo with known length, then coordinate with 4.5 to commit a new QUEUED asset/job pair. Generate the asset ID before PUT; never upsert a video asset. Configure/create the dedicated temp spool before the connector starts.

**Acceptance Criteria:**
- Check declared part MIME against the configured allowlist; require compatible family: `ftyp` at offset 4 for mp4/mov or EBML `1A45DFA3` for mkv/webm. Reject text, renamed HLS/ffconcat, family mismatch and legacy MOV without ftyp with 400 `UNSUPPORTED_VIDEO_TYPE`; empty file remains 400 validation error
- Extension is mp4/mov/mkv/webm from validated declared type within its family, never filename; MP4 declared video/quicktime is accepted as family-compatible
- Set Spring file/request limits to `${MAX_UPLOAD_SIZE_MB:2048}MB`, spool `${TEMP_UPLOAD_DIR:/tmp/vod-uploads}`, threshold 0B. Validate directory and sweep only stale regular Tomcat `upload_*.tmp` files non-recursively before connector start, no symlink traversal/general temp sweep/ApplicationRunner
- Stream `getInputStream()` with `getSize()` to private `vod-raw` at `{movieId}/{videoAssetId}.{ext}`, never `getBytes()`; release request temp files on completion/abort
- Advisory in-flight pre-check before PUT saves storage writes; authoritative V5 partial index maps concurrent loser to 409 `VIDEO_UPLOAD_IN_PROGRESS`
- Raw PUT precedes the new asset/job transaction; if it does not commit (including 409 loser), best-effort delete only this request's raw key, WARN with IDs on cleanup failure. Read back uncertain commits by generated asset/job IDs; retain raw while outcome is unknown. Never delete raw on a post-commit publish failure
- Assign asset `created_at` only inside that insert transaction, after PUT and per-movie serialization. Test overlapping PUTs: X starts first, Y commits and reaches READY before X's PUT ends, then X inserts; X must rank newest, Y stays current until X reaches READY, then X becomes current
- Return the committed acceptance DTO (QUEUED), with no internal keys/credentials; UPLOADED stays reserved
- Tests cover all MIME/signature cases, size/empty rejection, known-length streaming, DB rollback and concurrent 409 compensation, ambiguous commit retaining raw, failed-delete logging, temp cleanup and cancelled body parsing; use private isolated storage/PostgreSQL evidence

**Dependencies:** Issues 4.1, 4.3

---

### Issue 4.5: Backend Encoding Job Service - Queue Publishing

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

**Description:**  
Implement `POST /api/v1/admin/movies/{movieId}/video` endpoint for admin video upload. Require `ROLE_ADMIN`. Accept multipart file. Call upload service and encoding job service. Return 202 Accepted with video asset status.

**Acceptance Criteria:**
- Endpoint requires `ROLE_ADMIN`
- Accepts multipart file
- Validates movie exists
- Calls upload service and encoding job service
- Returns 202 after raw storage and DB commit, even if broker publication is pending; validation 400, missing movie 404, anonymous 401, non-admin 403, in-flight 409 `VIDEO_UPLOAD_IN_PROGRESS`
- Backend-direct oversize raises `MaxUploadSizeExceededException` -> 413 `PAYLOAD_TOO_LARGE` ApiError; multipart request ceiling includes overhead
- Actual HTTP multipart tests prove 401/403 before parse and no upload temp files (including invalid body/missing movie); PostgreSQL parallel uploads produce one accepted transaction and one mapped 409 with compensating delete
- PostgreSQL overlap test pauses X's PUT while Y commits/reaches READY, then lets X finish PUT and insert; assert asset created_at is assigned after serialization, newest/current follow insert order rather than request start, and no timestamp was captured at pre-PUT ID generation
- Implement #52/#116 Nginx change only with/after this route: global 1m, nested upload-only regex with own URI-less proxy_pass, 2048m, buffering off, read timeout 600s, inherited headers/HTTP1.1, 60s body/send idle defaults, edge ApiError 413/502/503/504 and proxy_intercept_errors off per INFRASTRUCTURE
- Smoke/preflight asserts edge literal and SPA constant equal MAX_UPLOAD_SIZE_MB; changing that value without coordinated limits fails closed; document the consistency rule in `.env.example`
- #52 runtime tests: >1 MiB ordinary/auth body rejected; valid 2 MiB upload accepted by a custom stub with client_max_body_size 0; oversized upload edge 413 JSON; deterministic unavailable/slow stub 502/504 JSON with status/requestId; HLS/health unchanged. A short test timeout may accelerate 504 but verify production 600s
- Test cancelled sending releases temp files; record a real 2 GB (2,000,000,000 B) prod-overlay upload with no 504 and measurements listed in Sprint 4 DoD. Upload endpoints/storage/broker use only isolated test resources

**Dependencies:** Issues 4.4, 4.5, Issue 2.6

---

### Issue 4.7: Frontend Admin Upload UI

**Description:**  
Create protected admin upload UI and shared `ApiClient.upload()` on XMLHttpRequest. Distinguish body transfer, storage-saving wait, committed acceptance and recoverable errors without automatic body replay.

**Acceptance Criteria:**
- File picker accepts video files
- Upload button disabled until valid selection and during its own active request; no parallel duplicate submission
- Pre-check file size against the guarded 2048 MiB limit constant, allowlisted extension and MIME; fill empty File.type from extension. GET movie detail through the existing refresh path before upload; do not add unconditional refresh or pull Sprint 9 admin listing forward
- videoStatus may stay READY during replacement; it is availability, not a complete in-flight pre-check. Server 409 remains authoritative
- XHR percent progress; Cancel only during body transfer. At 100%, “Saving to storage…” with Cancel hidden/disabled
- Success message shows video asset status (QUEUED)
- Map 400/413 clearly, 409 as upload already in progress, 401 as session expired (never automatically resend body), status 0 as file too large or connection lost, 504 as refresh movie status before trying again; ambiguous disconnect/cancel also requires checking status before resubmission
- Tests cover pre-check failure, empty MIME fallback, own-request disable, progress/saving switch, cancel availability, no 401 replay and 409/status-0/504 errors
- Admin upload page is protected with `ROLE_ADMIN` guard

**Dependencies:** Issue 4.6, Issue 2.10

---

### Sprint 4 Definition of Done

- [ ] Admin can upload video files through API and UI
- [ ] Upload validates declared MIME/signature family and size from config
- [ ] Raw video is stored in MinIO `vod-raw` bucket
- [ ] New asset and attempt-1 job commit together as QUEUED; UPLOADED is reserved; V5 and schema-count changes verified on PostgreSQL
- [ ] Version-1 IDs-only persistent RabbitMQ message, topology, confirms/returns and unpublished-job relay verified; broker outage keeps 202 QUEUED
- [ ] Conditional writes prevent late publish/status regression; parallel uploads prove one-in-flight index/409 and raw compensation
- [ ] Upload endpoint enforces `ROLE_ADMIN`
- [ ] XHR progress, saving after 100%, send-only Cancel, pre-checks, no 401 replay and explicit ambiguous-error recovery verified
- [ ] Unit and integration tests cover upload and queue publish
- [ ] Postman/Newman collection covers upload 202/400/401/403/404/409/413; edge smoke covers ApiError 413/502/503/504, route limits and unchanged HLS/health
- [ ] `.env.example` documents size/MIME/temp, confirm/relay settings and edge/SPA consistency rule; backend spool/abort/startup cleanup verified
- [ ] Real 2 GB prod-overlay upload finishes without 504; record client time, post-100% saving wait, temp-disk peak, docker stats memory and actual VPS disk size, **to be measured in Issue 4.6/4.7**. Demo target remains 500 MB-1 GB. If saving approaches 600s or disk is insufficient, propose a separate ADR
- [ ] This sprint proves upload/storage/queue only; upload -> transcode -> HLS playback remains #125

---

## Sprint 5 - Worker

**Goal:** Implement worker queue consumer, FFprobe metadata extraction, FFmpeg HLS transcoding, HLS upload to MinIO, and status transitions.

**Related Stories:** US-009  
**Related NFRs:** NFR-004, NFR-005, NFR-009, NFR-011

### Issue 5.1: Worker RabbitMQ Consumer Configuration

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
- #74 consumer-timeout rule is implemented/verified: 4.3+ classic has no timeout; on 3.13 render env-driven broker consumer_timeout through conf.d, not immutable queue args. #74 owns sizing from #103/#124 and DB-outage handling, without an application retry-exhaustion path. Long-job test keeps a second job queued and confirms no premature delivery/channel close

**Dependencies:** Sprint 4 complete, #74; startup recovery from Issue 5.7 must be wired before enabling consumption

---

### Issue 5.2: Worker MinIO Client and PostgreSQL Configuration

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

**Description:**  
Implement service that updates `video_assets.status` and `encoding_jobs.status` using direct JDBC writes. Support transitions: `QUEUED` → `PROCESSING`, `PROCESSING` → `READY`, `PROCESSING` → `FAILED`. Store failure reason for failed jobs.

**Acceptance Criteria:**
- Status update method accepts video asset ID, job ID, new status, optional failure reason
- Claim validates latest attempt and conditionally changes both QUEUED rows to PROCESSING in one short JDBC transaction; roll back both if either precondition fails; duplicates/older valid attempts are skipped
- Finish conditionally changes both PROCESSING rows to READY (including HLS keys/metadata) or FAILED (safe reason) in one transaction; lost expected state never overwrites a newer status. Read back ambiguous commits: same-attempt READY with the expected keys is success and retains HLS; only a genuinely lost finish cleans an unreferenced prefix
- No transaction/DB connection is held during probe/encode/upload; DB unavailability retries final persistence without re-running media work
- Timestamps are updated (started_at, finished_at)
- Failure reason is stored if status is `FAILED`
- Tests cover expected-state/zero-row semantics, atomic pair rollback, two claims, late finish, lost commit response/readback preserving READY HLS, and startup recovery; conditional update races require PostgreSQL evidence

**Dependencies:** Issue 5.2

---

### Issue 5.4: Worker FFprobe Service

**Description:**  
Implement FFprobe service that executes `ffprobe` command to extract duration, width, height, codec, bitrate from raw video. Parse JSON output. Store metadata in `video_assets` table.

**Acceptance Criteria:**
- FFprobe command execution uses configurable path from environment
- Force mov or matroska demuxer from trusted key extension, whitelist file protocol and absolute file: input; typed ProcessBuilder arguments, close stdin, clear environment except PATH/LANG/HOME; do not pass FFmpeg-only -nostdin
- Extracts duration, width, height, codec, bitrate
- Parses JSON output safely
- Drain capped stderr and bound JSON output; enforce `WORKER_FFPROBE_TIMEOUT_SECONDS`, duration/width/height/stream-count caps from INFRASTRUCTURE, with values **to be measured in Issue 5.4/#124**
- Deadline kills the process tree; safe codes PROBE_FAILED/PROBE_TIMEOUT/MEDIA_LIMIT_EXCEEDED feed #107; codec/fps allowlists optional
- Returns metadata DTO
- Unit tests cover parsing logic
- Generated #123 fixtures test valid families, truncated file, fake MIME, disguised playlist, timeout and caps; command integration test uses the maintained binary selected by #54

**Dependencies:** Issue 5.2, #54, #123, #124

---

### Issue 5.5: Worker FFmpeg HLS Transcoding Service

**Description:**  
Implement FFmpeg service that generates single-quality HLS output. Use simple preset (e.g., 720p, CRF 23, baseline profile). Generate `master.m3u8` and `.ts` segments. Store output in worker temp directory before upload.

**Acceptance Criteria:**
- FFmpeg command execution uses configurable path from environment
- Force trusted mov/matroska input, -protocol_whitelist file, absolute file: path and -nostdin; typed argument list without shell, cleared environment except PATH/LANG/HOME, capped stderr
- Generates HLS master playlist and segments
- Target resolution configurable (default 720p)
- Output stored in temp directory
- Playlist uses relative segments/ URIs; FFmpeg deadline `max(WORKER_FFMPEG_TIMEOUT_MIN_SECONDS, WORKER_FFMPEG_TIMEOUT_FACTOR * durationSeconds)` kills the process tree, yields ENCODE_TIMEOUT via #107; values **to be measured in Issue 5.5/5.7/#124**
- Command construction unit tests
- Integration test or manual verification with sample video file

**Dependencies:** Issue 5.2, #54, #124

---

### Issue 5.6: Worker HLS Upload Service

**Description:**  
Upload HLS artifacts to private `vod-hls` under attempt-scoped keys: `video-assets/{videoAssetId}/attempt-{n}/master.m3u8` and sibling `segments/*.ts`. Public API HLS paths remain unchanged and are resolved through the stored master key.

**Acceptance Criteria:**
- Uploads all HLS files from temp directory to `vod-hls` bucket
- Attempt prefixes cannot overwrite another attempt; relative segment URIs match uploaded files
- Return storage locations to the orchestrator; write `hls_bucket`/`hls_master_object_key` only in the successful conditional READY transaction
- Unit tests cover object key generation
- Integration test or manual verification confirms upload

**Dependencies:** Issue 5.5

---

### Issue 5.7: Worker Job Processing Orchestration

**Description:**  
Implement main processing orchestrator that coordinates: mark status `PROCESSING` → download raw video → run FFprobe → run FFmpeg → upload HLS → mark status `READY` or `FAILED`. Handle failures at each step.

**Acceptance Criteria:**
- Conditionally claim latest QUEUED asset/job pair through 5.3; no work for duplicates/older valid attempts
- Check free disk from raw object size plus output allowance **to be measured in Issue 5.7/#124**; INSUFFICIENT_DISK is a safe failure code, not a new API quota response
- Bounded download into owned `job-*` directory; no symlink/path escape
- Runs FFprobe and stores metadata
- Runs FFmpeg and generates HLS output
- Uploads HLS artifacts to MinIO
- Delete downloaded raw copy when FFmpeg exits, before HLS upload; original raw object stays retained
- Conditionally finish asset/job READY with metadata/HLS keys in one transaction; on media/storage/timeout failure, persist paired FAILED with safe code/timestamps before AUTO ack
- Best-effort delete only the attempt prefix ending `/` after FAILED, lost conditional finish or startup orphan recovery; warn on delete failure without undoing terminal state
- Clean owned temp files on every exit. Before listener startup, sweep validated WORKER_TEMP_DIR job-* dirs, log bytes freed, conditionally fail all orphaned PROCESSING pairs as WORKER_INTERRUPTED, delete their attempt prefixes, then enable listener. Startup fails closed if DB recovery cannot finish; assume one non-overlapping worker
- No QUEUED timeout/backend PROCESSING reconciler/heartbeat columns; unpublished QUEUED belongs to backend relay. Manual wedged-JVM restart and confirmed-message-loss procedure follow deployment runbook
- Bounded probe/encode/storage/JDBC waits per operation; DB outage during claim/finish retries in-process with capped backoff delays without FAILED/DLQ or re-encoding. No DB-retry exhaustion, listener/worker shutdown or startup recovery is triggered by the outage alone; consumer-timeout sizing remains #74. Reserve PROBE_FAILED, PROBE_TIMEOUT, ENCODE_TIMEOUT, MEDIA_LIMIT_EXCEEDED, INSUFFICIENT_DISK, WORKER_INTERRUPTED via #107
- Tests cover happy path, truncated/disguised media, process-tree timeout, insufficient disk, raw-copy removal order, failed-prefix deletion/failure, attempt-1/ vs attempt-10/ isolation, lost finish, DB outage, crash/restart/orphan cleanup and redelivery after READY

**Dependencies:** Issues 5.3, 5.4, 5.5, 5.6

---

### Issue 5.8: Worker Dockerfile and Environment Config

**Description:**  
Create worker Dockerfile with FFmpeg and FFprobe installed. Document required environment variables in `.env.example`: RabbitMQ credentials, MinIO credentials and bucket names, PostgreSQL connection, FFmpeg/FFprobe paths.

**Acceptance Criteria:**
- Use maintained FFmpeg/FFprobe source selected/verified in #54 before Sprint 5; capture package/version and uid evidence, update routine and container OS scan limits
- Non-root, read_only root, a disk-backed writable volume (not tmpfs) for WORKER_TEMP_DIR guarded by the raw-size free-disk check, cap_drop ALL, no-new-privileges, pids_limit validated in this issue; stop_grace_period 30s
- Add the worker temp volume/mount to Compose and document it in INFRASTRUCTURE Persistent Volumes; this does not move the backend TEMP_UPLOAD_DIR out of its container layer
- Worker image builds successfully
- `.env.example`/Compose bind prefetch/concurrency, temp, probe timeout, duration/width/height/stream caps and min/factor encode deadline, bounded storage/JDBC waits; cap values from #124, never invented placeholders
- Worker starts in Docker Compose with correct config
- Manual verification confirms worker can process a job end-to-end

**Dependencies:** Issue 5.7, #54, #74, #124

---

### Sprint 5 Definition of Done

- [ ] Worker consumes encoding jobs from RabbitMQ queue
- [ ] Worker concurrency is 1 (sequential processing)
- [ ] One non-overlapping replica, prefetch 1, startup sweep/orphan failure before listener, 30s stop grace and long-job consumer-timeout evidence
- [ ] Worker downloads raw video from MinIO
- [ ] FFprobe extracts metadata and updates `video_assets` table
- [ ] FFmpeg generates single-quality HLS output
- [ ] Worker uploads HLS artifacts to MinIO `vod-hls` bucket
- [ ] Video asset status transitions: `QUEUED` → `PROCESSING` → `READY` or `FAILED`
- [ ] Encoding job status matches video asset status
- [ ] Failed jobs store safe diagnostic information
- [ ] Worker cleans up temp files after processing
- [ ] Restricted media commands, metadata caps, bounded diagnostics, process-tree deadlines/disk check and hardened container verified against #123/#124 fixtures
- [ ] Redelivery/poison/DB-outage/lost-finish/crash recovery and failed-attempt-prefix cleanup tested; R-006 remains Open until 4.4/5.7 failure-path evidence exists
- [ ] Worker logs job lifecycle events
- [ ] Unit and integration tests cover worker modules
- [ ] End-to-end test: upload → queue → worker → HLS output verified
- [ ] Worker Dockerfile includes FFmpeg/FFprobe
- [ ] `.env.example` documents worker environment variables

---

## Sprint 6 - Streaming

**Goal:** Implement HLS playback API, authenticated HLS proxy through backend, frontend video player, and playback progress tracking with resume.

**Related Stories:** US-010, US-011  
**Related NFRs:** NFR-005, NFR-006, NFR-016

### Issue 6.1: Backend Playback API

**Description:**  
Implement `GET /api/v1/movies/{movieId}/playback` per API-CONTRACT: authenticate, apply visibility 404 first, then select current READY asset by greatest `(created_at,id)` and return manifest, duration and current user's per-movie resume position.

**Acceptance Criteria:**
- Endpoint requires valid JWT
- Validates movie exists and is published
- Uses `findCurrentReadyByMovieId`; pending/failed replacement preserves playback on the previous current asset
- Returns HLS manifest path per API-CONTRACT format: `/hls/video-assets/{videoAssetId}/master.m3u8`
- Returns video duration from `video_assets` metadata
- Returns user's current resume position from `playback_progress` table (0 if not started)
- Visible movie with no current asset returns 409 VIDEO_NOT_READY; missing/hidden movie is 404 for every playback caller
- Tests cover null/no READY, replacement pending/failed/succeeded, tie ordering and visibility-first 404

**Dependencies:** Sprint 5 complete

---

### Issue 6.2: Backend HLS Proxy Endpoint

**Description:**  
Implement authenticated HLS proxy endpoints per API-CONTRACT.yaml (Stream tag): `GET /hls/video-assets/{videoAssetId}/master.m3u8` (playlist) and `GET /hls/video-assets/{videoAssetId}/segments/{segmentName}` (segments). Validate JWT, check video access, read HLS objects from MinIO, stream to client. Nginx forwards `/hls/*` to backend.

**Acceptance Criteria:**
- Endpoints match API-CONTRACT paths: `/hls/video-assets/{videoAssetId}/master.m3u8` and `/hls/video-assets/{videoAssetId}/segments/{segmentName}`
- Validates JWT from `Authorization` header
- Validates published movie and requested asset is current READY on every playlist/segment request; non-current/hidden/non-ready/missing is 404, including after a mid-stream switch
- Derives the attempt prefix from stored hls_master_object_key, validates segmentName and streams only those objects from private vod-hls; public paths unchanged
- Streams object with correct Content-Type (`application/vnd.apple.mpegurl` for `.m3u8`, `video/mp2t` for `.ts`)
- Missing or invalid JWT returns 401
- Missing object returns 404
- Integration tests cover current-only playlist/segments, superseded 404, attempt-prefix resolution, unsafe segment names and visibility
- Nginx config forwards `/hls/*` with `Authorization` header

**Dependencies:** Issue 6.1

---

### Issue 6.3: Backend Progress Save API

**Description:**  
Implement progress endpoint per API-CONTRACT.yaml (Progress tag): `PUT /api/v1/movies/{movieId}/progress` accepts current playback position. Buffer progress in Redis, flush to PostgreSQL periodically. Require authentication.

**Acceptance Criteria:**
- Endpoint is PUT (not POST) per API-CONTRACT
- Accepts `current_seconds`, optional `duration_seconds`, required `finished` flag per ProgressRequest schema
- Validates user is authenticated
- Validates movie exists
- Buffers progress in Redis with TTL
- Scheduled task flushes buffered progress to `playback_progress` table
- User can only update their own progress
- Unit tests cover buffering logic
- Integration test confirms Redis buffer and PostgreSQL flush

**Dependencies:** Issue 6.1

---

### Issue 6.4: Backend Progress Flush Service

**Description:**  
Implement scheduled service that reads buffered progress from Redis and writes to `playback_progress` table. Run every 30-60 seconds. Update `last_played_at`, `current_seconds`, `duration_seconds`, `finished` flag.

**Acceptance Criteria:**
- Scheduled task runs every 30-60 seconds (configurable)
- Reads all buffered progress entries from Redis
- Upserts `playback_progress` rows (insert or update)
- Clears processed entries from Redis
- Logs flush metrics (number of entries processed)
- Unit tests cover flush logic

**Dependencies:** Issue 6.3

---

### Issue 6.5: Backend Continue Watching API

**Description:**  
Implement continue watching endpoint per API-CONTRACT.yaml (History + Progress tags): `GET /api/v1/me/continue-watching` returns movies user started but hasn't finished, ordered by last_played_at descending. Require authentication.

**Acceptance Criteria:**
- Endpoint path is `/api/v1/me/continue-watching` per API-CONTRACT
- Endpoint requires valid JWT
- Returns movies from `playback_progress` where `finished = false` and `current_seconds > 0`
- Results ordered by `last_played_at` descending
- Includes movie metadata and resume position per ContinueWatchingItem schema
- No pagination per API-CONTRACT (returns array, not page)
- Unit tests cover query logic

**Dependencies:** Issue 6.3

---

### Issue 6.6: Frontend Video Player Component

**Description:**  
Create video player component using HLS.js or Video.js. Player accepts HLS manifest URL and resume position. Player sends progress updates every 5-10 seconds. Handle loading, buffering, error states.

**Acceptance Criteria:**
- Player uses HLS.js or Video.js for HLS playback
- Player accepts manifest URL and start position props
- Player starts at resume position if provided
- Player sends progress updates to backend every 5-10 seconds
- Player includes `Authorization` header with bearer token on HLS requests
- Loading spinner during buffering
- Error message on playback failure
- Player controls (play, pause, seek, volume, fullscreen)

**Dependencies:** Issue 6.2, Issue 6.3

---

### Issue 6.7: Frontend Watch Page

**Description:**  
Create watch page that fetches playback info and displays video player. Show movie title, description, and player. Handle not-ready video status.

**Acceptance Criteria:**
- Watch page fetches playback info for movie
- Displays player with HLS manifest and resume position
- Shows movie title and description
- Handles loading state during fetch
- Shows appropriate message if video is not ready (e.g., "Processing", "Failed")
- Error state handled

**Dependencies:** Issue 6.6, Issue 6.1

---

### Issue 6.8: Frontend Continue Watching Page

**Description:**  
Create continue watching page that displays movies user started but hasn't finished. Link to watch page for each movie.

**Acceptance Criteria:**
- Page fetches continue watching list
- Displays movie cards with resume position indicator
- Cards link to watch page
- Loading, empty, error states handled
- Pagination if list is long

**Dependencies:** Issue 6.5

---

### Sprint 6 Definition of Done

- [ ] Authenticated users can fetch playback info for ready videos
- [ ] Backend HLS proxy streams playlists and segments with JWT validation
- [ ] Frontend video player plays HLS content in browser
- [ ] Player starts at resume position if available
- [ ] Player sends progress updates every 5-10 seconds
- [ ] Backend buffers progress in Redis and flushes to PostgreSQL
- [ ] Continue watching API returns user's unfinished movies
- [ ] Frontend watch page displays player and handles not-ready videos
- [ ] Frontend continue watching page shows resumable movies
- [ ] Unit and integration tests cover playback and progress APIs
- [ ] End-to-end manual test: watch video, close, resume from same position
- [ ] Postman collection includes playback and progress endpoints
- [ ] Player handles loading, buffering, and error states

---

## Sprint 7 - Search

**Goal:** Implement PostgreSQL full-text search over movie metadata, search API, and search UI.

**Related Stories:** US-012  
**Related NFRs:** NFR-010, NFR-016

### Issue 7.1: Backend Search Service - Full-Text Search

**Description:**  
Implement search service that uses PostgreSQL FTS on `movies.search_vector`. Search matches title, description, genre, actor, director. Support pagination.

**Acceptance Criteria:**
- Search uses `search_vector` GIN index
- Query matches title, description, genre names, actor names, director names
- Returns paginated results
- Results ranked by relevance (ts_rank)
- Empty query returns validation error or recent movies
- Unit tests cover query construction

**Dependencies:** Sprint 6 complete

---

### Issue 7.2: Backend Search Vector Update Logic

**Description:**  
Implement logic to rebuild `search_vector` when movie metadata, genres, or credits change. Use application code to update vector (defer triggers to avoid hidden logic).

**Acceptance Criteria:**
- Movie create/update triggers search vector rebuild
- Genre assignment triggers search vector rebuild
- Credit assignment triggers search vector rebuild
- Genre or person rename rebuilds the vectors of the movies that reference it
- Existing movies with a null search vector are backfilled
- Search vector includes normalized text from title, description, genres, people
- Unit tests cover vector update logic

**Dependencies:** Issue 7.1

---

### Issue 7.3: Backend Search API

**Description:**  
Implement search endpoint per API-CONTRACT.yaml (Search tag): `GET /api/v1/search?q={query}`. Require authentication. Return paginated search results with movie metadata.

**Acceptance Criteria:**
- Endpoint path is `/api/v1/search` (not `/api/v1/movies/search`) per API-CONTRACT
- Endpoint requires valid JWT
- Accepts required query string parameter `q` (minLength: 1)
- Returns paginated results per SearchResponse schema
- Empty query returns validation error per schema requirement
- No-results returns empty list with 200 OK
- Integration tests cover search scenarios

**Dependencies:** Issue 7.1

---

### Issue 7.4: Backend Search Suggestions API

**Description:**  
Implement search suggestions endpoint per API-CONTRACT.yaml (Search tag): `GET /api/v1/search/suggestions?q={query}` returns quick suggestions (e.g., top 5 movie titles matching query). Require authentication.

**Acceptance Criteria:**
- Endpoint path is `/api/v1/search/suggestions` (not `/api/v1/movies/search/suggestions`) per API-CONTRACT
- Endpoint requires valid JWT
- Accepts required query string parameter `q` (minLength: 1)
- Returns suggestions per SearchSuggestionResponse schema (array of strings)
- Fast query (limit results for performance)
- Unit tests cover suggestion logic

**Dependencies:** Issue 7.1

---

### Issue 7.5: Frontend Search UI

**Description:**  
Create search page with search input, search button, and results list. Display movie cards with search results. Handle loading, empty, error states.

**Acceptance Criteria:**
- Search input with submit button or auto-search on input change
- Fetches search results from API
- Displays movie cards with search results
- Loading spinner during search
- Empty state message when no results
- Error message on search failure
- Results link to movie detail page

**Dependencies:** Issue 7.3

---

### Issue 7.6: Frontend Search Suggestions Component

**Description:**  
Add search suggestions dropdown that appears as user types. Fetch suggestions from API. Click suggestion to navigate to movie detail or perform search.

**Acceptance Criteria:**
- Suggestions dropdown appears below search input
- Fetches suggestions as user types (debounced)
- Displays top suggestions
- Click suggestion navigates to movie detail or fills search input
- Dropdown closes on selection or blur
- Loading and error states handled

**Dependencies:** Issue 7.4, Issue 7.5

---

### Sprint 7 Definition of Done

- [ ] Backend search API uses PostgreSQL FTS on movie metadata
- [ ] Search matches title, description, genres, actors, directors
- [ ] Search results are paginated and ranked by relevance
- [ ] Search vector is updated when metadata changes
- [ ] Search suggestions API returns quick results
- [ ] Frontend search page displays results with loading/error/empty states
- [ ] Frontend search suggestions dropdown works with debouncing
- [ ] Unit and integration tests cover search service and API
- [ ] Postman collection includes search endpoints
- [ ] Manual test: search returns expected movies from seeded data

---

## Sprint 8 - User Library Features

**Goal:** Implement watchlist, watch history, and rating features with APIs and UI.

**Related Stories:** US-013, US-014, US-015  
**Related NFRs:** NFR-007, NFR-016

### Issue 8.1: Backend Watchlist Service and API

**Description:**  
Implement watchlist service with add, remove, list operations per API-CONTRACT.yaml (Watchlist tag): `PUT /api/v1/me/watchlist/{movieId}` (add), `DELETE /api/v1/me/watchlist/{movieId}` (remove), `GET /api/v1/me/watchlist` (list). Require authentication. Enforce ownership.

**Acceptance Criteria:**
- PUT /api/v1/me/watchlist/{movieId} adds movie to watchlist (not POST)
- DELETE /api/v1/me/watchlist/{movieId} removes movie from watchlist
- GET /api/v1/me/watchlist lists user's watchlist (returns array, not paginated per API-CONTRACT)
- Duplicate prevention on add
- Watchlist scoped to authenticated user
- Unit tests cover service logic
- Integration tests cover ownership enforcement

**Dependencies:** Sprint 6 complete (can run in parallel with Sprint 7)

---

### Issue 8.2: Backend Watch History Service and API

**Description:**  
Implement watch history service that records when user starts watching a movie per API-CONTRACT.yaml (History tag): `GET /api/v1/me/history` returns paginated watch history. Require authentication. Enforce ownership.

**Acceptance Criteria:**
- Endpoint path is `/api/v1/me/history` (not `/api/v1/users/me/watch-history`) per API-CONTRACT
- Watch history entry created when user starts watching (first progress update)
- User can list their own watch history ordered by last_watched_at descending
- Watch history scoped to authenticated user
- Pagination supported per HistoryPageResponse schema
- Unit tests cover service logic
- Integration tests cover ownership enforcement

**Dependencies:** Issue 6.3 (progress tracking triggers history)

---

### Issue 8.3: Backend Rating Service and API

**Description:**  
Implement rating service with create/update operation per API-CONTRACT.yaml (Rating tag): `PUT /api/v1/movies/{movieId}/rating` creates or updates user's rating. Require authentication. Enforce ownership.

**Acceptance Criteria:**
- Endpoint is PUT /api/v1/movies/{movieId}/rating (singular "rating", not "ratings") per API-CONTRACT
- User can create or update their own rating for a movie
- Rating value validated per RatingRequest schema (1-5 stars)
- Users cannot rate on behalf of another user
- Aggregate rating calculation available (mean, count) returned in RatingResponse
- Unit tests cover validation and service logic
- Integration tests cover ownership enforcement
- Note: GET endpoint for retrieving user's own rating is NOT in API-CONTRACT; MovieDetail.userRating provides this

**Dependencies:** Sprint 6 complete

---

### Issue 8.4: Backend Aggregate Rating in Movie APIs

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

**Description:**  
Create watch history page showing user's recently watched movies. Display last watched timestamp.

**Acceptance Criteria:**
- Watch history page fetches and displays user's history
- Movies ordered by last watched descending
- Movie cards link to detail or watch page
- Loading, empty, error states handled
- Pagination if list is long

**Dependencies:** Issue 8.2

---

### Issue 8.7: Frontend Rating UI

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

### Sprint 8 Definition of Done

- [ ] User can add and remove movies from watchlist
- [ ] User can view their watchlist with pagination
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

---

## Sprint 9 - Admin Operations

**Goal:** Implement admin encoding status dashboard and failed job retry.

**Related Stories:** US-016  
**Related NFRs:** NFR-007, NFR-011, NFR-016

### Issue 9.1: Backend Video Asset List API for Admin

**Description:**  
Implement `GET /api/v1/admin/video-assets` endpoint that returns all video assets with status, metadata, failure reason. Require `ROLE_ADMIN`. Support filtering by status and movie ID.

**Acceptance Criteria:**
- Endpoint requires `ROLE_ADMIN`
- Returns video assets with movie title, status, metadata, failure reason
- Supports filtering by status (e.g., `?status=FAILED`)
- Supports filtering by movie ID
- Current/newest/superseded are derived per ADR-010; historical READY is not a playback promise and purged historical keys may dangle. Implement the contract's movieId filter here, not in Sprint 4
- Before implementation, settle the admin classification/eligibility projection and UI with an authorized contract follow-up if new fields are needed; never infer movie-wide current/newest from a paginated subset
- Pagination supported
- Integration tests cover admin authorization

**Dependencies:** Sprint 8 complete

---

### Issue 9.2: Backend Encoding Retry Service

**Description:**  
Retry only the newest `(created_at,id)` asset of its movie when FAILED. Serialize per movie with the same movie-row lock order as upload commit, conditionally claim FAILED -> QUEUED, clear failure, insert next attempt/published_at NULL and commit before eager publish/relay.

**Acceptance Criteria:**
- Retry service accepts video asset ID
- Non-newest/non-FAILED/lost concurrent claim returns 409 VIDEO_ASSET_NOT_RETRYABLE; missing asset 404
- Asset transition and next job insert are atomic; attempt increases per asset, source raw retained, expected-state predicates on every write
- After commit publish the same version-1 envelope with confirms/returns; publish failure remains accepted QUEUED for relay, never deletes raw
- PostgreSQL tests: double retry yields one 202/one 409, retry versus new upload preserves one-in-flight, rollback publishes nothing, late worker/status writes cannot regress state

**Dependencies:** Issue 9.1

---

### Issue 9.3: Backend Encoding Retry API

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

**Description:**  
Create admin dashboard page showing video assets with status. Display movie title, upload date, status, duration, failure reason. Support filtering by status.

**Acceptance Criteria:**
- Dashboard fetches video assets from admin API
- Table displays movie title, status, metadata, failure reason
- Filter dropdown for status (All, Uploaded, Queued, Processing, Ready, Failed)
- Loading, empty, error states handled
- Pagination supported
- Retry button enabled only for newest FAILED; label superseded history and handle 409 refresh. A historical READY row may have manually purged objects and is not current playback
- Admin route protected with `ROLE_ADMIN` guard

**Dependencies:** Issue 9.1

---

### Issue 9.5: Frontend Admin Retry UI

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

---

## Sprint 10 - Polish

**Goal:** Finalize error handling, documentation, OpenAPI/Swagger, Postman collection, and VPS deployment preparation.

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
- 401 errors trigger logout and redirect to login
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
- [ ] Project is ready for local demo and VPS deployment

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
- **R-002 (FFmpeg behavior):** Sprint 5 isolates worker implementation with sample video verification before dependent features.
- **R-003 (VPS resources):** Sprint 1 production Compose includes resource limits; NFR-002 validated before VPS deployment.
- **R-004 (API contract drift):** Sprint 10 reviews OpenAPI vs. implementation; Postman collection serves as contract test.
- **R-006 (Storage leaks):** 4.4 compensates uncommitted raw PUTs; 5.7 cleans failed/orphan/lost-finish prefixes and temp; deployment runbook owns manual superseded purge and orphan audit. Keep Open until failure-path tests exist.
- **R-007 (Queue failures):** 4.5 commits job-row outbox before publish and relays pending publication; 5.3/5.7 conditionally claim/finish and recover startup orphans with deadlines; Sprint 9 retries only newest FAILED. Never time out QUEUED by age.
- **R-008 (Auth/RBAC):** Sprint 2 completed before catalog and upload features; authorization tests required.
- **R-012 (Tests lag):** Definition of Done includes tests for every sprint; traceability matrix tracks coverage.
- **R-014 (HLS headers):** Sprint 6 verifies MIME types and playback in browser; Nginx config reviewed.
- **R-015 (Progress durability):** Sprint 6 defines Redis buffer + PostgreSQL flush; integration test confirms sync.

---

## Assumptions

1. Backend and worker use Spring Boot 4.x with Java 21 (ADR-002, amended 2026-10-18); frontend uses React 18 with TypeScript.
2. Docker Compose is used for local and VPS deployment; no Kubernetes.
3. Single-quality HLS output (e.g., 720p) is sufficient for MVP; multi-quality deferred.
4. PostgreSQL full-text search is sufficient; Elasticsearch deferred.
5. MinIO buckets are private; HLS delivered through authenticated backend proxy.
6. Worker concurrency is 1 for MVP stability.
7. Redis is used for cache and progress buffer only; PostgreSQL is source of truth.
8. Frontend tokens stored in localStorage or chosen secure mechanism; strategy decided in Sprint 2.
9. GitHub Issues will be created based on this backlog; issue numbers referenced in commits.
10. Sample video files available for testing worker encoding flow.

---

## Blockers and Dependencies

**External Dependencies:**
- FFmpeg and FFprobe must be available in worker Docker image
- Oracle Cloud Free Tier VPS account for demo deployment (Phase 5)
- Let's Encrypt for HTTPS (deferred to Phase 5 deployment)

**Internal Dependencies:**
- Auth (Sprint 2) must complete before catalog, upload, streaming features
- Upload (Sprint 4) must complete before worker (Sprint 5)
- Worker (Sprint 5) must complete before streaming (Sprint 6)
- Search (Sprint 7) and Library (Sprint 8) can run in parallel after Sprint 6
- Admin Operations (Sprint 9) depends on upload and worker completion

**Known Risks:**
- FFmpeg command may differ on ARM vs x86 architectures (mitigate with early worker testing)
- VPS resource constraints may require tuning (mitigate with resource limits and monitoring)
- HLS playback may require MIME type and CORS configuration (mitigate with early browser testing)

---

## Next Steps After Sprint 10

After Sprint 10 completion, the project enters **Phase 4 - Testing and Hardening** and **Phase 5 - Deployment and Operations**:

1. Execute comprehensive Postman/Newman tests
2. Create manual E2E test checklist and execute
3. Deploy to Oracle Cloud VPS
4. Configure HTTPS with Let's Encrypt
5. Document backup, restore, rollback procedures
6. Create runbook for operations
7. Verify demo stability over HTTPS
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
| 2026-10-26 | 1.2 | Contract-first API documentation (Issue #120, owner decision 2026-10-18): the Global Definition of Done requires `API-CONTRACT.yaml` updated or confirmed with responses validated in tests instead of OpenAPI annotations, and Issue 10.2 serves the frozen contract in Swagger UI instead of generating a code-first document |

---

**End of Backlog**
