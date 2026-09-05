ALTER TABLE work_item ADD COLUMN compensation_status VARCHAR(20) DEFAULT 'NONE';
ALTER TABLE work_item ADD COLUMN compensates_work_item_id UUID;
ALTER TABLE work_item ADD CONSTRAINT fk_compensates_work_item
    FOREIGN KEY (compensates_work_item_id) REFERENCES work_item(id);
CREATE INDEX idx_work_item_compensates ON work_item(compensates_work_item_id);
