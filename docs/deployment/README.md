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
