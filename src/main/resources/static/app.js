'use strict';
const $ = id => document.getElementById(id);
const state = {token: null, user: null, events: [], event: null, seats: [], selected: new Set(), booking: null, busy: false, register: false, pending: null, inventoryRequest: 0};
const money = paise => new Intl.NumberFormat('en-IN', {style: 'currency', currency: 'INR', minimumFractionDigits: 0, maximumFractionDigits: 2}).format(paise / 100);
const date = iso => new Date(iso).toLocaleDateString('en-IN', {day: 'numeric', month: 'short'});
const time = iso => new Date(iso).toLocaleTimeString('en-IN', {hour: 'numeric', minute: '2-digit'});
const escape = text => String(text).replace(/[&<>"']/g, char => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[char]));
let toastTimer;
function toast(message, error = false) {
  $('toast').textContent = message; $('toast').classList.toggle('error', error); $('toast').hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').hidden = true, error ? 9000 : 5000);
}
async function api(path, options = {}) {
  const response = await fetch(path, {signal: AbortSignal.timeout(45000), ...options,
    headers: {'Content-Type': 'application/json', ...(state.token ? {Authorization: `Bearer ${state.token}`} : {}), ...options.headers}});
  const result = await response.json();
  if (!response.ok) {
    if (response.status === 401 && state.token) signOut();
    const error = new Error(result.detail || 'Something went wrong. Please try again.');
    error.code = result.code; error.status = response.status; throw error;
  }
  return result;
}
function signOut() {
  state.token = null; state.user = null; state.booking = null; state.selected.clear(); state.pending = null;
  $('account-label').textContent = 'Sign in'; renderSeats(); renderSummary();
}
async function bootstrap() {
  try {
    state.events = await api('/api/events'); renderEvents();
    if (state.events.length) await selectEvent(state.events[0].id);
    else $('events').innerHTML = '<p class="loading">No experiences yet. Start the app with demo data, or create an event through the administrator API.</p>';
  } catch (error) { $('events').innerHTML = '<p class="loading">The booking service is unavailable. Check that the application and database are running, then refresh.</p>'; toast(error.message, true); }
}
function renderEvents() {
  $('events').innerHTML = state.events.map((event, index) => `<button class="event-card ${event.id === state.event?.id ? 'active' : ''}" data-event="${escape(event.id)}" aria-pressed="${event.id === state.event?.id}">
    <span class="event-poster poster-${index % 3}" aria-hidden="true">${['✳','♪','◒'][index % 3]}</span><span class="event-text"><span class="event-genre">LIVE EXPERIENCE · ${escape(event.city.toUpperCase())}</span>
    <span class="event-title">${escape(event.title)}</span><span class="event-date">${escape(date(event.startsAt))} · ${escape(time(event.startsAt))}</span><span class="event-price">${money(event.pricePaise)} <span class="muted">/ person</span></span></span><span class="event-check">${event.id === state.event?.id ? '✓' : ''}</span></button>`).join('');
  $('events').querySelectorAll('[data-event]').forEach(button => button.addEventListener('click', () => selectEvent(button.dataset.event)));
}
async function selectEvent(id) {
  if (state.busy) return;
  if (state.booking?.status === 'HELD' && state.booking.eventId !== id) {
    toast('Your current seats are on hold. Confirm or release them before choosing another experience.'); return;
  }
  state.event = state.events.find(event => event.id === id); state.selected.clear(); state.pending = null;
  if (state.booking?.eventId !== id) state.booking = null;
  $('booking-area').hidden = false; renderEvents(); renderSummary();
  await refreshSeats();
}
async function refreshSeats(quiet = false) {
  if (!state.event || state.busy) return;
  const eventId = state.event.id, requestId = ++state.inventoryRequest;
  try {
    const seats = await api(`/api/events/${eventId}/seats`);
    if (requestId !== state.inventoryRequest || state.event.id !== eventId) return;
    state.seats = seats;
    if (!state.booking) {
      for (const code of [...state.selected]) {
        if (!seats.some(seat => seat.code === code && seat.status === 'AVAILABLE')) { state.selected.delete(code); state.pending = null; if (!quiet) toast('A selected seat has just been taken. Choose another.'); }
      }
    }
    renderSeats(); renderSummary();
    const available = seats.filter(seat => seat.status === 'AVAILABLE').length;
    $('availability-note').textContent = `${available} seats available · updated just now`;
  } catch (error) { if (!quiet) toast(error.message, true); $('availability-note').textContent = 'Availability could not be refreshed'; }
}
function renderSeats() {
  if (!state.event) return;
  const map = $('seat-map'); map.style.gridTemplateColumns = `18px repeat(${state.event.columns}, 1fr) 18px`;
  const rows = [...new Set(state.seats.map(seat => seat.row))];
  map.innerHTML = rows.map(row => `<span class="row-label">${escape(row)}</span>${state.seats.filter(seat => seat.row === row).map(seat => {
    const yours = state.booking && ['HELD','CONFIRMED'].includes(state.booking.status) && state.booking.seats.includes(seat.code);
    const chosen = state.selected.has(seat.code);
    const disabled = state.busy || !!state.booking || seat.status !== 'AVAILABLE';
    return `<button class="seat ${yours ? 'yours' : chosen ? 'selected' : seat.status.toLowerCase()}" data-seat="${escape(seat.code)}" aria-label="Seat ${escape(seat.code)}, ${yours ? 'your booking' : chosen ? 'selected' : seat.status.toLowerCase()}" aria-pressed="${chosen || !!yours}" ${disabled ? 'disabled' : ''}>${seat.column}</button>`;
  }).join('')}<span class="row-label">${escape(row)}</span>`).join('');
  map.querySelectorAll('[data-seat]').forEach(button => button.addEventListener('click', () => {
    const code = button.dataset.seat;
    if (state.selected.has(code)) state.selected.delete(code);
    else if (state.selected.size < 8) state.selected.add(code);
    else { toast('Good company, small group. Select up to 8 seats per booking.'); return; }
    state.pending = null; renderSeats(); renderSummary();
  }));
}
function renderSummary() {
  const event = state.event; if (!event) return;
  const booking = state.booking, selected = booking ? booking.seats : [...state.selected].sort();
  $('summary-title').textContent = event.title;
  $('summary-meta').textContent = `${event.venue}, ${event.city} · ${date(event.startsAt)} · ${time(event.startsAt)}`;
  const status = booking?.status;
  const banner = status && status !== 'HELD' ? `<div class="status-banner ${status.toLowerCase()}">${status === 'CONFIRMED' ? '✓ You’re going. See you there!' : status === 'EXPIRED' ? 'Your hold has expired.' : 'Your booking was cancelled.'}</div>` : '';
  $('summary-content').innerHTML = `${banner}<div class="selection-label"><span>${selected.length ? `${selected.length} seat${selected.length > 1 ? 's' : ''}` : 'Your seats'}</span><span>${money(event.pricePaise)} each</span></div>
    ${selected.length ? `<div class="seat-pills">${selected.map(seat => `<span class="seat-pill">${escape(seat)}</span>`).join('')}</div>` : '<p class="empty-selection">A great night starts with a seat.</p>'}
    ${status === 'HELD' ? '<div class="hold-clock"><span>Reserved just for you</span><strong id="hold-countdown">15:00</strong></div>' : ''}
    ${booking ? `<div class="booking-ref">REF ${escape(booking.id.slice(0, 8).toUpperCase())}</div>` : ''}`;
  $('summary-total').textContent = money(booking ? booking.totalPaise : selected.length * event.pricePaise);
  $('hold-btn').disabled = state.busy || (!booking && !selected.length);
  $('hold-btn').textContent = state.busy ? 'One moment…' : status === 'HELD' ? 'Confirm booking →' : status ? 'Find more seats →' : selected.length ? 'Hold these seats →' : 'Select your seats';
  $('cancel-btn').hidden = !booking || !['HELD', 'CONFIRMED'].includes(status);
  $('cancel-btn').disabled = state.busy; $('cancel-btn').textContent = status === 'CONFIRMED' ? 'Cancel booking' : 'Release seats';
  $('summary-help').textContent = status === 'HELD' ? 'Confirm before your hold expires. Your selection is reserved until then.' : status === 'CONFIRMED' ? 'Your booking is saved in My bookings.' : 'Your seats are held for up to 15 minutes. Take a breath. Make it a plan.';
  tick();
}
function tick() {
  if (state.booking?.status !== 'HELD') return;
  const seconds = Math.max(0, Math.ceil((new Date(state.booking.expiresAt) - Date.now()) / 1000));
  const clock = $('hold-countdown'); if (clock) clock.textContent = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  if (seconds === 0) { state.booking.status = 'EXPIRED'; renderSummary(); refreshSeats(true); }
}
async function holdOrConfirm() {
  if (!state.token) { $('auth-dialog').showModal(); return; }
  if (state.booking && state.booking.status !== 'HELD') { state.booking = null; state.selected.clear(); state.pending = null; await refreshSeats(); return; }
  state.busy = true; renderSummary(); renderSeats();
  try {
    if (state.booking) {
      state.booking = await api(`/api/bookings/${state.booking.id}/confirm`, {method: 'POST'});
      toast('You’re on the list. Your booking is confirmed.');
    } else {
      const body = {eventId: state.event.id, seats: [...state.selected].sort()};
      const snapshot = JSON.stringify(body);
      if (state.pending?.snapshot !== snapshot) state.pending = {snapshot, key: crypto.randomUUID()};
      state.booking = await api('/api/bookings/holds', {method: 'POST', headers: {'Idempotency-Key': state.pending.key}, body: snapshot});
      state.selected.clear();
      toast('Your seats are reserved. Confirm before the timer runs out.');
    }
  } catch (error) {
    toast(error.message || 'Request status unknown. Retry the same selection to recover your hold.', true);
    if (state.booking && ['HOLD_EXPIRED', 'INVALID_STATE', 'LOST_HOLD'].includes(error.code)) {
      try { state.booking = await api(`/api/bookings/${state.booking.id}`); } catch { /* Keep the last known booking until it can be refreshed. */ }
    }
    if (error.code === 'SEAT_UNAVAILABLE') { state.selected.clear(); state.pending = null; }
  } finally { state.busy = false; renderSummary(); await refreshSeats(true); }
}
async function cancelBooking(id = state.booking?.id) {
  if (!id || state.busy) return;
  state.busy = true; renderSummary();
  try {
    const booking = await api(`/api/bookings/${id}/cancel`, {method: 'POST'});
    if (state.booking?.id === id) state.booking = booking;
    toast('Seats released. There’s always another good night.');
    if ($('bookings-dialog').open) await loadBookings();
  } catch (error) { toast(error.message, true); }
  finally { state.busy = false; renderSummary(); await refreshSeats(true); }
}
async function authenticate(demo = false) {
  $('auth-error').textContent = ''; $('auth-submit').disabled = true; $('demo-login').disabled = true;
  const form = new FormData($('auth-form'));
  const body = demo ? {email: 'demo@seatlock.dev', password: 'DemoPass123!'} : {email: form.get('email'), password: form.get('password')};
  if (state.register && !demo) body.displayName = form.get('displayName');
  try {
    const result = await api(`/api/auth/${state.register && !demo ? 'register' : 'login'}`, {method: 'POST', body: JSON.stringify(body)});
    state.token = result.accessToken; state.user = result.user;
    $('account-label').textContent = `${state.user.displayName} · Sign out`;
    $('auth-dialog').close(); $('auth-form').reset(); toast(`You’re in, ${state.user.displayName}. Pick your seats.`);
  } catch (error) { $('auth-error').textContent = error.message; }
  finally { $('auth-submit').disabled = false; $('demo-login').disabled = false; }
}
async function loadBookings() {
  try {
    const bookings = await api('/api/bookings');
    $('bookings-list').innerHTML = bookings.length ? bookings.map(booking => `<article class="booking-item"><div><span class="status-chip chip-${escape(booking.status)}">${escape(booking.status)}</span><h3>${escape(booking.eventTitle)}</h3><p>${escape(booking.venue)} · ${escape(date(booking.startsAt))}<br>Seats ${booking.seats.map(escape).join(', ')} · ${money(booking.totalPaise)}<br>REF ${escape(booking.id.slice(0, 8).toUpperCase())}</p></div><div>${booking.status === 'HELD' ? `<button class="secondary" data-resume="${escape(booking.id)}">Continue →</button>` : ''}${['HELD', 'CONFIRMED'].includes(booking.status) ? `<button class="secondary" data-cancel="${escape(booking.id)}">${booking.status === 'HELD' ? 'Release' : 'Cancel booking'}</button>` : ''}</div></article>`).join('') : '<p class="dialog-subtitle">No plans yet. Your next good night is waiting.</p>';
    $('bookings-list').querySelectorAll('[data-cancel]').forEach(button => button.addEventListener('click', () => cancelBooking(button.dataset.cancel)));
    $('bookings-list').querySelectorAll('[data-resume]').forEach(button => button.addEventListener('click', async () => {
      const booking = bookings.find(item => item.id === button.dataset.resume);
      if (state.booking?.status === 'HELD' && state.booking.id !== booking.id) { toast('Finish or release the current hold first.'); return; }
      state.booking = null;
      if (!state.events.some(event => event.id === booking.eventId)) state.events.push(await api(`/api/events/${booking.eventId}`));
      await selectEvent(booking.eventId); state.booking = booking; renderSeats(); renderSummary(); $('bookings-dialog').close(); $('booking-area').scrollIntoView({behavior: 'smooth'});
    }));
  } catch (error) { toast(error.message, true); }
}
$('hold-btn').addEventListener('click', holdOrConfirm);
$('cancel-btn').addEventListener('click', () => cancelBooking());
$('refresh-seats').addEventListener('click', () => refreshSeats());
$('account-btn').addEventListener('click', () => state.user ? signOut() : $('auth-dialog').showModal());
$('auth-form').addEventListener('submit', event => { event.preventDefault(); authenticate(); });
$('demo-login').addEventListener('click', () => authenticate(true));
$('auth-toggle').addEventListener('click', () => {
  state.register = !state.register; $('name-field').hidden = !state.register;
  $('auth-form').elements.displayName.required = state.register;
  $('auth-form').elements.password.minLength = state.register ? 10 : 1;
  $('auth-title').textContent = state.register ? 'Make yourself at home.' : 'Welcome back.';
  $('auth-submit').textContent = state.register ? 'Create account' : 'Sign in';
  $('auth-toggle').textContent = state.register ? 'Already here? Sign in' : 'New here? Create an account'; $('auth-error').textContent = '';
});
$('bookings-nav').addEventListener('click', async () => {
  if (!state.token) { $('auth-dialog').showModal(); return; }
  $('bookings-list').innerHTML = '<p class="loading">Finding your bookings…</p>'; $('bookings-dialog').showModal(); await loadBookings();
});
$('how-nav').addEventListener('click', () => $('how-dialog').showModal());
document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
setInterval(tick, 1000); setInterval(() => { if (!document.hidden) refreshSeats(true); }, 5000);
bootstrap();
