-- Only correlation context is needed for request-to-worker tracing.
ALTER TABLE integration_job ADD COLUMN traceparent varchar(100);
