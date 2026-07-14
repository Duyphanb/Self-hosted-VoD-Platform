CREATE TABLE users (
    id uuid PRIMARY KEY,
    email varchar(320) NOT NULL,
    password_hash varchar(255) NOT NULL,
    display_name varchar(100) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ux_users_email UNIQUE (email)
);

CREATE TABLE roles (
    id smallserial PRIMARY KEY,
    name varchar(50) NOT NULL,
    CONSTRAINT ux_roles_name UNIQUE (name)
);

CREATE TABLE user_roles (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id smallint NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE refresh_tokens (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash varchar(255) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT ux_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE TABLE movies (
    id uuid PRIMARY KEY,
    title varchar(255) NOT NULL,
    slug varchar(255) NOT NULL,
    description text NOT NULL,
    release_year int,
    maturity_rating varchar(20),
    poster_object_key varchar(512),
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    search_vector tsvector,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ux_movies_slug UNIQUE (slug)
);

CREATE TABLE genres (
    id uuid PRIMARY KEY,
    name varchar(100) NOT NULL,
    slug varchar(120) NOT NULL,
    CONSTRAINT ux_genres_name UNIQUE (name),
    CONSTRAINT ux_genres_slug UNIQUE (slug)
);

CREATE TABLE movie_genres (
    movie_id uuid NOT NULL REFERENCES movies (id) ON DELETE CASCADE,
    genre_id uuid NOT NULL REFERENCES genres (id) ON DELETE RESTRICT,
    PRIMARY KEY (movie_id, genre_id)
);

CREATE TABLE people (
    id uuid PRIMARY KEY,
    name varchar(255) NOT NULL,
    slug varchar(255) NOT NULL,
    CONSTRAINT ux_people_slug UNIQUE (slug)
);

CREATE TABLE movie_credits (
    movie_id uuid NOT NULL REFERENCES movies (id) ON DELETE CASCADE,
    person_id uuid NOT NULL REFERENCES people (id) ON DELETE RESTRICT,
    credit_role varchar(20) NOT NULL CHECK (credit_role IN ('ACTOR', 'DIRECTOR')),
    sort_order int,
    PRIMARY KEY (movie_id, person_id, credit_role)
);

CREATE TABLE video_assets (
    id uuid PRIMARY KEY,
    movie_id uuid NOT NULL REFERENCES movies (id) ON DELETE CASCADE,
    raw_bucket varchar(100) NOT NULL,
    raw_object_key varchar(512) NOT NULL,
    hls_bucket varchar(100),
    hls_master_object_key varchar(512),
    status varchar(20) NOT NULL CHECK (status IN ('UPLOADED', 'QUEUED', 'PROCESSING', 'READY', 'FAILED')),
    duration_seconds int,
    width int,
    height int,
    codec varchar(100),
    bitrate int,
    failure_reason text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ux_video_assets_raw_object UNIQUE (raw_bucket, raw_object_key)
);

CREATE TABLE encoding_jobs (
    id uuid PRIMARY KEY,
    video_asset_id uuid NOT NULL REFERENCES video_assets (id) ON DELETE CASCADE,
    status varchar(20) NOT NULL CHECK (status IN ('QUEUED', 'PROCESSING', 'READY', 'FAILED')),
    attempt int NOT NULL CHECK (attempt >= 1),
    error_message text,
    queued_at timestamptz NOT NULL,
    started_at timestamptz,
    finished_at timestamptz,
    CONSTRAINT ux_encoding_jobs_asset_attempt UNIQUE (video_asset_id, attempt)
);

CREATE TABLE playback_progress (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    movie_id uuid NOT NULL REFERENCES movies (id) ON DELETE CASCADE,
    current_seconds int NOT NULL CHECK (current_seconds >= 0),
    duration_seconds int CHECK (duration_seconds >= 0),
    finished boolean NOT NULL DEFAULT false,
    last_played_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, movie_id)
);

CREATE TABLE watchlist_items (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    movie_id uuid NOT NULL REFERENCES movies (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    CONSTRAINT ux_watchlist_items_user_movie UNIQUE (user_id, movie_id)
);

CREATE TABLE watch_history (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    movie_id uuid NOT NULL REFERENCES movies (id) ON DELETE CASCADE,
    started_at timestamptz NOT NULL,
    last_watched_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT ux_watch_history_user_movie UNIQUE (user_id, movie_id)
);

CREATE TABLE ratings (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    movie_id uuid NOT NULL REFERENCES movies (id) ON DELETE CASCADE,
    rating int NOT NULL CHECK (rating >= 1 AND rating <= 5),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ux_ratings_user_movie UNIQUE (user_id, movie_id)
);

CREATE INDEX ix_users_status ON users (status);
CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_expires_at ON refresh_tokens (expires_at);
CREATE INDEX ix_movies_search_vector ON movies USING GIN (search_vector);
CREATE INDEX ix_movies_status ON movies (status);
CREATE INDEX ix_movies_release_year ON movies (release_year);
CREATE INDEX ix_people_name ON people (name);
CREATE INDEX ix_movie_credits_person_id ON movie_credits (person_id);
CREATE INDEX ix_movie_credits_role ON movie_credits (credit_role);
CREATE INDEX ix_video_assets_movie_id ON video_assets (movie_id);
CREATE INDEX ix_video_assets_status ON video_assets (status);
CREATE UNIQUE INDEX ux_video_assets_hls_master ON video_assets (hls_bucket, hls_master_object_key) WHERE hls_master_object_key IS NOT NULL;
CREATE INDEX ix_encoding_jobs_video_asset_id ON encoding_jobs (video_asset_id);
CREATE INDEX ix_encoding_jobs_status ON encoding_jobs (status);
CREATE INDEX ix_playback_progress_user_last_played ON playback_progress (user_id, last_played_at);
CREATE INDEX ix_watchlist_items_user_created ON watchlist_items (user_id, created_at);
CREATE INDEX ix_watch_history_user_last_watched ON watch_history (user_id, last_watched_at);
CREATE INDEX ix_ratings_movie_id ON ratings (movie_id);
