-- A replay starts a new bounded recovery window; history remains intact.
ALTER TABLE integration_job ADD COLUMN retry_started_at timestamptz NOT NULL DEFAULT now();
UPDATE integration_job SET retry_started_at=created_at;
CREATE INDEX integration_job_expired_lease ON integration_job(lease_expires_at) WHERE status='PROCESSING';
