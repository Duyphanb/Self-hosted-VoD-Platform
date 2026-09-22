#!/usr/bin/env sh
set -eu

root="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
container="vod-email-migration-$$"
created=0
log="$(mktemp)"
cleanup() {
    if [ "$created" = 1 ]; then docker rm -f -v "$container" >/dev/null; fi
    rm -f "$log"
}
trap cleanup EXIT INT TERM

docker run -d --name "$container" --network none \
    -e POSTGRES_USER=fixture -e POSTGRES_PASSWORD=fixture-only \
    -e POSTGRES_DB=fixture postgres:16-alpine >/dev/null
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
        flyway/flyway:12.10.0 "$@" migrate >"$log" 2>&1
}
migrate -target=3
sql "INSERT INTO users (id,email,password_hash,display_name,status,created_at,updated_at) VALUES
('00000000-0000-0000-0000-000000000001','Case@example.test','fixture','One','ACTIVE',now(),now()),
('00000000-0000-0000-0000-000000000002','case@example.test','fixture','Two','ACTIVE',now(),now());" >/dev/null
if migrate; then
    printf '%s\n' 'FAIL: migration accepted colliding identities' >&2
    exit 1
fi
grep -q 'Email identity collisions exist' "$log"
[ "$(sql 'SELECT count(*) FROM users;')" = 2 ]
[ "$(sql "SELECT count(*) FROM flyway_schema_history WHERE version = '4';")" = 0 ]
printf '%s\n' 'PASS: V4 collision abort preserves both accounts and migration history'

# Resolve only the disposable fixture, never user data.
sql "DELETE FROM users WHERE id = '00000000-0000-0000-0000-000000000002';" >/dev/null
migrate
[ "$(sql "SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success;")" = 1 ]
[ "$(sql "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_users_email_identity';")" = 1 ]
printf '%s\n' 'PASS: V4 succeeds after explicit fixture collision resolution'
