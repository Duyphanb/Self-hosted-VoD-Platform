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

## Browser session upgrades

For Issue 6.10, set `JWT_ACCESS_TOKEN_TTL_MINUTES=5` in existing environment files before starting the new backend.
The old value `60` fails startup. The allowed ranges are 1–15 minutes for access tokens and 1–30 days for
`JWT_REFRESH_TOKEN_TTL_DAYS`, which now sets the absolute session lifetime. The default session lifetime is 7 days.

Apply Flyway V6 before serving traffic. It leaves existing users and refresh rows in place. Existing refresh rows lack
a session ID and return 401 on use. Existing access tokens lack `sid` and `ver` and also return 401. Each browser must
sign in once after the upgrade. The daily cleanup subsequently removes legacy refresh rows.

### Upgrade from before Issue 6.9

Deploy the backend and SPA together: refresh/logout no longer accept a JSON token, and login/refresh no longer
return one. Old SPA bundles sign out once after this upgrade; reload to get the new bundle and sign in again.
The Issue 6.9 cookie change requires no database migration. A direct upgrade to Issue 6.10 also requires Flyway
V6 before serving traffic.

For a local `.env` created before Issue 6.9, update the existing value explicitly; new Compose defaults do not replace it:

```dotenv
CORS_ALLOWED_ORIGINS=http://localhost,http://localhost:5173
```

Add any non-default edge origin, for example `http://localhost:8088`. Keeping only the old Vite origin makes login from the Compose edge return 403 `ORIGIN_NOT_ALLOWED`. Production must instead list its public HTTPS origin.

Set `CORS_ALLOWED_ORIGINS` to the browser-visible public origin (include a non-default port). Refresh/logout require an exact allowed `Origin`, independently of Nginx's forwarded origin and CORS. An empty allowlist fails production preflight. Leave `AUTH_COOKIE_SECURE=true`; production preflight rejects false. Local false is restricted at application startup to a nonempty loopback-only allowlist. Loopback HTTP works with Secure cookies in Chromium and Firefox; LAN HTTP sign-in does not. Use HTTPS (#136) for other devices.

Auth-state read failures return 503 `AUTH_STATE_UNAVAILABLE` and preserve the cookie. An unconfirmed logout stays
pending in the SPA and must be retried. A rejected refresh returns 401 and clears the cookie.

## Catalog Rename Upgrade (Flyway V7)

Issue 7.1 (#259) renames `movies`, `movie_genres` and `movie_credits` to `titles`, `title_genres` and `title_credits`.
The backend maps those tables, so a backend built before V7 fails against V7, and the reverse. The worker only uses
`encoding_jobs` and `video_assets`, which V7 does not change, but it is drained and stopped too. That way PostgreSQL,
RabbitMQ and object storage cross the cutover together. The release follows the fenced sequence of
[ADR-013](../architecture/adr/ADR-013.md) D13 ("Deployment of every migration slice"). It was rehearsed on a disposable
stack for #259. Set `C` to the Compose command of the deployment, as in
[Production Credential Preflight](#production-credential-preflight), and `PROJECT` to its project name.

**Before the window**

- Check that PostgreSQL, RabbitMQ and MinIO are healthy and that their init services have completed. The commands
  below use `--no-deps`, so nothing is started implicitly.
- Keep a checkout of the running release (V6). Its Compose file and migration bundle are what a rollback uses.
- Tag the image each running service uses, for example
  `docker image tag "$(docker inspect --format '{{.Image}}' "$($C ps -q backend)")" "$PROJECT-backend:pre-v7"`, and the
  same for `worker` and `frontend`.
- From the V7 checkout, build the new images with `$C build backend worker frontend`.

**The window**

1. **Stop new uploads.**
   - Close the upload location in the deployed Nginx configuration so that it answers 503.
   - Check the configuration with `$C exec nginx nginx -t`, then reload gracefully with `$C exec nginx nginx -s reload`.
   - A new upload `POST` must now get 503, while `/api/v1/health` still returns 200.
   - Uploads already accepted finish on the previous Nginx worker generation. Wait until those worker processes have
     exited, within a time limit you set. If uploads still run at the limit, decide whether to wait or to cut them.
   - The edge started in step 6 uses the release's own configuration, with uploads open.
2. **Stop the edge and the backend:** `$C stop nginx backend`. No job is published any more; the worker keeps
   consuming what the broker already holds.
3. **Drain the worker.**
   - Watch the encoding queue (named by `RABBITMQ_ENCODING_QUEUE`, default `video-encoding-queue`, not its dead-letter
     queue) with `$C exec rabbitmq rabbitmqctl --quiet list_queues -p / name messages_ready messages_unacknowledged consumers`.
   - Watch the database with `SELECT count(*) FROM encoding_jobs WHERE status = 'PROCESSING'`.
   - Wait until `messages_ready` and `messages_unacknowledged` are both 0 and no job is `PROCESSING`, then run
     `$C stop worker`.
   - Record the jobs that are `QUEUED` with `published_at IS NULL`. These are the outbox: the new release's relay
     publishes them after the decision.
   - If you cannot wait, stopping the worker interrupts one encode. It fails as `WORKER_INTERRUPTED` at the next
     start and needs a retry or a new upload ([Encoding Recovery](#encoding-recovery)).
4. **Record, dump and prove the restore.**
   - Record the row counts of `movies`, `movie_genres`, `movie_credits`, `ratings` and `watchlist_items`.
   - Take `pg_dump --format=custom` of the application database.
   - Prove that it restores:
     - use a disposable PostgreSQL of the same image and ICU settings;
     - roles are cluster-wide, so create the owner and grantee roles empty first;
     - run `pg_restore --clean --if-exists --single-transaction --exit-on-error`;
     - require exit code 0, a Flyway history ending at 6, and the recorded row counts.
5. **Apply the migration alone:** `$C run --rm --no-deps flyway`.
   - V7 runs in one transaction, so a failed V7 leaves V6 unchanged.
   - The `afterMigrate` grants callback runs in its own transaction after V7. If it fails, V7 can stay committed with
     incomplete grants. Run the same command again (the callback is idempotent) and continue only after it succeeds.

   Then check before starting anything:
   - `flyway_schema_history` shows version 7 as successful;
   - the schema matches the committed snapshot:
     `python3 deploy/tests/schema-snapshot.py --container <postgres container> --user <owner> --database <db>`;
   - the row counts of the renamed tables equal those recorded in step 4;
   - the runtime roles keep exactly their table grants (the privilege query in
     `deploy/tests/title-rename-migration-smoke.sh`).
6. **Decide here,** before any new backend or worker runs.
   - **Roll forward** if step 5 passed:
     - `$C up -d --no-build --no-deps --wait backend worker frontend`;
     - then `$C up -d --no-build --no-deps --wait nginx`.

     `--no-deps` keeps Compose from running `postgres-init` and Flyway again after this point.
   - **Roll back** if anything in step 5 failed:
     - restore the dump into a clean database (recreate the database or use a new volume, with the roles present),
       stopping on any error;
     - verify that the Flyway history ends at 6, that the schema matches the V6 snapshot (`schema-snapshot.py` from the
       V6 checkout) and that the grants are exact;
     - from the V6 checkout, point the image tags back with `docker image tag "$PROJECT-backend:pre-v7" "$PROJECT-backend"`
       and the same for `worker` and `frontend`;
     - start with the same two `--no-deps` commands.

   Once the new backend or worker has started, the only way back is forward.

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
BEGIN;
SELECT id FROM users WHERE lower(email) = lower(:'email') FOR UPDATE;
WITH granted AS (
    INSERT INTO user_roles (user_id, role_id)
    SELECT u.id, r.id
    FROM users u
    JOIN roles r ON r.name = 'ROLE_ADMIN'
    WHERE lower(u.email) = lower(:'email')
    ON CONFLICT DO NOTHING
    RETURNING user_id
), updated AS (
    UPDATE users SET auth_epoch = auth_epoch + 1, updated_at = now()
    WHERE id IN (SELECT user_id FROM granted)
    RETURNING id
)
UPDATE auth_sessions SET revoked_at = now()
WHERE user_id IN (SELECT id FROM updated) AND revoked_at IS NULL;
SELECT count(*) AS admin_role_granted
FROM users u
JOIN user_roles ur ON ur.user_id = u.id
JOIN roles r ON r.id = ur.role_id
WHERE lower(u.email) = lower(:'email') AND r.name = 'ROLE_ADMIN';
COMMIT;
SQL
```

`admin_role_granted` must be `1`; `0` means no account uses that email. The
transaction is idempotent. A new grant revokes existing sessions and requires a new sign-in with the role.

The catalog runtime smoke in CI (`deploy/tests/catalog-runtime-smoke.py`)
promotes its administrator by running the `sh -c` command and the SQL of this
block as written here, so keep the block's form when editing it.

## Disable an account, remove an admin role, or sign out everywhere

Run the following with the same `psql -X -v ON_ERROR_STOP=1` connection as above. Set `user_id` to the verified account
UUID and choose exactly one operation: `disable_account`, `remove_admin_role`, or `sign_out_everywhere`.

```sql
\set user_id '00000000-0000-0000-0000-000000000000'
\set disable_account false
\set remove_admin_role false
\set sign_out_everywhere true

BEGIN;
SELECT id FROM users WHERE id = :'user_id'::uuid FOR UPDATE;
\if :disable_account
    UPDATE users SET status = 'DISABLED' WHERE id = :'user_id'::uuid;
\elif :remove_admin_role
    DELETE FROM user_roles WHERE user_id = :'user_id'::uuid
      AND role_id = (SELECT id FROM roles WHERE name = 'ROLE_ADMIN');
\elif :sign_out_everywhere
    -- The epoch and revocation below are the sign-out operation.
\else
    ROLLBACK;
    \quit
\endif
UPDATE users SET auth_epoch = auth_epoch + 1, updated_at = now() WHERE id = :'user_id'::uuid;
UPDATE auth_sessions SET revoked_at = now() WHERE user_id = :'user_id'::uuid AND revoked_at IS NULL;
COMMIT;
```

Refresh and admin operations enforce the change when the transaction commits. Ordinary bearer and HLS requests enforce
it within 30 seconds. A user who retains an active account can sign in again. Session rows and their refresh tokens
are pruned in batches once they have been expired or revoked for more than 30 days.

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

On a non-default port, include it in the browser URL, for example `http://localhost:8088`.
Same-origin requests need no CORS allowlist entry: Nginx overwrites the forwarded host, port,
scheme and client IP, and clears RFC `Forwarded`. The port comes from the browser's Host authority
(including bracketed IPv6), otherwise 80 for HTTP or 443 for HTTPS, never the container port.
Tomcat's native valve accepts these values only from Nginx's fixed `/32` on `vod_proxy`;
outside Compose it trusts only loopback. A private address alone confers no trust.
Keep the backend port unpublished; only Nginx may publish service ports. Cross-origin clients
still need the CORS allowlist. ADR-012's separate auth Origin check is implemented by Issue 6.9.
Spring's HSTS writer is disabled; HTTPS/HSTS rollout remains an edge decision (#72, #136).
A load balancer or CDN in front of Nginx requires explicit `real_ip` configuration and a review
of `X-Forwarded-For` and client-IP trust before deployment (#51, #136).

### Proxy Network Configuration

Only Nginx/backend join `vod_proxy`; frontend stays on `vod_edge` with Nginx, and backend also
joins `vod_internal`. The link is IPv4-only and preserves backend egress. Defaults work with plain
`docker compose up`: `VOD_PROXY_SUBNET=10.94.250.0/29`, `VOD_PROXY_NGINX_IP=10.94.250.2`,
`VOD_PROXY_BACKEND_IP=10.94.250.3`. Both addresses are static so backend startup cannot take the
trusted proxy IP and backend recreation preserves Nginx's upstream address.

If Docker reports `Pool overlaps` or `address already in use`, inspect existing host/Docker routes
and choose an unused IPv4 subnet. Change **all three** variables together in the protected env
file. Addresses must be distinct usable members, excluding network, gateway (normally `.1`) and
broadcast. Compose derives backend trust from the Nginx address automatically. Recreate backend
and Nginx after changing these values; changing the subnet also requires recreating the network.
Confirm the Compose project and recoverable backups first: as in the #106 upgrade, use
`$C down --remove-orphans` **without `--volumes` or `-v`**, then `$C up -d --wait`; named volumes
are preserved. Never remove another project's network to resolve a collision.

Upgrade from the pre-#94 topology: after preflight, `$C up -d --wait --force-recreate backend frontend nginx`
creates `vod_proxy`, removes backend from `vod_edge` and recreates the three services. Volumes
are untouched. Verify aggregate health, same-origin login and the frontend/internal-peer negative
probes. Disposable smoke stacks use their own /29, excluding this deployment default; CI's main
full stack deliberately uses the defaults.

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
- `POSTGRES_OWNER_USER`
- `POSTGRES_OWNER_PASSWORD`
- `POSTGRES_BACKEND_USER`
- `POSTGRES_BACKEND_PASSWORD`
- `POSTGRES_WORKER_USER`
- `POSTGRES_WORKER_PASSWORD`
- `REDIS_PASSWORD`
- `RABBITMQ_USERNAME`
- `RABBITMQ_PASSWORD`
- `RABBITMQ_BACKEND_USERNAME`
- `RABBITMQ_BACKEND_PASSWORD`
- `RABBITMQ_WORKER_USERNAME`
- `RABBITMQ_WORKER_PASSWORD`
- `MINIO_ACCESS_KEY`
- `MINIO_SECRET_KEY`
- `MINIO_BACKEND_ACCESS_KEY`
- `MINIO_BACKEND_SECRET_KEY`
- `MINIO_WORKER_ACCESS_KEY`
- `MINIO_WORKER_SECRET_KEY`
- `JWT_SECRET`

`POSTGRES_USER`/`POSTGRES_PASSWORD` (the cluster's bootstrap superuser) and
`RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD` (the broker administrator) are reserved
for the servers, the one-shot `postgres-init` job, broker definitions and
explicit operator maintenance (#106). Flyway migrates as the schema owner
(`POSTGRES_OWNER_*`); the backend and worker receive only their own
`POSTGRES_BACKEND_*`/`POSTGRES_WORKER_*` and `RABBITMQ_BACKEND_*`/`RABBITMQ_WORKER_*`
identities, and only the backend receives `REDIS_PASSWORD`. The roles, grants
and permission patterns are in
[Service identities and network](../architecture/INFRASTRUCTURE.md#service-identities-and-network).
User names must differ from the other identities of the same service, and no
secret may be reused by any other identity, including the storage secrets and
`JWT_SECRET`. PostgreSQL role names use lowercase letters, digits and `_`, do
not start with `pg_` and have at most 63 characters. `REDIS_PASSWORD` must not
contain whitespace or quotes. Leave `SPRING_DATASOURCE_URL` unset: the
backend and worker derive it from `POSTGRES_HOST`, `POSTGRES_PORT` and
`POSTGRES_DB` exactly as Flyway does, and preflight rejects an explicit URL
that differs.

Root storage keys are reserved for bootstrap and explicit operator maintenance, inside a one-off internal `minio-init` container; applications receive only scoped identities. Supply distinct backend/worker identities and secrets, also distinct from root, before rerunning `minio-init` and starting the applications on an existing volume. Do not reuse an operator account or attach extra policies/groups to these dedicated users. See [the IAM policy and SDK contract](../architecture/INFRASTRUCTURE.md#storage-identities-and-policies); account provisioning does not require deleting storage data. All three storage access keys require at least three characters, no `=` or `,`, and no leading/trailing whitespace; all three secrets require at least eight characters. Preflight reports the variable name only.

It must also set `NGINX_HTTP_PORT=80`, as described in
[Edge Port Binding](#edge-port-binding).

The overlay passes JVM options to the backend and worker through `JAVA_TOOL_OPTIONS` (#124):
`-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError` for the backend and
`-XX:MaxRAMPercentage=30 -XX:+ExitOnOutOfMemoryError` for the worker. Each heap then follows its container
memory limit, and an `OutOfMemoryError` ends the JVM, so `restart: unless-stopped` replaces it. Without the
exit, a JVM that ran out of memory could keep passing the TCP health check. The worker's share is smaller
because FFmpeg runs inside the same memory limit. `.env.production` may override the defaults with
`BACKEND_JAVA_TOOL_OPTIONS` and `WORKER_JAVA_TOOL_OPTIONS`. The preflight then requires
`-XX:+ExitOnOutOfMemoryError` as the last `ExitOnOutOfMemoryError` setting, and a last `-XX:MaxRAMPercentage`
that is a whole number from 1 to 100. It rejects `-Xmx`, `-Xms`, `-Xmn`, `-XX:MaxHeapSize`, `-XX:InitialHeapSize`,
`-XX:MinHeapSize`, `-XX:NewSize`, `-XX:OldSize`, `-XX:MaxRAM` and `-XX:-UseContainerSupport`, which would let a
heap ignore its memory limit, and quotes and
backslashes, which the JVM would parse differently. The [worker encode measurement](../testing/WORKER-ENCODE-MEASUREMENT.md)
records the resulting heaps and memory peaks, from which the owner confirms or changes these defaults.

The production-only `production-config-check` service rejects the published
development placeholders and a loopback-bound edge before PostgreSQL,
RabbitMQ, MinIO, or Nginx can start. It reports only the rejected variable name
and never its value. Each application, Flyway and each initialization job
receives only the identity listed above for it; no two services share a
credential. Starting production applications or
provisioning with `--no-deps` is unsupported because that option bypasses the
dependency gates. The one intended production exception is the explicit root
maintenance shell below, after preflight and the target storage server have
been verified; it must not start applications or replace the normal init path.

The preflight validates the configuration being supplied; it does not itself rotate persisted credentials. A subsequent `minio-init` run updates the configured scoped storage secrets and re-enables those users. Use [Service Identity Rotation](#service-identity-rotation) for PostgreSQL, RabbitMQ and Redis, and the storage procedure below for Silo.

Run the repository regression check before a deployment:

```bash
sh deploy/tests/production-config-smoke.sh
```

The script uses synthetic test-only values. It verifies that local Compose
still accepts its development defaults and publishes the edge on loopback only,
missing production values fail during configuration, published defaults and a
loopback-bound edge fail the runtime preflight, invalid storage key/secret formats fail with variable names only, and non-default values pass.
It also checks that every PostgreSQL, RabbitMQ, Redis and storage identity is
independent, that unsafe role names and Redis passwords fail, that a datasource
URL differing from Flyway's fails, and that each service receives only its own
identity. `python3 deploy/tests/service-identity-smoke.py --project <name>`
proves on a fresh disposable stack that each PostgreSQL role and Redis client
can do only its own operations; see [Testing Docs](../testing/README.md#service-identity-checks-106).

The smoke also checks that the JVM options reach both the preflight and the JVMs, and that the preflight rejects an
override without the `OutOfMemoryError` exit.

## Service Identity Rotation

This is an operator procedure for an explicitly authorized environment, with the
same precautions as the storage procedure below: confirm the Compose project and
environment file, keep a recoverable backup and the previous configuration,
never print secrets, enable shell tracing or pass secrets as arguments. The
examples use production; for a local stack use `--env-file .env` and the base
file only. `C` stands for the full command prefix:

```bash
C="docker compose -p $PROJECT --env-file .env.production -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml"
```

**PostgreSQL application roles.** Generate a new independent secret for the
affected `POSTGRES_OWNER_PASSWORD`, `POSTGRES_BACKEND_PASSWORD` or
`POSTGRES_WORKER_PASSWORD`. `postgres-init` sets the configured passwords on
every run, so run `$C run --rm production-config-check`, then
`$C run --rm postgres-init`, and stop if either fails. Then recreate only the
consumer: `$C run --rm flyway` for the owner, `$C up -d backend` or
`$C up -d worker` for an application role. Verify aggregate `/actuator/health`
for the backend; it does not check the worker. After a worker rotation, require
HTTP 200 from the worker's internal health endpoint, as in step 3 of
[Storage Identity Rotation and Revocation](#storage-identity-rotation-and-revocation).

**PostgreSQL bootstrap superuser.** `POSTGRES_PASSWORD` is applied only when the
volume is first initialized, and the user name cannot change afterwards. Set the
new password inside the container over the local socket, where psql prompts for
it, then store the same value in the environment file and confirm it by rerunning
`production-config-check` and `postgres-init`:

```bash
$C exec postgres sh -c 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "\password"'
```

**RabbitMQ.** The broker imports its definitions at every boot, which overwrites
the configured users' passwords and permissions, so a changed broker secret needs
a broker restart. After preflight, run `$C up -d rabbitmq` and then `$C up -d`
for the affected application. Restarting the broker interrupts both applications
briefly; the durable queue keeps its persistent jobs, and the relay retries jobs
whose publication was not confirmed ([Encoding Recovery](#encoding-recovery)).
Verify the backend through aggregate `/actuator/health` and the worker through
its internal health endpoint (step 3 of the storage rotation).

**Redis.** After preflight, `$C up -d redis backend` recreates the server with
the new `requirepass` and the backend with the new password.

**Retired identities.** Changing a user name leaves the old identity in place.
For RabbitMQ, run `$C exec rabbitmq rabbitmqctl delete_user "$OLD_USER"` after
the new user works. For PostgreSQL, rerun `postgres-init` and Flyway with the new
name first: init moves the relations to a new owner, and Flyway grants the
application roles. Then remove the reviewed old role; the guard refuses the
bootstrap user and any configured role:

```bash
OLD_ROLE='reviewed_retired_role'   # never the bootstrap user or a configured role
$C exec -T postgres sh -c '
  for active in "$POSTGRES_USER" "$2" "$3" "$4"; do [ "$1" != "$active" ] || exit 1; done
  psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v old="$1" -v owner="$2"' \
  sh "$OLD_ROLE" "$POSTGRES_OWNER_USER" "$POSTGRES_BACKEND_USER" "$POSTGRES_WORKER_USER" <<'SQL'
REASSIGN OWNED BY :"old" TO :"owner";
DROP OWNED BY :"old";
DROP ROLE :"old";
SQL
```

Export the three configured role names from the environment file first. A
non-zero exit means nothing was dropped or the role still holds privileges
elsewhere; investigate before retrying.

**Adopting #106 on an existing deployment.** Plan a maintenance window and take
a recoverable PostgreSQL backup first. If the deployment still uses RabbitMQ 3.13,
follow [RabbitMQ Upgrade (#74)](#rabbitmq-upgrade-74) before starting the stack
with the new broker image. With the #106 revision checked out and the new
variables in the protected environment file:

1. Run `$C run --rm production-config-check` and stop if it fails. The check
   has no network, so it does not touch the running stack.
2. Run `$C down --remove-orphans`, never with `--volumes` or `-v`. #106 makes
   `vod_internal` an `internal: true` network and moves `nginx` and `frontend`
   to `vod_edge`. Compose recreates a network whose definition changed, but it
   disconnects only the services the new configuration attaches to it, so a
   plain `up` fails while the old `nginx` and `frontend` containers still hold
   endpoints, after it has already stopped the data services. `down` removes
   the project's containers and networks and keeps the named volumes.
3. Run `$C up -d --wait` and verify aggregate `/actuator/health`.

The first `postgres-init` run transfers the existing tables to the schema owner
and removes `PUBLIC` access. It fails at `role membership check` if a configured
role already exists and holds a membership in another role, such as
`pg_read_all_data`: review the grant, `REVOKE` it as the bootstrap superuser,
and rerun `postgres-init`. Because the applications held the bootstrap
PostgreSQL and RabbitMQ identities before #106, rotate both bootstrap passwords
afterwards as above, just as the storage root pair is rotated after #169.

## Storage Identity Rotation and Revocation

This is an operator procedure for an explicitly authorized environment. Confirm the Compose project, environment file and storage volume before changing identities. Take a recoverable backup and preserve the previous configuration securely. Never print secrets, enable shell tracing, pass secrets as CLI arguments, or paste IAM inventories into logs/issues. Keep storage ports unpublished.

Init creates or overwrites the configured backend/worker users, re-enables them and attaches the corresponding policy. It does not remove an old user when an access key changes, detach other policies or reconcile unconfigured identities. A manual disable is undone if that key is still configured on the next init. Keep these dedicated users free of other policies, groups and child credentials.

### Routine Secret Rotation

1. Generate a fresh independent random secret in the protected environment file for the affected `MINIO_BACKEND_SECRET_KEY` or `MINIO_WORKER_SECRET_KEY`, retaining its access key. Follow the preflight format rules; do not put the value in command history.
2. During an approved interruption of affected reads/writes, require preflight and `minio-init` to exit 0, then apply the updated environment to only the affected app through the normal production dependency path. Select `backend` for `MINIO_BACKEND_*` or `worker` for `MINIO_WORKER_*`; do not restart both for a single-role rotation. For production, run:

   ```bash
   STORAGE_APP=backend  # use worker only when rotating MINIO_WORKER_*
   case "$STORAGE_APP" in backend|worker) ;; *) exit 1;; esac
   previous_id=$(docker compose -p "$PROJECT" --env-file .env.production -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml ps -q "$STORAGE_APP") || exit 1
   docker compose -p "$PROJECT" --env-file .env.production -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml run --rm production-config-check || exit 1
   docker compose -p "$PROJECT" --env-file .env.production -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml run --rm minio-init || exit 1
   docker compose -p "$PROJECT" --env-file .env.production -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml up -d "$STORAGE_APP" || exit 1
   current_id=$(docker compose -p "$PROJECT" --env-file .env.production -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml ps -q "$STORAGE_APP") || exit 1
   test -n "$current_id" && test "$current_id" != "$previous_id" || exit 1
   ```

   Stop if any command fails. Compose recreates the affected app because its configured credentials changed; unchanged dependencies follow their normal health/completion gates.
3. Verify the affected application's readiness, its scoped read/write operations and rejection of the previous secret. The aggregate `/actuator/health` through Nginx checks only the backend identity. For a worker rotation, wait for the new container to start and require HTTP 200 from the worker's internal health endpoint. The worker response includes its own authenticated raw storage check and JDBC check. Keep the worker port unpublished.

   ```bash
   docker compose -p "$PROJECT" --env-file .env.production -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml exec -T worker timeout 15 bash -c '
     exec 3<>"/dev/tcp/127.0.0.1/${SERVER_PORT}" || exit 1
     printf "GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3
     IFS= read -r status <&3 && [[ $status == "HTTP/1.1 200 "* ]]
   ' || exit 1
   ```

   A secret update alone is not incident revocation. Silo STS sessions issued from the old credentials can remain valid.

### Incident Response and Retired Users

1. Generate a **new access key and secret** for the affected role and replace that pair in the protected environment file. Keep the compromised/retired access key available only to the operator performing revocation.
2. Run preflight, init and the normal `up` path for only the affected app as above. Confirm the container ID changed, verify the affected application's readiness and check the replacement identity before deleting the old parent user. Do not fall back to a compromised key.
3. Authenticate as root in a one-off internal container and run `mc admin user remove` on the old key. Root is required here because the scoped users cannot administer other users. This maintenance shell is the intended production `--no-deps` exception: use it only after the normal preflight/init path and target storage health have succeeded, without starting apps or rerunning provisioning. Open it with the verified project/environment (the service receives root credentials through its existing environment):

   ```bash
   docker compose -p "$PROJECT" --env-file .env.production -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml run --rm --no-deps --entrypoint /bin/sh minio-init
   # Inside the ephemeral container; do not enable set -x:
   umask 077
   printf '%s\n%s\n' "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" | mc alias set vod-store http://minio:9000 >/dev/null 2>&1 || exit 1
   # Set OLD_ACCESS_KEY to the reviewed retired access key, never the active/root key.
   if ! { test -n "$OLD_ACCESS_KEY" && test "$OLD_ACCESS_KEY" != "$MINIO_ACCESS_KEY" && test "$OLD_ACCESS_KEY" != "$MINIO_BACKEND_ACCESS_KEY" && test "$OLD_ACCESS_KEY" != "$MINIO_WORKER_ACCESS_KEY"; }; then
     echo 'Retired user selection failed' >&2
     exit 1
   fi
   mc admin user remove vod-store "$OLD_ACCESS_KEY" >/dev/null 2>&1 || exit 1
   ```

   Check every command succeeds before proceeding; stop on any failed guard. Removing the old parent user revokes its service accounts and STS sessions. A regular Silo user can call STS AssumeRole despite the scoped policy: policy cannot deny this path. The explicit Deny on self service-account creation/password change prevents those two operations, but neither changing a secret nor disabling a user is the incident closeout mechanism.
4. In the same root container, run `mc admin policy entities vod-store --policy vod-backend` and `mc admin policy entities vod-store --policy vod-worker`. Inspect the inventory privately: only the currently configured corresponding users may hold these policies, with no unexpected groups/accounts. Remove each reviewed stale identity, verify old credentials and any known child sessions are rejected, then exit so the alias credentials disappear with the container. Record safe verification outcomes without credentials.

After adopting scoped app identities, rotate the root pair as well: applications held root before #169. Update the protected root configuration and recreate the storage server/init in a planned maintenance window, preserving the volume; verify root authentication, init, private buckets and app readiness. Keep a secure rollback configuration until verification, but do not restore a compromised parent identity to recover service.

## Object Storage Backup and Restore

Object storage is PGSTY Silo under the existing `minio` service/volume names ([ADR-004 addendum](../architecture/adr/ADR-004.md#runtime-addendum)). Both server and init are pinned to the same verified index digest. The console is off and S3/console ports are unpublished in local and production Compose. Curl `/minio/health/live` remains the server health probe; backend storage readiness separately authenticates with its scoped identity. Use the following offline procedure before a version change; never test upgrades on the only copy of data.

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

Use a temporary Compose overlay to point `minio.volumes` to that restored external volume, and pin the server/init to the digest recorded with the backup. Validate health, object checksums, metadata and all bucket policies on an isolated copy before reconnecting readers/writers. The backup restores IAM metadata too: a user removed after the backup can become valid again. With readers/writers still disconnected, rerun init using the current protected configuration, then authenticate as root in a one-off internal `minio-init` container and remove identities no longer configured. Audit both policy memberships and verify retired/compromised credentials and child sessions are rejected, following [revocation](#incident-response-and-retired-users). Only then reconnect apps with their current scoped credentials and verify readiness. Do not start an older binary against metadata written by a newer version: rollback restores **both** the backup and its matching digest. Do not run `down -v` during a deployment/rollback. Establish retention, permissions and an independently tested restore before public production use. The #14 spike proved restart/recreation on a Silo-created volume, not restoration of real production data or migration from an upstream-created volume.

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

## RabbitMQ Upgrade (#74)

The frozen broker baseline is RabbitMQ 4.4 classic queues, pinned to a multi-arch index digest with a fixed `hostname: rabbitmq`. No consumer acknowledgement timeout is configured. The existing local 3.13 broker volume cannot be treated as an automatic supported in-place upgrade: a new hostname selects a new node directory, and the 4.4 classic queue store format cannot be downgraded.

**Before changing any broker with data:** quiesce uploads, backend relay and the one worker; record `rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers` and alarms; enable all stable feature flags on the current version; stop the broker; then make an independently restorable backup of the `rabbitmq_data` volume. That backup contains password hashes and message bodies: keep it private and outside the repository. An in-place multi-series upgrade requires a supported hop sequence and a separate owner-approved rehearsal (https://www.rabbitmq.com/docs/upgrade). Do not attempt a direct 3.13 -> 4.4 jump on an existing data directory.

For **disposable local 3.13 data**, the approved default is a fresh 4.4 node. Record any QUEUED jobs and reconcile them by re-uploading test media or, with a separately approved, quiesced exact-job review, following the guarded `published_at` recovery procedure below. The old node directory can remain inert; removing a volume or its contents is destructive and requires explicit owner authorization. Never run `down -v` as an upgrade shortcut.

For **future 4.4 patch and series upgrades**, verify official support, digests, SBOM/provenance and feature flags, back up the volume with the old broker stopped, then start the supported target image on the same `hostname: rabbitmq`; wait for `rabbitmqctl await_startup`, enable stable feature flags and check queue counts, message persistence, health and the broker/Erlang versions before restoring the relay and worker. Roll back only by restoring the previous volume backup together with its previous image digest: RabbitMQ does not support in-place downgrades, and 4.4 `.sqs` store data is not compatible with 4.3 or earlier. Follow the [broker image update procedure](../architecture/INFRASTRUCTURE.md#broker-runtime-image).

## Encoding Recovery

This is the approved procedure for the future Sprint 4/5 pipeline ([ADR-010](../architecture/adr/ADR-010.md)), not evidence that it exists at `24873be`. Exercise it against disposable local fixtures in Issues 4.5/5.7 before operational use. Commands below are operator procedures; this documentation task does not execute them. Confirm the project/environment/volume identities and obtain explicit authorization before mutating shared or production state. Preserve DB/storage backups for data-repair operations.

1. Inspect safe job IDs/status/queued/started/finished/publication timestamps, worker logs and queue counts. A long QUEUED wait is valid with concurrency 1; do not fail it by age. QUEUED with `published_at NULL` belongs to the backend relay. Check confirms/returns, broker route and relay health first.
2. A wedged worker needs a controlled restart: with the verified environment/project and Compose base (plus prod overlay only for production), run `docker compose ... restart worker`. Do not scale up or overlap a replacement worker. The 30s grace stops the old container; on restart, listener remains stopped until owned `job-*` temp sweep, conditional PROCESSING -> FAILED `WORKER_INTERRUPTED`, and attempt-prefix cleanup finish. If DB recovery fails, the listener must stay stopped. Confirm cleanup bytes/outcomes and final paired state.
3. Before Sprint 9, re-upload after FAILED. With Sprint 9, retry only the movie's newest FAILED asset; double retry produces one accepted attempt and one 409. A malformed/unsupported-version/ID-mismatch message, or an unexpected pre-claim exception, may dead-letter into `<RABBITMQ_ENCODING_QUEUE>.dlq` while its committed job/asset pair remains QUEUED with `published_at` set. Inspect counts with `rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers`. No public management UI or automatic DLQ replay. Fix and verify the producer/schema/consumer cause, then identify an existing QUEUED job/asset pair from trusted DB records, not from unvalidated DLQ IDs. Recover that exact job using step 4's guarded `published_at` reset with the same quiesce and approval; the relay rebuilds the canonical message from DB. Do not blindly replay the bad DLQ payload or reset terminal/missing/mismatched DB rows; never bulk dump sensitive payloads.
4. An exceptional confirmed-delivery repair covers either broker data loss or a corrected DLQ cause for an existing QUEUED job/asset pair from step 3. Stop new uploads/retries and the relay (quiesce edge/backend), stop worker, ensure no other publisher/consumer, inspect an empty source queue with zero unacknowledged deliveries, and verify no PROCESSING jobs/assets. Snapshot the exact QUEUED job IDs selected for repair. This is not the normal unpublished-job case. In one approved transaction, lock the selected rows and reset only their `published_at`; do not rewrite lifecycle status or attempts. An example for **one reviewed job ID**, using a psql variable `job_id`, is:

```sql
BEGIN;
SELECT j.id, j.video_asset_id, j.status, j.published_at
FROM encoding_jobs j JOIN video_assets a ON a.id = j.video_asset_id
WHERE j.id = :'job_id'::uuid
FOR UPDATE OF j, a;
-- Expect exactly the reviewed QUEUED job and QUEUED asset; otherwise ROLLBACK.
UPDATE encoding_jobs j SET published_at = NULL
FROM video_assets a
WHERE j.id = :'job_id'::uuid AND j.video_asset_id = a.id
  AND j.status = 'QUEUED' AND a.status = 'QUEUED'
  AND j.published_at IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM encoding_jobs WHERE status = 'PROCESSING')
  AND NOT EXISTS (SELECT 1 FROM video_assets WHERE status = 'PROCESSING');
-- Expect UPDATE 1; otherwise ROLLBACK and investigate before COMMIT.
COMMIT;
```

Restore backend/relay and the one worker, then edge only after health/status/queue checks. Relay republishes the same job ID; conditional claim makes duplicates safe. Recovery from an incorrect repair is a reviewed DB backup restore, never a blanket status update. On the #74 baseline (RabbitMQ 4.4 classic queues) no consumer timeout is evaluated; a DB outage at claim/finish retries in-process with capped backoff and must not become FAILED or DLQ.

## Media Purge and Orphan Audit

The frozen retention rule is [ADR-010](../architecture/adr/ADR-010.md#hls-objects-and-cleanup-d22-d23). This procedure is exercised in Issue 10.6 (Sprint 10/Phase 5) and must be exercised on isolated fixtures before use. It does not authorize production deletion. Keep DB rows as history, label superseded rows in admin views and treat their old keys as possibly absent. Do not alter a historical READY row solely to make it retryable.

For an authorized purge, stop uploads/retries, the backend relay and worker, and quiesce playback during the final review/delete window. This prevents a newer asset/retry from changing candidate classification between selection and deletion. Take a recoverable DB/object backup, confirm the actual private bucket names, and review this read-only PostgreSQL candidate query:

```sql
WITH ranked AS (
    SELECT a.*, row_number() OVER (
        PARTITION BY movie_id ORDER BY created_at DESC, id DESC
    ) AS newest_rank
    FROM video_assets a
), current_ready AS (
    SELECT DISTINCT ON (movie_id) movie_id, id
    FROM video_assets WHERE status = 'READY'
    ORDER BY movie_id, created_at DESC, id DESC
)
SELECT a.id, a.movie_id, a.status, a.raw_bucket, a.raw_object_key,
       a.hls_bucket, a.hls_master_object_key
FROM ranked a
JOIN titles m ON m.id = a.movie_id
LEFT JOIN current_ready c ON c.movie_id = a.movie_id
WHERE m.status <> 'ARCHIVED'
  AND ((a.status = 'READY' AND a.id IS DISTINCT FROM c.id)
       OR (a.status = 'FAILED' AND a.newest_rank > 1))
ORDER BY a.movie_id, a.created_at, a.id;
```

Exclude every archived movie, current asset, newest FAILED raw and in-flight asset from deletion. Re-run the candidate query after quiescing and immediately before deletion. For each reviewed asset, enumerate attempts from DB rather than object-list guesses:

```sql
SELECT id AS encoding_job_id, video_asset_id, attempt, status,
       'video-assets/' || video_asset_id || '/attempt-' || attempt || '/' AS attempt_prefix
FROM encoding_jobs
WHERE video_asset_id = :'asset_id'::uuid
ORDER BY attempt;
```

Only terminal attempts qualify. Cross-check a stored master key, when present, against the generated same-asset/attempt prefix; reject malformed keys, empty raw keys/attempt prefixes and all bucket roots. A prefix must end with `/` so `attempt-1/` cannot select `attempt-10/`. Purge and orphan audit require **root as an explicit operator identity**, inside a one-off internal `minio-init` container: use the shell/secret-through-stdin alias setup in [the revocation procedure](#incident-response-and-retired-users), omitting the user-removal commands. The scoped app identities cannot list and are unsuitable for inventory or `mc rm --recursive` dry-runs. The root alias `vod-store` lasts only in that ephemeral container; never publish a storage port. Set `RAW_BUCKET`, `RAW_KEY` and `ATTEMPT_PREFIX` only from those reviewed DB rows. Use the row's `hls_bucket` for `HLS_BUCKET` when present; for an asset without it (including superseded FAILED, where no READY write occurred), use the verified private bucket from the deployment's `MINIO_BUCKET_HLS` for its DB-enumerated attempt prefixes. Confirm that configured bucket against the actual private Silo bucket before dry-run; refuse an empty/unverified bucket and never guess a bucket root. This permits purging remnants of failed best-effort attempt cleanup. Verify the exact targets before running:

```bash
# Preview each exact raw object and exact attempt prefix; inspect the output.
mc rm --dry-run "vod-store/$RAW_BUCKET/$RAW_KEY"
mc rm --dry-run --recursive --force "vod-store/$HLS_BUCKET/$ATTEMPT_PREFIX"
# Only after approval of those exact targets and the preview:
mc rm "vod-store/$RAW_BUCKET/$RAW_KEY"
mc rm --recursive --force "vod-store/$HLS_BUCKET/$ATTEMPT_PREFIX"
```

These examples are not a bulk script. Refuse unset/empty variables, non-private buckets, any `..`/unexpected key structure, current/non-terminal assets or archive media. Log one safe result per deletion with asset/job IDs; keep the approved manifest and failure list outside Git. Repeating an already successful deletion is safe, but never broaden a failed target to a bucket root. Verify protected objects still exist, terminal DB history is unchanged and playback resolves the current prefix before resuming writers. Restore a matching backup to recover a mistaken deletion.

Orphan audit covers crash-before-DB raw objects and abandoned multipart uploads, not a scheduled janitor. Compare a private object/incomplete-upload inventory against **all** DB raw/HLS references while writers are quiesced. Missing DB references alone are insufficient: require proof of an interrupted upload, no active request/job, and explicit approval of the exact orphan key. Use `mc rm --dry-run` first; for a proven abandoned multipart upload use `mc rm --incomplete --dry-run` then `mc rm --incomplete` on that exact object target, never recursively at bucket root. Keep raw for newest FAILED/current/in-flight assets and preserve everything on archive. The single known-limitation statement lives in ADR-010; no retention scheduler or ILM is introduced here.
