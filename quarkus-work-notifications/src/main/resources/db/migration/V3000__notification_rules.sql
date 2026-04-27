CREATE TABLE work_item_notification_rule (
    id           UUID         NOT NULL,
    channel_type VARCHAR(50)  NOT NULL,
    target_url   VARCHAR(2048) NOT NULL,
    event_types  VARCHAR(500) NOT NULL,
    category     VARCHAR(255),
    secret       VARCHAR(255),
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_work_item_notification_rule PRIMARY KEY (id)
);

CREATE INDEX idx_notification_rule_enabled ON work_item_notification_rule (enabled);
