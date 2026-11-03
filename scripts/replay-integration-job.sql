\set ON_ERROR_STOP on
-- Run only after diagnosing/correcting the provider failure; pass -v job_id=UUID.
-- Normal database access controls apply. No application replay HTTP endpoint exists.
UPDATE integration_job
SET status=CASE WHEN operation='EMAIL' THEN 'BLOCKED' ELSE 'READY' END,
    attempt_count=0,next_attempt_at=now(),last_error_code=NULL,updated_at=now()
WHERE id=:'job_id'::uuid AND status='FAILED'
  AND (operation<>'EMAIL' OR EXISTS (
    SELECT 1 FROM appointment a WHERE a.id=integration_job.appointment_id AND a.starts_at>now()
  ))
RETURNING id,appointment_id,operation,status;
