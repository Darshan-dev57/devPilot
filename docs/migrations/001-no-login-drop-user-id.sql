-- DevPilot: migrate from the OAuth-era per-user schema to the no-login schema.
--
-- Run this in the Neon SQL editor. It is idempotent: every statement checks for
-- the object before touching it, so re-running is safe.
--
-- Why: repositories and chat_sessions carried a NOT NULL user_id from when the app
-- used GitHub OAuth. Nobody sets it now, so every insert failed with
--   null value in column "user_id" of relation "repositories" violates not-null constraint
-- Hibernate ddl-auto=update cannot fix this, because it never relaxes an existing
-- NOT NULL constraint. It needs explicit DDL.

-- ---------------------------------------------------------------------------
-- 1. repositories
-- ---------------------------------------------------------------------------

-- Drop the per-user foreign key, if one exists. Named defensively because the
-- constraint name is generated and varies by how the table was first created.
DO $$
DECLARE fk_name text;
BEGIN
    FOR fk_name IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'repositories'::regclass
          AND contype = 'f'
          AND conkey = ARRAY[
              (SELECT attnum FROM pg_attribute
               WHERE attrelid = 'repositories'::regclass AND attname = 'user_id')
          ]::smallint[]
    LOOP
        EXECUTE format('ALTER TABLE repositories DROP CONSTRAINT %I', fk_name);
        RAISE NOTICE 'dropped FK %', fk_name;
    END LOOP;
END $$;

-- Remove any unique constraint that spans user_id, since it no longer expresses
-- the intent. A unique index on github_repo_id alone replaces it below.
DO $$
DECLARE uq_name text;
BEGIN
    FOR uq_name IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'repositories'::regclass
          AND contype = 'u'
          AND 2 = (SELECT count(*) FROM unnest(conkey) AS k
                   WHERE k = (SELECT attnum FROM pg_attribute
                              WHERE attrelid = 'repositories'::regclass
                                AND attname IN ('user_id', 'github_repo_id')))
    LOOP
        EXECUTE format('ALTER TABLE repositories DROP CONSTRAINT %I', uq_name);
        RAISE NOTICE 'dropped unique constraint %', uq_name;
    END LOOP;
END $$;

-- Make user_id nullable before dropping it. Dropping outright would also work,
-- but the app is mid-deploy and a nullable leftover column is harmless.
ALTER TABLE repositories ALTER COLUMN user_id DROP NOT NULL;

-- Global dedupe. Anyone can paste any public repo URL, so the same repository
-- must resolve to one row regardless of who added it. Without this, two visitors
-- pasting the same URL create two rows and index it twice, which spends the
-- server's GitHub API quota and the visitor's embedding credits for nothing.
--
-- The index is created concurrently-safe in the sense that it is built online by
-- Postgres; the table is small so a plain CREATE INDEX is fine here.
CREATE UNIQUE INDEX IF NOT EXISTS repositories_github_repo_id_key
    ON repositories (github_repo_id);

-- ---------------------------------------------------------------------------
-- 2. chat_sessions
-- ---------------------------------------------------------------------------

DO $$
DECLARE fk_name text;
BEGIN
    FOR fk_name IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'chat_sessions'::regclass
          AND contype = 'f'
          AND conkey = ARRAY[
              (SELECT attnum FROM pg_attribute
               WHERE attrelid = 'chat_sessions'::regclass AND attname = 'user_id')
          ]::smallint[]
    LOOP
        EXECUTE format('ALTER TABLE chat_sessions DROP CONSTRAINT %I', fk_name);
        RAISE NOTICE 'dropped FK %', fk_name;
    END LOOP;
END $$;

ALTER TABLE chat_sessions ALTER COLUMN user_id DROP NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. Verify
-- ---------------------------------------------------------------------------
-- Expect: repositories_github_repo_id_key present, and no NOT NULL on either
-- user_id column. Run this after the migration to confirm.
--
-- SELECT conname, contype, pg_get_constraintdef(oid)
--   FROM pg_constraint
--  WHERE conrelid IN ('repositories'::regclass, 'chat_sessions'::regclass);
--
-- SELECT indexname, indexdef FROM pg_indexes
--  WHERE tablename = 'repositories';
--
-- SELECT column_name, is_nullable FROM information_schema.columns
--  WHERE column_name = 'user_id'
--    AND table_name IN ('repositories', 'chat_sessions');
