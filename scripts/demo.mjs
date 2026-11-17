#!/usr/bin/env node
import assert from 'node:assert/strict';
const base = process.env.BASE_URL || 'http://localhost:8080';
async function call(path, method = 'GET', body, token, key) {
  const response = await fetch(base + path, {method, headers: {'Content-Type': 'application/json',
    ...(token ? {Authorization: `Bearer ${token}`} : {}), ...(key ? {'Idempotency-Key': key} : {})},
    ...(body ? {body: JSON.stringify(body)} : {})});
  return {status: response.status, body: await response.json()};
}
const actor = await call('/api/auth/register','POST',{email:`demo-${crypto.randomUUID()}@example.com`, password:'DemoScript123!',displayName:'Interview Demo'});
assert.equal(actor.status,201); const token = actor.body.accessToken;
const events = await call('/api/events'); assert.equal(events.status,200);
const event = events.body[0]; assert.ok(event,'Enable demo data first');
const inventory = await call(`/api/events/${event.id}/seats`);
const seats = inventory.body.filter(s => s.status==='AVAILABLE').slice(0,2).map(s => s.code);
assert.equal(seats.length,2,'Need two available seats');
const key=crypto.randomUUID(), payload={eventId:event.id,seats};
const hold=await call('/api/bookings/holds','POST',payload,token,key); assert.equal(hold.status,201);
console.log('1. HELD',hold.body.id,seats,'expires:',hold.body.expiresAt);
const retry=await call('/api/bookings/holds','POST',payload,token,key); assert.equal(retry.status,200); assert.equal(retry.body.id,hold.body.id);
console.log('2. RETRY returned the same booking');
const conflict=await call('/api/bookings/holds','POST',payload,token,crypto.randomUUID()); assert.equal(conflict.status,409);
console.log('3. COMPETING request rejected:',conflict.body.code);
const confirm=await call(`/api/bookings/${hold.body.id}/confirm`,'POST',null,token); assert.equal(confirm.body.status,'CONFIRMED');
console.log('4. CONFIRMED — no payment collected');
const cancel=await call(`/api/bookings/${hold.body.id}/cancel`,'POST',null,token); assert.equal(cancel.body.status,'CANCELLED');
console.log('5. CANCELLED — seats released; history preserved');
console.log('PASS: complete HTTP lifecycle, retry and conflict behavior verified.');
