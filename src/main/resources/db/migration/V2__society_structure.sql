-- Societies, configuration, memberships, towers / wings / flats, residents, files.

CREATE TABLE societies (
    id                       uuid         PRIMARY KEY,
    code                     varchar(20)  NOT NULL,
    name                     varchar(120) NOT NULL,
    address_line1            varchar(200) NOT NULL,
    address_line2            varchar(200),
    city                     varchar(80)  NOT NULL,
    state                    varchar(80)  NOT NULL,
    pin_code                 varchar(10)  NOT NULL,
    country                  varchar(60)  NOT NULL,
    contact_phone            varchar(20),
    contact_email            varchar(254),
    time_zone                varchar(40)  NOT NULL DEFAULT 'Asia/Kolkata',
    status                   varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    subscription_plan        varchar(20)  NOT NULL DEFAULT 'BASIC' CHECK (subscription_plan IN ('TRIAL', 'BASIC', 'PREMIUM')),
    subscription_valid_until date,
    created_by               uuid         REFERENCES users (id),
    created_at               timestamptz  NOT NULL,
    updated_at               timestamptz  NOT NULL,
    version                  bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_societies_code UNIQUE (code)
);

CREATE TABLE society_settings (
    society_id                     uuid        PRIMARY KEY REFERENCES societies (id) ON DELETE CASCADE,
    max_invite_validity_hours      int         NOT NULL DEFAULT 168 CHECK (max_invite_validity_hours BETWEEN 1 AND 720),
    approval_timeout_seconds       int         NOT NULL DEFAULT 180 CHECK (approval_timeout_seconds BETWEEN 30 AND 3600),
    pending_visitor_expiry_minutes int         NOT NULL DEFAULT 120 CHECK (pending_visitor_expiry_minutes BETWEEN 5 AND 1440),
    visitor_retention_days         int         NOT NULL DEFAULT 365 CHECK (visitor_retention_days BETWEEN 30 AND 3650),
    visit_purposes                 text,
    delivery_companies             text,
    updated_at                     timestamptz NOT NULL
);

-- Per-society override of whether a delivery category needs resident approval.
CREATE TABLE society_delivery_rules (
    society_id        uuid        NOT NULL REFERENCES society_settings (society_id) ON DELETE CASCADE,
    category          varchar(20) NOT NULL CHECK (category IN ('FOOD', 'GROCERY', 'COURIER', 'ECOMMERCE', 'OTHER')),
    requires_approval boolean     NOT NULL,
    PRIMARY KEY (society_id, category)
);

-- Which role a user holds in which society. Detail rows live in flat_memberships / security_guards.
CREATE TABLE society_memberships (
    id         uuid        PRIMARY KEY,
    society_id uuid        NOT NULL REFERENCES societies (id),
    user_id    uuid        NOT NULL REFERENCES users (id),
    role_code  varchar(40) NOT NULL REFERENCES roles (code),
    status     varchar(20) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_society_memberships UNIQUE (society_id, user_id, role_code)
);
CREATE INDEX ix_society_memberships_user ON society_memberships (user_id);

-- Atomic per-society counters (complaint ticket numbers, ...).
CREATE TABLE society_counters (
    society_id    uuid        NOT NULL REFERENCES societies (id) ON DELETE CASCADE,
    counter_name  varchar(30) NOT NULL,
    current_value bigint      NOT NULL,
    PRIMARY KEY (society_id, counter_name)
);

CREATE TABLE stored_files (
    id            uuid         PRIMARY KEY,
    society_id    uuid         REFERENCES societies (id),
    uploaded_by   uuid         REFERENCES users (id),
    purpose       varchar(30)  NOT NULL CHECK (purpose IN ('VISITOR_PHOTO', 'COMPLAINT_ATTACHMENT', 'NOTICE_ATTACHMENT', 'STAFF_PHOTO', 'AVATAR')),
    original_name varchar(200),
    content_type  varchar(100) NOT NULL,
    size_bytes    bigint       NOT NULL CHECK (size_bytes > 0),
    storage_key   varchar(300) NOT NULL,
    sha256        varchar(64)  NOT NULL,
    created_at    timestamptz  NOT NULL,
    CONSTRAINT uq_stored_files_key UNIQUE (storage_key)
);

ALTER TABLE users
    ADD CONSTRAINT fk_users_avatar FOREIGN KEY (avatar_file_id) REFERENCES stored_files (id) ON DELETE SET NULL;

CREATE TABLE towers (
    id          uuid        PRIMARY KEY,
    society_id  uuid        NOT NULL REFERENCES societies (id),
    code        varchar(10) NOT NULL,
    name        varchar(60) NOT NULL,
    floor_count int         CHECK (floor_count IS NULL OR floor_count BETWEEN 0 AND 200),
    status      varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_towers_code UNIQUE (society_id, code),
    CONSTRAINT uq_towers_id_society UNIQUE (id, society_id)
);

CREATE TABLE wings (
    id         uuid        PRIMARY KEY,
    society_id uuid        NOT NULL,
    tower_id   uuid        NOT NULL,
    name       varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_wings_tower FOREIGN KEY (tower_id, society_id) REFERENCES towers (id, society_id),
    CONSTRAINT uq_wings_name UNIQUE (tower_id, name),
    CONSTRAINT uq_wings_id_tower UNIQUE (id, tower_id)
);

-- Floors are modelled as floor_number: they carry no data of their own in Phase 1.
CREATE TABLE flats (
    id           uuid        PRIMARY KEY,
    society_id   uuid        NOT NULL,
    tower_id     uuid        NOT NULL,
    wing_id      uuid,
    floor_number int         NOT NULL CHECK (floor_number BETWEEN -5 AND 200),
    flat_number  varchar(20) NOT NULL,
    flat_type    varchar(20),
    area_sqft    int         CHECK (area_sqft IS NULL OR area_sqft > 0),
    status       varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT fk_flats_tower FOREIGN KEY (tower_id, society_id) REFERENCES towers (id, society_id),
    CONSTRAINT fk_flats_wing FOREIGN KEY (wing_id, tower_id) REFERENCES wings (id, tower_id),
    CONSTRAINT uq_flats_id_society UNIQUE (id, society_id)
);
-- Flat numbers are unique within a tower, or within a wing when the tower has wings.
CREATE UNIQUE INDEX ux_flats_number
    ON flats (tower_id, COALESCE(wing_id, '00000000-0000-0000-0000-000000000000'::uuid), flat_number);
CREATE INDEX ix_flats_society ON flats (society_id, status);

-- A resident's link to a flat. Ended rows are kept as occupancy history.
CREATE TABLE flat_memberships (
    id             uuid         PRIMARY KEY,
    society_id     uuid         NOT NULL,
    flat_id        uuid         NOT NULL,
    user_id        uuid         NOT NULL REFERENCES users (id),
    occupancy_type varchar(20)  NOT NULL CHECK (occupancy_type IN ('OWNER', 'TENANT')),
    status         varchar(20)  NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'ENDED')),
    is_primary     boolean      NOT NULL DEFAULT false,
    requested_by   uuid         REFERENCES users (id),
    approved_by    uuid         REFERENCES users (id),
    approved_at    timestamptz,
    start_date     date,
    end_date       date,
    end_reason     varchar(200),
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    version        bigint       NOT NULL DEFAULT 0,
    CONSTRAINT fk_flat_memberships_flat FOREIGN KEY (flat_id, society_id) REFERENCES flats (id, society_id),
    CONSTRAINT ck_flat_memberships_dates CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);
CREATE UNIQUE INDEX ux_flat_memberships_open ON flat_memberships (flat_id, user_id) WHERE status IN ('PENDING', 'ACTIVE');
CREATE INDEX ix_flat_memberships_user ON flat_memberships (user_id, status);
CREATE INDEX ix_flat_memberships_flat ON flat_memberships (flat_id, status);
CREATE INDEX ix_flat_memberships_society ON flat_memberships (society_id, status);

-- Household members without their own login.
CREATE TABLE family_members (
    id                 uuid        PRIMARY KEY,
    flat_membership_id uuid        NOT NULL REFERENCES flat_memberships (id) ON DELETE CASCADE,
    society_id         uuid        NOT NULL REFERENCES societies (id),
    name               varchar(60) NOT NULL,
    relation           varchar(30) NOT NULL,
    mobile             varchar(15),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);
CREATE INDEX ix_family_members_membership ON family_members (flat_membership_id);
