-- Visitors (walk-in requests and pre-approved invitations) and deliveries.
-- Approval and gate movement are tracked independently. Every change is appended to an immutable *_events table.

CREATE TABLE visitors (
    id                uuid         PRIMARY KEY,
    society_id        uuid         NOT NULL REFERENCES societies (id),
    flat_id           uuid         NOT NULL,
    gate_id           uuid,
    source            varchar(20)  NOT NULL CHECK (source IN ('WALK_IN', 'INVITATION')),
    name              varchar(60)  NOT NULL,
    mobile            varchar(15),
    visitor_type      varchar(20)  NOT NULL CHECK (visitor_type IN ('GUEST', 'FAMILY', 'SERVICE', 'DELIVERY', 'CAB', 'OTHER')),
    purpose           varchar(120) NOT NULL,
    vehicle_number    varchar(13),
    guest_count       int          NOT NULL DEFAULT 1 CHECK (guest_count BETWEEN 1 AND 20),
    photo_file_id     uuid         REFERENCES stored_files (id) ON DELETE SET NULL,
    notes             varchar(200),
    approval_status   varchar(20)  NOT NULL CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    movement_status   varchar(20)  NOT NULL CHECK (movement_status IN ('NOT_ENTERED', 'INSIDE', 'EXITED')),
    created_by        uuid         NOT NULL REFERENCES users (id),
    decided_by        uuid         REFERENCES users (id),
    decided_at        timestamptz,
    rejection_reason  varchar(200),
    pass_code         varchar(6),
    valid_from        timestamptz,
    valid_until       timestamptz,
    requested_at      timestamptz  NOT NULL,
    entry_at          timestamptz,
    entry_gate_id     uuid         REFERENCES society_gates (id),
    entry_recorded_by uuid         REFERENCES users (id),
    exit_at           timestamptz,
    exit_gate_id      uuid         REFERENCES society_gates (id),
    exit_recorded_by  uuid         REFERENCES users (id),
    anonymized_at     timestamptz,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    version           bigint       NOT NULL DEFAULT 0,
    CONSTRAINT fk_visitors_flat FOREIGN KEY (flat_id, society_id) REFERENCES flats (id, society_id),
    CONSTRAINT fk_visitors_gate FOREIGN KEY (gate_id, society_id) REFERENCES society_gates (id, society_id),
    CONSTRAINT ck_visitors_invitation CHECK (source <> 'INVITATION'
        OR (pass_code IS NOT NULL AND valid_from IS NOT NULL AND valid_until IS NOT NULL AND valid_until > valid_from)),
    CONSTRAINT ck_visitors_walk_in_gate CHECK (source <> 'WALK_IN' OR gate_id IS NOT NULL),
    CONSTRAINT ck_visitors_entry CHECK (movement_status = 'NOT_ENTERED' OR (entry_at IS NOT NULL AND approval_status = 'APPROVED')),
    CONSTRAINT ck_visitors_exit CHECK (movement_status <> 'EXITED' OR (exit_at IS NOT NULL AND exit_at >= entry_at))
);
-- An invite code can only match one usable invitation per society.
CREATE UNIQUE INDEX ux_visitors_active_pass ON visitors (society_id, pass_code)
    WHERE pass_code IS NOT NULL AND approval_status = 'APPROVED' AND movement_status = 'NOT_ENTERED';
CREATE INDEX ix_visitors_society_requested ON visitors (society_id, requested_at DESC);
CREATE INDEX ix_visitors_flat_requested ON visitors (flat_id, requested_at DESC);
CREATE INDEX ix_visitors_society_state ON visitors (society_id, approval_status, movement_status);
CREATE INDEX ix_visitors_invite_expiry ON visitors (valid_until)
    WHERE source = 'INVITATION' AND approval_status = 'APPROVED' AND movement_status = 'NOT_ENTERED';

CREATE TABLE visitor_events (
    id              uuid         PRIMARY KEY,
    visitor_id      uuid         NOT NULL REFERENCES visitors (id) ON DELETE CASCADE,
    society_id      uuid         NOT NULL REFERENCES societies (id),
    event_type      varchar(30)  NOT NULL,
    status          varchar(20)  NOT NULL,
    title           varchar(150) NOT NULL,
    description     varchar(300),
    actor_user_id   uuid         REFERENCES users (id),
    actor_name      varchar(80),
    gate_id         uuid         REFERENCES society_gates (id),
    occurred_at     timestamptz  NOT NULL
);
CREATE INDEX ix_visitor_events_visitor ON visitor_events (visitor_id, occurred_at);
CREATE INDEX ix_visitor_events_actor ON visitor_events (actor_user_id, occurred_at DESC);
CREATE TRIGGER trg_visitor_events_immutable BEFORE UPDATE ON visitor_events
    FOR EACH ROW EXECUTE FUNCTION forbid_row_update();

CREATE TABLE deliveries (
    id                uuid        PRIMARY KEY,
    society_id        uuid        NOT NULL REFERENCES societies (id),
    flat_id           uuid        NOT NULL,
    gate_id           uuid        NOT NULL,
    category          varchar(20) NOT NULL CHECK (category IN ('FOOD', 'GROCERY', 'COURIER', 'ECOMMERCE', 'OTHER')),
    company           varchar(60) NOT NULL,
    agent_name        varchar(60) NOT NULL,
    agent_mobile      varchar(15),
    package_count     int         NOT NULL DEFAULT 1 CHECK (package_count BETWEEN 1 AND 20),
    notes             varchar(200),
    requires_approval boolean     NOT NULL,
    approval_status   varchar(20) NOT NULL CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'LEFT_AT_GATE', 'CANCELLED')),
    movement_status   varchar(20) NOT NULL CHECK (movement_status IN ('NOT_ENTERED', 'INSIDE', 'EXITED')),
    created_by        uuid        NOT NULL REFERENCES users (id),
    decided_by        uuid        REFERENCES users (id),
    decided_at        timestamptz,
    requested_at      timestamptz NOT NULL,
    entry_at          timestamptz,
    entry_gate_id     uuid        REFERENCES society_gates (id),
    entry_recorded_by uuid        REFERENCES users (id),
    exit_at           timestamptz,
    exit_gate_id      uuid        REFERENCES society_gates (id),
    exit_recorded_by  uuid        REFERENCES users (id),
    anonymized_at     timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT fk_deliveries_flat FOREIGN KEY (flat_id, society_id) REFERENCES flats (id, society_id),
    CONSTRAINT fk_deliveries_gate FOREIGN KEY (gate_id, society_id) REFERENCES society_gates (id, society_id),
    CONSTRAINT ck_deliveries_entry CHECK (movement_status = 'NOT_ENTERED' OR (entry_at IS NOT NULL AND approval_status = 'APPROVED')),
    CONSTRAINT ck_deliveries_exit CHECK (movement_status <> 'EXITED' OR (exit_at IS NOT NULL AND exit_at >= entry_at))
);
CREATE INDEX ix_deliveries_society_requested ON deliveries (society_id, requested_at DESC);
CREATE INDEX ix_deliveries_flat_requested ON deliveries (flat_id, requested_at DESC);
CREATE INDEX ix_deliveries_society_state ON deliveries (society_id, approval_status, movement_status);

CREATE TABLE delivery_events (
    id            uuid         PRIMARY KEY,
    delivery_id   uuid         NOT NULL REFERENCES deliveries (id) ON DELETE CASCADE,
    society_id    uuid         NOT NULL REFERENCES societies (id),
    event_type    varchar(30)  NOT NULL,
    status        varchar(20)  NOT NULL,
    title         varchar(150) NOT NULL,
    description   varchar(300),
    actor_user_id uuid         REFERENCES users (id),
    actor_name    varchar(80),
    gate_id       uuid         REFERENCES society_gates (id),
    occurred_at   timestamptz  NOT NULL
);
CREATE INDEX ix_delivery_events_delivery ON delivery_events (delivery_id, occurred_at);
CREATE INDEX ix_delivery_events_actor ON delivery_events (actor_user_id, occurred_at DESC);
CREATE TRIGGER trg_delivery_events_immutable BEFORE UPDATE ON delivery_events
    FOR EACH ROW EXECUTE FUNCTION forbid_row_update();
