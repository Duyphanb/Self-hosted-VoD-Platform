ALTER TABLE titles ADD COLUMN content_type varchar(20);
ALTER TABLE titles ADD COLUMN part_label varchar(100);
UPDATE titles SET content_type = 'MOVIE';
ALTER TABLE titles ALTER COLUMN content_type SET NOT NULL;
ALTER TABLE titles ADD CONSTRAINT ck_titles_content_type
    CHECK (content_type IN ('MOVIE', 'TV_SEASON', 'OVA', 'ONA', 'SPECIAL'));

CREATE TABLE episodes (
    id uuid PRIMARY KEY,
    title_id uuid NOT NULL REFERENCES titles (id) ON DELETE RESTRICT,
    ordinal integer NOT NULL CHECK (ordinal > 0),
    display_label varchar(50),
    name varchar(255),
    synopsis text,
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ux_episodes_title_ordinal UNIQUE (title_id, ordinal) DEFERRABLE INITIALLY IMMEDIATE
);

INSERT INTO episodes (id, title_id, ordinal, display_label, status, created_at, updated_at)
SELECT gen_random_uuid(), id, 1, '1', 'PUBLISHED', created_at, updated_at
FROM titles;

ALTER TABLE video_assets DROP CONSTRAINT video_assets_movie_id_fkey;
ALTER TABLE playback_progress DROP CONSTRAINT playback_progress_movie_id_fkey;
ALTER TABLE watch_history DROP CONSTRAINT watch_history_movie_id_fkey;

UPDATE video_assets AS a SET movie_id = e.id FROM episodes AS e WHERE a.movie_id = e.title_id;
UPDATE playback_progress AS p SET movie_id = e.id FROM episodes AS e WHERE p.movie_id = e.title_id;
UPDATE watch_history AS h SET movie_id = e.id FROM episodes AS e WHERE h.movie_id = e.title_id;

ALTER TABLE video_assets RENAME COLUMN movie_id TO episode_id;
ALTER TABLE playback_progress RENAME COLUMN movie_id TO episode_id;
ALTER TABLE watch_history RENAME COLUMN movie_id TO episode_id;

ALTER TABLE video_assets ADD CONSTRAINT video_assets_episode_id_fkey
    FOREIGN KEY (episode_id) REFERENCES episodes (id) ON DELETE RESTRICT;
ALTER TABLE playback_progress ADD CONSTRAINT playback_progress_episode_id_fkey
    FOREIGN KEY (episode_id) REFERENCES episodes (id) ON DELETE RESTRICT;
ALTER TABLE watch_history ADD CONSTRAINT watch_history_episode_id_fkey
    FOREIGN KEY (episode_id) REFERENCES episodes (id) ON DELETE RESTRICT;
ALTER TABLE watch_history RENAME CONSTRAINT ux_watch_history_user_movie TO ux_watch_history_user_episode;

ALTER INDEX ix_video_assets_movie_id RENAME TO ix_video_assets_episode_id;
ALTER INDEX ux_video_assets_movie_in_flight RENAME TO ux_video_assets_episode_in_flight;
CREATE INDEX ix_video_assets_episode_ready
    ON video_assets (episode_id, created_at DESC, id DESC) WHERE status = 'READY';

CREATE VIEW current_episode_assets AS
SELECT DISTINCT ON (episode_id) episode_id, id AS asset_id, duration_seconds
FROM video_assets
WHERE status = 'READY'
ORDER BY episode_id, created_at DESC, id DESC;

DROP INDEX ix_playback_progress_user_last_played;
CREATE INDEX ix_playback_progress_user_last_played
    ON playback_progress (user_id, last_played_at DESC, episode_id DESC);
