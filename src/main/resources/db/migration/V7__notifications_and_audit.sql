-- In-app notifications, push outbox, devices, preferences and the audit log.

CREATE TABLE notifications (
    id             uuid         PRIMARY KEY,
    user_id        uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    society_id     uuid         REFERENCES societies (id),
    type           varchar(40)  NOT NULL,
    title          varchar(150) NOT NULL,
    body           varchar(500) NOT NULL,
    reference_type varchar(30),
    reference_id   uuid,
    read_at        timestamptz,
    created_at     timestamptz  NOT NULL
);
CREATE INDEX ix_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;

-- Transactional outbox: written in the same transaction as the business change, sent by NotificationDispatcher.
CREATE TABLE notification_outbox (
    id              uuid         PRIMARY KEY,
    notification_id uuid         NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    status          varchar(20)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    attempts        int          NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL,
    last_error      varchar(500),
    created_at      timestamptz  NOT NULL,
    processed_at    timestamptz
);
CREATE INDEX ix_notification_outbox_due ON notification_outbox (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE user_devices (
    id                 uuid         PRIMARY KEY,
    user_id            uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token              varchar(512) NOT NULL,
    platform           varchar(10)  NOT NULL CHECK (platform IN ('ANDROID', 'IOS', 'WEB')),
    active             boolean      NOT NULL DEFAULT true,
    failure_count      int          NOT NULL DEFAULT 0,
    deactivated_reason varchar(60),
    last_seen_at       timestamptz  NOT NULL,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    CONSTRAINT uq_user_devices_token UNIQUE (token)
);
CREATE INDEX ix_user_devices_user ON user_devices (user_id) WHERE active;

CREATE TABLE notification_preferences (
    user_id           uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    notification_type varchar(40) NOT NULL,
    enabled           boolean     NOT NULL,
    PRIMARY KEY (user_id, notification_type)
);

CREATE TABLE audit_logs (
    id            uuid         PRIMARY KEY,
    society_id    uuid         REFERENCES societies (id),
    actor_user_id uuid         REFERENCES users (id),
    actor_name    varchar(80),
    action        varchar(60)  NOT NULL,
    entity_type   varchar(40)  NOT NULL,
    entity_id     uuid,
    summary       varchar(300) NOT NULL,
    details       text,
    ip_address    varchar(45),
    occurred_at   timestamptz  NOT NULL
);
CREATE INDEX ix_audit_logs_society_time ON audit_logs (society_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX ix_audit_logs_actor ON audit_logs (actor_user_id, occurred_at DESC);
CREATE TRIGGER trg_audit_logs_immutable BEFORE UPDATE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_row_update();
