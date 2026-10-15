import { createHash } from 'node:crypto';

export function canonicalDigest(value) {
  return createHash('sha256').update(JSON.stringify({
    bookingId: value.bookingId,
    userId: value.userId,
    amountCents: value.amountCents,
    currency: value.currency,
    methodToken: value.methodToken,
    callbackUrl: value.callbackUrl || null
  })).digest('hex');
}

export function paymentDecision(methodToken) {
  if (methodToken === 'pm_test_success') return { status: 'SUCCEEDED', failureCode: null };
  if (methodToken === 'pm_test_decline') return { status: 'FAILED', failureCode: 'CARD_DECLINED' };
  if (methodToken === 'pm_test_late_success') return { status: 'PENDING', failureCode: null };
  return null;
}
