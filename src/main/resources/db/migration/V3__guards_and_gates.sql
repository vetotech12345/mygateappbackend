-- Gates, guards, gate assignments (shifts) and duty logs.

CREATE TABLE society_gates (
    id         uuid        PRIMARY KEY,
    society_id uuid        NOT NULL REFERENCES societies (id),
    name       varchar(60) NOT NULL,
    gate_type  varchar(20) NOT NULL CHECK (gate_type IN ('MAIN', 'SERVICE', 'PEDESTRIAN', 'VEHICLE', 'OTHER')),
    status     varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_gates_name UNIQUE (society_id, name),
    CONSTRAINT uq_gates_id_society UNIQUE (id, society_id)
);

CREATE TABLE security_guards (
    id              uuid         PRIMARY KEY,
    society_id      uuid         NOT NULL REFERENCES societies (id),
    user_id         uuid         NOT NULL REFERENCES users (id),
    employee_code   varchar(30),
    agency_name     varchar(100),
    status          varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    on_duty         boolean      NOT NULL DEFAULT false,
    duty_changed_at timestamptz,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_guards_user_society UNIQUE (society_id, user_id),
    CONSTRAINT uq_guards_id_society UNIQUE (id, society_id)
);
CREATE UNIQUE INDEX ux_guards_employee_code ON security_guards (society_id, employee_code) WHERE employee_code IS NOT NULL;

-- Per-guard gate capabilities (the Flutter app shows/hides actions from this list).
CREATE TABLE guard_permissions (
    guard_id   uuid        NOT NULL REFERENCES security_guards (id) ON DELETE CASCADE,
    permission varchar(30) NOT NULL CHECK (permission IN ('VISITOR_ENTRY', 'DELIVERY_ENTRY', 'STAFF_ATTENDANCE')),
    PRIMARY KEY (guard_id, permission)
);

-- Gate + shift assignment. Exactly one active assignment per guard; older rows are history.
CREATE TABLE guard_assignments (
    id          uuid        PRIMARY KEY,
    society_id  uuid        NOT NULL,
    guard_id    uuid        NOT NULL,
    gate_id     uuid        NOT NULL,
    shift_name  varchar(40),
    shift_start time,
    shift_end   time,
    active      boolean     NOT NULL DEFAULT true,
    assigned_by uuid        REFERENCES users (id),
    valid_from  timestamptz NOT NULL,
    valid_until timestamptz,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT fk_guard_assignments_guard FOREIGN KEY (guard_id, society_id) REFERENCES security_guards (id, society_id),
    CONSTRAINT fk_guard_assignments_gate FOREIGN KEY (gate_id, society_id) REFERENCES society_gates (id, society_id)
);
CREATE UNIQUE INDEX ux_guard_assignments_active ON guard_assignments (guard_id) WHERE active;
CREATE INDEX ix_guard_assignments_gate ON guard_assignments (gate_id) WHERE active;

CREATE TABLE guard_duty_logs (
    id         uuid        PRIMARY KEY,
    society_id uuid        NOT NULL,
    guard_id   uuid        NOT NULL,
    gate_id    uuid        REFERENCES society_gates (id),
    started_at timestamptz NOT NULL,
    ended_at   timestamptz,
    CONSTRAINT fk_guard_duty_logs_guard FOREIGN KEY (guard_id, society_id) REFERENCES security_guards (id, society_id),
    CONSTRAINT ck_guard_duty_logs_times CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE UNIQUE INDEX ux_guard_duty_logs_open ON guard_duty_logs (guard_id) WHERE ended_at IS NULL;
CREATE INDEX ix_guard_duty_logs_guard ON guard_duty_logs (guard_id, started_at DESC);
