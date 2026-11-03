# Compose smoke check

[README testing](../../README.md#testing) · [Recovery checks](reliability.md)

## Reproduce

Run from the project root (the directory containing `pom.xml` and `compose.yaml`). If your terminal is in `docs/performance`, run `cd ../..` first.

In IntelliJ, run the command in the Terminal tool window from the project root, or set the run configuration's working directory to the project root. The Markdown code block's Run action may use `docs/performance` as its working directory; the command below assumes the project root.

```sh
python3 scripts/load/run.py --smoke --output /tmp/healthcare-smoke
```

Requires Python 3 and Docker Compose. The runner builds the current source and starts PostgreSQL, the application with real workers/adapters, provider contract stubs and Mailpit in an isolated project. It publishes no host ports and cleans up its own containers, network and volumes. It skips k6, historical fixtures and injected failures.

## Assertions

The probe creates two simultaneous Cardiology bookings for different synthetic patients, replays the first request key, follows a one-item listing cursor and allows up to 30 seconds for the jobs to succeed. It verifies:

- Exactly two appointments and six jobs, including after replay.
- Both booking IDs appear once across the two HTTP pages.
- Two reservations in each provider, with stable operation keys and matching appointment IDs, allocated external resource IDs and start/end instants.
- Two emails, one per patient, with the Portuguese subject, patient/doctor/room names, booking reference and Lisbon start/end times.
- HTTP detail responses report `COMPLETED` for both bookings.

A failed assertion exits nonzero. The output directory contains source/image identifiers, environment details and the result in `environment-and-jobs.json`; failures also retain recent application logs. CI runs this command after Maven verification.

## Recorded measurements

**Passed.** The retained probe took **6.923 seconds**, excluding build, startup and cleanup.

| Check | Observed |
|---|---|
| Appointments / durable jobs | 2 / 6 |
| Successful calendar / room / email jobs | 2 / 2 / 2 |
| Pending / failed / retried jobs | 0 / 0 / 0 |
| Unique calendar / room reservations | 2 / 2 |
| Confirmation emails | 2, one per patient |
| Replay, cursor, payload, email content and detail checks | All passed |

[Raw evidence](smoke-run/environment-and-jobs.json) identifies the measured source/images and environment. The recorded result describes that snapshot; rerun the command for the current checkout.

## Scope

This is a small normal-flow integration check. [Reliability checks](reliability.md) cover injected failures and [load measurements](load-baseline.md) cover sustained offered traffic. Mailpit capture verifies local SMTP submission, not actual mailbox delivery. Maven test coverage and the current suite total are summarized in the [README](../../README.md#testing).
