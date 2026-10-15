import test from 'node:test';
import assert from 'node:assert/strict';
import { canonicalDigest, paymentDecision } from '../src/logic.js';

test('mock payment tokens produce deterministic success and decline results', () => {
  assert.deepEqual(paymentDecision('pm_test_success'), { status: 'SUCCEEDED', failureCode: null });
  assert.deepEqual(paymentDecision('pm_test_decline'), { status: 'FAILED', failureCode: 'CARD_DECLINED' });
  assert.deepEqual(paymentDecision('pm_test_late_success'), { status: 'PENDING', failureCode: null });
  assert.equal(paymentDecision('unknown'), null);
});

test('payment request digest is stable and binds every payment field', () => {
  const payment = { bookingId: 'b-1', userId: 'u-1', amountCents: 6500, currency: 'USD', methodToken: 'pm_test_success' };
  assert.equal(canonicalDigest(payment), canonicalDigest({ ...payment }));
  assert.notEqual(canonicalDigest(payment), canonicalDigest({ ...payment, amountCents: 6501 }));
  assert.notEqual(canonicalDigest(payment), canonicalDigest({ ...payment, methodToken: 'pm_test_decline' }));
});
