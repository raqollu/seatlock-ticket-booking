# Learn this project well enough to explain it

Start with behavior, then follow one request through the code. You do not need to memorize every line. You do need to predict what happens when requests overlap, fail or arrive late.

## Your first 20 minutes

1. Run `./scripts/dev.sh` and open http://localhost:8080.
2. Sign in using **Try the demo account**.
3. Select two seats. The selection is only in your browser at this point; another customer can still acquire them.
4. Click **Hold these seats**. The backend now reserves both seats and returns a booking ID and expiry instant.
5. Confirm. The seat state becomes `BOOKED`, and the booking state becomes `CONFIRMED`.
6. Open **My bookings**. Cancel this booking. The seats become available, while its history remains.
7. Run `node scripts/demo.mjs`. Read its five printed steps and connect them to what you just did.

Say this aloud: “Selecting a seat in the browser is not a reservation. A reservation exists only after the database transaction commits.”

## Seven sessions, about 45–60 minutes each

| Session | Read and do | You are ready to move on when… |
|---|---|---|
| 1 — Product and API | Run the journey above. Read `api/*Controller.java` and `Models.java`. Inspect requests in browser developer tools. | You can name the routes, status codes, required authentication and server-computed price. |
| 2 — Relational model | Read `V1__booking_schema.sql` and the data model in `ARCHITECTURE.md`. Draw the five tables from memory. | You can explain why inventory is separate from booking-seat history and why a seat needs its own row. |
| 3 — One transaction | Read `BookingService.hold` slowly. Follow the two-customer timeline in `ARCHITECTURE.md`. | You can explain why checking availability before a transaction is insufficient and when locks are released. |
| 4 — Retries | Study the unique user/key constraint, `ON CONFLICT`, fingerprint and rollback test. Run `node scripts/load.mjs`. | You can distinguish a retry from a new booking attempt and explain a lost HTTP response after commit. |
| 5 — Time and transitions | Study `confirm`, `cancel`, `expire`, `databaseNow`, and `ExpiryScheduler`. Read the expiry race section. | You can show why delayed cleanup cannot create a double-booking or erase a newer hold. |
| 6 — Security and evidence | Read `SecurityConfig`, `AuthService`, and the integration tests. Run `./scripts/test.sh`; open the coverage report. | You can explain authentication vs ownership, rejected-token tests, and exactly what the coverage percentage measures. |
| 7 — Interview rehearsal | Follow the five-minute demo in `INTERVIEW_GUIDE.md`. Answer its questions without reading. Complete at least exercises 1 and 2. | You can reproduce the demo, explain a tradeoff, show test evidence and discuss an improvement you implemented yourself. |

## Follow a request in this order

Open these files relative to the project root:

1. `src/main/resources/static/app.js` → `holdOrConfirm`: the browser builds the selected seat list and preserves the key when retrying the same request.
2. `src/main/java/dev/seatlock/security/SecurityConfig.java` → bearer-token verification and route access.
3. `src/main/java/dev/seatlock/api/BookingController.java` → extracts identity from the verified JWT, validates the body, reads the idempotency header.
4. `src/main/java/dev/seatlock/booking/BookingService.java` → `hold`: the business transaction.
5. `src/main/resources/db/migration/V1__booking_schema.sql` → uniqueness and seat-state constraints that back the service.
6. `src/test/java/dev/seatlock/BookingIntegrationTest.java` → `fiveHundredConcurrentContendersHaveExactlyOneWinner` and `conflictingMultiSeatRequestRollsBackEverythingIncludingKey`.

The controller translates HTTP into a method call. The service enforces the lifecycle. PostgreSQL coordinates competing processes. The browser reflects the result; it does not decide who owns a seat.

## Terms you must be able to explain simply

| Term | Meaning here |
|---|---|
| Transaction | A group of database changes that commit together or roll back together. |
| Row lock | A database mechanism that makes another writer wait before modifying the same seat. |
| Pessimistic locking | Acquire the seat lock before deciding whether to reserve it. |
| Idempotency | Retrying the same successful hold intent yields the same booking instead of another reservation. |
| TTL | Time to live: the duration a hold is valid. No database connection stays open for that duration. |
| Isolation | What one transaction can see of other transactions. PostgreSQL's default `READ COMMITTED` is used here. |
| Deadlock | Requests each hold a lock the other needs. Consistent seat ordering helps prevent this. |
| JWT | A signed token containing identity and claims. Signed means tamper-evident; it does not mean encrypted. |
| Invariant | A property that must always hold: an active booking's seats must still be assigned to that booking. |
| Connection pool | A reusable, bounded set of database connections. 500 HTTP callers share 32 connections here. |

## Explain these before looking at the answers

1. Why is `synchronized` insufficient when there are two application instances?
2. Can a request see “available” on the screen and still receive `409`? Why is that correct?
3. What happens if one requested seat is available and the other is already booked?
4. What happens if the database commits a hold but the HTTP response is lost?
5. Does a 15-minute hold mean keeping a database transaction open for 15 minutes?
6. If cleanup runs late, can expired seats be held by another customer?
7. If an old cleanup runs after reassignment, what stops it from releasing the new customer's seat?
8. Why does confirmation read database time after obtaining the locks?
9. Do 500 outstanding HTTP requests imply 500 PostgreSQL connections?
10. Does 96.88% Java line coverage mean all possible races have been proven safe?

Answers and code pointers are in `INTERVIEW_GUIDE.md`. If you cannot answer, return to the relevant method and trace an example with two customers and one seat.

## Your personal ownership milestone

Complete two exercises without copying their solutions, rerun tests and write a short note explaining your changes. Rehearse the demo once with a fresh terminal and once with a deliberately failing request. Being able to modify, diagnose and explain the system is stronger evidence of understanding than reciting an architecture paragraph.
