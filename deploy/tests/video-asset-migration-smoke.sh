#!/usr/bin/env sh
set -eu

root="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
container="vod-video-asset-migration-$$"
created=0
log="$(mktemp)"
cleanup() {
    if [ "$created" = 1 ]; then docker rm -f -v "$container" >/dev/null; fi
    rm -f "$log"
}
trap cleanup EXIT INT TERM

# This script owns this container and its anonymous volume; no Compose/configured DB.
docker run -d --name "$container" --network none \
    -e POSTGRES_USER=fixture -e POSTGRES_PASSWORD=fixture-only \
    -e POSTGRES_DB=fixture \
    -e 'POSTGRES_INITDB_ARGS=--locale-provider=icu --icu-locale=vi' \
    postgres:16-alpine >/dev/null
created=1
attempt=0
until docker exec "$container" pg_isready -U fixture -d fixture >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 40 ] || exit 1
    sleep 1
done
sql() { docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U fixture -d fixture -Atc "$1"; }
migrate() {
    docker run --rm --network "container:$container" \
        -v "$root/backend/common/src/main/resources/db/migration:/flyway/sql:ro" \
        -e FLYWAY_URL=jdbc:postgresql://localhost:5432/fixture \
        -e FLYWAY_USER=fixture -e FLYWAY_PASSWORD=fixture-only \
        flyway/flyway:12.10.0 -connectRetries=10 "$@" migrate >"$log" 2>&1
}
assets() { sql 'SELECT row_to_json(v)::text FROM video_assets v ORDER BY id;'; }
jobs() {
    sql 'SELECT id,video_asset_id,status,attempt,error_message,queued_at,started_at,finished_at FROM encoding_jobs ORDER BY id;'
}
expect_in_flight_failure() {
    before="$(assets)"
    if sql "$1" >"$log" 2>&1; then
        printf '%s\n' 'FAIL: accepted a second in-flight asset' >&2
        exit 1
    fi
    grep -q 'ux_video_assets_movie_in_flight' "$log"
    [ "$(assets)" = "$before" ]
}

migrate -target=4
sql "INSERT INTO movies (id,title,slug,description,status,created_at,updated_at) VALUES
('00000000-0000-0000-0000-000000000001','Conflict','migration-conflict','fixture','PUBLISHED',now(),now()),
('00000000-0000-0000-0000-000000000002','History','migration-history','fixture','PUBLISHED',now(),now()),
('00000000-0000-0000-0000-000000000003','Reserved','migration-reserved','fixture','PUBLISHED',now(),now()),
('00000000-0000-0000-0000-000000000004','Independent','migration-independent','fixture','PUBLISHED',now(),now());
INSERT INTO video_assets (id,movie_id,raw_bucket,raw_object_key,status,created_at,updated_at) VALUES
('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','fixture','conflict-uploaded','UPLOADED',now(),now()),
('10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','fixture','conflict-queued','QUEUED',now(),now()),
('20000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','fixture','history-ready','READY',now(),now()),
('20000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000002','fixture','history-failed','FAILED',now(),now()),
('20000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','fixture','history-queued','QUEUED',now(),now());
INSERT INTO encoding_jobs (id,video_asset_id,status,attempt,queued_at) VALUES
('40000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000002','QUEUED',1,now()),
('40000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000003','QUEUED',1,now());
-- Twenty more conflicts prove the total count and the bounded, ordered sample.
INSERT INTO movies (id,title,slug,description,status,created_at,updated_at)
SELECT ('00000000-0000-0000-0000-' || lpad(n::text,12,'0'))::uuid,
       'Sample ' || n, 'migration-sample-' || n, 'fixture', 'PUBLISHED', now(), now()
FROM generate_series(10,29) n;
INSERT INTO video_assets (id,movie_id,raw_bucket,raw_object_key,status,created_at,updated_at)
SELECT ('50000000-0000-0000-0000-' || lpad((n*10+s)::text,12,'0'))::uuid,
       ('00000000-0000-0000-0000-' || lpad(n::text,12,'0'))::uuid,
       'fixture', 'sample-' || n || '-' || s,
       CASE s WHEN 1 THEN 'UPLOADED' ELSE 'PROCESSING' END, now(), now()
FROM generate_series(10,29) n CROSS JOIN generate_series(1,2) s;" >/dev/null

before_assets="$(assets)"
before_jobs="$(jobs)"
if migrate; then
    printf '%s\n' 'FAIL: V5 accepted legacy in-flight conflicts' >&2
    exit 1
fi
grep -q 'Movies with more than one in-flight video asset: 21 (first:' "$log"
grep -q '00000000-0000-0000-0000-000000000001' "$log"
grep -q '00000000-0000-0000-0000-000000000028' "$log"
if grep -Eq '00000000-0000-0000-0000-000000000002|00000000-0000-0000-0000-000000000029' "$log"; then
    printf '%s\n' 'FAIL: V5 sample includes a legal movie or exceeds 20 ids' >&2
    exit 1
fi
[ "$(assets)" = "$before_assets" ]
[ "$(jobs)" = "$before_jobs" ]
[ "$(sql "SELECT count(*) FROM flyway_schema_history WHERE version = '5';")" = 0 ]
[ "$(sql "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'encoding_jobs' AND column_name = 'published_at';")" = 0 ]
[ "$(sql "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'ux_video_assets_movie_in_flight';")" = 0 ]
printf '%s\n' 'PASS: V5 abort reports 21 movies with a 20-id sample and preserves all assets/jobs/schema history'

# Explicit repair belongs only to this disposable fixture, never to V5 itself.
sql "UPDATE video_assets SET status = 'FAILED' WHERE status = 'UPLOADED';" >/dev/null
repaired_assets="$(assets)"
migrate
[ "$(assets)" = "$repaired_assets" ]
[ "$(jobs)" = "$before_jobs" ]
[ "$(sql "SELECT count(*) FROM flyway_schema_history WHERE version = '5' AND success;")" = 1 ]
[ "$(sql "SELECT data_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'encoding_jobs' AND column_name = 'published_at';")" = 'timestamp with time zone|YES|' ]
[ "$(sql "SELECT count(*) FROM encoding_jobs WHERE status = 'QUEUED' AND published_at IS NULL;")" = 2 ]
[ "$(sql "SELECT indisunique AND indpred IS NOT NULL FROM pg_index WHERE indexrelid = 'ux_video_assets_movie_in_flight'::regclass;")" = t ]
[ "$(sql "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history';")" = 15 ]
[ "$(sql "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname ~ '^(ux|ix)_';")" = 32 ]
[ "$(sql "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = 'public' AND t.relname <> 'flyway_schema_history';")" = 53 ]
[ "$(sql "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = 'public' AND c.contype = 'f';")" = 17 ]
[ "$(sql "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = 'public' AND c.contype = 'c';")" = 9 ]
printf '%s\n' 'PASS: repaired fixture migrates; pre-existing QUEUED jobs remain unpublished; schema counts 15/32/53/17/9'

expect_in_flight_failure "INSERT INTO video_assets (id,movie_id,raw_bucket,raw_object_key,status,created_at,updated_at) VALUES ('30000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','fixture','losing-queued','QUEUED',now(),now());"
expect_in_flight_failure "INSERT INTO video_assets (id,movie_id,raw_bucket,raw_object_key,status,created_at,updated_at) VALUES ('30000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000002','fixture','losing-processing','PROCESSING',now(),now());"
expect_in_flight_failure "UPDATE video_assets SET status = 'QUEUED' WHERE id = '20000000-0000-0000-0000-000000000002';"
sql "INSERT INTO video_assets (id,movie_id,raw_bucket,raw_object_key,status,created_at,updated_at) VALUES ('30000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000003','fixture','reserved-uploaded','UPLOADED',now(),now());" >/dev/null
expect_in_flight_failure "INSERT INTO video_assets (id,movie_id,raw_bucket,raw_object_key,status,created_at,updated_at) VALUES ('30000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000003','fixture','reserved-loser','QUEUED',now(),now());"
sql "INSERT INTO video_assets (id,movie_id,raw_bucket,raw_object_key,status,created_at,updated_at) VALUES ('30000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000004','fixture','independent-queued','QUEUED',now(),now());
UPDATE video_assets SET status = 'READY' WHERE id = '20000000-0000-0000-0000-000000000003';
INSERT INTO video_assets (id,movie_id,raw_bucket,raw_object_key,status,created_at,updated_at) VALUES ('30000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000002','fixture','replacement-queued','QUEUED',now(),now());" >/dev/null
[ "$(sql "SELECT count(*) FROM video_assets WHERE movie_id = '00000000-0000-0000-0000-000000000002' AND status = 'READY';")" = 2 ]
printf '%s\n' 'PASS: in-flight insert/retry losers name the index and preserve rows; other movies and terminal history remain legal'
