-- #106 Flyway SQL callback, mounted only by Compose. Flyway runs it as the schema
-- owner after every migrate, including runs with nothing pending, and it resets
-- the application roles to exactly these table privileges: the backend has DML
-- on every application table, the worker reads and updates the encoding job and
-- video asset rows (ADR-010 claim, finish and startup recovery), and neither
-- touches the migration history. deploy/postgres-init.sh creates the roles and
-- validates their names, and Compose passes them as Flyway placeholders.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM "${backend_role}", "${worker_role}";
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM "${backend_role}", "${worker_role}";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "${backend_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "${backend_role}";
REVOKE ALL ON TABLE "${flyway:table}" FROM "${backend_role}";
GRANT SELECT, UPDATE ON TABLE encoding_jobs, video_assets TO "${worker_role}";
