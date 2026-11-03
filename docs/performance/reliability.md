# Provider failure and recovery checks

[README testing](../../README.md#testing) · [Healthy-provider baseline](load-baseline.md)

These checks exercise provider stalls/outages, application termination after remote success, and database readiness/recovery. Recorded measurements belong to the source/image identifiers in the raw evidence. Run the harness against the current checkout to verify that checkout.

## Reproduce

Run from the project root (the directory containing `pom.xml` and `compose.yaml`). If your terminal is in `docs/performance`, run `cd ../..` first.

In IntelliJ, run the command in the Terminal tool window from the project root, or set the run configuration's working directory to the project root. The Markdown code block's Run action may use `docs/performance` as its working directory; the command below assumes the project root.

```sh
python3 scripts/load/run.py --reliability --output /tmp/healthcare-reliability
```

Requires Python 3 and Docker Compose. The runner builds the application in a uniquely named disposable project, publishes no host ports and cleans up its own resources. It uses the [baseline workload and resource limits](load-baseline.md#workload-and-pass-criteria): 40,000 historical appointments, 30-second warmup, 10-second gap and 180 measured seconds at 100 mixed requests/minute. Real adapters call local stubs/Mailpit. Tracing sampling is disabled and Prometheus does not scrape.

## Failure scenarios

Fault injection starts after ten measured patients have been created:

1. Calendar returns 503 for at least 20 seconds with a five-second retry hint. `HTTP_503` must be recorded while room work continues.
2. Calendar commits reservations before delaying responses by eight seconds, exceeding the five-second adapter timeout, for at least 15 seconds. `HTTP_UNAVAILABLE` must be recorded and deduplicated retries must recover.
3. SMTP is paused for at least 20 seconds and resumed with its mailbox intact. `SMTP_DELIVERY_FAILED` must be recorded while calendar and room work continue.
4. After load/drain, the application is killed after a confirmed remote calendar commit and before the delayed response returns. The local attempt must roll back to `READY` with zero recorded attempts. Restart and replay must return the same appointment, complete its jobs and retain one unique calendar reservation for its key.
5. PostgreSQL is stopped after outstanding work finishes. Readiness must return 503, liveness 200 and a new booking 503. Restarting the same database must restore readiness and preserve bookings. Repeated retries with one request key must produce one appointment.

## Assertions

All measured bookings must finish their three jobs, with no pending/failed jobs, orphan jobs, conflicting appointment pairs or successful emails with unfinished prerequisites. Each provider's unique reservation count must match the test appointments, including recovery probes. Recorded attempts must stay within the configured default of five.

SMTP is checked separately: uncertain delivery can be repeated, so captured message counts may exceed appointment counts. A stable Message-ID does not guarantee deduplication or actual receipt.

## Recorded measurements

**Passed.** Measurement completed **301 requests in 180 seconds**, with one extra iteration at the scenario boundary: 151 bookings, 60 list reads, 60 detail reads and 30 expected conflicts. The count-based equivalent is 6,020 requests/hour around the nominal 6,000/hour target. Unexpected errors and dropped iterations were **zero**.

| Request | Samples | p95 ms | p99 ms | Maximum ms |
|---|---:|---:|---:|---:|
| All measured requests | 301 | 119.30 | 122.26 | 165.04 |
| New booking | 151 | 20.95 | 23.46 | 24.29 |
| Appointment list | 60 | 122.08 | 152.55 | 165.04 |
| Appointment detail | 60 | 119.33 | 121.31 | 122.26 |
| Expected conflict | 30 | 12.47 | 13.57 | 13.72 |

| Failure observation | Result |
|---|---|
| Calendar 503 | `HTTP_503` recorded; 18 room jobs completed during the outage |
| Calendar timeout | Remote commit followed by `HTTP_UNAVAILABLE`; retries recovered without additional unique reservations |
| SMTP pause | `SMTP_DELIVERY_FAILED` recorded; 50 calendar and 17 room jobs completed during the pause |
| Kill after remote commit | Calendar attempt rolled back; replay recovered one appointment and one remote calendar reservation |
| Database stop | Readiness 503, liveness 200, booking 503 |
| Database restart | Readiness 200; retry/replay recovered one appointment and preserved completed work |

All **151 measured bookings / 453 jobs** completed. With setup, warmup and recovery probes, **169 appointments / 507 jobs** finished successfully. Both providers held 169 unique reservations and Mailpit captured 169 messages. The final state had no pending/failed jobs, orphan jobs, overlap pairs involving test bookings or successful emails with unfinished prerequisites.

The sampled backlog peaked at **69 jobs**, with oldest pending age **59.99 seconds**. An empty queue was sampled about **4.4 seconds** after SMTP resumed. Approximate integration completion delay was p95 **52.74 seconds**, p99 **59.29 seconds**, maximum **60.11 seconds**. These estimates use transaction-start timestamps and exclude the final provider call/commit, as explained in the [baseline limits](load-baseline.md#evidence-and-limits).

Twenty calendar jobs and three email jobs had more than one recorded attempt; no room jobs retried. The maximum recorded attempt count was four. All k6 thresholds, operation-isolation checks, recovery probes and final invariants passed.

## Evidence and limits

- [Environment, source/image identity, recovery probes and final invariants](reliability-run/environment-and-jobs.json)
- [Fault events and operation progress](reliability-run/fault-events.json)
- [k6 summary](reliability-run/k6-summary.json)
- [Queue samples](reliability-run/backlog-samples.json)

The [Maven suite](../../README.md#testing) complements these checks with concurrent allocation/deduplication, atomic rollback, job-lock ownership, retry timing/exhaustion, past-start email suppression and safe error contracts. A crashed transaction rolls back its counter, so recorded-attempt limits do not bound all remote calls across repeated crashes. Durable provider deduplication remains necessary.

These are controlled failures on one application instance with database storage surviving process restart. They do not establish an uptime SLO, long-term stability, storage-loss recovery, database failover, backup/restore, multi-instance failover or behavior of real providers. Sampled queue recovery is not an exact recovery-time objective. Mailpit capture does not prove patient receipt or production alert delivery.
