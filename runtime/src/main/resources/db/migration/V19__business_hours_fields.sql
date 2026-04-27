-- Business-hours duration fields on WorkItemTemplate
-- When set, these are resolved to absolute expiresAt/claimDeadline via BusinessCalendar at create time
ALTER TABLE work_item_template ADD COLUMN default_expiry_business_hours INTEGER;
ALTER TABLE work_item_template ADD COLUMN default_claim_business_hours INTEGER;
