# Sprint 2 Auth Postman Collection

This folder contains the executable Sprint 2 authentication subset:

- `vod-platform-sprint-2-auth.postman_collection.json`
- `vod-platform-local.postman_environment.json`

It verifies registration, login, refresh-token rotation and rejection, authenticated profile access, and logout. It is intentionally not the comprehensive all-MVP collection owned by backlog Issue 10.3.

## Prerequisites

Start the local Compose stack through the Nginx edge by following the repository [local development instructions](../../../README.md#run-the-local-stack). With the default environment, the collection uses:

```text
http://localhost
```

If `NGINX_HTTP_PORT` is not `80`, change `baseUrl` in the imported environment, for example to `http://localhost:8088`.

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
npx --yes newman run docs/testing/postman/vod-platform-sprint-2-auth.postman_collection.json \
  --environment docs/testing/postman/vod-platform-local.postman_environment.json
```

Newman exits nonzero when a request, assertion, or script fails. The command does not write runtime tokens back to the committed environment file unless an explicit export option is added.

## Test Data and Secret Safety

- `testPassword` is a synthetic local-only value, not a real credential.
- The collection generates a new synthetic email on every ordered run.
- Synthetic users remain in the local PostgreSQL volume after a run.
- Access and refresh token variables start empty and are marked as secrets.
- Do not export or commit an environment after it has captured live tokens.
- Do not point this collection at a shared or production deployment without an explicit test-data and cleanup plan.

## Contract Notes

The frozen OpenAPI contract lists logout as unauthenticated with an optional body and documents both `204` and `401`. Sprint 2 Issue 2.4 and the current implementation are more specific: logout is public and idempotently returns `204` whether the submitted token is active, revoked, unknown, or omitted. This collection verifies the implemented Sprint 2 behavior without changing the frozen architecture documents.

The `ApiError.code` checks are implementation regression evidence. The frozen schema requires a string code but does not define those values as an enum.

No test-only RBAC probe is included. Production admin business endpoints belong to later sprints, and frontend route guards are not a backend authorization boundary.
