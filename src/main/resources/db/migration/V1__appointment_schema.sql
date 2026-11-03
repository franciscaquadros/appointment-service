CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE specialty (id uuid PRIMARY KEY, code varchar(80) NOT NULL UNIQUE, name varchar(160) NOT NULL);
CREATE TABLE doctor (id uuid PRIMARY KEY, name varchar(160) NOT NULL, active boolean NOT NULL DEFAULT true, external_id varchar(160) NOT NULL UNIQUE);
CREATE TABLE doctor_specialty (doctor_id uuid REFERENCES doctor(id), specialty_id uuid REFERENCES specialty(id), PRIMARY KEY (doctor_id, specialty_id));
CREATE TABLE room (id uuid PRIMARY KEY, code varchar(80) NOT NULL UNIQUE, active boolean NOT NULL DEFAULT true, external_id varchar(160) NOT NULL UNIQUE);
CREATE TABLE patient (id uuid PRIMARY KEY, name varchar(160) NOT NULL, email varchar(254) NOT NULL, normalized_email varchar(254) NOT NULL UNIQUE);
CREATE TABLE appointment (
 id uuid PRIMARY KEY, patient_id uuid NOT NULL REFERENCES patient(id), doctor_id uuid NOT NULL REFERENCES doctor(id),
 room_id uuid NOT NULL REFERENCES room(id), specialty_id uuid NOT NULL REFERENCES specialty(id),
 starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 status text NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED')),
 FOREIGN KEY (doctor_id, specialty_id) REFERENCES doctor_specialty(doctor_id, specialty_id),
 CHECK (ends_at > starts_at),
 CONSTRAINT no_doctor_overlap EXCLUDE USING gist (doctor_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&),
 CONSTRAINT no_room_overlap EXCLUDE USING gist (room_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&),
 CONSTRAINT no_patient_overlap EXCLUDE USING gist (patient_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
);
CREATE INDEX appointment_listing ON appointment(starts_at, id);
CREATE INDEX doctor_specialty_lookup ON doctor_specialty(specialty_id, doctor_id);
CREATE TABLE idempotency_request (request_key uuid PRIMARY KEY, request_hash varchar(64) NOT NULL, appointment_id uuid REFERENCES appointment(id), created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE integration_job (
 id uuid PRIMARY KEY, appointment_id uuid NOT NULL REFERENCES appointment(id),
 operation text NOT NULL CHECK (operation IN ('CALENDAR', 'ROOM', 'EMAIL')),
 status text NOT NULL CHECK (status IN ('BLOCKED', 'READY', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
 attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0), next_attempt_at timestamptz NOT NULL DEFAULT now(),
 lease_token uuid, lease_expires_at timestamptz, last_error_code varchar(100), traceparent varchar(100),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (appointment_id, operation),
 CHECK ((status = 'PROCESSING') = (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL))
);
CREATE INDEX integration_job_due ON integration_job(status, next_attempt_at);
CREATE TABLE integration_job_attempt (
 id uuid PRIMARY KEY, job_id uuid NOT NULL REFERENCES integration_job(id), lease_token uuid,
 event varchar(30) NOT NULL CHECK (event IN ('CLAIMED','SUCCEEDED','RETRY','FAILED','REPLAYED','LEASE_EXPIRED')),
 error_code varchar(100), actor varchar(160), created_at timestamptz NOT NULL DEFAULT now()
);
