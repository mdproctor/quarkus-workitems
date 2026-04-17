-- WorkItemLedgerEntry subclass table (JPA JOINED inheritance)
-- Joins to ledger_entry(id) for all base fields.
-- The ledger_entry and ledger_attestation base tables are created by
-- quarkus-ledger V1/V2 migrations, which are on the classpath automatically.
CREATE TABLE work_item_ledger_entry (
    id           UUID         NOT NULL,
    command_type VARCHAR(100),
    event_type   VARCHAR(100),
    CONSTRAINT pk_work_item_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_work_item_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry (id)
);
