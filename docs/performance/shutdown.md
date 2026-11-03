# Graceful worker shutdown check

[README testing](../../README.md#testing) · [Shutdown behavior](../operations.md#health-and-graceful-shutdown)

## Reproduce

Run from the project root (the directory containing `pom.xml` and `compose.yaml`). If your terminal is in `docs/performance`, run `cd ../..` first.

In IntelliJ, run the command in the Terminal tool window from the project root, or set the run configuration's working directory to the project root. The Markdown code block's Run action may use `docs/performance` as its working directory; the command below assumes the project root.

```sh
python3 scripts/load/run.py --shutdown-check --output /tmp/healthcare-shutdown
```

Requires Python 3 and Docker Compose. The runner builds the current source in its own disposable project, without k6 or historical fixtures. It uses PostgreSQL, the real worker/adapters, provider stubs and Mailpit, exposes no host ports and cleans up its resources.

## Probe and expected behavior

The calendar provider commits a reservation and delays its response for four seconds, below the adapter timeout. Once the remote commit is confirmed, the probe creates a second booking and checks that the first local calendar attempt is still uncommitted. It sends SIGTERM using `docker compose stop app`.

On context close, the worker stops taking jobs and allows the active attempt to record its result. The scheduler waits up to 30 seconds; Compose grants 45 seconds to stop. The first calendar job must commit successfully, and the next must remain ready and unexecuted remotely. Restart must complete all remaining work without duplicate unique reservations.

## Recorded measurements

**Passed.** Stop completed in **2.760 seconds** after the command. Exit code was **143** (SIGTERM), with no OOM kill or forced SIGKILL.

| Calendar job | Before SIGTERM | After application exit | After restart |
|---|---|---|---|
| Active first job | READY, zero recorded attempts; remote reservation committed | SUCCEEDED, one attempt | SUCCEEDED, one attempt |
| Queued second job | Not executed remotely | READY, zero attempts; no remote reservation | SUCCEEDED, one attempt |

The first booking's room job had succeeded. Its email and the second booking's jobs remained unstarted at exit. After restart, all **six jobs** succeeded, with **two unique calendar reservations**.

## Evidence and limits

[Raw evidence](shutdown-run/environment-and-jobs.json) contains the source hash, image IDs, environment, before/after job snapshots, stop duration and exit status. The recorded result describes that source snapshot; the command builds the current checkout. Maven regressions cover stopping between jobs while committing active work and ending the full-batch drain loop on shutdown.

This checks one controlled in-flight HTTP call and a queued successor. Forced termination outside the grace period still requires rollback/retry and provider idempotency. It does not guarantee completion for a provider that ignores configured timeouts.
