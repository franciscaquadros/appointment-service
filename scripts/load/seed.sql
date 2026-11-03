-- Only used in the runner's disposable database, after the demo migrations.
INSERT INTO doctor SELECT md5('load-doctor-'||i)::uuid,'Load Doctor '||i,true,'load-doctor-'||i
  FROM generate_series(1,20) i;
INSERT INTO room SELECT md5('load-room-'||i)::uuid,'Load Room '||i,true,'load-room-'||i
  FROM generate_series(1,20) i;
INSERT INTO patient SELECT md5('load-patient-'||i)::uuid,'Historical Patient '||i,
  'history-'||i||'@example.invalid','history-'||i||'@example.invalid'
  FROM generate_series(1,20) i;
INSERT INTO doctor_specialty SELECT md5('load-doctor-'||i)::uuid,
  '00000000-0000-0000-0000-000000000001'::uuid FROM generate_series(1,20) i;
INSERT INTO appointment(id,patient_id,doctor_id,room_id,specialty_id,starts_at,ends_at)
  SELECT md5('load-history-'||i||'-'||n)::uuid,md5('load-patient-'||i)::uuid,
    md5('load-doctor-'||i)::uuid,md5('load-room-'||i)::uuid,
    '00000000-0000-0000-0000-000000000001'::uuid,
    '2020-01-01 10:00Z'::timestamptz+n*interval '1 day',
    '2020-01-01 10:30Z'::timestamptz+n*interval '1 day'
  FROM generate_series(1,20) i CROSS JOIN generate_series(0,1999) n;
ANALYZE;
