# Postman Collections

This folder contains the executable Sprint 2 authentication subset:

- `vod-platform-sprint-2-auth.postman_collection.json`
- `vod-platform-local.postman_environment.json`

It verifies registration, login, refresh-token rotation and rejection, authenticated profile access, and logout. It is intentionally not the comprehensive all-MVP collection owned by backlog Issue 10.3.

## Prerequisites

Start the local Compose stack through the Nginx edge by following the repository [local development instructions](../../../README.md#run-the-local-stack). With the default environment, the collection uses:

```text
http://localhost
```

If `NGINX_HTTP_PORT` uses a host port other than `80`, change `baseUrl` in the imported environment; for example, `NGINX_HTTP_PORT=127.0.0.1:8088` needs `http://localhost:8088`.

## Run in Postman

1. Import both JSON files.
2. Select **VoD Platform - Local** as the active environment.
3. Run the complete **VoD Platform - Sprint 2 Auth** collection in its numbered order.
4. Confirm that every request and test passes.

The first request generates a unique synthetic `testEmail` for the run. The ordered flow then:

1. registers the user;
2. proves duplicate registration is rejected;
3. proves invalid credentials are rejected;
4. logs in and captures the access and refresh tokens;
5. rotates the refresh token;
6. proves the prior refresh token cannot be replayed;
7. calls the protected current-profile endpoint;
8. revokes the current refresh token;
9. proves the logged-out token cannot refresh;
10. proves repeated logout is idempotent;
11. proves logout without a submitted token is an idempotent no-op.

Run the whole collection rather than starting in the middle because later requests depend on variables captured by earlier requests.

## Run with Newman

From the repository root:

```bash
npx --yes newman@6.2.2 run docs/testing/postman/vod-platform-sprint-2-auth.postman_collection.json \
  --environment docs/testing/postman/vod-platform-local.postman_environment.json --silent
```

Newman exits nonzero when a request, assertion, or script fails. `--silent` prevents failed assertions from printing token-bearing response values. The command does not write runtime tokens back to the committed environment file unless an explicit export option is added.

CI runs this collection after the PostgreSQL auth regression script against a unique, disposable Compose project and removes its volumes on exit. It starts only the Auth dependencies and Nginx/frontend; it does not verify object storage or the media pipeline. The object-store image availability and maintenance decision remains tracked in Issue #14.

## Sprint 3 Catalog Collection

`vod-platform-sprint-3-catalog.postman_collection.json` (**VoD Platform - Sprint 3 Catalog**) checks the catalog through Nginx in 31 numbered requests:

- administrator CRUD for genres (including a duplicate `409 GENRE_CONFLICT`), people (create and search) and movies;
- visibility: a draft, an archived and a restored draft movie return `404 MOVIE_NOT_FOUND` to a regular user, while the administrator still reads them;
- `403` for a regular user on admin genres, people, movies and movie creation, and `401` without a token;
- the public list: Vietnamese title order (Ánh Sáng < Avatar < Đất Rừng Phương Nam < Zootopia, which needs the ICU collation of ADR-009), page metadata, `size=1` paging and an empty page beyond the end, and the archived movie dropping out.

It needs an administrator. Register an account and grant `ROLE_ADMIN` with the SQL in [docs/deployment/README.md](../../deployment/README.md#first-administrator), or run `deploy/tests/newman-catalog-admin.py`, which does both against a disposable Compose project, using `NEWMAN_ADMIN_EMAIL` and `NEWMAN_ADMIN_PASSWORD`. Then pass the credentials at run time instead of saving them in the environment file:

```bash
npx --yes newman@6.2.2 run docs/testing/postman/vod-platform-sprint-3-catalog.postman_collection.json   -e docs/testing/postman/vod-platform-local.postman_environment.json   --env-var baseUrl=http://127.0.0.1:18087   --env-var "adminEmail=$NEWMAN_ADMIN_EMAIL" --env-var "adminPassword=$NEWMAN_ADMIN_PASSWORD" --silent
```

Every run creates its own genre, person and movies with a unique suffix, so it tolerates existing catalog data. Use a fresh admin email per run: the helper accepts an existing account but cannot change its password.

## Test Data and Secret Safety

- `testPassword` is a synthetic local-only value, not a real credential.
- The collection generates a new synthetic email on every ordered run.
- Synthetic users remain in the local PostgreSQL volume after a run.
- Access and refresh token variables start empty and are marked as secrets.
- Do not export or commit an environment after it has captured live tokens.
- Do not point this collection at a shared or production deployment without an explicit test-data and cleanup plan.

## Contract Notes

The frozen OpenAPI contract lists logout as unauthenticated with an optional body and documents `204`, `400` and `401`. Sprint 2 Issue 2.4 and the current implementation are more specific: logout is public and idempotently returns `204` whether the submitted token is active, revoked, unknown, or omitted. This collection verifies the implemented Sprint 2 behavior without changing the frozen architecture documents.

The `ApiError.code` checks are implementation regression evidence. The frozen schema requires a string code but does not define those values as an enum.

The contract now also declares the validation `400` responses (`VALIDATION_ERROR`, `MALFORMED_REQUEST`) that login, refresh and logout already returned through the existing request-validation layer (Issue #120). The runtime behavior did not change. The backend full-context tests validate these responses against `API-CONTRACT.yaml`; this collection does not exercise them.

No test-only RBAC probe is included. Production admin business endpoints belong to later sprints, and frontend route guards are not a backend authorization boundary.
