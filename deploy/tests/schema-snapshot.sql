CREATE TABLE public.encoding_jobs (
    id uuid NOT NULL,
    video_asset_id uuid NOT NULL,
    status character varying(20) NOT NULL,
    attempt integer NOT NULL,
    error_message text,
    queued_at timestamp with time zone NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    published_at timestamp with time zone,
    CONSTRAINT encoding_jobs_attempt_check CHECK ((attempt >= 1)),
    CONSTRAINT encoding_jobs_status_check CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying, 'READY'::character varying, 'FAILED'::character varying])::text[])))
);

CREATE TABLE public.genres (
    id uuid NOT NULL,
    name character varying(100) NOT NULL,
    slug character varying(120) NOT NULL
);

CREATE TABLE public.movie_credits (
    movie_id uuid NOT NULL,
    person_id uuid NOT NULL,
    credit_role character varying(20) NOT NULL,
    sort_order integer,
    CONSTRAINT movie_credits_credit_role_check CHECK (((credit_role)::text = ANY ((ARRAY['ACTOR'::character varying, 'DIRECTOR'::character varying])::text[])))
);

CREATE TABLE public.movie_genres (
    movie_id uuid NOT NULL,
    genre_id uuid NOT NULL
);

CREATE TABLE public.movies (
    id uuid NOT NULL,
    title character varying(255) NOT NULL,
    slug character varying(255) NOT NULL,
    description text NOT NULL,
    release_year integer,
    maturity_rating character varying(20),
    poster_object_key character varying(512),
    status character varying(20) NOT NULL,
    search_vector tsvector,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT movies_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying, 'ARCHIVED'::character varying])::text[])))
);

CREATE TABLE public.people (
    id uuid NOT NULL,
    name character varying(255) NOT NULL,
    slug character varying(255) NOT NULL
);

CREATE TABLE public.playback_progress (
    user_id uuid NOT NULL,
    movie_id uuid NOT NULL,
    current_seconds integer NOT NULL,
    duration_seconds integer,
    finished boolean DEFAULT false NOT NULL,
    last_played_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT playback_progress_current_seconds_check CHECK ((current_seconds >= 0)),
    CONSTRAINT playback_progress_duration_seconds_check CHECK ((duration_seconds >= 0))
);

CREATE TABLE public.ratings (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    movie_id uuid NOT NULL,
    rating integer NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ratings_rating_check CHECK (((rating >= 1) AND (rating <= 5)))
);

CREATE TABLE public.refresh_tokens (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_hash character varying(255) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE public.roles (
    id smallint NOT NULL,
    name character varying(50) NOT NULL
);

CREATE SEQUENCE public.roles_id_seq
    AS smallint
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.roles_id_seq OWNED BY public.roles.id;

CREATE TABLE public.user_roles (
    user_id uuid NOT NULL,
    role_id smallint NOT NULL
);

CREATE TABLE public.users (
    id uuid NOT NULL,
    email character varying(320) NOT NULL,
    password_hash character varying(255) NOT NULL,
    display_name character varying(100) NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT users_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[])))
);

CREATE TABLE public.video_assets (
    id uuid NOT NULL,
    movie_id uuid NOT NULL,
    raw_bucket character varying(100) NOT NULL,
    raw_object_key character varying(512) NOT NULL,
    hls_bucket character varying(100),
    hls_master_object_key character varying(512),
    status character varying(20) NOT NULL,
    duration_seconds integer,
    width integer,
    height integer,
    codec character varying(100),
    bitrate integer,
    failure_reason text,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT video_assets_status_check CHECK (((status)::text = ANY ((ARRAY['UPLOADED'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'READY'::character varying, 'FAILED'::character varying])::text[])))
);

CREATE TABLE public.watch_history (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    movie_id uuid NOT NULL,
    started_at timestamp with time zone NOT NULL,
    last_watched_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone
);

CREATE TABLE public.watchlist_items (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    movie_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);

ALTER TABLE ONLY public.roles ALTER COLUMN id SET DEFAULT nextval('public.roles_id_seq'::regclass);

ALTER TABLE ONLY public.encoding_jobs
    ADD CONSTRAINT encoding_jobs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.genres
    ADD CONSTRAINT genres_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.movie_credits
    ADD CONSTRAINT movie_credits_pkey PRIMARY KEY (movie_id, person_id, credit_role);

ALTER TABLE ONLY public.movie_genres
    ADD CONSTRAINT movie_genres_pkey PRIMARY KEY (movie_id, genre_id);

ALTER TABLE ONLY public.movies
    ADD CONSTRAINT movies_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.people
    ADD CONSTRAINT people_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.playback_progress
    ADD CONSTRAINT playback_progress_pkey PRIMARY KEY (user_id, movie_id);

ALTER TABLE ONLY public.ratings
    ADD CONSTRAINT ratings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.user_roles
    ADD CONSTRAINT user_roles_pkey PRIMARY KEY (user_id, role_id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.encoding_jobs
    ADD CONSTRAINT ux_encoding_jobs_asset_attempt UNIQUE (video_asset_id, attempt);

ALTER TABLE ONLY public.genres
    ADD CONSTRAINT ux_genres_name UNIQUE (name);

ALTER TABLE ONLY public.genres
    ADD CONSTRAINT ux_genres_slug UNIQUE (slug);

ALTER TABLE ONLY public.movies
    ADD CONSTRAINT ux_movies_slug UNIQUE (slug);

ALTER TABLE ONLY public.people
    ADD CONSTRAINT ux_people_slug UNIQUE (slug);

ALTER TABLE ONLY public.ratings
    ADD CONSTRAINT ux_ratings_user_movie UNIQUE (user_id, movie_id);

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT ux_refresh_tokens_token_hash UNIQUE (token_hash);

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT ux_roles_name UNIQUE (name);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT ux_users_email UNIQUE (email);

ALTER TABLE ONLY public.video_assets
    ADD CONSTRAINT ux_video_assets_raw_object UNIQUE (raw_bucket, raw_object_key);

ALTER TABLE ONLY public.watch_history
    ADD CONSTRAINT ux_watch_history_user_movie UNIQUE (user_id, movie_id);

ALTER TABLE ONLY public.watchlist_items
    ADD CONSTRAINT ux_watchlist_items_user_movie UNIQUE (user_id, movie_id);

ALTER TABLE ONLY public.video_assets
    ADD CONSTRAINT video_assets_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.watch_history
    ADD CONSTRAINT watch_history_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.watchlist_items
    ADD CONSTRAINT watchlist_items_pkey PRIMARY KEY (id);

CREATE INDEX ix_encoding_jobs_status ON public.encoding_jobs USING btree (status);

CREATE INDEX ix_encoding_jobs_video_asset_id ON public.encoding_jobs USING btree (video_asset_id);

CREATE INDEX ix_movie_credits_person_id ON public.movie_credits USING btree (person_id);

CREATE INDEX ix_movie_credits_role ON public.movie_credits USING btree (credit_role);

CREATE INDEX ix_movies_release_year ON public.movies USING btree (release_year);

CREATE INDEX ix_movies_search_vector ON public.movies USING gin (search_vector);

CREATE INDEX ix_movies_status ON public.movies USING btree (status);

CREATE INDEX ix_people_name ON public.people USING btree (name);

CREATE INDEX ix_playback_progress_user_last_played ON public.playback_progress USING btree (user_id, last_played_at);

CREATE INDEX ix_ratings_movie_id ON public.ratings USING btree (movie_id);

CREATE INDEX ix_refresh_tokens_expires_at ON public.refresh_tokens USING btree (expires_at);

CREATE INDEX ix_refresh_tokens_user_id ON public.refresh_tokens USING btree (user_id);

CREATE INDEX ix_users_status ON public.users USING btree (status);

CREATE INDEX ix_video_assets_movie_id ON public.video_assets USING btree (movie_id);

CREATE INDEX ix_video_assets_status ON public.video_assets USING btree (status);

CREATE INDEX ix_watch_history_user_last_watched ON public.watch_history USING btree (user_id, last_watched_at);

CREATE INDEX ix_watchlist_items_user_created ON public.watchlist_items USING btree (user_id, created_at);

CREATE UNIQUE INDEX ux_users_email_identity ON public.users USING btree (lower((email)::text));

CREATE UNIQUE INDEX ux_video_assets_hls_master ON public.video_assets USING btree (hls_bucket, hls_master_object_key) WHERE (hls_master_object_key IS NOT NULL);

CREATE UNIQUE INDEX ux_video_assets_movie_in_flight ON public.video_assets USING btree (movie_id) WHERE ((status)::text = ANY ((ARRAY['UPLOADED'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying])::text[]));

ALTER TABLE ONLY public.encoding_jobs
    ADD CONSTRAINT encoding_jobs_video_asset_id_fkey FOREIGN KEY (video_asset_id) REFERENCES public.video_assets(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.movie_credits
    ADD CONSTRAINT movie_credits_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES public.movies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.movie_credits
    ADD CONSTRAINT movie_credits_person_id_fkey FOREIGN KEY (person_id) REFERENCES public.people(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.movie_genres
    ADD CONSTRAINT movie_genres_genre_id_fkey FOREIGN KEY (genre_id) REFERENCES public.genres(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.movie_genres
    ADD CONSTRAINT movie_genres_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES public.movies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.playback_progress
    ADD CONSTRAINT playback_progress_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES public.movies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.playback_progress
    ADD CONSTRAINT playback_progress_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.ratings
    ADD CONSTRAINT ratings_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES public.movies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.ratings
    ADD CONSTRAINT ratings_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.user_roles
    ADD CONSTRAINT user_roles_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.user_roles
    ADD CONSTRAINT user_roles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.video_assets
    ADD CONSTRAINT video_assets_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES public.movies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.watch_history
    ADD CONSTRAINT watch_history_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES public.movies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.watch_history
    ADD CONSTRAINT watch_history_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.watchlist_items
    ADD CONSTRAINT watchlist_items_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES public.movies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.watchlist_items
    ADD CONSTRAINT watchlist_items_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
