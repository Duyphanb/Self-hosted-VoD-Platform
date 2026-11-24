# Postman Collections

This folder contains the executable sprint collections and their shared environment:

- `vod-platform-sprint-2-auth.postman_collection.json` (described first below)
- `vod-platform-sprint-3-catalog.postman_collection.json`
- `vod-platform-sprint-4-upload.postman_collection.json`
- `vod-platform-local.postman_environment.json`

The Sprint 2 collection verifies registration, login, refresh-token rotation and rejection, authenticated profile access, and logout. It is intentionally not the comprehensive all-MVP collection owned by backlog Issue 10.3.

## Prerequisites

Start the local Compose stack through the Nginx edge by following the repository [local development instructions](../../../README.md#run-the-local-stack). With the default environment, the collection uses:

```text
http://localhost
```

If `NGINX_HTTP_PORT` uses a host port other than `80`, change `baseUrl` in the imported environment; for example, `NGINX_HTTP_PORT=127.0.0.1:8088` needs `http://localhost:8088`.

## Run in Postman

1. Import the collection you want to run and the environment file.
2. Select **VoD Platform - Local** as the active environment.
3. Run the complete **VoD Platform - Sprint 2 Auth** collection in its numbered order.
4. Confirm that every request and test passes.

The first request generates a unique synthetic `testEmail` for the run. The ordered flow then:

1. Registers the user.
2. Confirms that duplicate registration is rejected.
3. Confirms that invalid credentials are rejected.
4. Signs in and captures the access token and refresh cookie.
5. Rotates the refresh token and captures the successor.
6. Reads the protected current-profile endpoint.
7. Replays the previous refresh token and receives 401, revoking the session.
8. Confirms that the access token from the revoked session receives 401.
9. Signs in again to start a fresh session for the logout checks.
10. Logs out of the fresh session.
11. Confirms that its refresh cookie receives 401.
12. Confirms that repeated logout is idempotent.
13. Confirms that logout without a cookie is idempotent.

Run the whole collection rather than starting in the middle because later requests depend on variables captured by earlier requests.

## Run with Newman

From the repository root:

```bash
npx --yes newman@6.2.2 run docs/testing/postman/vod-platform-sprint-2-auth.postman_collection.json \
  --environment docs/testing/postman/vod-platform-local.postman_environment.json --silent
```

Newman exits nonzero when a request, assertion, or script fails. `--silent` prevents failed assertions from printing token-bearing response values. The command does not write runtime tokens back to the committed environment file unless an explicit export option is added.

CI runs this collection after the PostgreSQL auth regression script against a unique, disposable Compose project
and removes its volumes on exit. The auth collection exercises the auth endpoints through Nginx. Separate CI
steps exercise object storage and the media pipeline. The object-store image availability and maintenance
decision remains tracked in Issue #14.

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

## Sprint 4 Upload Collection

`vod-platform-sprint-4-upload.postman_collection.json` (**VoD Platform - Sprint 4 Upload**) checks the admin upload endpoint `POST /api/v1/admin/movies/{movieId}/video` through Nginx in 10 numbered requests:

1. signs in as the administrator;
2. registers a regular user;
3. signs in as that user;
4. rejects an anonymous upload with `401 UNAUTHORIZED` (authorization runs before the multipart body is parsed);
5. rejects the regular user's upload with `403 FORBIDDEN`, also before parsing;
6. rejects a text file with `400 UNSUPPORTED_VIDEO_TYPE`;
7. rejects an upload for a missing movie with `404 MOVIE_NOT_FOUND`;
8. rejects an upload for a movie with a seeded in-flight asset with `409 VIDEO_UPLOAD_IN_PROGRESS`;
9. rejects an oversized file with the backend `413 PAYLOAD_TOO_LARGE` ApiError, passed through the edge;
10. accepts a valid upload with `202` and status `QUEUED`.

Every error body must carry a safe `requestId` (`[A-Za-z0-9._-]{1,64}`) and no storage internals; the `202` body carries no `requestId` and no internal keys.

Prerequisites:

- An administrator promoted with the documented first-administrator SQL, as for the Sprint 3 collection (`deploy/tests/newman-catalog-admin.py` does both steps).
- `deploy/tests/newman-upload-fixtures.py --project <disposable project> --base-url <loopback URL> --fixtures-dir <directory>` generates `valid.mp4`, `note.txt` and a 1.5 MiB `oversize.mp4` in that directory, seeds two DRAFT movies in the project's PostgreSQL container and prints `uploadMovieId=...` and `inFlightMovieId=...`. The in-flight movie gets a QUEUED asset/job pair already marked published, so the 409 does not depend on worker timing. The helper refuses the default `vod-platform` project and non-loopback URLs. Run it before every collection run: after request 10 the upload movie has an in-flight asset, so a rerun with the same `uploadMovieId` gets `409`.
- The backend must run with `MAX_UPLOAD_SIZE_MB=1`, as the CI full-stack step does; otherwise the 1.5 MiB file is accepted and request 9 fails.

Run Newman with `--working-dir` pointing at the generated files, because the collection's form-data files are relative names:

```bash
npx --yes newman@6.2.2 run docs/testing/postman/vod-platform-sprint-4-upload.postman_collection.json \
  -e docs/testing/postman/vod-platform-local.postman_environment.json \
  --working-dir "$UPLOAD_FIXTURES_DIR" --env-var baseUrl=http://127.0.0.1:18087 \
  --env-var "adminEmail=$NEWMAN_ADMIN_EMAIL" --env-var "adminPassword=$NEWMAN_ADMIN_PASSWORD" \
  --env-var "uploadMovieId=$UPLOAD_MOVIE_ID" --env-var "inFlightMovieId=$IN_FLIGHT_MOVIE_ID" --silent
```

The Postman app needs absolute file paths for form-data files: reselect the generated files in requests 04-10 before running the collection there.

CI runs this collection in the full-stack step, after the Sprint 3 catalog collection, against the same disposable Compose project and administrator. `MAX_UPLOAD_SIZE_MB=1` is set for that step only, the fixtures are generated under `$RUNNER_TEMP`, the two movie IDs are passed with `--env-var`, and `--silent` keeps only the exit status. The accepted upload streams to the project's private raw bucket, which is removed with the project's volumes on exit.

## Test Data and Secret Safety

- `testPassword` is a synthetic local-only value, not a real credential.
- The collection generates a new synthetic email on every ordered run.
- Synthetic users remain in the local PostgreSQL volume after a run. The Sprint 3 collection leaves its catalog fixtures, and the Sprint 4 upload collection leaves two seeded movies, an in-flight QUEUED asset/job pair and a raw object; run them only against a disposable project.
- Access-token and refresh-cookie variables start empty and are marked as secrets.
- Do not export or commit an environment after it has captured live tokens.
- Do not point this collection at a shared or production deployment without an explicit test-data and cleanup plan.

## Contract Notes

The notes below concern the Sprint 2 auth collection.

The collection follows ADR-012 Issue 6.9: auth requests send `Origin: {{baseUrl}}`; refresh/logout send no body. Explicit `Cookie` headers and a disabled jar make Secure cookies work under Newman 6.2.2 on loopback HTTP and prevent duplicate cookies. Login/refresh assert no refresh token in JSON and validate HttpOnly, Secure, SameSite, host-only scope, path and lifetime. Replay/401 and logout assert the clearing cookie. Runtime cookie variables are secret and never exported or printed; keep `--silent` when running Newman.

`baseUrl` must be an origin listed in `CORS_ALLOWED_ORIGINS`. A non-default edge port needs its own entry for the auth Origin check. Logout is public/idempotent 204, always clears the cookie and ignores any request body. Refresh state failures return 503 and do not clear the cookie. Backend full-context tests validate every auth response against `API-CONTRACT.yaml` and exercise the Origin/Fetch-Site matrix.

The Sprint 2 collection includes no test-only RBAC probe; the admin catalog and upload authorization checks live in the Sprint 3 and Sprint 4 collections. Frontend route guards are not a backend authorization boundary.
