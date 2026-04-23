-- quarkus-workitems-queues V2001: persistent queue membership tracking
-- Stores the last-resolved queue membership per WorkItem, enabling correct
-- ADDED / REMOVED / CHANGED event computation across JVM restarts.
--
-- Rows are upserted on every WorkItemLifecycleEvent and deleted when an item
-- leaves all queues. The table is the authoritative "before-state" for the
-- QueueMembershipContext diff algorithm.

CREATE TABLE work_item_queue_membership (
    id            UUID         NOT NULL,
    work_item_id  UUID         NOT NULL,
    queue_view_id UUID         NOT NULL,
    queue_name    VARCHAR(255) NOT NULL,
    CONSTRAINT pk_work_item_queue_membership PRIMARY KEY (id),
    CONSTRAINT uq_work_item_queue_membership UNIQUE (work_item_id, queue_view_id)
);

CREATE INDEX idx_wiqm_work_item_id ON work_item_queue_membership (work_item_id);
