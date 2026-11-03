# HTTP and integration load baseline

[README testing](../../README.md#testing) · [Failure/recovery checks](reliability.md)

The chosen workload is **6,000 mixed HTTP requests/hour**, measured over a short run. The figures below belong to the source/image identifiers in the retained evidence. The runner builds the current checkout; rerun it for measurements of that checkout.

## Reproduce

Requires Python 3 and Docker Compose.

Run from the project root (the directory containing `pom.xml` and `compose.yaml`). If your terminal is in `docs/performance`, run `cd ../..` first.

In IntelliJ, run the command in the Terminal tool window from the project root, or set the run configuration's working directory to the project root. The Markdown code block's Run action may use `docs/performance` as its working directory; the command below assumes the project root.

```sh
python3 scripts/load/run.py --output /tmp/healthcare-load
```

The runner builds the application, creates a uniquely named Compose deployment, waits for database-backed readiness, seeds history, runs k6, samples the job queue about every two seconds and allows up to 30 seconds for remaining jobs to finish. It publishes no host ports and removes its own containers, network and anonymous volumes. A separate output directory preserves the retained measurements.

For a different workload:

```sh
python3 scripts/load/run.py --rate-per-minute 200 --duration-seconds 300 \
  --output /tmp/healthcare-load-12000
```

The k6 script is [baseline.js](../../scripts/load/baseline.js), with [Compose services](../../scripts/load/compose.yaml) and [history fixtures](../../scripts/load/seed.sql) beside it. Direct k6 execution accepts `BASE_URL`, `RATE_PER_MINUTE` and `DURATION`. Protected reads use demo ADMIN credentials. The current app also applies its configured booking rate limit; keep it consistent with the workload under test.

## Workload and pass criteria

- Warmup: 30 seconds at 60 requests/minute, followed by a 10-second gap. Measurement: 180 seconds at 100 requests/minute, one request per iteration.
- Mix: 50% new bookings, 20% first-page lists, 20% appointment details and 10% intentional conflicts for one existing patient.
- Synthetic bookings use distinct valid future slots. Conflicts must return `409 PATIENT_ALREADY_BOOKED` and are separate from unexpected errors.
- Booking/detail bodies must identify the expected confirmed appointment; lists must contain 20 items and a continuation cursor.
- Per endpoint and overall: p95 below 500 ms, p99 below 1,000 ms, unexpected error rate below 1%, all correctness checks passing and zero dropped iterations. These are test thresholds, not production SLOs.
- The real worker stays enabled. All measured new bookings must complete their three jobs, with no pending or failed jobs after the drain allowance. The planned number of measured HTTP requests must complete.

The k6 constant-arrival-rate scenario maintains offered traffic independently of response speed. Measurement uses 10 preallocated virtual users and allows 40; warmup uses 5 and allows 20. Custom `baseline_*` metrics exclude setup and warmup. Endpoint latency includes expected conflicts.

## Environment

One application instance, PostgreSQL 17.6, k6 1.6.0, local calendar/room stubs without injected delay and Mailpit 1.24.1. Real HTTP/SMTP adapters and the worker execute against those local services. Tracing sampling is disabled and Prometheus does not scrape during the run.

The database contains 40,000 historical non-overlapping appointments, 22 doctors, 22 rooms and 20 historical patients. The demo catalog contributes two doctors and two rooms; historical appointments have no pending jobs. New bookings use Cardiology.

Container limits are app 2 CPUs/1 GiB, database 2 CPUs/1 GiB, k6 1 CPU/512 MiB, and each HTTP/SMTP stub 0.5 CPU/256 MiB. The application uses Java 25, demo authentication and a 12-connection Hikari pool. The retained Docker Desktop environment exposed 8 CPUs and 7.75 GiB on macOS ARM64. Exact resource, image and source identifiers are in the [environment record](load-baseline/environment-and-jobs.json).

## Recorded measurements

**Passed.** The measured workload completed **300 requests in 180 seconds**: 150 bookings, 60 list reads, 60 detail reads and 30 expected patient conflicts. All conflict codes matched. Unexpected errors and dropped iterations were **zero**.

| Request | Samples | p95 ms | p99 ms | Maximum ms |
|---|---:|---:|---:|---:|
| All measured requests | 300 | 118.71 | 124.09 | 129.92 |
| New booking | 150 | 17.71 | 19.62 | 20.97 |
| Appointment list | 60 | 124.11 | 126.88 | 129.92 |
| Appointment detail | 60 | 119.11 | 120.74 | 121.94 |
| Expected conflict | 30 | 10.62 | 11.62 | 11.90 |

All **150 measured bookings / 450 jobs** completed. Including setup and warmup, 498 jobs succeeded, with no failed or pending jobs at the end. The 108 queue samples peaked at **7 pending jobs** and **1.55 seconds** oldest pending age. Approximate integration completion delay was p95 **1.68 seconds**, p99 **1.79 seconds**, maximum **1.84 seconds**. No extra drain interval was needed; the final queue check took about 0.15 seconds.

## Evidence and limits

- [k6 summary](load-baseline/k6-summary.json)
- [Queue samples](load-baseline/backlog-samples.json)
- [Environment and final job counts](load-baseline/environment-and-jobs.json)

Calculate measured throughput from the custom `baseline_requests` count divided by 180 seconds. Global HTTP metrics also include setup, warmup and the gap. In the boolean `baseline_unexpected_errors` metric, `rate: 0` means no errors; its `fails` count represents false values, not failed HTTP requests.

Completion-delay estimates subtract appointment `created_at` from the last successful job's `updated_at`. Both use transaction-start timestamps, so these estimates exclude the final provider call and commit. Completion counts are checked after commit; queue peaks are sampled observations.

This supports 6,000 mixed requests/hour under the measured conditions, including about 3,000 new bookings/hour. It is not an hour-long soak or maximum-capacity test. Repeated fixture-detail and first-page reads favor a warm cache. The run does not establish real-provider latency, outage recovery, multi-instance scaling or long-term backlog stability; see the [reliability checks](reliability.md) for controlled failures.
