-- Complaints (with comments and status history) and society notices.

CREATE TABLE complaints (
    id            uuid          PRIMARY KEY,
    society_id    uuid          NOT NULL REFERENCES societies (id),
    flat_id       uuid          NOT NULL,
    raised_by     uuid          NOT NULL REFERENCES users (id),
    ticket_number varchar(20)   NOT NULL,
    category      varchar(20)   NOT NULL CHECK (category IN ('PLUMBING', 'ELECTRICAL', 'LIFT', 'SECURITY', 'HOUSEKEEPING', 'PARKING', 'AMENITIES', 'OTHER')),
    title         varchar(80)   NOT NULL,
    description   varchar(1000) NOT NULL,
    status        varchar(20)   NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    assigned_to   uuid          REFERENCES users (id),
    admin_remarks varchar(500),
    resolved_at   timestamptz,
    closed_at     timestamptz,
    created_at    timestamptz   NOT NULL,
    updated_at    timestamptz   NOT NULL,
    version       bigint        NOT NULL DEFAULT 0,
    CONSTRAINT fk_complaints_flat FOREIGN KEY (flat_id, society_id) REFERENCES flats (id, society_id),
    CONSTRAINT uq_complaints_ticket UNIQUE (society_id, ticket_number)
);
CREATE INDEX ix_complaints_society_status ON complaints (society_id, status, updated_at DESC);
CREATE INDEX ix_complaints_flat ON complaints (flat_id, updated_at DESC);

CREATE TABLE complaint_attachments (
    complaint_id uuid NOT NULL REFERENCES complaints (id) ON DELETE CASCADE,
    file_id      uuid NOT NULL REFERENCES stored_files (id),
    position     int  NOT NULL,
    PRIMARY KEY (complaint_id, position)
);

CREATE TABLE complaint_comments (
    id           uuid          PRIMARY KEY,
    complaint_id uuid          NOT NULL REFERENCES complaints (id) ON DELETE CASCADE,
    author_id    uuid          NOT NULL REFERENCES users (id),
    author_name  varchar(80),
    body         varchar(1000) NOT NULL,
    internal     boolean       NOT NULL DEFAULT false,
    created_at   timestamptz   NOT NULL
);
CREATE INDEX ix_complaint_comments_complaint ON complaint_comments (complaint_id, created_at);

CREATE TABLE complaint_status_history (
    id              uuid         PRIMARY KEY,
    complaint_id    uuid         NOT NULL REFERENCES complaints (id) ON DELETE CASCADE,
    from_status     varchar(20),
    to_status       varchar(20)  NOT NULL,
    title           varchar(150) NOT NULL,
    remarks         varchar(500),
    changed_by      uuid         REFERENCES users (id),
    changed_by_name varchar(80),
    changed_at      timestamptz  NOT NULL
);
CREATE INDEX ix_complaint_history_complaint ON complaint_status_history (complaint_id, changed_at);
CREATE TRIGGER trg_complaint_history_immutable BEFORE UPDATE ON complaint_status_history
    FOR EACH ROW EXECUTE FUNCTION forbid_row_update();

-- Notices are soft-deleted (deleted_at) so notifications and the audit trail keep a valid reference.
CREATE TABLE notices (
    id              uuid         PRIMARY KEY,
    society_id      uuid         NOT NULL REFERENCES societies (id),
    title           varchar(150) NOT NULL,
    body            varchar(5000) NOT NULL,
    category        varchar(20)  NOT NULL CHECK (category IN ('GENERAL', 'MAINTENANCE', 'EVENT', 'FINANCE', 'SAFETY')),
    important       boolean      NOT NULL DEFAULT false,
    audience        varchar(20)  NOT NULL CHECK (audience IN ('ALL', 'RESIDENTS', 'GUARDS', 'TOWERS')),
    status          varchar(20)  NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    posted_by_label varchar(80)  NOT NULL,
    published_at    timestamptz,
    created_by      uuid         NOT NULL REFERENCES users (id),
    updated_by      uuid         REFERENCES users (id),
    deleted_at      timestamptz,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_notices_published CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);
CREATE INDEX ix_notices_society_published ON notices (society_id, published_at DESC) WHERE deleted_at IS NULL;

CREATE TABLE notice_audience_towers (
    notice_id uuid NOT NULL REFERENCES notices (id) ON DELETE CASCADE,
    tower_id  uuid NOT NULL REFERENCES towers (id),
    PRIMARY KEY (notice_id, tower_id)
);

CREATE TABLE notice_attachments (
    id           uuid         PRIMARY KEY,
    notice_id    uuid         NOT NULL REFERENCES notices (id) ON DELETE CASCADE,
    file_id      uuid         NOT NULL REFERENCES stored_files (id),
    display_name varchar(200) NOT NULL,
    created_at   timestamptz  NOT NULL
);
CREATE INDEX ix_notice_attachments_notice ON notice_attachments (notice_id);

CREATE TABLE notice_read_receipts (
    notice_id uuid        NOT NULL REFERENCES notices (id) ON DELETE CASCADE,
    user_id   uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    read_at   timestamptz NOT NULL,
    PRIMARY KEY (notice_id, user_id)
);
CREATE INDEX ix_notice_read_receipts_user ON notice_read_receipts (user_id);
