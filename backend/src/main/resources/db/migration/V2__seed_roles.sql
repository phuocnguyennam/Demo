-- ============================================================================
-- V2__seed_roles.sql
--
-- Reference data for the `roles` table.
--
-- WHY A SEPARATE MIGRATION (and not part of V1):
--   1. V1 is "structure", V2 is "content". Flyway's own guidance is one change
--      per migration; the checksum of V1 is already fixed once it has run.
--   2. Demo value: this is where DBUnit's CHECKPOINT meets seed data. A test
--      asserting `SELECT * FROM roles` must expect exactly these two rows, in
--      this order of role_id (1 = user, 2 = admin), otherwise it fails.
--
-- role_id is left to the SERIAL sequence, so the ids a test should expect are
--   1 -> 'user'     2 -> 'admin'
-- on a freshly migrated container.
-- ============================================================================

INSERT INTO roles (role_name) VALUES ('user'), ('admin');
