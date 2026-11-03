-- Demo-only catalog maintenance; applied after versioned migrations.
-- Keep V1000 unchanged for existing Flyway history. Add future fixtures here.
-- Existing rows (including local edits) are preserved.
INSERT INTO specialty (id,code,name) VALUES
  ('00000000-0000-0000-0000-000000000001','CARDIOLOGY','Cardiologia'),
  ('00000000-0000-0000-0000-000000000002','DERMATOLOGY','Dermatologia')
ON CONFLICT (id) DO NOTHING;

INSERT INTO doctor (id,name,external_id) VALUES
  ('10000000-0000-0000-0000-000000000001','Dra. Silva','doctor-silva'),
  ('10000000-0000-0000-0000-000000000002','Dr. Costa','doctor-costa')
ON CONFLICT (id) DO NOTHING;

INSERT INTO doctor_specialty (doctor_id,specialty_id) VALUES
  ('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001'),
  ('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002'),
  ('10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001')
ON CONFLICT (doctor_id,specialty_id) DO NOTHING;

INSERT INTO room (id,code,external_id) VALUES
  ('20000000-0000-0000-0000-000000000001','Room 1','room-1'),
  ('20000000-0000-0000-0000-000000000002','Room 2','room-2')
ON CONFLICT (id) DO NOTHING;
