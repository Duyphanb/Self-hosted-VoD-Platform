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

## Database Collation

PostgreSQL clusters are created with Vietnamese ICU collation (`POSTGRES_INITDB_ARGS` in `deploy/docker-compose.yml`, ADR-009), so titles and names sort in Vietnamese alphabetical order. The setting only applies when a cluster is first initialised:

- An existing `postgres_data` volume keeps the collation it was created with. To adopt the new one, dump the database, recreate the volume and restore the dump; recreating without a dump deletes the data. Check the active setting with `SELECT datlocprovider, daticulocale FROM pg_database WHERE datname = current_database();` (expect `i` and `vi`).
- After an image change that upgrades ICU (including switching image variants, #54), run `REINDEX DATABASE` before serving traffic, because index order may change.

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

## Object Storage Backup and Restore

Object storage is PGSTY Silo under the existing `minio` service/volume names ([ADR-004 addendum](../architecture/adr/ADR-004.md#runtime-addendum)). Both server and init are pinned to the same verified index digest. The console is off and S3/console ports are unpublished in local and production Compose. Curl `/minio/health/live` remains the health probe. Use the following offline procedure before a version change; never test upgrades on the only copy of data.

These Bash examples use production configuration. Set `PROJECT`, the **actual** volume name from your deployment, a new absolute backup directory and the exact currently deployed image from Compose/source control. Do not print the composed configuration or credentials. The directory/volume names below are examples, not discovery of existing resources.

```bash
set -eu
PROJECT=vod-platform
VOLUME="${PROJECT}_minio_data"
BACKUP=/srv/vod/backups/storage-2026-10-28
IMAGE='docker.io/pgsty/silo:RELEASE.2026-10-26T00-00-00Z@sha256:7bb2aff6b89efe1cb1303c10fc21d579447434ed06649ac9ec05a5e2dbf30ad7'
# First verify the source volume exists; a typo must not create an empty source.
docker volume inspect "$VOLUME" >/dev/null
# Stop all readers/writers, then the store, using the same project as the deployment.
docker compose -p "$PROJECT" --env-file .env.production -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml stop nginx backend worker minio
mkdir -m 700 "$BACKUP"  # must be a new directory, not an existing backup
# The Silo image has cp -a, but no tar. Include dotfiles and metadata, not just objects.
docker run --rm --entrypoint cp -v "$VOLUME:/data:ro" -v "$BACKUP:/backup" "$IMAGE" -a /data/. /backup/
```

Record the index digest, source revision, creation time and volume identity alongside the backup; retain the matching credentials securely outside Git. Protect IAM metadata as sensitive data. Check the backup contains `.minio.sys` and object data. Restore to a **new** empty volume, preserving the old volume for recovery:

```bash
set -eu
RESTORE_VOLUME="${PROJECT}_minio_restore_20261028"
# Require a new name; do not overwrite an existing restore target.
if docker volume inspect "$RESTORE_VOLUME" >/dev/null 2>&1; then exit 1; fi
docker volume create "$RESTORE_VOLUME" >/dev/null
docker run --rm --entrypoint cp -v "$BACKUP:/backup:ro" -v "$RESTORE_VOLUME:/data" "$IMAGE" -a /backup/. /data/
```

Use a temporary Compose overlay to point `minio.volumes` to that restored external volume, and pin the server/init to the digest recorded with the backup. Validate health, object checksums, metadata and all bucket policies on an isolated copy before reconnecting readers/writers. Do not start an older binary against metadata written by a newer version: rollback restores **both** the backup and its matching digest. Do not run `down -v` during a deployment/rollback. Establish retention, permissions and an independently tested restore before public production use. The #14 spike proved restart/recreation on a Silo-created volume, not restoration of real production data or migration from an upstream-created volume.

Example restore overlay (set/export `RESTORE_VOLUME` and `STORAGE_RESTORE_IMAGE` to the verified backup values). Compose merges volume mounts by target, so this replaces `/data` without deleting the original volume:

```yaml
services:
  minio:
    image: ${STORAGE_RESTORE_IMAGE:?Set the backup image digest}
    volumes:
      - restored_storage:/data
  minio-init:
    image: ${STORAGE_RESTORE_IMAGE:?Set the backup image digest}
volumes:
  restored_storage:
    external: true
    name: ${RESTORE_VOLUME:?Set the new restored volume name}
```

### Temporary Local Console

For a local demonstration only, create an untracked temporary overlay, load the same ignored local environment, and stop external readers/writers as appropriate:

```yaml
services:
  minio:
    environment:
      MINIO_BROWSER: "on"
    command: ["server", "/data", "--console-address", ":9001"]
    ports:
      - "127.0.0.1:19001:9001"
```

```bash
docker compose --env-file .env -f deploy/docker-compose.yml -f /absolute/path/demo-console.yml up -d --no-deps minio
# Open http://localhost:19001 locally; never share storage root credentials.
# Remove the temporary configuration by recreating from the base file only:
docker compose --env-file .env -f deploy/docker-compose.yml up -d --no-deps --force-recreate minio
```

Keep that file outside Git and do not combine it with the production overlay. Production explicitly keeps `MINIO_BROWSER: "off"`; the production configuration regression check rejects console/port/image drift. Omitting `--console-address` alone is insufficient to disable the console.
