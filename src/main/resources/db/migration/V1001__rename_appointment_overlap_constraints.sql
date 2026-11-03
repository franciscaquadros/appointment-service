-- Version follows V1000 so existing demo databases also apply this migration.
ALTER TABLE appointment RENAME CONSTRAINT no_patient_overlap TO patient_overlap;
ALTER TABLE appointment RENAME CONSTRAINT no_doctor_overlap TO doctor_overlap;
ALTER TABLE appointment RENAME CONSTRAINT no_room_overlap TO room_overlap;
