-- Replace expiring ownership leases with transaction-scoped row locks.
-- Stop old application instances before applying: they use the removed columns/history.
ALTER TABLE integration_job DROP CONSTRAINT integration_job_check;
ALTER TABLE integration_job DROP CONSTRAINT integration_job_status_check;
UPDATE integration_job SET status='READY',attempt_count=greatest(attempt_count-1,0)
WHERE status='PROCESSING';
ALTER TABLE integration_job ADD CONSTRAINT integration_job_status_check
  CHECK (status IN ('BLOCKED','READY','SUCCEEDED','FAILED'));
ALTER TABLE integration_job
  DROP COLUMN lease_token,
  DROP COLUMN lease_expires_at,
  DROP COLUMN traceparent,
  DROP COLUMN retry_started_at;
DROP TABLE integration_job_attempt;
