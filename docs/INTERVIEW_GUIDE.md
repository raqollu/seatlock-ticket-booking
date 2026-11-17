# Explain and defend Seatlock

Use these as examples to understand, then answer in your own words. The repository is a reconstruction created now. Its tests establish current behavior; they do not verify dates, past authorship or deployment history from an existing resume. Describe the work you have personally run, understood and modified accurately.

## A 60-second explanation

“This is a ticket booking engine built with Spring Boot and PostgreSQL. Customers can browse events, hold up to eight seats, then confirm or cancel. The difficult part is preventing overlapping reservations under concurrent requests. Each hold runs in a short transaction, locks its requested seat rows with SELECT FOR UPDATE in a consistent order, validates their current state and assigns them atomically.

“A unique user-and-idempotency-key constraint makes retries return the same booking. Holds expire after at most 15 minutes. Time checks allow immediate reclamation even if the cleanup scheduler is late, and cleanup only releases seats still owned by the old booking. JWT authentication and owner checks protect booking operations. I can show the PostgreSQL integration tests and a local HTTP burst with 500 outstanding requests for one seat: one creation and 499 expected conflicts.”

After completing an exercise, add a sentence describing that specific change and the test you wrote. Do not claim a production rollout, real payment integration, original historical work or a feature this implementation does not contain.

## A five-minute live demo

Prepare two terminals. In terminal 1 run `./scripts/dev.sh`. In terminal 2 keep the project root open. Have the source and test file ready in your editor.

| Time | Show | Explain |
|---|---|---|
| 0:00–0:45 | Event list and seat map in the browser | A displayed available seat is advisory, not a reservation. |
| 0:45–1:30 | Sign in, select two seats, hold them | One transactional request returns the booking ID, server-calculated total and expiry. The database locks have already been released. |
| 1:30–2:00 | Confirm, open My bookings, then cancel your demo booking | Confirmation and cancellation are state transitions, and history survives release. No money is collected. |
| 2:00–3:00 | Run `node scripts/demo.mjs` | Show the same-key replay and a different-key conflict without needing a GUI race. |
| 3:00–4:00 | Run `node scripts/load.mjs` | Explain one creation, 499 expected conflicts, and the separate 500-request same-key test. Point to the saved JSON. |
| 4:00–5:00 | Open `BookingService.hold`, `lockSeats`, the unique key constraint and a concurrency test | Connect observed results to the actual mechanism. Mention limits of one local burst. |

Run `./scripts/test.sh` beforehand and keep the JaCoCo report available. Tests usually finish quickly after dependencies are downloaded, but do not depend on a live package download during an interview.

For a two-customer demonstration, register distinct users in two tabs. The shared demo login is one account and intentionally exposes the same account's booking history in both tabs.

## Questions you should expect

### 1. What exactly stops double-booking?

Every writer locks the relevant inventory rows, checks availability while holding those locks, and commits the whole selection together. Another transaction must wait and then inspect the updated state. Inventory has one current owner, while the transaction keeps active booking records consistent with that owner. The primary key alone would not stop two unchecked writers from creating two active bookings and overwriting ownership.

**Show:** `BookingService.hold`, `lockSeats`; test `fiveHundredConcurrentContendersHaveExactlyOneWinner`.

### 2. Why not read availability and update later?

Two requests could both read AVAILABLE before either writes. Both would believe they won. The decision must be made while the database prevents another writer from changing those same seats.

### 3. Why not use Java `synchronized`?

It coordinates threads inside one JVM. Two application instances have different monitors. PostgreSQL locks coordinate every instance using the same database. A global Java lock would also serialize unrelated seats unnecessarily.

### 4. Why pessimistic rather than optimistic locking?

Seats are scarce, and a popular seat can attract many simultaneous writers. Pessimistic locking makes the contention path explicit: wait, re-read, reject if unavailable. Optimistic version checks or conditional atomic updates are valid alternatives, but require careful affected-row checks, retries and all-or-nothing multi-seat logic. This implementation chose understandable row locks over retrying an entire conflicting group transaction.

### 5. What isolation level do you use?

PostgreSQL's default READ COMMITTED, with explicit row locks for decisions that require serialization. Serializable isolation could detect additional anomalies but introduces abort/retry behavior. Raising isolation does not remove the need to design the lifecycle, retry contract and lock scope.

### 6. How are multiple seats atomic?

All requested seat rows are locked before assignment. If any seat is missing, booked or held by a live booking, the method throws. Spring rolls back the booking insert, key claim, history and updates. The customer gets the complete selection or none.

**Show:** `conflictingMultiSeatRequestRollsBackEverythingIncludingKey`.

### 7. Why sort seat codes before locking?

If one request locks A1 then A2 and another locks A2 then A1, each could wait for the other's lock. Every selection is acquired in the same immutable `seat_code` order. Alphabetical ordering putting A10 before A2 is fine; a consistent order matters more than human seat-number order.

### 8. Can you guarantee that deadlocks are impossible?

No blanket guarantee. These application paths use a consistent protocol, and the overlapping reverse-selection test passes. PostgreSQL still detects deadlocks, and bounded waits matter. New SQL, additional tables or administrative jobs could introduce another lock order. I would review those changes and retain retry behavior and monitoring.

### 9. How does your idempotency key work?

The database has a unique `(user_id, idempotency_key)` constraint. The transaction attempts an insert with ON CONFLICT DO NOTHING. A simultaneous same-key request waits for the original transaction. If the original commits, the second request reads the existing booking and compares the canonical request fingerprint. A match returns that booking; a mismatch is a conflict.

**Show:** schema constraint and test `concurrentSameKeyCreatesOneBookingAndOneSeatAssignment`.

### 10. Why not just check whether the key exists first?

Two requests could both observe that it does not exist. The unique constraint is the arbiter. A preliminary existence check can be an optimization, but it cannot be the correctness mechanism.

### 11. What if the response is lost after the transaction commits?

The retry uses the same key and canonical selection, finds the committed booking, and returns the same ID. If the process fails before commit, PostgreSQL rolls the transaction back; a retry can create the booking. No external side effects happen inside this transaction, so there is no gateway charge to reconcile here.

### 12. Are replay responses byte-for-byte identical?

No. This is resource idempotency. The booking ID and original hold intent stay the same, while the current status can change to CONFIRMED, CANCELLED or EXPIRED. The API documents that distinction. It returns 201 for creation and 200 for replay, with an explicit replay header.

### 13. Can the same key be reused for another selection?

After a successful hold, no: a different fingerprint returns 409 for that user/key. A failed transaction rolls its key claim back, so it does not permanently consume the key. Different users have different key namespaces. Successful booking keys are retained with history; there is no key-expiry policy yet.

### 14. Does the system lock a seat for 15 minutes?

It reserves the seat in persisted state for up to 15 minutes. The database row lock lasts only for the short transaction. Holding an open transaction for human checkout would exhaust connections and create long lock queues.

### 15. Why use the database clock and `clock_timestamp()`?

Expiry and inventory writes share one time source. PostgreSQL's `now()` is fixed at transaction start; a request might then wait behind a lock. `clock_timestamp()` is read after the locks are acquired, so the service evaluates the deadline at its decision point rather than using stale transaction-start time.

The expiry rule applies to that guarded decision point, not a promise that the HTTP response or commit reaches the client before the wall-clock deadline.

### 16. What if the scheduler is down?

Expired held seats still appear available and can be reclaimed under row locks. Confirmation checks expiry directly and rejects late holds. Booking reads report an effective EXPIRED status. The scheduler persists cleanup and releases rows eventually; it is not required for acquisition correctness.

### 17. Could cleanup erase a newer hold?

It must not. Cleanup locks the old booking and its historical seats, then releases only inventory rows whose `booking_id` still equals the old ID. A seat reclaimed by a new booking does not match. There are tests for both sequential and concurrent reclaim-versus-expiry paths.

**Show:** `releaseOwnedSeats`, `lazyExpiryReclaimsSeatsWithoutSchedulerAndOldCleanupCannotSteal`, `concurrentReclaimAndExpiryPreserveNewOwner`.

### 18. What if confirm and cancel happen together?

Both lock the same booking row first, so they serialize. If confirmation wins first, cancellation can subsequently cancel the confirmed booking in this demo. If cancellation wins first, confirmation sees CANCELLED and rejects. The final state is CANCELLED with released seats. The test asserts both the lifecycle and inventory consistency.

### 19. Why store both current inventory and booking-seat history?

Current inventory answers “who owns A1 now?” History answers “which seats did this cancelled or expired booking select?” Reclaiming a seat changes its current owner but must not erase the earlier booking's history.

### 20. Why JDBC rather than JPA?

The project needs clear control over the locking query, order, timestamps and ON CONFLICT behavior. JDBC makes those visible. JPA with pessimistic locking could work, but introduces persistence-context and generated-query behavior that would need careful review. Spring Boot does not require JPA.

### 21. What does JWT provide, and what does it not provide?

The signature makes identity and role claims tamper-evident. Spring Security validates signature, issuer, audience and time claims. JWT does not encrypt those claims, reserve a seat, prove booking ownership on its own, or provide immediate logout revocation. Ownership is checked against the booking's user ID. The browser only discards its token locally on sign-out.

### 22. Could a user register as an administrator or change the price?

Registration does not accept a role, and the database defaults to USER. Event creation requires the ADMIN authority. Hold requests accept only event and seat selection; the server computes the total from the stored event price. Unknown JSON fields are rejected.

### 23. Why are passwords hashed rather than encrypted?

Login needs to verify a password, not recover it. BCrypt stores a salted, deliberately costly hash. Passwords and tokens are not logged. BCrypt's 72-byte input limit is checked using UTF-8 bytes, not just character count, including for multibyte inputs.

### 24. Why disable CSRF?

This API does not use automatically attached authentication cookies. The browser explicitly sends an Authorization bearer header. If authentication moved into cookies, CSRF protection would need to be reconsidered. XSS is a separate concern; the UI escapes dynamic content and uses a restrictive same-origin CSP.

### 25. Did you really handle 500 concurrent requests?

The included script launched 500 outstanding HTTP hold requests in one burst against one local application and real PostgreSQL. In the different-key case, one request created the booking and 499 received expected seat conflicts. In the same-key case, one created the booking and 499 replayed it. Both scenarios then confirmed one booking and checked inventory. Raw results, environment and latency percentiles are saved in `docs/evidence/http-load.json`.

This does not mean 500 successful buyers acquired one seat, 500 database queries executed simultaneously, or the service sustained a production throughput target. HikariCP has 32 connections, so some HTTP calls queue.

### 26. What does zero double-bookings prove?

No double-booking was observed in the tested scenarios, and the locking design explains the invariant. Finite tests are not a mathematical proof of every schedule, failure mode or future code change. The strongest demonstration combines the reasoning, a synchronized service race, HTTP tests, ownership assertions, and explicit limits on the claim.

### 27. What does your coverage number measure?

JaCoCo measures Java production lines exercised by the integration suite. The saved run includes all production classes, including controllers, security and scheduler; there are no class exclusions. The gate is 90% line coverage. The measured initial run was 96.88% line coverage and 93.48% branch coverage.

It does not measure JavaScript coverage, all possible execution orders, real payment integration, or production readiness. Explain the multi-seat rollback and expired-owner tests, rather than treating the percentage as the only quality signal.

### 28. Why test with PostgreSQL instead of H2?

Row locks, ON CONFLICT, array queries, time functions and error behavior are database-specific. A fast in-memory substitute would not establish the PostgreSQL concurrency behavior the project claims. Tests use a separate real PostgreSQL database and reset their event/booking fixtures.

### 29. How would real payments change the design?

Create a payment attempt tied to the booking, call the provider outside seat-locking transactions, and use gateway idempotency. Validate signed webhooks, deduplicate them, and handle late success after expiry through explicit reconciliation/refund rules. Do not equate a browser success page with payment settlement. Cancellations here release inventory without refunds because no payment is taken.

### 30. How would you scale it?

First measure pool waits, lock waits, request mix and database utilization. Add bounded admission/queueing and anti-hoarding limits for hot sales. Cache read-only catalog data if needed, but do not trust cached availability for writes. Multiple app instances sharing PostgreSQL preserve the same lock invariant; prove them with multi-instance tests. Partition inventory by event only when scale justifies it and keep one booking's atomic boundary explicit.

### 31. Why no Redis, Kafka or microservices?

The hardest invariant is already expressible in one database transaction. Adding another coordination system would introduce lease, message and cross-system failure cases before there is a measured need. Separate modules make the code understandable while retaining an atomic booking boundary. A broker/outbox becomes useful when adding asynchronous notifications or payment-event delivery.

### 32. What would you improve first?

Choose based on the requested deployment: for real users, rate limits and per-user hold quotas prevent inventory hoarding; for a larger catalog, cursor pagination; for money movement, a properly reconciled payment workflow. Explain one implementation plan and its failure cases. Do not list tools without connecting them to a problem.

## Defensible resume wording

After you understand and can reproduce the project, current evidence supports wording such as:

- “Implemented a Spring Boot/PostgreSQL booking engine with 12 application API operations, JWT authentication, transactional multi-seat holds and a browser demo.”
- “Used SELECT FOR UPDATE, durable user-scoped idempotency keys and 15-minute expiring holds; a local 500-request contention burst produced one booking and 499 expected conflicts.”
- “Verified booking lifecycle, ownership, expiry/reclamation and concurrent retries against PostgreSQL; achieved over 90% Java line coverage with a build-enforced threshold.”

Keep actual dates and personal contributions accurate. Describe this as a reconstruction or implementation completed during your preparation if that is what happened. There is no need to claim historical production use to make the concurrency reasoning and reproducible evidence valuable.
