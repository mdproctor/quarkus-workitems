-- Quarkus Tarkus V1 schema: work_item and audit_entry tables
-- Compatible with H2 (dev/test) and PostgreSQL (production)

CREATE TABLE work_item (
    id                  UUID            NOT NULL,
    title               VARCHAR(500)    NOT NULL,
    description         VARCHAR(4000),
    category            VARCHAR(255),
    form_key            VARCHAR(500),

    -- Status and priority stored as strings for readability
    status              VARCHAR(50)     NOT NULL,
    priority            VARCHAR(20)     NOT NULL DEFAULT 'NORMAL',

    -- Assignment
    assignee_id         VARCHAR(255),
    owner               VARCHAR(255),
    candidate_groups    VARCHAR(2000),
    candidate_users     VARCHAR(2000),
    required_capabilities VARCHAR(2000),
    created_by          VARCHAR(255),

    -- Delegation
    delegation_state    VARCHAR(20),
    delegation_chain    VARCHAR(4000),
    prior_status        VARCHAR(50),

    -- Payload (JSON, stored as TEXT)
    payload             TEXT,
    resolution          TEXT,

    -- Deadlines
    claim_deadline      TIMESTAMP,
    expires_at          TIMESTAMP,
    follow_up_date      TIMESTAMP,

    -- Timestamps
    created_at          TIMESTAMP       NOT NULL,
    updated_at          TIMESTAMP       NOT NULL,
    assigned_at         TIMESTAMP,
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    suspended_at        TIMESTAMP,

    CONSTRAINT pk_work_item PRIMARY KEY (id)
);

-- Indexes for common queries
CREATE INDEX idx_work_item_status          ON work_item (status);
CREATE INDEX idx_work_item_assignee_id     ON work_item (assignee_id);
CREATE INDEX idx_work_item_expires_at      ON work_item (expires_at);
CREATE INDEX idx_work_item_claim_deadline  ON work_item (claim_deadline);
CREATE INDEX idx_work_item_follow_up_date  ON work_item (follow_up_date);

CREATE TABLE audit_entry (
    id              UUID            NOT NULL,
    work_item_id    UUID            NOT NULL,
    event           VARCHAR(50)     NOT NULL,
    actor           VARCHAR(255),
    detail          TEXT,
    occurred_at     TIMESTAMP       NOT NULL,

    CONSTRAINT pk_audit_entry PRIMARY KEY (id),
    CONSTRAINT fk_audit_entry_work_item FOREIGN KEY (work_item_id) REFERENCES work_item (id)
);

CREATE INDEX idx_audit_entry_work_item_id ON audit_entry (work_item_id);
CREATE INDEX idx_audit_entry_occurred_at  ON audit_entry (occurred_at);
