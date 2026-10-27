# Deployment Docs

Use this folder for Phase 5 deployment and operations artifacts.

## Planned Contents

- `DEPLOYMENT.md`
- `ROLLBACK.md`
- `BACKUP-RESTORE.md`
- `RUNBOOK.md`
- VPS setup notes
- production environment checklist

## Purpose

- document Oracle Cloud Free Tier VPS deployment
- document HTTPS and Nginx setup
- document backup, restore, and rollback
- document operational checks for the public demo

## Boundary

Do not put business requirements or implementation code here. Deployment docs should describe how to operate the implemented system.

## First Administrator

Registration always assigns `ROLE_USER`, and no administrator account or
password is seeded. To create the first administrator, register the account
through the application, then grant `ROLE_ADMIN` by email with SQL. The email
match uses `lower(email)`, the same identity rule as login
([ADR-008](../architecture/adr/ADR-008.md)).

Local stack shown; for production, replace `--env-file .env` with
`--env-file .env.production` and add `-f deploy/docker-compose.prod.yml`:

```bash
ADMIN_EMAIL='admin@example.com'
docker compose --env-file .env -f deploy/docker-compose.yml exec -T postgres \
  sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v email="$1"' sh "$ADMIN_EMAIL" <<'SQL'
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON r.name = 'ROLE_ADMIN'
WHERE lower(u.email) = lower(:'email')
ON CONFLICT DO NOTHING;
SELECT count(*) AS admin_role_granted
FROM users u
JOIN user_roles ur ON ur.user_id = u.id
JOIN roles r ON r.id = ur.role_id
WHERE lower(u.email) = lower(:'email') AND r.name = 'ROLE_ADMIN';
SQL
```

`admin_role_granted` must be `1`; `0` means no account uses that email. The
statement is idempotent. Roles are carried in the access token, so the new role
applies after the next token refresh or sign-in.

The catalog runtime smoke in CI (`deploy/tests/catalog-runtime-smoke.py`)
promotes its administrator by running the `sh -c` command and the SQL of this
block as written here, so keep the block's form when editing it.

To remove the role, delete the `user_roles` row and revoke the user's active
refresh tokens (`UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ...
AND revoked_at IS NULL`). An access token already issued stays valid until it
expires (60 minutes by default); the accepted delay is tracked in issue #72.

## Edge Port Binding

`NGINX_HTTP_PORT` sets the host address and port that publish the Nginx edge.
The base Compose file and `.env.example` default to `127.0.0.1:80`, so a local
stack is reachable only from the same machine at `http://localhost/`. The local
`JWT_SECRET` in `.env.example` is a public placeholder, and anyone who can
reach an edge that uses it can sign access tokens for any account, including
`ROLE_ADMIN`.

To show a local stack to other devices on a trusted network, first replace
`JWT_SECRET` in `.env` with a random value of at least 32 bytes that is not the
placeholder. Then set `NGINX_HTTP_PORT=80` (every interface) or
`NGINX_HTTP_PORT=<LAN address>:80` and recreate the stack. The README section
[LAN access for a demo](../../README.md#lan-access-for-a-demo) lists the steps.
Return to `127.0.0.1:80` when the demo ends.

Production publishes the edge on the public interface, so `.env.production`
must set `NGINX_HTTP_PORT=80` explicitly. The production overlay fails during
configuration when the value is missing or empty, and the
`production-config-check` service accepts only a port or `IPv4:port` and
rejects a loopback binding (`127.*`), hostnames such as `localhost`, and IPv6
in any notation before Nginx or the infrastructure services start. HTTPS keeps
the `NGINX_HTTPS_PORT` default of `443`.

## Production Credential Preflight

The production overlay must be supplied together with the local baseline:

```bash
docker compose \
  --env-file .env.production \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.prod.yml \
  config --quiet
```

Keep `.env.production` outside version control and restrict it to its owner
(for example, mode `0600` on Linux). Generate independent values rather than
copying the local template. If a value contains `$`, follow Docker Compose's
env-file quoting rules so it is not interpolated unexpectedly. The file must
provide non-empty, non-default values for:

- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `RABBITMQ_USERNAME`
- `RABBITMQ_PASSWORD`
- `MINIO_ACCESS_KEY`
- `MINIO_SECRET_KEY`
- `JWT_SECRET`

It must also set `NGINX_HTTP_PORT=80`, as described in
[Edge Port Binding](#edge-port-binding).

The production-only `production-config-check` service rejects the published
development placeholders and a loopback-bound edge before PostgreSQL,
RabbitMQ, MinIO, or Nginx can start. It reports only the rejected variable name
and never its value. Backend, worker, Flyway, and initialization services
consume the same canonical credentials. Starting production with `--no-deps` is
unsupported because that option explicitly bypasses dependency gates.

The preflight validates the configuration being supplied; it does not rotate
credentials already stored in initialized service volumes. PostgreSQL and
other bootstrap credentials must be applied to fresh production volumes or
changed with an explicit service-specific rotation procedure.

Run the repository regression check before a deployment:

```bash
sh deploy/tests/production-config-smoke.sh
```

The script uses synthetic test-only values. It verifies that local Compose
still accepts its development defaults and publishes the edge on loopback only,
missing production values fail during configuration, published defaults and a
loopback-bound edge fail the runtime preflight, and non-default values pass.
