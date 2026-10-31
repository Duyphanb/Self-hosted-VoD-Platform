-- ADR-010 V5 (D11, D16, D17, D24). Stop for explicit repair of legacy conflicts;
-- never delete assets or choose a winner. The lock keeps preflight and index
-- creation atomic within Flyway's PostgreSQL migration transaction.
LOCK TABLE video_assets IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
    affected bigint;
    sample text;
BEGIN
    SELECT count(*) INTO affected
      FROM (SELECT movie_id FROM video_assets
             WHERE status IN ('UPLOADED', 'QUEUED', 'PROCESSING')
             GROUP BY movie_id HAVING count(*) > 1) duplicates;
    IF affected > 0 THEN
        SELECT string_agg(movie_id::text, ', ' ORDER BY movie_id) INTO sample
          FROM (SELECT movie_id FROM video_assets
                 WHERE status IN ('UPLOADED', 'QUEUED', 'PROCESSING')
                 GROUP BY movie_id HAVING count(*) > 1
                 ORDER BY movie_id LIMIT 20) first_movies;
        RAISE EXCEPTION 'Movies with more than one in-flight video asset: % (first: %); repair them through an approved data decision before retrying migration V5', affected, sample;
    END IF;
END
$$;

-- No default or backfill: existing QUEUED jobs remain unpublished/relay-eligible.
ALTER TABLE encoding_jobs ADD COLUMN published_at timestamptz NULL;
CREATE UNIQUE INDEX ux_video_assets_movie_in_flight
    ON video_assets (movie_id)
    WHERE status IN ('UPLOADED', 'QUEUED', 'PROCESSING');
