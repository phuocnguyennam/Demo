-- ============================================================================
-- V1__auth_schema.sql
--
-- Schema for the authentication demo, transcribed from README.md section 3
-- (3.1 tables, 3.2 foreign keys, 3.3 history indexes).
--
-- Lives on the MAIN classpath (src/main/resources/db/migration), which Flyway
-- resolves by default as classpath:db/migration. Both the running application
-- (Spring Boot's FlywayAutoConfiguration) and the test suite (an explicit
-- Flyway.migrate() against Testcontainers) therefore execute these exact files.
--
-- This replaces the old DatabaseInitializer, which created only `users` in Java
-- and could not evolve the schema. Flyway now owns the schema end to end.
--
-- Applied by: Flyway (auto-configured at startup, and explicitly in tests).
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 3.1 Table declarations
--
-- Order matters: `roles` and `users` must exist before `user_info` and
-- `edit_history` reference them.
-- ----------------------------------------------------------------------------

CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Lookup/reference table for the two application roles: 'user' and 'admin'.
CREATE TABLE roles (
    role_id SERIAL PRIMARY KEY,
    role_name VARCHAR(50) UNIQUE NOT NULL
);

-- Profile table. `id` is BOTH the primary key and the reference to users.id,
-- which enforces "at most one profile per account".
CREATE TABLE user_info (
    id INTEGER PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    phone_number VARCHAR(30),
    user_role INTEGER NOT NULL
);

-- Append-only audit log. `changed_fields` holds the before/after values as JSONB.
CREATE TABLE edit_history (
    history_id SERIAL PRIMARY KEY,
    actor_user_id INTEGER NOT NULL,
    target_user_id INTEGER NOT NULL,
    changed_fields JSONB NOT NULL,
    edited_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- ----------------------------------------------------------------------------
-- 3.2 Foreign key declarations
--
-- user_info.id       -> users.id      (one profile per user)
-- user_info.user_role -> roles.role_id (profile's role)
-- edit_history.actor / target -> users.id  (ON DELETE RESTRICT keeps the audit
--                                            trail intact; an account that is
--                                            referenced by history cannot be
--                                            deleted)
-- ----------------------------------------------------------------------------

ALTER TABLE user_info
    ADD CONSTRAINT fk_user_info_user
    FOREIGN KEY (id) REFERENCES users(id);

ALTER TABLE user_info
    ADD CONSTRAINT fk_user_info_role
    FOREIGN KEY (user_role) REFERENCES roles(role_id);

ALTER TABLE edit_history
    ADD CONSTRAINT fk_edit_history_actor
    FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE RESTRICT;

ALTER TABLE edit_history
    ADD CONSTRAINT fk_edit_history_target
    FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE RESTRICT;


-- ----------------------------------------------------------------------------
-- 3.3 Indexes supporting history queries
--
-- (actor, time)  -> admin filtering by "who made the change"
-- (target, time) -> user viewing "my own history", newest first
-- ----------------------------------------------------------------------------

CREATE INDEX idx_edit_history_actor_time
    ON edit_history (actor_user_id, edited_at DESC);

CREATE INDEX idx_edit_history_target_time
    ON edit_history (target_user_id, edited_at DESC);
