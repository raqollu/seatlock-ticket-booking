# Seatlock — High-Concurrency Ticket Booking Engine

A complete, runnable reconstruction of the ticket booking project described in Rahul Kolluru's resume. Java 21, Spring Boot 3.5.16, PostgreSQL, Spring Security JWT, Flyway, a browser demo, and tests against real PostgreSQL.

The central problem: two buyers must never both acquire the same seat. The implementation combines short database transactions, `SELECT … FOR UPDATE`, durable idempotency keys and expiring holds. Checkout is simulated; there is no payment provider.

## Start here

From this folder, with **Java 21 and PostgreSQL 15+ installed**:

```sh
./scripts/dev.sh
```

Open [localhost:8080](http://localhost:8080), click **Sign in → Try the demo account**, choose seats, hold them and confirm. The script creates a project-owned database on port **55432**, runs migrations, generates a local JWT secret and seeds three events. It does not use the parent workspace's `.env` file.

The first Maven run downloads dependencies. Maven itself is supplied by the official wrapper. If PostgreSQL binaries cannot be found, set `PG_BIN` to their `bin` directory. On macOS, the script selects Java 21 automatically when available.

Demo accounts exist **only when `TICKET_DEMO_ENABLED=true`** (enabled by the local script and demo Compose file):

| Account | Password | Role |
|---|---|---|
| `demo@seatlock.dev` | `DemoPass123!` | Customer |
| `admin@seatlock.dev` | `AdminPass123!` | Administrator |

To simulate two different customers, register two different accounts in separate tabs. Two tabs using the demo account represent the **same** customer. Tokens live only in browser memory; after reloading, sign in and use **My bookings** to find previous holds and confirmations.

**Learn the project:** [Start here](docs/START_HERE.md) → [Architecture and code walkthrough](docs/ARCHITECTURE.md) → [Interview questions and demo](docs/INTERVIEW_GUIDE.md) → [Exercises](docs/EXERCISES.md).

## Verify it yourself

```sh
# Real PostgreSQL integration tests, executable JAR and a 90% Java line coverage gate
./scripts/test.sh

# Keep the application running in another terminal; Node 22+ is optional for these
node scripts/demo.mjs
node scripts/load.mjs

# Stop the application with Ctrl+C. Stop this project's database when finished:
./scripts/local-db.sh stop
```

The test script uses the separate **`tickets_test`** database and truncates its booking/event fixtures. It refuses to run test fixtures unless the database name ends with `_test`. Never point `TEST_DATABASE_URL` at a database whose contents you need to retain.

The initial verified run on 17 November 2026 used Java 21 and PostgreSQL 15.19:

| Check | Observed result |
|---|---|
| PostgreSQL integration suite | 24 tests, 0 failures, 0 skipped |
| Java line coverage | 96.88% (248/256), no production class exclusions |
| Java branch coverage | 93.48% (86/92) |
| 500 simultaneous HTTP hold attempts, different keys, one seat | 1 created booking; 499 expected `409` conflicts |
| 500 simultaneous HTTP retries, same key, one seat | 1 created booking; 499 `200` replays; one booking ID |
| Each load scenario's final state | Exactly one confirmed booking and one booked seat |
| Browser verification | Sign in, selection, hold/countdown, confirmation, saved history, cancellation |

See [verification details](docs/VERIFICATION.md) and [raw HTTP evidence](docs/evidence/http-load.json). This is a local burst test, not a sustained production capacity guarantee. The 500 requests are outstanding HTTP requests; the database pool has 32 connections.

Coverage reports: `target/site/jacoco/index.html`. JUnit reports: `target/surefire-reports/`. To refresh the saved verification summary after a test run:

```sh
node scripts/record-evidence.mjs
```

## Docker alternative

With Docker running:

```sh
cp .env.example .env
# Replace TICKET_JWT_SECRET in .env with the output of: openssl rand -hex 32
docker compose up --build
```

Open [localhost:8080](http://localhost:8080). `docker compose down` stops the demo and retains its volume. The Compose database and the native local database both use port 55432; run one approach at a time. The Docker image and remote GitHub Actions workflow are included but were not executed during the initial local verification.

For tests against an existing dedicated PostgreSQL database:

```sh
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/tickets_test \
TEST_DATABASE_USER=tickets TEST_DATABASE_PASSWORD=tickets ./scripts/test.sh
```

For a packaged application, set `TICKET_DB_URL`, `TICKET_DB_USER`, `TICKET_DB_PASSWORD`, and a random `TICKET_JWT_SECRET` of at least 32 UTF-8 bytes, then run:

```sh
java -jar target/ticket-booking-1.0.0.jar
```

Demo accounts and seed events are disabled by default outside the development script. A fresh non-demo database has no administrator; provision that role through a controlled administrative process. Public registration always creates `USER` accounts.

## API

There are **12 application operations**, plus health. Import [OpenAPI 3.0](src/main/resources/static/api-docs.yaml) into Postman or another OpenAPI client; it is also served at [localhost:8080/api-docs.yaml](http://localhost:8080/api-docs.yaml).

| Method | Route | Access / behavior |
|---|---|---|
| POST | `/api/auth/register` | Public; register and receive a bearer token |
| POST | `/api/auth/login` | Public; receive a one-hour bearer token |
| GET | `/api/auth/me` | Authenticated profile |
| GET | `/api/events` | Public; first 100 events by start time |
| GET | `/api/events/{id}` | Public event and availability counts |
| GET | `/api/events/{id}/seats` | Public seat map; expired holds appear available |
| POST | `/api/events` | Administrator; create event and inventory atomically |
| POST | `/api/bookings/holds` | Customer; `Idempotency-Key` required; up to 8 seats |
| GET | `/api/bookings` | Own latest 100 bookings |
| GET | `/api/bookings/{id}` | Own booking |
| POST | `/api/bookings/{id}/confirm` | Confirm a live hold; repeat confirmation is safe |
| POST | `/api/bookings/{id}/cancel` | Release own held/confirmed seats; repeat cancellation is safe |
| GET | `/actuator/health` | Public health without sensitive details |

All prices are integer **paise**. `149900` means ₹1,499. Timestamps are ISO 8601 UTC instants. The browser renders local times. The server determines price and ownership; the client cannot submit either.

New hold requests return `201`. A matching successful retry returns `200`, `Idempotency-Replayed: true` and the original booking ID with its **current** state. Same user/key with different seats returns `409`. Failed hold transactions do not retain the key. Successful keys remain reserved for that user's booking history.

## Code map

```text
src/main/java/dev/seatlock/
  api/            HTTP routes, request validation, response records, problem responses
  security/       BCrypt passwords, token issuance, JWT/role enforcement
  catalog/        Event creation and seat availability queries
  booking/        Transactions, locks, idempotency, state changes, expiry scheduler
  config/         Opt-in local demo fixtures
src/main/resources/
  db/migration/   PostgreSQL schema, constraints and indexes (Flyway)
  static/         Complete browser demo and OpenAPI specification
src/test/         PostgreSQL integration and synchronized concurrency tests
scripts/          Native DB startup, development, tests, HTTP demo/load, evidence
docs/             Learning plan, design reasoning, interview practice and evidence
```

JDBC is deliberate: the locking SQL and transaction boundaries are visible, and no ORM behavior needs to be inferred. This is one deployable application with internal modules. Distributed locks, Redis and message brokers are unnecessary for the current single-database booking invariant.

## Current scope

Implemented: multi-seat atomic holds, 15-minute maximum TTL capped at show start, lazy seat reclamation, periodic expiry cleanup, replay-safe hold creation, safe repeated confirmation/cancellation, JWT ownership checks, admin event creation, database constraints, bounded database waits, input validation, health, browser demo, real-PostgreSQL tests, load scripts, Docker packaging and CI configuration.

The project does not collect money, issue QR admission credentials, send emails or implement payment webhooks/refunds. Further production work would include rate limits/anti-hoarding, TLS/secret management, narrower database roles, key rotation and revocation, pagination, payment reconciliation, monitoring, operational backups and sustained multi-instance load tests. The local database uses trust authentication bound to loopback for convenience; it is a development fixture.

The evidence establishes what this reconstruction does today. It does not establish the historical dates or work described on a pre-existing resume. Use the interview guide to explain what you have personally run, understood and changed.
