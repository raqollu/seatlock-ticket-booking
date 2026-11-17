#!/usr/bin/env node
// Node 22+; no npm packages. Run against a LOCAL DEMO instance with demo data enabled.
import assert from 'node:assert/strict';
import {writeFile, mkdir} from 'node:fs/promises';
import os from 'node:os';

const base = process.env.BASE_URL || 'http://localhost:8080';
const concurrency = Number(process.env.CONCURRENCY || 500);
if (!Number.isInteger(concurrency) || concurrency < 2 || concurrency > 2000) throw new Error('CONCURRENCY must be 2–2000');
async function request(path, method = 'GET', body, token, headers = {}) {
  const start = performance.now();
  const response = await fetch(new URL(path, base), {method, signal: AbortSignal.timeout(60000),
    headers: {'Content-Type': 'application/json', ...(token ? {Authorization: `Bearer ${token}`} : {}), ...headers},
    ...(body === undefined ? {} : {body: JSON.stringify(body)})});
  return {status: response.status, body: await response.json(), latencyMs: performance.now() - start,
    replayed: response.headers.get('Idempotency-Replayed')};
}
const login = await request('/api/auth/login', 'POST', {
  email: process.env.ADMIN_EMAIL || 'admin@seatlock.dev', password: process.env.ADMIN_PASSWORD || 'AdminPass123!'});
assert.equal(login.status, 200, 'Demo administrator login must work');
const adminToken = login.body.accessToken;
const signup = await request('/api/auth/register', 'POST', {email: `load-${crypto.randomUUID()}@example.com`, password: 'LocalLoadTest123!', displayName: 'Load Audit'});
assert.equal(signup.status, 201); const token = signup.body.accessToken;
const reports = [];
for (const mode of ['contention', 'same-key-retry']) {
  const created = await request('/api/events', 'POST', {
    title: `Load audit: ${mode}`, venue: 'Isolated load fixture', city: 'Local',
    startsAt: new Date(Date.now() + 90 * 86400000).toISOString(), pricePaise: 10000, rows: 1, columns: 1}, adminToken);
  assert.equal(created.status, 201);
  const event = created.body, sharedKey = crypto.randomUUID();
  let inFlight = 0, peakInFlight = 0;
  const start = performance.now();
  // All promises are launched together. Connection pools may queue them; peak in-flight
  // is client-side HTTP concurrency, NOT a claim of 500 active database transactions.
  const results = await Promise.all(Array.from({length: concurrency}, async (_, index) => {
    inFlight++; peakInFlight = Math.max(peakInFlight, inFlight);
    try { return await request('/api/bookings/holds', 'POST', {eventId: event.id, seats: ['A1']}, token,
      {'Idempotency-Key': mode === 'contention' ? `${sharedKey}-${index}` : sharedKey}); }
    finally { inFlight--; }
  }));
  const durationMs = performance.now() - start;
  const statuses = results.reduce((counts, result) => { counts[result.status] = (counts[result.status] || 0) + 1; return counts; }, {});
  const successes = results.filter(result => [200, 201].includes(result.status));
  const ids = [...new Set(successes.map(result => result.body.id))];
  assert.equal(peakInFlight, concurrency);
  assert.equal(statuses[201], 1, 'Exactly one booking must be created');
  assert.equal(ids.length, 1, 'All successful responses must identify the same booking');
  if (mode === 'contention') {
    assert.equal(statuses[409], concurrency - 1);
    assert.ok(results.filter(r => r.status === 409).every(r => r.body.code === 'SEAT_UNAVAILABLE'));
  } else {
    assert.equal(statuses[200], concurrency - 1);
    assert.equal(results.filter(r => r.replayed === 'true').length, concurrency - 1);
  }
  const confirmed = await request(`/api/bookings/${ids[0]}/confirm`, 'POST', undefined, token);
  assert.equal(confirmed.status, 200); assert.equal(confirmed.body.status, 'CONFIRMED');
  const inventory = await request(`/api/events/${event.id}/seats`);
  assert.deepEqual(inventory.body.map(seat => seat.status), ['BOOKED']);
  const myBookings = await request('/api/bookings', 'GET', undefined, token);
  assert.equal(myBookings.body.filter(booking => booking.eventId === event.id).length, 1);
  const latencies = results.map(r => r.latencyMs).sort((a,b) => a-b);
  const percentile = p => Math.round(latencies[Math.min(latencies.length - 1, Math.ceil(p * latencies.length) - 1)] * 100) / 100;
  const report = {scenario: mode, requests: concurrency, peakClientInFlight: peakInFlight, statuses,
    uniqueBookingIds: ids.length, confirmedSeatCount: 1, unexpectedFailures: 0,
    elapsedMs: Math.round(durationMs), attemptedRequestsPerSecond: Math.round(concurrency / durationMs * 1000),
    latencyMs: {p50: percentile(.5), p95: percentile(.95), p99: percentile(.99)}, eventId: event.id, bookingId: ids[0]};
  reports.push(report); console.log(JSON.stringify(report, null, 2));
}
const report = {recordedAt: new Date().toISOString(), baseUrl: base, runtime: process.version,
  platform: `${os.platform()} ${os.arch()}`, logicalCpus: os.cpus().length,
  notes: 'Single local app. 32 JDBC connections. Includes expected contention responses in latency/throughput. Not a production capacity or SLA claim.', scenarios: reports};
await mkdir(new URL('../docs/evidence/', import.meta.url), {recursive: true});
await writeFile(new URL('../docs/evidence/http-load.json', import.meta.url), JSON.stringify(report, null, 2) + '\n');
console.log('PASS: both HTTP scenarios preserved one booking and one confirmed seat. Evidence: docs/evidence/http-load.json');
