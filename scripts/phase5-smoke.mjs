import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const gateway = process.env.GATEWAY_URL || 'http://localhost:8080';
const showId = process.env.STAGEPASS_SHOW_ID || 'show-boundary';
const adminEmail = process.env.STAGEPASS_EMAIL || 'demo@stagepass.local';
const adminPassword = process.env.STAGEPASS_PASSWORD || 'stagepass-demo';

async function request(path, options = {}) {
  const response = await fetch(`${gateway}${path}`, options);
  const body = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
  return { response, body };
}

async function login(email, password) {
  const result = await request('/api/auth/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email, password })
  });
  assert.equal(result.response.status, 200, `login failed: ${JSON.stringify(result.body)}`);
  return result.body.accessToken;
}

const adminToken = await login(adminEmail, adminPassword);
const adminAuth = { Authorization: `Bearer ${adminToken}` };
const seats = await request(`/api/booking/shows/${showId}/seats`);
assert.equal(seats.response.status, 200);
const freeSeats = seats.body.filter(item => item.status === 'AVAILABLE').slice(0, 3);
assert.equal(freeSeats.length, 3, `Expected three free seats for ${showId}.`);

const presenceWrite = await request(`/api/booking/shows/${showId}/presence`, { method: 'POST', headers: adminAuth });
assert.equal(presenceWrite.response.status, 200);
assert.ok(presenceWrite.body.activeViewers >= 1);
const presenceRead = await request(`/api/booking/shows/${showId}/presence`);
assert.equal(presenceRead.response.status, 200);
assert.ok(presenceRead.body.activeViewers >= 1);

const mode = await request(`/api/booking/admin/shows/${showId}/waitlist-mode`, {
  method: 'PUT', headers: { ...adminAuth, 'content-type': 'application/json' }, body: JSON.stringify({ mode: 'ROUND_ROBIN' })
});
assert.equal(mode.response.status, 200, `admin waitlist mode update failed: ${JSON.stringify(mode.body)}`);

const controller = new AbortController();
const stream = await fetch(`${gateway}/api/booking/shows/${showId}/events`, { signal: controller.signal, headers: { Accept: 'text/event-stream' } });
assert.equal(stream.status, 200, `SSE returned ${stream.status}`);
assert.match(stream.headers.get('content-type') || '', /text\/event-stream/);
const reader = stream.body.getReader();
const decoder = new TextDecoder();
let sseConnected = false;
let eventText = '';
const connectDeadline = Date.now() + 6000;
while (Date.now() < connectDeadline && !sseConnected) {
  const timeout = new Promise(resolve => setTimeout(() => resolve(null), connectDeadline - Date.now()));
  const item = await Promise.race([reader.read(), timeout]);
  if (!item) break;
  eventText += decoder.decode(item.value || new Uint8Array());
  sseConnected = eventText.includes('connected');
}
assert.ok(sseConnected, 'SSE did not send its initial connected event.');

const holderEmail = `phase5-holder-${randomUUID()}@example.test`;
const holderPassword = `Local-${randomUUID()}-pass`;
const registered = await request('/api/auth/register', {
  method: 'POST', headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ email: holderEmail, password: holderPassword, displayName: 'Phase 5 Holder' })
});
assert.equal(registered.response.status, 201, `test holder registration failed: ${JSON.stringify(registered.body)}`);
const holderToken = await login(holderEmail, holderPassword);
const holder = await request(`/api/booking/shows/${showId}/holds`, {
  method: 'POST', headers: { Authorization: `Bearer ${holderToken}`, 'content-type': 'application/json' },
  body: JSON.stringify({ seatIds: freeSeats.map(seat => seat.seatId) })
});
assert.equal(holder.response.status, 201, `test hold failed: ${JSON.stringify(holder.body)}`);

const joined = await request(`/api/booking/shows/${showId}/waitlist`, {
  method: 'POST', headers: { ...adminAuth, 'content-type': 'application/json' },
  body: JSON.stringify({ quantity: 2 })
});
assert.equal(joined.response.status, 201, `waitlist join failed: ${JSON.stringify(joined.body)}`);
assert.equal(joined.body.offeredSeatIds.length, 0, 'waitlist should wait while all test seats are held');
const position = await request(`/api/booking/shows/${showId}/waitlist/me`, { headers: adminAuth });
assert.equal(position.response.status, 200);
assert.ok(position.body.position >= 1);

const released = await request(`/api/booking/bookings/${holder.body.bookingId}/cancel`, {
  method: 'POST', headers: { Authorization: `Bearer ${holderToken}` }
});
assert.equal(released.response.status, 200, `test hold cancellation failed: ${JSON.stringify(released.body)}`);

let sseOfferEvent = false;
const eventDeadline = Date.now() + 6000;
while (Date.now() < eventDeadline && !sseOfferEvent) {
  const timeout = new Promise(resolve => setTimeout(() => resolve(null), eventDeadline - Date.now()));
  const item = await Promise.race([reader.read(), timeout]);
  if (!item) break;
  eventText += decoder.decode(item.value || new Uint8Array());
  sseOfferEvent = eventText.includes('waitlist.offered');
}
assert.ok(sseOfferEvent, 'SSE did not deliver the waitlist.offered domain event.');

let offeredHold;
const offerDeadline = Date.now() + 6000;
while (Date.now() < offerDeadline) {
  const current = await request(`/api/booking/shows/${showId}/waitlist/me`, { headers: adminAuth });
  if (current.response.status === 200 && current.body.offeredSeatIds.length === 2) {
    const accepted = await request(`/api/booking/shows/${showId}/waitlist/accept`, { method: 'POST', headers: adminAuth });
    if (accepted.response.status === 201) { offeredHold = accepted.body; break; }
    assert.equal(accepted.response.status, 409, `unexpected offer response: ${JSON.stringify(accepted.body)}`);
  }
  await new Promise(resolve => setTimeout(resolve, 250));
}
assert.ok(offeredHold, 'Two-seat waitlist offer was not created after the three held seats were released.');
const finishOffer = await request(`/api/booking/bookings/${offeredHold.bookingId}/cancel`, { method: 'POST', headers: adminAuth });
assert.equal(finishOffer.response.status, 200, 'could not cancel accepted waitlist test hold');
await reader.cancel();
controller.abort();

console.log(JSON.stringify({
  result: 'PASS', showId, requestedSeats: 2,
  presence: `active viewers=${presenceRead.body.activeViewers}`,
  waitlist: `joined at position ${position.body.position}`,
  adminMode: mode.body.mode,
  releasedSeatOffer: 'created and accepted; temporary hold cancelled',
  sse: 'gateway stream received waitlist.offered'
}, null, 2));
