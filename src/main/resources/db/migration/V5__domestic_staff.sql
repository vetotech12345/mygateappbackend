-- Domestic staff, their flat assignments and gate attendance.

CREATE TABLE domestic_staff (
    id                uuid        PRIMARY KEY,
    society_id        uuid        NOT NULL REFERENCES societies (id),
    name              varchar(60) NOT NULL,
    mobile            varchar(15) NOT NULL,
    category          varchar(20) NOT NULL CHECK (category IN ('MAID', 'DRIVER', 'COOK', 'CLEANER', 'OTHER')),
    photo_file_id     uuid        REFERENCES stored_files (id) ON DELETE SET NULL,
    pass_number       varchar(30),
    status            varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    working_since     date,
    inside            boolean     NOT NULL DEFAULT false,
    last_check_in_at  timestamptz,
    last_check_out_at timestamptz,
    created_by        uuid        REFERENCES users (id),
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_staff_id_society UNIQUE (id, society_id),
    CONSTRAINT uq_staff_mobile UNIQUE (society_id, mobile)
);
CREATE UNIQUE INDEX ux_staff_pass_number ON domestic_staff (society_id, pass_number) WHERE pass_number IS NOT NULL;

CREATE TABLE domestic_staff_flat_assignments (
    id          uuid        PRIMARY KEY,
    society_id  uuid        NOT NULL,
    staff_id    uuid        NOT NULL,
    flat_id     uuid        NOT NULL,
    active      boolean     NOT NULL DEFAULT true,
    assigned_at timestamptz NOT NULL,
    ended_at    timestamptz,
    CONSTRAINT fk_staff_assignments_staff FOREIGN KEY (staff_id, society_id) REFERENCES domestic_staff (id, society_id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_assignments_flat FOREIGN KEY (flat_id, society_id) REFERENCES flats (id, society_id)
);
CREATE UNIQUE INDEX ux_staff_assignments_active ON domestic_staff_flat_assignments (staff_id, flat_id) WHERE active;
CREATE INDEX ix_staff_assignments_flat ON domestic_staff_flat_assignments (flat_id) WHERE active;

CREATE TABLE domestic_staff_attendance (
    id                uuid        PRIMARY KEY,
    society_id        uuid        NOT NULL,
    staff_id          uuid        NOT NULL,
    check_in_at       timestamptz NOT NULL,
    check_in_gate_id  uuid        REFERENCES society_gates (id),
    checked_in_by     uuid        REFERENCES users (id),
    check_out_at      timestamptz,
    check_out_gate_id uuid        REFERENCES society_gates (id),
    checked_out_by    uuid        REFERENCES users (id),
    CONSTRAINT fk_staff_attendance_staff FOREIGN KEY (staff_id, society_id) REFERENCES domestic_staff (id, society_id) ON DELETE CASCADE,
    CONSTRAINT ck_staff_attendance_times CHECK (check_out_at IS NULL OR check_out_at >= check_in_at)
);
-- At most one open attendance (checked in, not checked out) per staff member.
CREATE UNIQUE INDEX ux_staff_attendance_open ON domestic_staff_attendance (staff_id) WHERE check_out_at IS NULL;
CREATE INDEX ix_staff_attendance_staff ON domestic_staff_attendance (staff_id, check_in_at DESC);
CREATE INDEX ix_staff_attendance_society ON domestic_staff_attendance (society_id, check_in_at DESC);
