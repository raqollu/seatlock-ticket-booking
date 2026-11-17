# Verification record

Recorded on **17 November 2026**. This records tests actually executed for the local reconstruction. The raw results remain alongside this file in `evidence/`.

## Environment and scope

- macOS, ARM64, 10 logical CPUs.
- OpenJDK 21.0.12.1; Spring Boot 3.5.16; PostgreSQL 15.19.
- Node v24.15.0 for HTTP scripts; Maven 3.9.16 via wrapper.
- One application instance on localhost:8080; HikariCP pool of 32 connections.
- Real PostgreSQL databases: `tickets` for the demo and `tickets_test` for integration tests.
- Java coverage includes all production classes with no class exclusions. The browser JavaScript is not instrumented.

## Integration tests

Command: `./scripts/test.sh`.

**24 tests passed, 0 failures, 0 errors, 0 skipped.** The build creates the executable JAR and enforces at least 90% Java line coverage.

| Counter | Covered | Missed | Coverage |
|---|---:|---:|---:|
| Lines | 248 | 8 | 96.88% |
| Branches | 86 | 6 | 93.48% |
| Instructions | 2106 | 65 | 97.01% |
| Classes | 25 | 0 | 100% |

The test suite covers full HTTP lifecycle, repeated transitions, canonical idempotency/replay, payload mismatch, all-or-nothing multi-seat failure, malformed input, unknown resources, server-calculated pricing, TTL, event closure, lazy expiry/reassignment, delayed cleanup, ownership, per-user key scope, role enforcement, invalid JWT signature/issuer/audience/expiry, credential handling and repeatable demo initialization.

Its concurrency checks include a barrier-synchronized **500-contender service race**, 50 same-key retries, 50 reversed overlapping selections, simultaneous confirm/cancel, reclaim/expiry and mismatched same-key intents. Concurrent operations use independent real database transactions through the Spring service proxy. No H2 database or mocked repository is substituted.

JaCoCo's generated XML and HTML are in `target/site/jacoco/`. Raw JUnit XML/text is in `target/surefire-reports/`. `scripts/record-evidence.mjs` snapshots their summary and source SHA-256 digests into `evidence/test-summary.json`.

## HTTP load

Command, with the app already running: `node scripts/load.mjs`.

| Scenario | Outstanding HTTP requests | Created | Replayed | Expected conflicts | Unique booking IDs | Unexpected failures |
|---|---:|---:|---:|---:|---:|---:|
| One seat, different keys | 500 | 1 | 0 | 499 | 1 | 0 |
| One seat, same key | 500 | 1 | 499 | 0 | 1 | 0 |

Each scenario subsequently confirmed its winning booking and checked that the event had exactly one BOOKED seat and the user had one booking for that event.

The initial contention burst took 389ms (p95 request latency 361.46ms). The initial same-key burst took 162ms (p95 148.98ms). These are single local-run observations, include expected conflicts/replays and are not sustained throughput or production latency claims. Exact measurements and fixture IDs are in [http-load.json](evidence/http-load.json). Re-running the script creates separate events and overwrites that file with the newer observations.

The measured concurrency is **500 in-flight HTTP requests at the client**. Database work is bounded by the 32-connection pool. This does not claim 500 active database transactions or 500 successful purchases of the same seat.

## Complete API demonstration

Command: `node scripts/demo.mjs`.

Passed registration, event/seat lookup, two-seat hold, matching-key replay, competing-key `409`, confirmation and cancellation. Its booking history is retained, while its seats are released at the end.

## Browser verification

Verified the running application through the browser UI: event rendering, multi-seat selection and total, checkout-triggered sign-in, demo authentication, live hold/countdown, confirmation, My bookings history and cancellation with seats released. No warnings or errors appeared in the isolated verification tab's captured console.

[booking-demo.jpg](evidence/booking-demo.jpg) captures the confirmed A1/A2 booking before its subsequent cancellation. Existing demo-account bookings were preserved. The CSS includes responsive layouts; an attempted 390px browser override did not take effect (the observed viewport stayed 850px), so narrow mobile-device rendering is **not** counted as verified evidence.

## Included but not executed

- Docker image build and Compose run. The local Docker CLI did not have a working Compose command; native Java/PostgreSQL execution was used instead.
- Remote GitHub Actions execution. A PostgreSQL service workflow is included for when this project becomes a repository.
- Multi-instance, long-duration soak, failover and payment-provider tests.
- JavaScript coverage and automated browser regression suite.

The meaningful claim is that no double-booking occurred in these tested scenarios, with a locking/ownership design that explains the result. Finite tests are not a proof of every possible schedule or future change.
