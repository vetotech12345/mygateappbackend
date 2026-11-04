-- Identity, platform roles, permissions and refresh sessions.
-- Society-scoped roles live in society_memberships (V2).

CREATE TABLE roles (
    code        varchar(40)  PRIMARY KEY,
    description varchar(200) NOT NULL,
    scope       varchar(20)  NOT NULL CHECK (scope IN ('PLATFORM', 'SOCIETY'))
);

CREATE TABLE permissions (
    code        varchar(60)  PRIMARY KEY,
    description varchar(200) NOT NULL
);

CREATE TABLE role_permissions (
    role_code       varchar(40) NOT NULL REFERENCES roles (code) ON DELETE CASCADE,
    permission_code varchar(60) NOT NULL REFERENCES permissions (code) ON DELETE CASCADE,
    PRIMARY KEY (role_code, permission_code)
);

CREATE TABLE users (
    id             uuid         PRIMARY KEY,
    country_code   varchar(5)   NOT NULL,
    mobile         varchar(15)  NOT NULL,
    name           varchar(60),
    email          varchar(254),
    avatar_file_id uuid,
    status         varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    last_login_at  timestamptz,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    version        bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_mobile UNIQUE (country_code, mobile)
);

-- Platform-level role grants (SUPER_ADMIN). Society roles are in society_memberships.
CREATE TABLE user_roles (
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_code  varchar(40) NOT NULL REFERENCES roles (code),
    granted_at timestamptz NOT NULL,
    granted_by uuid        REFERENCES users (id),
    PRIMARY KEY (user_id, role_code)
);

-- One row per login session. The refresh token is rotated in place; only SHA-256 hashes are stored.
-- previous_token_hash detects replay of an already-rotated token.
CREATE TABLE refresh_sessions (
    id                  uuid         PRIMARY KEY,
    user_id             uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash          varchar(64)  NOT NULL,
    previous_token_hash varchar(64),
    ip_address          varchar(45),
    user_agent          varchar(255),
    created_at          timestamptz  NOT NULL,
    last_used_at        timestamptz  NOT NULL,
    rotated_at          timestamptz,
    expires_at          timestamptz  NOT NULL,
    revoked_at          timestamptz,
    revoked_reason      varchar(40),
    CONSTRAINT uq_refresh_sessions_token UNIQUE (token_hash)
);
CREATE INDEX ix_refresh_sessions_previous ON refresh_sessions (previous_token_hash) WHERE previous_token_hash IS NOT NULL;
CREATE INDEX ix_refresh_sessions_user_open ON refresh_sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE platform_settings (
    setting_key   varchar(80)   PRIMARY KEY,
    setting_value varchar(1000) NOT NULL,
    description   varchar(200),
    updated_at    timestamptz   NOT NULL,
    updated_by    uuid          REFERENCES users (id)
);

-- Rows of history tables must never change once written.
CREATE OR REPLACE FUNCTION forbid_row_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Rows in % are immutable', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END;
$$;
