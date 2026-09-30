-- Minimal retry: just make user_id nullable.
--
-- The 001 migration appears to have stopped partway (the NOT NULL is still in
-- place, so the ALTER on line 56 never took effect). This file does only the
-- two statements that matter, and deliberately avoids DO $$ blocks so there is
-- nothing to fail partway through.
--
-- Run this in the Neon SQL editor. Safe to re-run.

ALTER TABLE repositories ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE chat_sessions ALTER COLUMN user_id DROP NOT NULL;

-- Verify both now report is_nullable = YES. If they do not, the ALTER did not
-- run and the edit probably did not land in the same database as the app.
--
-- SELECT table_name, is_nullable FROM information_schema.columns
--  WHERE column_name = 'user_id'
--    AND table_name IN ('repositories', 'chat_sessions');
