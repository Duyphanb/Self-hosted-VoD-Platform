# User Stories

## Story ID Convention

Use stable IDs in the format `US-###`. Do not renumber existing stories after implementation begins.

## MVP Stories

### US-001 - Register Account

As a guest, I want to create an account so that I can use protected VoD features.

Acceptance criteria:

- Registration accepts email, password, and display name.
- Invalid email, weak password, or duplicate email returns a validation error.
- Passwords are stored as BCrypt hashes, never as plaintext.
- New users receive `ROLE_USER` by default.
- The registration response does not expose password data.

### US-002 - Login And Receive Token

As a registered user, I want to log in so that I can access protected APIs.

Acceptance criteria:

- Login accepts valid credentials and returns a JWT-based authentication result with access and refresh tokens in the JSON response body.
- Invalid credentials return a generic authentication failure.
- JWT secret and expiration settings come from environment variables.
- Protected APIs reject missing, expired, or malformed tokens.

### US-003 - Logout

As a logged-in user, I want to log out so that the frontend clears my authenticated session.

Acceptance criteria:

- The frontend removes local authentication state on logout.
- Protected pages redirect unauthenticated users to login.
- The backend behavior is documented if tokens are stateless for MVP.

### US-004 - Role-Based Access Control

As an admin, I want admin-only operations protected so that regular users cannot manage media.

Acceptance criteria:

- Admin upload, metadata mutation, encoding retry, and admin dashboards require `ROLE_ADMIN`.
- Regular users receive a forbidden response for admin APIs.
- Frontend admin routes are hidden or blocked for non-admin users.

### US-005 - Browse Movies

As a user, I want to browse available movies so that I can choose something to watch.

Acceptance criteria:

- Movie list returns persisted movies from PostgreSQL.
- Results support pagination.
- Response includes enough metadata for list cards.
- Loading, empty, and error states are handled in the frontend.

### US-006 - View Movie Details

As a user, I want to view movie details so that I can decide whether to watch it.

Acceptance criteria:

- Detail page shows title, description, release year, genres, people metadata, rating summary, and video availability status.
- Missing movie IDs return a not-found response.
- The page does not expose admin-only controls to regular users.

### US-007 - Manage Movie Metadata

As an admin, I want to create, update, and delete movie metadata so that the catalog stays accurate.

Acceptance criteria:

- Admin can create and edit movie title, description, release year, genre, actor, director, and related video asset metadata.
- Required fields are validated.
- Delete behavior is explicit and documented before implementation.
- API responses use DTOs, not JPA entities.

### US-008 - Upload Video

As an admin, I want to upload a video file so that it can be processed for streaming.

Acceptance criteria:

- Upload accepts configured allowed video MIME types only: MP4, MKV, WebM, and MOV for MVP.
- Upload enforces configured size limits.
- Raw video is stored in MinIO.
- A video asset record is created or updated in PostgreSQL.
- An encoding job is published to RabbitMQ.
- Upload returns a status that the admin UI can display.

### US-009 - Process Encoding Job

As the system, I want a worker to process encoding jobs asynchronously so that uploads do not block API requests.

Acceptance criteria:

- Worker consumes jobs from `video-encoding-queue`.
- Worker marks assets as `PROCESSING` before media work begins.
- Worker runs FFprobe to extract useful metadata.
- Worker runs FFmpeg to generate HLS output.
- Worker uploads HLS artifacts to MinIO.
- Worker marks successful jobs as `READY`.
- Worker marks failed jobs as `FAILED` with diagnostic information suitable for admin review.

### US-010 - Play HLS Video

As a logged-in user, I want to play a ready video in the browser so that I can watch it without manual file handling.

Acceptance criteria:

- Playback is allowed only for authenticated users.
- Backend returns playback information for ready assets.
- Frontend plays `.m3u8` HLS output through HLS.js or Video.js.
- Player shows loading, buffering, and error states.
- Not-ready or failed assets are shown with clear status.

### US-011 - Resume Playback

As a user, I want playback to resume near my last watched position so that I can continue watching later.

Acceptance criteria:

- Frontend sends progress updates every 5 to 10 seconds while playing.
- Backend buffers progress in Redis.
- Progress is periodically flushed to PostgreSQL.
- Movie detail or watch page can retrieve the last saved position.
- Continue-watching list shows recently started but unfinished movies.

### US-012 - Search Catalog

As a user, I want to search movies so that I can quickly find relevant content.

Acceptance criteria:

- Search supports title, description, genre, actor, and director.
- PostgreSQL full-text search is used for MVP.
- Search suggestions are available for the frontend.
- Search uses real persisted movie metadata.
- Empty query and no-results behavior are defined.

### US-013 - Manage Watchlist

As a user, I want to add and remove movies from my watchlist so that I can save movies for later.

Acceptance criteria:

- User can add a movie to their watchlist.
- User can remove a movie from their watchlist.
- Watchlist is scoped to the authenticated user.
- Duplicate watchlist entries are prevented.

### US-014 - Watch History

As a user, I want a watch history so that I can see what I have watched recently.

Acceptance criteria:

- Starting or updating playback records a watch history entry.
- User can retrieve their own recently watched movies.
- Users cannot read another user's watch history.

### US-015 - Rate Movie

As a user, I want to rate a movie so that my feedback is stored and reflected in the catalog.

Acceptance criteria:

- User can create or update their own rating for a movie.
- Rating values are validated against the accepted range.
- Aggregate rating can be returned with movie details.
- Users cannot submit ratings for another user.

### US-016 - Admin Encoding Status And Retry

As an admin, I want to inspect video processing status and retry failed jobs so that recoverable processing errors can be handled.

Acceptance criteria:

- Admin dashboard shows uploaded videos and encoding status.
- Failed assets expose safe diagnostic information.
- Retry is allowed only for `FAILED` assets.
- Retry publishes a new encoding job without duplicating movie metadata.

### US-017 - Run Local Stack

As a developer, I want to run the full stack locally so that I can test the complete flow before deployment.

Acceptance criteria:

- Docker Compose starts frontend, backend, worker, PostgreSQL, Redis, RabbitMQ, MinIO, and Nginx when those components exist.
- `.env.example` documents required variables.
- Local smoke test proves health checks and basic connectivity.

### US-018 - Deploy Demo To VPS

As an operator, I want to deploy the app to an Oracle Cloud Free Tier VPS so that the project has a public demo.

Acceptance criteria:

- Deployment instructions cover required VPS packages and environment variables.
- HTTPS is configured through Nginx and Let's Encrypt.
- Named volumes preserve database and object storage data across container restarts.
- Rollback and backup procedures are documented before production-style demo use.

### US-019 - Update User Profile

As a logged-in user, I want to update my display name so that my profile reflects how I want to appear in the app.

Acceptance criteria:

- User can update only their own display name.
- Display name validation matches registration rules.
- Response returns the updated profile DTO.
- Users cannot update email, roles, password, or another user's profile through this endpoint.
