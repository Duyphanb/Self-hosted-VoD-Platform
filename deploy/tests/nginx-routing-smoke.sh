#!/usr/bin/env sh

set -eu

frontend_image="${1:-vod-frontend-routing-test}"
test_id="$$"
network_name="vod-nginx-routing-test-${test_id}"
frontend_container="vod-frontend-routing-test-${test_id}"
backend_container="vod-backend-routing-stub-${test_id}"
edge_container="vod-edge-routing-test-${test_id}"
script_directory="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repository_root="$(CDPATH= cd -- "${script_directory}/../.." && pwd)"

cleanup() {
    docker rm -f \
        "${edge_container}" \
        "${backend_container}" \
        "${frontend_container}" >/dev/null 2>&1 || true
    docker network rm "${network_name}" >/dev/null 2>&1 || true
}

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

assert_spa_route() {
    route="$1"
    body="$(curl --fail --silent --show-error "${edge_url}${route}")"
    printf '%s' "${body}" | grep --fixed-strings --quiet '<div id="root"></div>' \
        || fail "Expected React application shell for ${route}"
}

assert_reserved_route() {
    route="$1"
    body="$(curl --silent --show-error "${edge_url}${route}")"
    if printf '%s' "${body}" | grep --fixed-strings --quiet '<div id="root"></div>'; then
        fail "Reserved route ${route} unexpectedly returned the React application shell"
    fi
}

trap cleanup EXIT INT TERM

docker network create "${network_name}" >/dev/null
docker run --detach --name "${frontend_container}" \
    --network "${network_name}" --network-alias frontend \
    "${frontend_image}" >/dev/null
docker run --detach --name "${backend_container}" \
    --network "${network_name}" --network-alias backend \
    nginx:1.27-alpine >/dev/null
docker run --detach --name "${edge_container}" \
    --network "${network_name}" \
    --publish 127.0.0.1::80 \
    --volume "${repository_root}/deploy/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" \
    nginx:1.27-alpine >/dev/null

published_address="$(docker port "${edge_container}" 80/tcp)"
edge_port="${published_address##*:}"
edge_url="http://127.0.0.1:${edge_port}"

attempt=0
until curl --fail --silent --show-error --output /dev/null "${edge_url}/"; do
    attempt=$((attempt + 1))
    if [ "${attempt}" -ge 30 ]; then
        docker logs "${edge_container}" >&2 || true
        fail 'Nginx routing smoke stack did not become ready'
    fi
    sleep 1
done

for route in / /login /register /account /admin; do
    assert_spa_route "${route}"
done

for route in \
    /api/v1 \
    /api/v1/health \
    /api/v1/__spa_probe__ \
    /hls \
    /hls/__spa_probe__.m3u8 \
    /actuator/health; do
    assert_reserved_route "${route}"
done

printf '%s\n' 'Nginx SPA and reserved-route smoke checks passed.'
