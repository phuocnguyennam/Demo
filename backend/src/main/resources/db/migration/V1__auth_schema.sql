
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE roles (
    role_id SERIAL PRIMARY KEY,
    role_name VARCHAR(50) UNIQUE NOT NULL
);

CREATE TABLE user_info (
    id INTEGER PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    phone_number VARCHAR(30),
    user_role INTEGER NOT NULL
);

CREATE TABLE edit_history (
    history_id SERIAL PRIMARY KEY,
    actor_user_id INTEGER NOT NULL,
    target_user_id INTEGER NOT NULL,
    changed_fields JSONB NOT NULL,
    edited_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);


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


CREATE INDEX idx_edit_history_actor_time
    ON edit_history (actor_user_id, edited_at DESC);

CREATE INDEX idx_edit_history_target_time
    ON edit_history (target_user_id, edited_at DESC);
