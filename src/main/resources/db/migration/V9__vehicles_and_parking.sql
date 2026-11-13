-- Vehicles, parking inventory, allocations, permits, requests, gate sessions, pricing, payments and receipts.
-- Additive only: no existing table, column or row is removed or rewritten. See docs/PARKING_DATABASE_DESIGN.md.

-- GiST exclusion constraints over (uuid, tstzrange) need btree_gist (a trusted extension since PostgreSQL 13).
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------- existing tables (additive changes)

-- Lets parking rows reference a visitor of the same society. Always satisfied: id is the primary key.
ALTER TABLE visitors ADD CONSTRAINT uq_visitors_id_society UNIQUE (id, society_id);

-- New per-guard toggle VEHICLE_ENTRY. The old CHECK (created inline in V3, so its name is generated) is replaced by a
-- named one that allows the new value. No row changes; existing guards do not receive the new toggle.
DO $$
DECLARE
    c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'guard_permissions'::regclass AND contype = 'c'
    LOOP
        EXECUTE format('ALTER TABLE guard_permissions DROP CONSTRAINT %I', c.conname);
    END LOOP;
END
$$;
ALTER TABLE guard_permissions ADD CONSTRAINT ck_guard_permissions_permission
    CHECK (permission IN ('VISITOR_ENTRY', 'DELIVERY_ENTRY', 'STAFF_ATTENDANCE', 'VEHICLE_ENTRY'));

-- ---------------------------------------------------------------- society parking settings

-- One row per society, created with defaults on first use (existing societies get no row from this migration).
CREATE TABLE society_parking_settings (
    society_id                     uuid         PRIMARY KEY REFERENCES societies (id) ON DELETE CASCADE,
    visitor_parking_enabled        boolean      NOT NULL DEFAULT true,
    visitor_parking_auto_approve   boolean      NOT NULL DEFAULT false,
    max_visitor_parking_hours      int          NOT NULL DEFAULT 12 CHECK (max_visitor_parking_hours BETWEEN 1 AND 168),
    paid_parking_enabled           boolean      NOT NULL DEFAULT false,
    external_paid_parking_allowed  boolean      NOT NULL DEFAULT false,
    free_grace_minutes             int          NOT NULL DEFAULT 15 CHECK (free_grace_minutes BETWEEN 0 AND 240),
    max_vehicles_per_flat          int          NOT NULL DEFAULT 4 CHECK (max_vehicles_per_flat BETWEEN 1 AND 20),
    max_temporary_permit_days      int          NOT NULL DEFAULT 30 CHECK (max_temporary_permit_days BETWEEN 1 AND 365),
    resident_entry_requires_permit boolean      NOT NULL DEFAULT true,
    payment_required_before_exit   boolean      NOT NULL DEFAULT true,
    emergency_override_allowed     boolean      NOT NULL DEFAULT false,
    vehicle_number_format          varchar(10)  NOT NULL DEFAULT 'INDIA' CHECK (vehicle_number_format IN ('INDIA', 'GENERIC')),
    allowed_vehicle_types          text,
    accepted_payment_methods       text,
    currency                       varchar(3)   NOT NULL DEFAULT 'INR' CHECK (currency ~ '^[A-Z]{3}$'),
    rules                          text,
    updated_by                     uuid         REFERENCES users (id),
    updated_at                     timestamptz  NOT NULL,
    version                        bigint       NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------- vehicles

CREATE TABLE vehicles (
    id                  uuid         PRIMARY KEY,
    society_id          uuid         NOT NULL REFERENCES societies (id),
    registration_number varchar(15)  NOT NULL CHECK (registration_number ~ '^[A-Z0-9]{4,13}$'),
    vehicle_type        varchar(20)  NOT NULL CHECK (vehicle_type IN ('CAR', 'BIKE', 'SCOOTER', 'OTHER')),
    category            varchar(20)  NOT NULL CHECK (category IN ('RESIDENT', 'VISITOR', 'PAID', 'SOCIETY', 'STAFF', 'SERVICE', 'EMERGENCY')),
    make                varchar(40),
    model               varchar(40),
    color               varchar(30),
    flat_id             uuid,
    owner_user_id       uuid         REFERENCES users (id),
    owner_name          varchar(80),
    registration_status varchar(20)  NOT NULL CHECK (registration_status IN ('NOT_SUBMITTED', 'PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    submitted_at        timestamptz,
    reviewed_at         timestamptz,
    reviewed_by         uuid         REFERENCES users (id),
    review_remarks      varchar(200),
    active              boolean      NOT NULL DEFAULT true,
    created_by          uuid         NOT NULL REFERENCES users (id),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    version             bigint       NOT NULL DEFAULT 0,
    CONSTRAINT fk_vehicles_flat FOREIGN KEY (flat_id, society_id) REFERENCES flats (id, society_id),
    CONSTRAINT uq_vehicles_id_society UNIQUE (id, society_id),
    CONSTRAINT ck_vehicles_resident_flat CHECK (category <> 'RESIDENT' OR flat_id IS NOT NULL)
);
-- A registration number can be held by only one active vehicle per society (duplicate registration guard).
CREATE UNIQUE INDEX ux_vehicles_active_number ON vehicles (society_id, registration_number) WHERE active;
CREATE INDEX ix_vehicles_flat ON vehicles (flat_id) WHERE active;
CREATE INDEX ix_vehicles_society_status ON vehicles (society_id, registration_status, category);
CREATE INDEX ix_vehicles_number_prefix ON vehicles (society_id, registration_number varchar_pattern_ops);

-- ---------------------------------------------------------------- inventory

CREATE TABLE parking_areas (
    id            uuid         PRIMARY KEY,
    society_id    uuid         NOT NULL REFERENCES societies (id),
    code          varchar(20)  NOT NULL,
    name          varchar(60)  NOT NULL,
    level         varchar(20),
    description   varchar(300),
    usage_type    varchar(20)  NOT NULL CHECK (usage_type IN ('RESIDENT', 'VISITOR', 'PAID', 'SOCIETY', 'STAFF', 'SERVICE', 'EMERGENCY', 'COMMON', 'RESERVED')),
    vehicle_types text         NOT NULL,
    status        varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    version       bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_parking_areas_code UNIQUE (society_id, code),
    CONSTRAINT uq_parking_areas_name UNIQUE (society_id, name),
    CONSTRAINT uq_parking_areas_id_society UNIQUE (id, society_id)
);

CREATE TABLE parking_slots (
    id                    uuid         PRIMARY KEY,
    society_id            uuid         NOT NULL,
    area_id               uuid         NOT NULL,
    slot_number           varchar(20)  NOT NULL,
    vehicle_type          varchar(20)  NOT NULL CHECK (vehicle_type IN ('CAR', 'BIKE', 'SCOOTER', 'OTHER')),
    usage_type            varchar(20)  NOT NULL CHECK (usage_type IN ('RESIDENT', 'VISITOR', 'PAID', 'SOCIETY', 'STAFF', 'SERVICE', 'EMERGENCY', 'COMMON', 'RESERVED')),
    operational_status    varchar(20)  NOT NULL CHECK (operational_status IN ('ACTIVE', 'OUT_OF_SERVICE')),
    out_of_service_reason varchar(200),
    notes                 varchar(200),
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    version               bigint       NOT NULL DEFAULT 0,
    CONSTRAINT fk_parking_slots_area FOREIGN KEY (area_id, society_id) REFERENCES parking_areas (id, society_id),
    CONSTRAINT uq_parking_slots_number UNIQUE (area_id, slot_number),
    CONSTRAINT uq_parking_slots_id_society UNIQUE (id, society_id)
);
CREATE INDEX ix_parking_slots_society ON parking_slots (society_id, usage_type, operational_status);
CREATE INDEX ix_parking_slots_number ON parking_slots (society_id, slot_number);

-- ---------------------------------------------------------------- pricing

CREATE TABLE parking_rate_plans (
    id                   uuid          PRIMARY KEY,
    society_id           uuid          NOT NULL REFERENCES societies (id),
    code                 varchar(30)   NOT NULL,
    name                 varchar(80)   NOT NULL,
    description          varchar(300),
    area_id              uuid,
    vehicle_type         varchar(20)   CHECK (vehicle_type IS NULL OR vehicle_type IN ('CAR', 'BIKE', 'SCOOTER', 'OTHER')),
    applies_to           varchar(20)   NOT NULL CHECK (applies_to IN ('VISITOR', 'RESIDENT', 'PUBLIC', 'ALL')),
    billing_unit         varchar(20)   NOT NULL CHECK (billing_unit IN ('HOURLY', 'DAILY', 'MONTHLY')),
    base_rate            numeric(12,2) NOT NULL CHECK (base_rate >= 0),
    currency             varchar(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    free_minutes         int           NOT NULL DEFAULT 0 CHECK (free_minutes BETWEEN 0 AND 10080),
    grace_period_minutes int           NOT NULL DEFAULT 0 CHECK (grace_period_minutes BETWEEN 0 AND 240),
    daily_cap            numeric(12,2) CHECK (daily_cap IS NULL OR daily_cap >= 0),
    overstay_hourly_rate numeric(12,2) CHECK (overstay_hourly_rate IS NULL OR overstay_hourly_rate >= 0),
    tax_percent          numeric(5,2)  NOT NULL DEFAULT 0 CHECK (tax_percent BETWEEN 0 AND 100),
    tax_label            varchar(30),
    effective_from       timestamptz   NOT NULL,
    effective_until      timestamptz,
    active               boolean       NOT NULL DEFAULT true,
    created_by           uuid          REFERENCES users (id),
    created_at           timestamptz   NOT NULL,
    updated_at           timestamptz   NOT NULL,
    version              bigint        NOT NULL DEFAULT 0,
    CONSTRAINT fk_parking_rate_plans_area FOREIGN KEY (area_id, society_id) REFERENCES parking_areas (id, society_id),
    CONSTRAINT uq_parking_rate_plans_code UNIQUE (society_id, code),
    CONSTRAINT uq_parking_rate_plans_id_society UNIQUE (id, society_id),
    CONSTRAINT ck_parking_rate_plans_effective CHECK (effective_until IS NULL OR effective_until > effective_from)
);
CREATE INDEX ix_parking_rate_plans_society ON parking_rate_plans (society_id, active);

-- ---------------------------------------------------------------- requests (resident requests + visitor parking)

CREATE TABLE parking_requests (
    id                uuid         PRIMARY KEY,
    society_id        uuid         NOT NULL REFERENCES societies (id),
    reference_number  varchar(20)  NOT NULL,
    request_type      varchar(20)  NOT NULL CHECK (request_type IN ('ADDITIONAL_SLOT', 'TEMPORARY_PERMIT', 'VISITOR_SLOT', 'PAID_PERMIT')),
    status            varchar(20)  NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    flat_id           uuid         NOT NULL,
    requested_by      uuid         NOT NULL REFERENCES users (id),
    vehicle_id        uuid,
    vehicle_number    varchar(15)  NOT NULL CHECK (vehicle_number ~ '^[A-Z0-9]{4,13}$'),
    vehicle_type      varchar(20)  NOT NULL CHECK (vehicle_type IN ('CAR', 'BIKE', 'SCOOTER', 'OTHER')),
    preferred_area_id uuid,
    rate_plan_id      uuid,
    valid_from        timestamptz,
    valid_until       timestamptz,
    reason            varchar(300),
    visitor_id        uuid,
    visitor_name      varchar(60),
    visitor_mobile    varchar(15),
    visit_purpose     varchar(120),
    parking_type      varchar(20)  CHECK (parking_type IS NULL OR parking_type IN ('VISITOR', 'PAID')),
    decided_by        uuid         REFERENCES users (id),
    decided_by_name   varchar(80),
    decided_at        timestamptz,
    decision_remarks  varchar(300),
    auto_approved     boolean      NOT NULL DEFAULT false,
    assigned_slot_id  uuid,
    allocation_id     uuid,
    permit_id         uuid,
    consumed_at       timestamptz,
    anonymized_at     timestamptz,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    version           bigint       NOT NULL DEFAULT 0,
    CONSTRAINT fk_parking_requests_flat FOREIGN KEY (flat_id, society_id) REFERENCES flats (id, society_id),
    CONSTRAINT fk_parking_requests_vehicle FOREIGN KEY (vehicle_id, society_id) REFERENCES vehicles (id, society_id),
    CONSTRAINT fk_parking_requests_area FOREIGN KEY (preferred_area_id, society_id) REFERENCES parking_areas (id, society_id),
    CONSTRAINT fk_parking_requests_rate FOREIGN KEY (rate_plan_id, society_id) REFERENCES parking_rate_plans (id, society_id),
    CONSTRAINT fk_parking_requests_visitor FOREIGN KEY (visitor_id, society_id) REFERENCES visitors (id, society_id),
    CONSTRAINT fk_parking_requests_slot FOREIGN KEY (assigned_slot_id, society_id) REFERENCES parking_slots (id, society_id),
    CONSTRAINT uq_parking_requests_reference UNIQUE (society_id, reference_number),
    CONSTRAINT uq_parking_requests_id_society UNIQUE (id, society_id),
    CONSTRAINT ck_parking_requests_window CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_parking_requests_visitor CHECK (request_type <> 'VISITOR_SLOT'
        OR (visitor_name IS NOT NULL AND valid_from IS NOT NULL AND valid_until IS NOT NULL AND parking_type IS NOT NULL))
);
-- A flat cannot have two pending requests of the same type for the same vehicle number.
CREATE UNIQUE INDEX ux_parking_requests_pending ON parking_requests (flat_id, request_type, vehicle_number) WHERE status = 'PENDING';
CREATE INDEX ix_parking_requests_society ON parking_requests (society_id, status, created_at DESC);
CREATE INDEX ix_parking_requests_flat ON parking_requests (flat_id, created_at DESC);
CREATE INDEX ix_parking_requests_usable ON parking_requests (society_id, vehicle_number)
    WHERE status = 'APPROVED' AND consumed_at IS NULL;

-- ---------------------------------------------------------------- allocations and permits

CREATE TABLE parking_allocations (
    id              uuid         PRIMARY KEY,
    society_id      uuid         NOT NULL REFERENCES societies (id),
    slot_id         uuid         NOT NULL,
    flat_id         uuid,
    vehicle_id      uuid,
    request_id      uuid,
    allocation_type varchar(20)  NOT NULL CHECK (allocation_type IN ('PERMANENT', 'TEMPORARY', 'VISITOR', 'PAID', 'SOCIETY')),
    status          varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'PENDING', 'SUSPENDED', 'EXPIRED', 'REVOKED')),
    valid_from      timestamptz  NOT NULL,
    valid_until     timestamptz,
    notes           varchar(300),
    approved_by     uuid         REFERENCES users (id),
    approved_at     timestamptz,
    ended_at        timestamptz,
    ended_by        uuid         REFERENCES users (id),
    end_reason      varchar(200),
    created_by      uuid         REFERENCES users (id),
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT fk_parking_allocations_slot FOREIGN KEY (slot_id, society_id) REFERENCES parking_slots (id, society_id),
    CONSTRAINT fk_parking_allocations_flat FOREIGN KEY (flat_id, society_id) REFERENCES flats (id, society_id),
    CONSTRAINT fk_parking_allocations_vehicle FOREIGN KEY (vehicle_id, society_id) REFERENCES vehicles (id, society_id),
    CONSTRAINT fk_parking_allocations_request FOREIGN KEY (request_id, society_id) REFERENCES parking_requests (id, society_id),
    CONSTRAINT uq_parking_allocations_id_society UNIQUE (id, society_id),
    CONSTRAINT ck_parking_allocations_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_parking_allocations_holder CHECK (allocation_type IN ('SOCIETY', 'VISITOR', 'PAID') OR flat_id IS NOT NULL),
    -- A slot can never have two live allocations whose periods overlap (also under concurrency).
    CONSTRAINT ex_parking_allocations_overlap EXCLUDE USING gist (
        slot_id WITH =, tstzrange(valid_from, valid_until, '[)') WITH &&)
        WHERE (status IN ('ACTIVE', 'PENDING', 'SUSPENDED'))
);
CREATE INDEX ix_parking_allocations_flat ON parking_allocations (flat_id, status);
CREATE INDEX ix_parking_allocations_vehicle ON parking_allocations (vehicle_id, status);
CREATE INDEX ix_parking_allocations_society ON parking_allocations (society_id, status, valid_from DESC);

CREATE TABLE parking_permits (
    id                 uuid         PRIMARY KEY,
    society_id         uuid         NOT NULL REFERENCES societies (id),
    permit_number      varchar(30)  NOT NULL,
    permit_type        varchar(20)  NOT NULL CHECK (permit_type IN ('RESIDENT', 'TEMPORARY', 'VISITOR', 'PAID_MONTHLY', 'SOCIETY', 'STAFF', 'SERVICE', 'EMERGENCY')),
    status             varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'PENDING_PAYMENT', 'EXPIRED', 'REVOKED', 'SUSPENDED')),
    vehicle_id         uuid,
    vehicle_number     varchar(15)  NOT NULL CHECK (vehicle_number ~ '^[A-Z0-9]{4,13}$'),
    vehicle_type       varchar(20)  NOT NULL CHECK (vehicle_type IN ('CAR', 'BIKE', 'SCOOTER', 'OTHER')),
    holder_name        varchar(80),
    flat_id            uuid,
    slot_id            uuid,
    area_id            uuid,
    allocation_id      uuid,
    request_id         uuid,
    valid_from         timestamptz  NOT NULL,
    valid_until        timestamptz,
    conditions         text,
    issued_by          uuid         REFERENCES users (id),
    issued_by_name     varchar(80),
    revoked_at         timestamptz,
    revoked_by         uuid         REFERENCES users (id),
    revoke_reason      varchar(200),
    expiry_reminded_at timestamptz,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    version            bigint       NOT NULL DEFAULT 0,
    CONSTRAINT fk_parking_permits_vehicle FOREIGN KEY (vehicle_id, society_id) REFERENCES vehicles (id, society_id),
    CONSTRAINT fk_parking_permits_flat FOREIGN KEY (flat_id, society_id) REFERENCES flats (id, society_id),
    CONSTRAINT fk_parking_permits_slot FOREIGN KEY (slot_id, society_id) REFERENCES parking_slots (id, society_id),
    CONSTRAINT fk_parking_permits_area FOREIGN KEY (area_id, society_id) REFERENCES parking_areas (id, society_id),
    CONSTRAINT fk_parking_permits_allocation FOREIGN KEY (allocation_id, society_id) REFERENCES parking_allocations (id, society_id),
    CONSTRAINT fk_parking_permits_request FOREIGN KEY (request_id, society_id) REFERENCES parking_requests (id, society_id),
    CONSTRAINT uq_parking_permits_number UNIQUE (society_id, permit_number),
    CONSTRAINT uq_parking_permits_id_society UNIQUE (id, society_id),
    CONSTRAINT ck_parking_permits_window CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX ix_parking_permits_vehicle_number ON parking_permits (society_id, vehicle_number)
    WHERE status IN ('ACTIVE', 'PENDING_PAYMENT', 'SUSPENDED');
CREATE INDEX ix_parking_permits_flat ON parking_permits (flat_id, status);
CREATE INDEX ix_parking_permits_expiry ON parking_permits (valid_until) WHERE status = 'ACTIVE' AND valid_until IS NOT NULL;

ALTER TABLE parking_requests
    ADD CONSTRAINT fk_parking_requests_allocation FOREIGN KEY (allocation_id, society_id) REFERENCES parking_allocations (id, society_id),
    ADD CONSTRAINT fk_parking_requests_permit FOREIGN KEY (permit_id, society_id) REFERENCES parking_permits (id, society_id);

-- ---------------------------------------------------------------- gate: verifications, sessions, movements

-- Short-lived, single-use result of a guard's vehicle check. Entry requires one from the same guard and society.
CREATE TABLE parking_verifications (
    id                   uuid         PRIMARY KEY,
    society_id           uuid         NOT NULL REFERENCES societies (id),
    guard_user_id        uuid         NOT NULL REFERENCES users (id),
    gate_id              uuid         NOT NULL,
    vehicle_number       varchar(15)  NOT NULL,
    vehicle_type         varchar(20),
    decision             varchar(30)  NOT NULL CHECK (decision IN ('ALLOWED', 'ALREADY_INSIDE', 'NEEDS_VISITOR_APPROVAL', 'NOT_REGISTERED', 'DENIED')),
    category             varchar(20),
    vehicle_id           uuid         REFERENCES vehicles (id),
    visitor_id           uuid         REFERENCES visitors (id),
    request_id           uuid         REFERENCES parking_requests (id),
    permit_id            uuid         REFERENCES parking_permits (id),
    slot_id              uuid         REFERENCES parking_slots (id),
    flat_id              uuid         REFERENCES flats (id),
    valid_until          timestamptz,
    paid_parking_offered boolean      NOT NULL DEFAULT false,
    summary              varchar(200),
    reasons              text,
    created_at           timestamptz  NOT NULL,
    expires_at           timestamptz  NOT NULL,
    used_at              timestamptz,
    CONSTRAINT fk_parking_verifications_gate FOREIGN KEY (gate_id, society_id) REFERENCES society_gates (id, society_id)
);
CREATE INDEX ix_parking_verifications_created ON parking_verifications (created_at);

CREATE TABLE parking_sessions (
    id                   uuid          PRIMARY KEY,
    society_id           uuid          NOT NULL REFERENCES societies (id),
    vehicle_number       varchar(15)   NOT NULL CHECK (vehicle_number ~ '^[A-Z0-9]{4,13}$'),
    vehicle_type         varchar(20)   NOT NULL CHECK (vehicle_type IN ('CAR', 'BIKE', 'SCOOTER', 'OTHER')),
    category             varchar(20)   NOT NULL CHECK (category IN ('RESIDENT', 'VISITOR', 'PAID', 'SOCIETY', 'STAFF', 'SERVICE', 'EMERGENCY')),
    vehicle_id           uuid,
    visitor_id           uuid,
    flat_id              uuid,
    slot_id              uuid,
    permit_id            uuid,
    request_id           uuid,
    allocation_id        uuid,
    rate_plan_id         uuid,
    verification_id      uuid          REFERENCES parking_verifications (id),
    rate_snapshot        text,
    currency             varchar(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status               varchar(20)   NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    entry_gate_id        uuid          NOT NULL,
    entry_at             timestamptz   NOT NULL,
    entry_recorded_by    uuid          NOT NULL REFERENCES users (id),
    exit_gate_id         uuid,
    exit_at              timestamptz,
    exit_recorded_by     uuid          REFERENCES users (id),
    valid_until          timestamptz,
    overstay_notified_at timestamptz,
    final_amount         numeric(12,2) CHECK (final_amount IS NULL OR final_amount >= 0),
    paid_amount          numeric(12,2) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    payment_status       varchar(20)   NOT NULL CHECK (payment_status IN ('NOT_APPLICABLE', 'PENDING', 'PAID', 'WAIVED', 'FAILED')),
    paid_until           timestamptz,
    emergency_override   boolean       NOT NULL DEFAULT false,
    override_reason      varchar(200),
    cancel_reason        varchar(200),
    created_at           timestamptz   NOT NULL,
    updated_at           timestamptz   NOT NULL,
    version              bigint        NOT NULL DEFAULT 0,
    CONSTRAINT fk_parking_sessions_vehicle FOREIGN KEY (vehicle_id, society_id) REFERENCES vehicles (id, society_id),
    CONSTRAINT fk_parking_sessions_visitor FOREIGN KEY (visitor_id, society_id) REFERENCES visitors (id, society_id),
    CONSTRAINT fk_parking_sessions_flat FOREIGN KEY (flat_id, society_id) REFERENCES flats (id, society_id),
    CONSTRAINT fk_parking_sessions_slot FOREIGN KEY (slot_id, society_id) REFERENCES parking_slots (id, society_id),
    CONSTRAINT fk_parking_sessions_permit FOREIGN KEY (permit_id, society_id) REFERENCES parking_permits (id, society_id),
    CONSTRAINT fk_parking_sessions_request FOREIGN KEY (request_id, society_id) REFERENCES parking_requests (id, society_id),
    CONSTRAINT fk_parking_sessions_allocation FOREIGN KEY (allocation_id, society_id) REFERENCES parking_allocations (id, society_id),
    CONSTRAINT fk_parking_sessions_rate FOREIGN KEY (rate_plan_id, society_id) REFERENCES parking_rate_plans (id, society_id),
    CONSTRAINT fk_parking_sessions_entry_gate FOREIGN KEY (entry_gate_id, society_id) REFERENCES society_gates (id, society_id),
    CONSTRAINT fk_parking_sessions_exit_gate FOREIGN KEY (exit_gate_id, society_id) REFERENCES society_gates (id, society_id),
    CONSTRAINT uq_parking_sessions_id_society UNIQUE (id, society_id),
    CONSTRAINT uq_parking_sessions_verification UNIQUE (verification_id),
    CONSTRAINT ck_parking_sessions_completed CHECK (status <> 'COMPLETED' OR exit_at IS NOT NULL),
    CONSTRAINT ck_parking_sessions_exit_order CHECK (exit_at IS NULL OR exit_at >= entry_at),
    CONSTRAINT ck_parking_sessions_override CHECK (NOT emergency_override OR override_reason IS NOT NULL)
);
-- One vehicle can be parked only once at a time per society; one slot holds at most one parked vehicle.
CREATE UNIQUE INDEX ux_parking_sessions_active_vehicle ON parking_sessions (society_id, vehicle_number) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX ux_parking_sessions_active_slot ON parking_sessions (slot_id) WHERE status = 'ACTIVE' AND slot_id IS NOT NULL;
CREATE INDEX ix_parking_sessions_society_entry ON parking_sessions (society_id, entry_at DESC);
CREATE INDEX ix_parking_sessions_flat ON parking_sessions (flat_id, entry_at DESC);
CREATE INDEX ix_parking_sessions_vehicle ON parking_sessions (vehicle_id, entry_at DESC);
CREATE INDEX ix_parking_sessions_overstay ON parking_sessions (valid_until) WHERE status = 'ACTIVE' AND valid_until IS NOT NULL;

-- Append-only gate movement history.
CREATE TABLE parking_movements (
    id             uuid         PRIMARY KEY,
    society_id     uuid         NOT NULL REFERENCES societies (id),
    session_id     uuid         REFERENCES parking_sessions (id),
    vehicle_number varchar(15)  NOT NULL,
    event_type     varchar(30)  NOT NULL CHECK (event_type IN ('ENTRY', 'ENTRY_OVERRIDE', 'EXIT', 'OVERSTAY', 'PAYMENT', 'CANCELLED')),
    gate_id        uuid         REFERENCES society_gates (id),
    actor_user_id  uuid         REFERENCES users (id),
    actor_name     varchar(80),
    details        varchar(300),
    occurred_at    timestamptz  NOT NULL
);
CREATE INDEX ix_parking_movements_session ON parking_movements (session_id, occurred_at);
CREATE INDEX ix_parking_movements_society ON parking_movements (society_id, occurred_at DESC);
CREATE TRIGGER trg_parking_movements_immutable BEFORE UPDATE ON parking_movements
    FOR EACH ROW EXECUTE FUNCTION forbid_row_update();

-- ---------------------------------------------------------------- quotes, payments, receipts

CREATE TABLE parking_quotes (
    id               uuid          PRIMARY KEY,
    society_id       uuid          NOT NULL REFERENCES societies (id),
    rate_plan_id     uuid          NOT NULL,
    session_id       uuid,
    permit_id        uuid,
    period_from      timestamptz   NOT NULL,
    period_until     timestamptz   NOT NULL,
    billable_minutes int           NOT NULL CHECK (billable_minutes >= 0),
    lines            text          NOT NULL,
    subtotal         numeric(12,2) NOT NULL CHECK (subtotal >= 0),
    tax              numeric(12,2) NOT NULL CHECK (tax >= 0),
    tax_label        varchar(30),
    already_paid     numeric(12,2) NOT NULL DEFAULT 0 CHECK (already_paid >= 0),
    total            numeric(12,2) NOT NULL CHECK (total >= 0),
    currency         varchar(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    calculated_at    timestamptz   NOT NULL,
    expires_at       timestamptz   NOT NULL,
    created_by       uuid          REFERENCES users (id),
    CONSTRAINT fk_parking_quotes_rate FOREIGN KEY (rate_plan_id, society_id) REFERENCES parking_rate_plans (id, society_id),
    CONSTRAINT fk_parking_quotes_session FOREIGN KEY (session_id, society_id) REFERENCES parking_sessions (id, society_id),
    CONSTRAINT fk_parking_quotes_permit FOREIGN KEY (permit_id, society_id) REFERENCES parking_permits (id, society_id),
    CONSTRAINT uq_parking_quotes_id_society UNIQUE (id, society_id),
    CONSTRAINT ck_parking_quotes_period CHECK (period_until >= period_from)
);
CREATE INDEX ix_parking_quotes_calculated ON parking_quotes (calculated_at);

CREATE TABLE parking_payments (
    id                uuid          PRIMARY KEY,
    society_id        uuid          NOT NULL REFERENCES societies (id),
    purpose           varchar(20)   NOT NULL CHECK (purpose IN ('SESSION', 'PERMIT')),
    session_id        uuid,
    permit_id         uuid,
    quote_id          uuid,
    flat_id           uuid,
    payer_name        varchar(80),
    vehicle_number    varchar(15),
    amount            numeric(12,2) NOT NULL CHECK (amount >= 0),
    currency          varchar(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    method            varchar(20)   CHECK (method IS NULL OR method IN ('CASH', 'UPI_AT_GATE', 'CARD_AT_GATE', 'UPI', 'BANK_TRANSFER', 'ONLINE', 'WAIVER')),
    reference         varchar(100),
    status            varchar(20)   NOT NULL CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'REFUNDED', 'WAIVED')),
    description       varchar(200),
    paid_at           timestamptz,
    recorded_by       uuid          REFERENCES users (id),
    recorded_by_name  varchar(80),
    confirmed_by      uuid          REFERENCES users (id),
    confirmed_by_name varchar(80),
    refunded_at       timestamptz,
    refunded_by       uuid          REFERENCES users (id),
    status_reason     varchar(200),
    created_at        timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL,
    version           bigint        NOT NULL DEFAULT 0,
    CONSTRAINT fk_parking_payments_session FOREIGN KEY (session_id, society_id) REFERENCES parking_sessions (id, society_id),
    CONSTRAINT fk_parking_payments_permit FOREIGN KEY (permit_id, society_id) REFERENCES parking_permits (id, society_id),
    CONSTRAINT fk_parking_payments_quote FOREIGN KEY (quote_id, society_id) REFERENCES parking_quotes (id, society_id),
    CONSTRAINT fk_parking_payments_flat FOREIGN KEY (flat_id, society_id) REFERENCES flats (id, society_id),
    CONSTRAINT uq_parking_payments_id_society UNIQUE (id, society_id),
    CONSTRAINT ck_parking_payments_target CHECK ((purpose = 'SESSION' AND session_id IS NOT NULL)
        OR (purpose = 'PERMIT' AND permit_id IS NOT NULL)),
    CONSTRAINT ck_parking_payments_settled CHECK (status NOT IN ('PAID', 'REFUNDED', 'WAIVED')
        OR (paid_at IS NOT NULL AND method IS NOT NULL)),
    CONSTRAINT ck_parking_payments_waiver CHECK (status <> 'WAIVED' OR method = 'WAIVER'),
    CONSTRAINT ck_parking_payments_refund CHECK (status <> 'REFUNDED' OR refunded_at IS NOT NULL)
);
-- A quote can settle at most one live payment (double-submit / retry guard).
CREATE UNIQUE INDEX ux_parking_payments_quote ON parking_payments (quote_id) WHERE quote_id IS NOT NULL AND status IN ('PENDING', 'PAID');
CREATE INDEX ix_parking_payments_society ON parking_payments (society_id, created_at DESC);
CREATE INDEX ix_parking_payments_session ON parking_payments (session_id);
CREATE INDEX ix_parking_payments_permit ON parking_payments (permit_id);
CREATE INDEX ix_parking_payments_flat ON parking_payments (flat_id, created_at DESC);
CREATE INDEX ix_parking_payments_paid ON parking_payments (society_id, paid_at) WHERE status = 'PAID';

-- Append-only payment audit history.
CREATE TABLE parking_payment_events (
    id            uuid          PRIMARY KEY,
    payment_id    uuid          NOT NULL REFERENCES parking_payments (id),
    society_id    uuid          NOT NULL REFERENCES societies (id),
    event_type    varchar(20)   NOT NULL CHECK (event_type IN ('CREATED', 'CONFIRMED', 'FAILED', 'REFUNDED', 'WAIVED')),
    status        varchar(20)   NOT NULL,
    amount        numeric(12,2) NOT NULL,
    method        varchar(20),
    reference     varchar(100),
    reason        varchar(200),
    actor_user_id uuid          REFERENCES users (id),
    actor_name    varchar(80),
    occurred_at   timestamptz   NOT NULL
);
CREATE INDEX ix_parking_payment_events_payment ON parking_payment_events (payment_id, occurred_at);
CREATE TRIGGER trg_parking_payment_events_immutable BEFORE UPDATE ON parking_payment_events
    FOR EACH ROW EXECUTE FUNCTION forbid_row_update();

-- Issued receipts never change (a refund changes the payment status, not the receipt).
CREATE TABLE parking_receipts (
    id             uuid          PRIMARY KEY,
    society_id     uuid          NOT NULL REFERENCES societies (id),
    payment_id     uuid          NOT NULL REFERENCES parking_payments (id),
    receipt_number varchar(30)   NOT NULL,
    issued_at      timestamptz   NOT NULL,
    payer_name     varchar(80),
    society_name   varchar(120)  NOT NULL,
    vehicle_number varchar(15),
    lines          text          NOT NULL,
    subtotal       numeric(12,2) NOT NULL,
    tax            numeric(12,2) NOT NULL,
    tax_label      varchar(30),
    total          numeric(12,2) NOT NULL,
    currency       varchar(3)    NOT NULL,
    issued_by_name varchar(80),
    CONSTRAINT uq_parking_receipts_payment UNIQUE (payment_id),
    CONSTRAINT uq_parking_receipts_number UNIQUE (society_id, receipt_number)
);
CREATE TRIGGER trg_parking_receipts_immutable BEFORE UPDATE ON parking_receipts
    FOR EACH ROW EXECUTE FUNCTION forbid_row_update();

-- ---------------------------------------------------------------- permissions

INSERT INTO permissions (code, description) VALUES
    ('PARKING_VIEW',              'View parking configuration, areas, availability and rates of the society'),
    ('PARKING_SETTINGS_MANAGE',   'Change society parking settings'),
    ('PARKING_INVENTORY_MANAGE',  'Create and change parking areas and slots'),
    ('PARKING_ALLOCATION_MANAGE', 'Allocate slots, issue / revoke permits and decide parking requests'),
    ('VEHICLE_MANAGE',            'View, register, approve, reject and suspend vehicles of the society'),
    ('PARKING_RATE_MANAGE',       'Manage parking rate plans'),
    ('PARKING_PAYMENT_MANAGE',    'Record, confirm, refund and waive parking payments'),
    ('PARKING_LOG_VIEW',          'View all parking sessions, permits, requests and payments of the society'),
    ('PARKING_REPORT_VIEW',       'View parking reports, including revenue'),
    ('VEHICLE_REGISTER_OWN',      'Register and manage vehicles of own flat'),
    ('PARKING_REQUEST',           'Request parking slots, permits and visitor parking for own flat'),
    ('PARKING_VIEW_OWN',          'View own allocations, permits, sessions and payments'),
    ('GATE_VEHICLE_OPERATE',      'Verify vehicles, record parking entry / exit and payments at the gate');

INSERT INTO role_permissions (role_code, permission_code)
SELECT r.code, p.code
FROM (VALUES ('SUPER_ADMIN'), ('SOCIETY_ADMIN')) AS r (code)
CROSS JOIN permissions p
WHERE p.code IN ('PARKING_VIEW', 'PARKING_SETTINGS_MANAGE', 'PARKING_INVENTORY_MANAGE', 'PARKING_ALLOCATION_MANAGE',
                 'VEHICLE_MANAGE', 'PARKING_RATE_MANAGE', 'PARKING_PAYMENT_MANAGE', 'PARKING_LOG_VIEW',
                 'PARKING_REPORT_VIEW');

INSERT INTO role_permissions (role_code, permission_code) VALUES
    ('RESIDENT', 'PARKING_VIEW'),
    ('RESIDENT', 'VEHICLE_REGISTER_OWN'),
    ('RESIDENT', 'PARKING_REQUEST'),
    ('RESIDENT', 'PARKING_VIEW_OWN'),
    ('GUARD',    'PARKING_VIEW'),
    ('GUARD',    'GATE_VEHICLE_OPERATE');
