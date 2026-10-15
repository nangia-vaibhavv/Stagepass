import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

const authUrl = process.env.AUTH_URL || 'http://localhost:8081';
const bookingUrls = (process.env.BOOKING_URLS || 'http://localhost:8091,http://localhost:8092').split(',');
const email = process.env.STAGEPASS_EMAIL || 'demo@stagepass.local';
const password = process.env.STAGEPASS_PASSWORD || 'stagepass-demo';
const showId = process.env.STAGEPASS_SHOW_ID || 'show-copper';
const paymentUrl = process.env.PAYMENT_URL || 'http://localhost:8090';
const execFileAsync = promisify(execFile);
const projectRoot = fileURLToPath(new URL('../', import.meta.url));

async function paymentServiceToken() {
  if (process.env.PAYMENT_SERVICE_TOKEN) return process.env.PAYMENT_SERVICE_TOKEN;
  try {
    const env = await readFile(new URL('../.env', import.meta.url), 'utf8');
    const value = env.match(/^PAYMENT_SERVICE_TOKEN=(.*)$/m)?.[1]?.trim();
    if (value) return value.replace(/^['"]|['"]$/g, '');
  } catch {}
  return 'local-payment-service-token';
}

async function json(url, options = {}) {
  const response = await fetch(url, options);
  let body;
  try { body = await response.json(); } catch { body = null; }
  return { status: response.status, body };
}

function expectStatus(result, expected, label) {
  assert.equal(result.status, expected, `${label}: expected HTTP ${expected}, got ${result.status}; response=${JSON.stringify(result.body)}`);
}

async function login(address, secret) {
  const result = await json(`${authUrl}/auth/login`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: address, password: secret })
  });
  expectStatus(result, 200, 'login');
  return result.body.accessToken;
}

async function main() {
  const demoToken = await login(email, password);
  const unique = randomUUID();
  const contenderEmail = `phase2-${unique}@example.test`;
  const contenderPassword = `local-${unique}`;
  const registration = await json(`${authUrl}/auth/register`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: contenderEmail, password: contenderPassword, displayName: 'Phase 2 Smoke' })
  });
  expectStatus(registration, 201, 'register contender');
  const contenderToken = await login(contenderEmail, contenderPassword);

  const seatsResult = await json(`${bookingUrls[0]}/shows/${showId}/seats`);
  expectStatus(seatsResult, 200, 'seat availability');
  const available = seatsResult.body.filter(seat => seat.status === 'AVAILABLE');
  assert.ok(available.length > 0, `No available seats for ${showId}; choose another seeded show or reset local data.`);
  const seatId = available[Math.floor(Math.random() * available.length)].seatId;

  const racers = [
    { url: bookingUrls[0], token: demoToken },
    { url: bookingUrls[1], token: contenderToken }
  ];
  const attempts = await Promise.all(racers.map(({ url, token }) => json(`${url}/shows/${showId}/holds`, {
    method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ seatIds: [seatId] })
  })));
  const winners = attempts.map((result, index) => ({ result, racer: racers[index] })).filter(entry => entry.result.status === 201);
  const losers = attempts.filter(result => result.status === 409);
  assert.equal(winners.length, 1, `Expected one hold winner, got ${winners.length}; attempts=${JSON.stringify(attempts)}`);
  assert.equal(losers.length, 1, `Expected one hold conflict, got ${losers.length}.`);
  assert.equal(losers[0].body.code, 'SEAT_HELD');
  const { result: hold, racer } = winners[0];
  const bookingId = hold.body.bookingId;

  const extension = await json(`${racer.url}/bookings/${bookingId}/extend`, {
    method: 'PUT', headers: { authorization: `Bearer ${racer.token}` }
  });
  expectStatus(extension, 200, 'hold extension');
  assert.ok(extension.body.expiresAt);

  const failedKey = `phase2-decline-${unique}`;
  const failed = await json(`${racer.url}/bookings/${bookingId}/confirm`, {
    method: 'POST', headers: { authorization: `Bearer ${racer.token}`, 'content-type': 'application/json', 'Idempotency-Key': failedKey },
    body: JSON.stringify({ paymentMethodToken: 'pm_test_decline' })
  });
  expectStatus(failed, 402, 'declined mock payment');
  assert.equal(failed.body.status, 'PAYMENT_FAILED');
  const failedReplay = await json(`${racer.url}/bookings/${bookingId}/confirm`, {
    method: 'POST', headers: { authorization: `Bearer ${racer.token}`, 'content-type': 'application/json', 'Idempotency-Key': failedKey },
    body: JSON.stringify({ paymentMethodToken: 'pm_test_decline' })
  });
  expectStatus(failedReplay, 402, 'declined payment replay');
  assert.equal(failedReplay.body.paymentId, failed.body.paymentId);

  const successKey = `phase2-success-${unique}`;
  const successRequest = {
    method: 'POST', headers: { authorization: `Bearer ${racer.token}`, 'content-type': 'application/json', 'Idempotency-Key': successKey },
    body: JSON.stringify({ paymentMethodToken: 'pm_test_success' })
  };
  const confirmed = await json(`${racer.url}/bookings/${bookingId}/confirm`, successRequest);
  expectStatus(confirmed, 200, 'successful retry');
  assert.equal(confirmed.body.status, 'CONFIRMED');
  const replay = await json(`${racer.url}/bookings/${bookingId}/confirm`, successRequest);
  expectStatus(replay, 200, 'idempotent replay');
  assert.equal(replay.body.paymentId, confirmed.body.paymentId);
  const otherNode = bookingUrls.find(url => url !== racer.url);
  const crossNodeReplay = await json(`${otherNode}/bookings/${bookingId}/confirm`, successRequest);
  expectStatus(crossNodeReplay, 200, 'cross-node idempotent replay');
  assert.equal(crossNodeReplay.body.paymentId, confirmed.body.paymentId);

  const reused = await json(`${racer.url}/bookings/${bookingId}/confirm`, {
    ...successRequest, body: JSON.stringify({ paymentMethodToken: 'pm_test_decline' })
  });
  expectStatus(reused, 409, 'idempotency key payload mismatch');
  const after = await json(`${bookingUrls[0]}/shows/${showId}/seats`);
  expectStatus(after, 200, 'confirmed seat query');
  assert.equal(after.body.find(seat => seat.seatId === seatId)?.status, 'CONFIRMED');

  const recoverySeat = after.body.find(seat => seat.status === 'AVAILABLE');
  assert.ok(recoverySeat, 'No available seat for booking-node restart recovery.');
  const recoveryHold = await json(`http://localhost:8091/shows/${showId}/holds`, {
    method: 'POST', headers: { authorization: `Bearer ${racer.token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ seatIds: [recoverySeat.seatId] })
  });
  expectStatus(recoveryHold, 201, 'restart-recovery hold');
  await execFileAsync('docker', ['compose', 'restart', 'booking-node-1'], { cwd: projectRoot });
  const healthDeadline = Date.now() + 30000;
  let nodeReady = false;
  while (Date.now() < healthDeadline) {
    try {
      const response = await fetch('http://localhost:8091/health');
      if (response.ok) { nodeReady = true; break; }
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  assert.ok(nodeReady, 'booking-node-1 did not recover after restart.');
  const recoveredSeat = await json(`${bookingUrls[1]}/shows/${showId}/seats`);
  expectStatus(recoveredSeat, 200, 'recovered hold visibility');
  assert.equal(recoveredSeat.body.find(seat => seat.seatId === recoverySeat.seatId)?.status, 'HELD');
  const recoveredConfirmation = await json(`http://localhost:8091/bookings/${recoveryHold.body.bookingId}/confirm`, {
    method: 'POST', headers: { authorization: `Bearer ${racer.token}`, 'content-type': 'application/json', 'Idempotency-Key': `phase2-restart-${unique}` },
    body: JSON.stringify({ paymentMethodToken: 'pm_test_success' })
  });
  expectStatus(recoveredConfirmation, 200, 'confirm after node restart');

  const latestSeats = await json(`${bookingUrls[0]}/shows/${showId}/seats`);
  expectStatus(latestSeats, 200, 'post-recovery availability');
  const lateSeat = latestSeats.body.find(seat => seat.status === 'AVAILABLE');
  assert.ok(lateSeat, `No second available seat for the late-callback scenario.`);
  const lateHold = await json(`${racer.url}/shows/${showId}/holds`, {
    method: 'POST', headers: { authorization: `Bearer ${racer.token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ seatIds: [lateSeat.seatId] })
  });
  expectStatus(lateHold, 201, 'late callback seat hold');
  const lateKey = `phase2-late-${unique}`;
  const latePayment = await json(`${racer.url}/bookings/${lateHold.body.bookingId}/confirm`, {
    method: 'POST', headers: { authorization: `Bearer ${racer.token}`, 'content-type': 'application/json', 'Idempotency-Key': lateKey },
    body: JSON.stringify({ paymentMethodToken: 'pm_test_late_success' })
  });
  expectStatus(latePayment, 202, 'asynchronous mock payment');
  const cancelled = await json(`${racer.url}/bookings/${lateHold.body.bookingId}/cancel`, {
    method: 'POST', headers: { authorization: `Bearer ${racer.token}` }
  });
  expectStatus(cancelled, 200, 'cancel pending payment');
  assert.equal(cancelled.body.status, 'CANCELLED');

  const paymentToken = await paymentServiceToken();
  const callbackDeadline = Date.now() + 15000;
  let paymentStatus;
  let paymentDetails;
  while (Date.now() < callbackDeadline) {
    const payment = await json(`${paymentUrl}/internal/payments/${latePayment.body.paymentId}`, {
      headers: { 'X-Service-Token': paymentToken }
    });
    expectStatus(payment, 200, 'payment callback status');
    paymentDetails = payment.body;
    paymentStatus = paymentDetails.status;
    if (paymentStatus === 'REFUNDED' && paymentDetails.callbackDeliveredAt) break;
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  assert.equal(paymentStatus, 'REFUNDED', `Expected late success to be compensated, got ${paymentStatus}.`);
  assert.ok(paymentDetails.callbackAttempts > 0);
  assert.ok(paymentDetails.callbackDeliveredAt);
  const lateReplay = await json(`${racer.url}/bookings/${lateHold.body.bookingId}/confirm`, {
    method: 'POST', headers: { authorization: `Bearer ${racer.token}`, 'content-type': 'application/json', 'Idempotency-Key': lateKey },
    body: JSON.stringify({ paymentMethodToken: 'pm_test_late_success' })
  });
  expectStatus(lateReplay, 409, 'final callback response replay');
  assert.equal(lateReplay.body.status, 'CANCELLED');

  console.log(JSON.stringify({
    result: 'PASS', showId, seatId,
    simultaneousHold: 'one success, one conflict',
    holdExtension: 'passed', declinedPaymentRetry: 'passed', durableIdempotencyReplay: 'passed across nodes',
    activeHoldRecovery: 'passed after booking-node restart',
    idempotencyPayloadMismatch: 'rejected', staleSuccessCompensation: 'refunded after cancellation', bookingId
  }, null, 2));
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
