-- Equivalent test projection for the entity-generated H2 tables.
CREATE VIEW current_episode_assets AS
SELECT episode_id, asset_id, duration_seconds
FROM (
    SELECT episode_id, id AS asset_id, duration_seconds,
           ROW_NUMBER() OVER (PARTITION BY episode_id ORDER BY created_at DESC, id DESC) AS position
    FROM video_assets
    WHERE status = 'READY'
) ranked
WHERE position = 1;
