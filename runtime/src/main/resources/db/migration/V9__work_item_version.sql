-- V9: Optimistic locking for WorkItem — atomic claim in clustered deployments
--
-- Adds a JPA @Version column to work_item. Hibernate includes this in every
-- UPDATE WHERE clause:
--
--   UPDATE work_item SET status = 'ASSIGNED', version = N+1, ...
--   WHERE id = ? AND version = N
--
-- If another node claimed the same WorkItem first (bumping version to N+1),
-- the WHERE clause matches zero rows. Hibernate throws OptimisticLockException,
-- which is mapped to HTTP 409 Conflict. The caller retries with fresh data.
--
-- DEFAULT 0 ensures existing rows are not affected.

ALTER TABLE work_item ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
