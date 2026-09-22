-- Preserve existing accounts and email spelling. Never merge colliding identities.
-- The lock makes collision detection and index creation one atomic migration.
LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users GROUP BY lower(email) HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Email identity collisions exist; resolve them through an approved account recovery decision before retrying migration V4';
    END IF;
END
$$;

CREATE UNIQUE INDEX ux_users_email_identity ON users (lower(email));
