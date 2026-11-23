ALTER TABLE users ADD COLUMN auth_epoch integer NOT NULL DEFAULT 0;

CREATE TABLE auth_sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);

CREATE INDEX ix_auth_sessions_user_created ON auth_sessions (user_id, created_at, id);
CREATE INDEX ix_auth_sessions_ended ON auth_sessions (LEAST(expires_at, revoked_at), id);

ALTER TABLE refresh_tokens ADD COLUMN session_id uuid REFERENCES auth_sessions (id) ON DELETE CASCADE;
CREATE INDEX ix_refresh_tokens_session_id ON refresh_tokens (session_id);
