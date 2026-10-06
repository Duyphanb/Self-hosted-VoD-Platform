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

To remove the role, delete the `user_roles` row and revoke the user's active
refresh tokens (`UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ...
AND revoked_at IS NULL`). An access token already issued stays valid until it
expires (60 minutes by default); the accepted delay is tracked in issue #72.

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

The production-only `production-config-check` service rejects the published
development placeholders before PostgreSQL, RabbitMQ, or MinIO can start. It
reports only the rejected variable name and never its value. Backend, worker,
Flyway, and initialization services consume the same canonical credentials.
Starting production with `--no-deps` is unsupported because that option
explicitly bypasses dependency gates.

The preflight validates the configuration being supplied; it does not rotate
credentials already stored in initialized service volumes. PostgreSQL and
other bootstrap credentials must be applied to fresh production volumes or
changed with an explicit service-specific rotation procedure.

Run the repository regression check before a deployment:

```bash
sh deploy/tests/production-config-smoke.sh
```

The script uses synthetic test-only values. It verifies that local Compose
still accepts its development defaults, missing production values fail during
configuration, published defaults fail the runtime preflight, and non-default
values pass.
