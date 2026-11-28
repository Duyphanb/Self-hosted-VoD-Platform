#!/usr/bin/env python3
"""Rehearse V8 on a populated V7 PostgreSQL and compare every retained column."""
from pathlib import Path
import subprocess
import time
import uuid


ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "backend/common/src/main/resources/db/migration"
CALLBACKS = ROOT / "deploy/flyway"
POSTGRES = "postgres:16.15-alpine@sha256:0cefaabd882e42bcb2a2f3dc56d3ce4770560ba62fd7f197f1a2e008fd5731ab"
FLYWAY = "flyway/flyway:12.10.0"


def docker(*args, data=None, check=True):
    result = subprocess.run(["docker", *args], input=data, capture_output=True, text=True, encoding="utf-8",
                            timeout=140)
    if check and result.returncode:
        raise RuntimeError(f"Docker {args[0]} exited {result.returncode}")
    return result


def main():
    container = "vod-episodes-migration-" + uuid.uuid4().hex[:12]
    docker("run", "-d", "--name", container, "--network", "none", "-e", "POSTGRES_USER=fixture",
           "-e", "POSTGRES_PASSWORD=fixture-only", "-e", "POSTGRES_DB=fixture",
           "-e", "POSTGRES_INITDB_ARGS=--locale-provider=icu --icu-locale=vi", POSTGRES)

    def sql(statement):
        return docker("exec", "-i", container, "psql", "-XqAt", "-v", "ON_ERROR_STOP=1", "-U", "fixture",
                      "-d", "fixture", data=statement).stdout.strip()

    def migrate(target, check=True):
        return docker("run", "--rm", "--network", f"container:{container}",
                      "-v", f"{MIGRATIONS}:/flyway/sql:ro", "-v", f"{CALLBACKS}:/flyway/callbacks:ro",
                      "-e", "FLYWAY_URL=jdbc:postgresql://localhost:5432/fixture",
                      "-e", "FLYWAY_USER=fixture_owner", "-e", "FLYWAY_PASSWORD=owner-fixture-only",
                      "-e", "FLYWAY_PLACEHOLDERS_BACKEND_ROLE=fixture_backend",
                      "-e", "FLYWAY_PLACEHOLDERS_WORKER_ROLE=fixture_worker", FLYWAY,
                      "-locations=filesystem:/flyway/sql,filesystem:/flyway/callbacks",
                      "-validateMigrationNaming=true", f"-target={target}", "migrate", check=check)

    try:
        for _ in range(60):
            ready = docker("exec", container, "pg_isready", "-h", "127.0.0.1", "-U", "fixture", "-d", "fixture",
                           check=False)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("PostgreSQL fixture did not start")
        sql("""
            CREATE ROLE fixture_owner LOGIN PASSWORD 'owner-fixture-only';
            CREATE ROLE fixture_backend;
            CREATE ROLE fixture_worker;
            ALTER SCHEMA public OWNER TO fixture_owner;
            REVOKE ALL ON SCHEMA public FROM PUBLIC;
            GRANT USAGE ON SCHEMA public TO fixture_backend, fixture_worker;
            """)
        migrate(7)
        seed(sql)
        sql("SET ROLE fixture_owner; CREATE VIEW current_episode_assets AS SELECT 1 AS forced_collision;")
        failure = migrate(8, check=False)
        failure_output = failure.stderr + failure.stdout
        assert failure.returncode and 'current_episode_assets' in failure_output, "Missing late failure"
        assert sql("SELECT to_regclass('public.episodes') IS NULL AND "
                   "(SELECT max(version) = '7' FROM flyway_schema_history);") == "t"
        assert sql("SELECT count(*) FROM "
                   "(TABLE public.video_assets EXCEPT ALL TABLE before_v8.video_assets) rows;") == "0"
        sql("SET ROLE fixture_owner; DROP VIEW current_episode_assets;")
        print("Late DDL failure rolled V8 back to V7", flush=True)

        migrate(8)
        for name in ("titles", "video_assets", "playback_progress", "watch_history", "encoding_jobs"):
            compare_rows(sql, name)
        assert sql("""
            SELECT count(*) FROM titles title LEFT JOIN episodes episode ON episode.title_id = title.id
            WHERE episode.id IS NULL OR episode.id = title.id OR NOT (
                episode.ordinal = 1 AND episode.status = 'PUBLISHED' AND title.content_type = 'MOVIE');
            """) == "0"
        assert sql("SELECT string_agg(asset_id::text, ',') FROM current_episode_assets;") == (
            "80000000-0000-0000-0000-000000000000")
        assert sql("SELECT count(*) FROM flyway_schema_history WHERE version = '8' AND success;") == "1"
        assert sql("""
            SELECT count(*) FROM pg_tables tables
            CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'),
                               ('REFERENCES'), ('TRIGGER')) privilege(name)
            WHERE tables.schemaname = 'public' AND (
              has_table_privilege('fixture_backend', format('public.%I', tables.tablename), privilege.name) <>
                (tables.tablename <> 'flyway_schema_history'
                 AND privilege.name IN ('SELECT','INSERT','UPDATE','DELETE'))
              OR has_table_privilege('fixture_worker', format('public.%I', tables.tablename), privilege.name) <>
                (tables.tablename IN ('encoding_jobs','video_assets') AND privilege.name IN ('SELECT','UPDATE')));
            """) == "0"
        assert sql("SELECT has_table_privilege('fixture_backend','public.current_episode_assets','SELECT') "
                   "AND NOT has_table_privilege('fixture_worker','public.current_episode_assets','SELECT');") == "t"
        print("V8 bidirectional EXCEPT ALL, current READY UUID tie, history and grants passed", flush=True)
    finally:
        docker("rm", "-f", "-v", container)
        assert docker("inspect", container, check=False).returncode != 0


def compare_rows(sql, name):
    expression = "to_jsonb(entry)"
    source = f"public.{name} entry"
    if name == "titles":
        expression += " - 'content_type' - 'part_label'"
    if name in ("video_assets", "playback_progress", "watch_history"):
        expression = "(to_jsonb(entry) - 'episode_id') || jsonb_build_object('movie_id', episode.title_id)"
        source += " JOIN episodes episode ON episode.id = entry.episode_id"
    difference = sql(f"""
        WITH before_rows AS (SELECT to_jsonb(entry) AS data FROM before_v8.{name} entry),
             after_rows AS (SELECT {expression} AS data FROM {source})
        SELECT count(*) FROM (
          (TABLE before_rows EXCEPT ALL TABLE after_rows)
          UNION ALL
          (TABLE after_rows EXCEPT ALL TABLE before_rows)
        ) differences;
        """)
    assert difference == "0", f"{name} differs after re-key"


def seed(sql):
    sql(r"""
        SELECT gen_random_uuid() AS first_user, gen_random_uuid() AS second_user,
               gen_random_uuid() AS title, gen_random_uuid() AS draft, gen_random_uuid() AS queued \gset
        INSERT INTO users (id,email,password_hash,display_name,status,created_at,updated_at)
        VALUES (:'first_user','first@example.test','placeholder','First','ACTIVE',now(),now()),
               (:'second_user','second@example.test','placeholder','Second','ACTIVE',now(),now());
        INSERT INTO titles (id,title,slug,description,status,created_at,updated_at)
        VALUES (:'title','Title','title','Published fixture','PUBLISHED',now(),now()),
               (:'draft','Draft','draft','Hidden fixture','DRAFT',now(),now());
        INSERT INTO video_assets (id,movie_id,raw_bucket,raw_object_key,status,duration_seconds,created_at,updated_at)
        VALUES ('7fffffff-ffff-ffff-ffff-ffffffffffff',:'title','vod-raw','older.mp4','READY',120,now(),now()),
               ('80000000-0000-0000-0000-000000000000',:'title','vod-raw','current.mp4','READY',120,now(),now()),
               (:'queued',:'title','vod-raw','queued.mp4','QUEUED',NULL,now(),now()),
               (gen_random_uuid(),:'draft','vod-raw','failed.mp4','FAILED',NULL,now(),now());
        INSERT INTO encoding_jobs (id,video_asset_id,status,attempt,queued_at,published_at)
        VALUES (gen_random_uuid(),:'queued','QUEUED',1,now(),NULL),
               (gen_random_uuid(),'80000000-0000-0000-0000-000000000000','READY',1,now(),now());
        INSERT INTO playback_progress
             (user_id,movie_id,current_seconds,duration_seconds,finished,last_played_at,created_at,updated_at)
        VALUES (:'first_user',:'title',108,120,true,now(),now(),now()),
               (:'second_user',:'title',24,120,false,now(),now(),now());
        INSERT INTO watch_history (id,user_id,movie_id,started_at,last_watched_at,completed_at)
        VALUES (gen_random_uuid(),:'first_user',:'title',now(),now(),now()),
               (gen_random_uuid(),:'second_user',:'title',now(),now(),NULL);
        CREATE SCHEMA before_v8;
        CREATE TABLE before_v8.titles AS TABLE public.titles;
        CREATE TABLE before_v8.video_assets AS TABLE public.video_assets;
        CREATE TABLE before_v8.encoding_jobs AS TABLE public.encoding_jobs;
        CREATE TABLE before_v8.playback_progress AS TABLE public.playback_progress;
        CREATE TABLE before_v8.watch_history AS TABLE public.watch_history;
        """)


if __name__ == "__main__":
    main()
