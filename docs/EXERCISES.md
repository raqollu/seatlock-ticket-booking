# Build your own understanding

Make one change at a time. Start with `./scripts/test.sh` passing, implement the behavior, add tests that would fail without it, and rerun the suite. Keep a note explaining what changed and why. These extensions are deliberately **not already implemented**.

## 1. Limit one customer's live inventory holds

**Goal:** Allow at most two live HELD bookings per user. CONFIRMED, CANCELLED and expired holds do not count.

**Why it matters:** An authenticated user can currently hoard inventory with many different keys. Idempotency protects against duplicate intent, not abuse.

**Acceptance checks:** The third live hold is rejected. Cancelling or expiring an earlier hold frees quota. Retrying an existing successful key still works. Fifty simultaneous different-key requests cannot bypass the quota.

**Think before coding:** A plain `SELECT count(*)` can race just like checking seat availability. What stable per-user row could serialize a quota decision? Where would that lock sit relative to booking/key and seat locks? What happens if you check quota before recognizing a replay?

<details><summary>Implementation direction</summary>

Serialize new hold creation on the corresponding `app_user` row before acquiring a new booking/key or any seat locks. Inside that scope, recognize successful replays before enforcing the new-intent quota. Count live held bookings using database time. Keep the quota check and new allocation in one transaction. Review the lock order across all writers. This serializes all new holds for one user, a reasonable simplicity/per-user throughput tradeoff. Confirmation/cancellation may make the quota check temporarily conservative; decide whether that is acceptable and document it.

</details>

## 2. Add cursor pagination to booking history

**Goal:** Replace the hard cap of 100 bookings with `limit` and a cursor. Preserve stable newest-first ordering.

**Acceptance checks:** No duplicate or missing bookings across pages when creation timestamps tie. Another user's cursor cannot expose their history. Invalid cursors return 400. New bookings arriving between page requests do not shift previously traversed results.

**Think before coding:** An offset counts positions; a cursor describes a boundary. Why does the boundary need both `created_at` and `id`?

<details><summary>Implementation direction</summary>

Use the existing `(created_at DESC,id DESC)` ordering and a tuple boundary `(created_at,id) < (:time,:id)`, always scoped by `user_id`. Encode both values into an opaque validated cursor and return a next cursor when another page exists. Add an index beginning with `(user_id,created_at DESC,id DESC)`. Use `limit + 1` to determine whether another page exists. Return the cursor in a response envelope and update the browser/history contract and OpenAPI spec together.

</details>

## 3. Prove the lock matters — in an isolated test experiment

**Goal:** Understand the failure the lock prevents. Work on an experimental copy/branch with the dedicated `_test` database.

Temporarily remove `FOR UPDATE` from seat acquisition and run the contention tests repeatedly. Observe which invariant fails. Restore the lock immediately after the experiment.

**Think before coding:** Tests may still pass by scheduling chance. How can you add a test-only barrier after availability reads to force two unlocked transactions to see AVAILABLE before either writes? Why would that same barrier deadlock a correctly locked implementation if inserted carelessly?

**Success means:** You can explain why a green uncontrolled race test is insufficient and can show a repeatable counterexample to check-then-write without locking. The real implementation must still pass its full suite after you restore it.

## 4. Add an expiry status audit

**Goal:** Add an administrator-only read endpoint that shows, for one event, counts of available seats, live held seats, booked seats and effectively expired bookings.

**Acceptance checks:** Counts use database time; delayed physical cleanup does not misclassify expired seats as live holds; ordinary users receive 403; the endpoint reveals no passwords or bearer tokens.

**Discussion:** A read audit is an observation, not an admission-control lock. If requests commit while separate queries run, can the counts describe different instants? Use one statement or an explicitly chosen read isolation boundary if the response promises one coherent snapshot.

## 5. Define a payment workflow before integrating a provider

**Goal:** Design a separate fake payment-attempt table and a signed local callback endpoint. Do not add real charges for this exercise.

**Acceptance checks:** Duplicate callback delivery cannot confirm twice. Failed payments do not confirm. A success callback after hold expiry follows a documented refund/reconciliation path. Callback processing cannot act on a booking by guessing an ID. No network call runs while holding seat locks.

**Discussion:** Which key deduplicates the provider event? Which state changes must be atomic? What if the server commits but acknowledgement is lost? If expiry and the payment callback race, how do you serialize the business decision?

## 6. Verify multiple application instances

**Goal:** Start two application processes on different ports against the same project database, with the same JWT secret. Send alternating requests to both.

**Acceptance checks:** Different-key contention still has one winner. Same-key requests across instances return one booking ID. Running both sweepers does not release reassigned seats. Report the number of instances, requests and JDBC connections honestly.

**Think before coding:** How do you preserve the same signing key? How many total pooled database connections will the two processes open? Why should every instance not independently change the hold expiry with an in-memory timer?

## Readiness check

You are ready to discuss this project when you can explain one HTTP request through the code, draw the ownership/history tables, reason through two simultaneous buyers, demonstrate retry/expiry behavior, reproduce the tests and describe an extension you completed yourself. If you need notes for a detail, use the code and evidence rather than inventing a metric.
