-- quarkus-workitems-queues V2000: filters, filter chains, queue views, queue state
-- Compatible with H2 (dev/test) and PostgreSQL (production).

CREATE TABLE work_item_filter (
    id                   UUID            PRIMARY KEY,
    name                 VARCHAR(255)    NOT NULL,
    scope                VARCHAR(20)     NOT NULL,
    owner_id             VARCHAR(255),
    condition_language   VARCHAR(20)     NOT NULL,
    condition_expression VARCHAR(4000),
    actions              VARCHAR(4000),
    active               BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP       NOT NULL
);

CREATE TABLE filter_chain (
    id          UUID    PRIMARY KEY,
    filter_id   UUID    NOT NULL REFERENCES work_item_filter(id) ON DELETE CASCADE
);

CREATE TABLE filter_chain_work_item (
    filter_chain_id UUID    NOT NULL REFERENCES filter_chain(id) ON DELETE CASCADE,
    work_item_id    UUID    NOT NULL REFERENCES work_item(id) ON DELETE CASCADE,
    PRIMARY KEY (filter_chain_id, work_item_id)
);

CREATE INDEX idx_fc_filter_id ON filter_chain(filter_id);
CREATE INDEX idx_fcwi_work_item_id ON filter_chain_work_item(work_item_id);

CREATE TABLE queue_view (
    id                    UUID            PRIMARY KEY,
    name                  VARCHAR(255)    NOT NULL,
    label_pattern         VARCHAR(500)    NOT NULL,
    scope                 VARCHAR(20)     NOT NULL,
    owner_id              VARCHAR(255),
    additional_conditions VARCHAR(2000),
    sort_field            VARCHAR(50)     NOT NULL DEFAULT 'createdAt',
    sort_direction        VARCHAR(4)      NOT NULL DEFAULT 'ASC',
    created_at            TIMESTAMP       NOT NULL
);

CREATE TABLE work_item_queue_state (
    work_item_id    UUID        PRIMARY KEY REFERENCES work_item(id) ON DELETE CASCADE,
    relinquishable  BOOLEAN     NOT NULL DEFAULT FALSE
);
