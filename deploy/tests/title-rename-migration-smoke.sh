#!/usr/bin/env sh
set -eu

root="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
container="vod-title-rename-migration-$$"
work="$(mktemp -d)"
created=0
cleanup() {
    if [ "$created" = 1 ]; then docker rm -f -v "$container" >/dev/null; fi
    rm -f "$work/before.sql" "$work/before.sql.raw" "$work/after.sql" "$work/after.sql.raw" "$work/flyway.log"
    rmdir "$work"
}
trap cleanup EXIT
trap 'exit 1' INT TERM

docker run -d --name "$container" --network none \
    -e POSTGRES_USER=fixture -e POSTGRES_PASSWORD=fixture-only -e POSTGRES_DB=fixture \
    -e 'POSTGRES_INITDB_ARGS=--locale-provider=icu --icu-locale=vi' \
    postgres:16.15-alpine@sha256:0cefaabd882e42bcb2a2f3dc56d3ce4770560ba62fd7f197f1a2e008fd5731ab >/dev/null
created=1
attempt=0
until docker exec "$container" pg_isready -h 127.0.0.1 -U fixture -d fixture >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 60 ] || exit 1
    sleep 1
done

sql() { docker exec -i "$container" psql -X -qAt -v ON_ERROR_STOP=1 -U fixture -d fixture "$@"; }
migrate() {
    docker run --rm --network "container:$container" \
        -v "$root/backend/common/src/main/resources/db/migration:/flyway/sql:ro" \
        -v "$root/deploy/flyway:/flyway/callbacks:ro" \
        -e FLYWAY_URL=jdbc:postgresql://localhost:5432/fixture \
        -e FLYWAY_USER=fixture_owner -e FLYWAY_PASSWORD=owner-fixture-only \
        -e FLYWAY_PLACEHOLDERS_BACKEND_ROLE=fixture_backend -e FLYWAY_PLACEHOLDERS_WORKER_ROLE=fixture_worker \
        flyway/flyway:12.10.0 -locations=filesystem:/flyway/sql,filesystem:/flyway/callbacks \
        -validateMigrationNaming=true "-target=$1" migrate >"$work/flyway.log" 2>&1
}

sql <<'SQL'
CREATE ROLE fixture_owner LOGIN PASSWORD 'owner-fixture-only';
CREATE ROLE fixture_backend;
CREATE ROLE fixture_worker;
ALTER SCHEMA public OWNER TO fixture_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO fixture_backend, fixture_worker;
SQL
migrate 6 || { cat "$work/flyway.log" >&2; exit 1; }

# Seed the V6 names deliberately. Every affected relation carries data across V7.
sql <<'SQL'
SELECT gen_random_uuid() AS uid, gen_random_uuid() AS mid, gen_random_uuid() AS gid,
       gen_random_uuid() AS pid, gen_random_uuid() AS aid \gset
INSERT INTO users (id, email, password_hash, display_name, status, created_at, updated_at)
VALUES (:'uid', 'fixture@example.test', 'fixture', 'Viewer', 'ACTIVE', now(), now());
INSERT INTO movies (id, title, slug, description, status, search_vector, created_at, updated_at)
VALUES (:'mid', 'Tiêu đề', 'fixture-title', 'Populated V6', 'PUBLISHED', to_tsvector('simple', 'fixture'), now(), now());
INSERT INTO genres (id, name, slug) VALUES (:'gid', 'Action', 'action');
INSERT INTO people (id, name, slug) VALUES (:'pid', 'Actor', 'actor');
INSERT INTO movie_genres (movie_id, genre_id) VALUES (:'mid', :'gid');
INSERT INTO movie_credits (movie_id, person_id, credit_role, sort_order) VALUES (:'mid', :'pid', 'ACTOR', 1);
INSERT INTO ratings (id, user_id, movie_id, rating, created_at, updated_at)
VALUES (gen_random_uuid(), :'uid', :'mid', 4, now(), now());
INSERT INTO watchlist_items (id, user_id, movie_id, created_at) VALUES (gen_random_uuid(), :'uid', :'mid', now());
INSERT INTO video_assets (id, movie_id, raw_bucket, raw_object_key, status, created_at, updated_at)
VALUES (:'aid', :'mid', 'fixture', 'raw.mp4', 'QUEUED', now(), now());
INSERT INTO encoding_jobs (id, video_asset_id, status, attempt, queued_at, published_at)
VALUES (gen_random_uuid(), :'aid', 'QUEUED', 1, now(), now());
INSERT INTO playback_progress (user_id, movie_id, current_seconds, duration_seconds, finished,
                               last_played_at, created_at, updated_at)
VALUES (:'uid', :'mid', 90, 100, true, now(), now(), now());
INSERT INTO watch_history (id, user_id, movie_id, started_at, last_watched_at, completed_at)
VALUES (gen_random_uuid(), :'uid', :'mid', now(), now(), now());
SQL

rows() {
    catalog="$1"
    genres="$2"
    credits="$3"
    key="$4"
    for relation in "$catalog" "$genres" "$credits" ratings watchlist_items \
        video_assets encoding_jobs playback_progress watch_history; do
        case "$relation" in
            "$genres"|"$credits"|ratings|watchlist_items)
                row="(to_jsonb(r) - '$key') || jsonb_build_object('title_id', r.$key)"
                ;;
            *) row="to_jsonb(r)" ;;
        esac
        sql -c "SELECT $row AS row_data FROM $relation r ORDER BY row_data;"
    done
}

schema() {
    docker exec "$container" pg_dump -U fixture -d fixture --schema-only >"$1.raw"
    sed '/^\\restrict /d; /^\\unrestrict /d' "$1.raw" >"$1"
    rm -f "$1.raw"
}

# Force a late index-name collision. Flyway must roll back every rename and its history row.
before_rows="$(rows movies movie_genres movie_credits movie_id)"
sql -c 'SET ROLE fixture_owner; CREATE INDEX ix_titles_status ON users (status);'
schema "$work/before.sql"
if migrate 7; then
    printf '%s\n' 'FAIL: V7 accepted a conflicting index name' >&2
    exit 1
fi
grep -q 'relation "ix_titles_status" already exists' "$work/flyway.log"
schema "$work/after.sql"
cmp "$work/before.sql" "$work/after.sql"
[ "$(rows movies movie_genres movie_credits movie_id)" = "$before_rows" ]
[ "$(sql -c "SELECT count(*) FROM flyway_schema_history WHERE version = '7'")" = 0 ]
printf '%s\n' 'PASS: failed V7 rolled back schema, ACLs, data and history'

sql -c 'SET ROLE fixture_owner; DROP INDEX ix_titles_status;'
# Remove one expected grant so that the check below proves the callback ran on the V7 upgrade itself.
sql -c 'SET ROLE fixture_owner; REVOKE SELECT ON movies FROM fixture_backend;'
[ "$(sql -c "SELECT has_table_privilege('fixture_backend', 'public.movies', 'SELECT')")" = f ]
migrate 7 || { cat "$work/flyway.log" >&2; exit 1; }
[ "$(rows titles title_genres title_credits title_id)" = "$before_rows" ]
[ "$(sql -c "SELECT count(*) FROM flyway_schema_history WHERE version = '7' AND success")" = 1 ]
[ "$(sql -c "SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND confrelid = 'titles'::regclass")" = 7 ]
printf '%s\n' 'PASS: populated V6 upgraded to V7 with all rows and seven incoming foreign keys'

# Verify each table privilege separately; PostgreSQL treats a list of privileges as any-of.
violations="$(sql <<'SQL'
SELECT count(*)
FROM pg_tables AS t
CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) AS p(priv)
WHERE t.schemaname = 'public'
  AND (has_table_privilege('fixture_backend', format('public.%I', t.tablename), p.priv) <>
         (t.tablename <> 'flyway_schema_history' AND p.priv IN ('SELECT', 'INSERT', 'UPDATE', 'DELETE'))
    OR has_table_privilege('fixture_worker', format('public.%I', t.tablename), p.priv) <>
         (t.tablename IN ('encoding_jobs', 'video_assets') AND p.priv IN ('SELECT', 'UPDATE')));
SQL
)"
[ "$violations" = 0 ]
printf '%s\n' 'PASS: production Flyway callback preserves exact backend and worker table grants'
