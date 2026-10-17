import express from 'express';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import pg from 'pg';
import { canonicalDigest, paymentDecision } from './logic.js';
import { createCallbackDelivery } from './callbackDelivery.js';

const { Pool } = pg;
const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 10 });
const app = express();
const port = Number(process.env.PORT || 8090);
const serviceToken = process.env.PAYMENT_SERVICE_TOKEN;
const callbackDelayMs = Number(process.env.MOCK_LATE_CALLBACK_DELAY_MS || 5000);
const callbackRetryMs = Number(process.env.PAYMENT_CALLBACK_RETRY_MS || 2000);
if (!serviceToken || serviceToken.length < 24) throw new Error('PAYMENT_SERVICE_TOKEN must contain at least 24 characters');
app.use(express.json({ limit: '16kb' }));
const internalPrefix = '/_internal/payment';
app.use((req, _res, next) => {
  if (process.env.VERCEL_URL && (req.path === internalPrefix || req.path.startsWith(`${internalPrefix}/`)))
    req.url = req.url.slice(internalPrefix.length) || '/';
  next();
});

function authorize(req, res, next) {
  const received = req.get('X-Service-Token') || '';
  const a = Buffer.from(received);
  const b = Buffer.from(serviceToken);
  if (a.length !== b.length || !timingSafeEqual(a, b)) return res.status(401).json({ code: 'UNAUTHORIZED_SERVICE' });
  next();
}

app.get('/health', async (_req, res) => {
  try { await pool.query('SELECT 1'); res.json({ status: 'ok', service: 'payment' }); }
  catch { res.status(503).json({ status: 'unavailable' }); }
});

app.post('/internal/payments', authorize, async (req, res, next) => {
  const { bookingId, userId, amountCents, currency, methodToken } = req.body || {};
  const callbackUrl = typeof req.body?.callbackUrl === 'string' ? req.body.callbackUrl : null;
  const idempotencyKey = req.get('Idempotency-Key') || '';
  const decision = paymentDecision(methodToken);
  if (!idempotencyKey || idempotencyKey.length > 128 || !bookingId || !userId ||
      !Number.isSafeInteger(amountCents) || amountCents < 1 || !/^[A-Z]{3}$/.test(currency || '') || !decision ||
      (decision.status === 'PENDING' && !callbackUrl)) {
    return res.status(400).json({ code: 'INVALID_PAYMENT_REQUEST' });
  }
  if (callbackUrl) {
    try { if (!['http:', 'https:'].includes(new URL(callbackUrl).protocol)) throw new Error('invalid protocol'); }
    catch { return res.status(400).json({ code: 'INVALID_CALLBACK_URL' }); }
  }
  const requestHash = canonicalDigest({ bookingId, userId, amountCents, currency, methodToken, callbackUrl });
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const id = randomUUID();
    await client.query(
      `INSERT INTO payment.intents(id,booking_id,user_id,amount_cents,currency,method_token,status,failure_code,request_hash,idempotency_key,callback_url,callback_due_at)
       VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,CASE WHEN $7='PENDING' THEN now()+($12::bigint*interval '1 millisecond') ELSE NULL END)
       ON CONFLICT(idempotency_key) DO NOTHING`,
      [id, bookingId, userId, amountCents, currency, methodToken, decision.status, decision.failureCode, requestHash, idempotencyKey,
        callbackUrl, callbackDelayMs]
    );
    const { rows } = await client.query('SELECT * FROM payment.intents WHERE idempotency_key=$1 FOR UPDATE', [idempotencyKey]);
    const intent = rows[0];
    if (intent.request_hash !== requestHash) {
      await client.query('ROLLBACK');
      return res.status(409).json({ code: 'IDEMPOTENCY_KEY_REUSED' });
    }
    await client.query('COMMIT');
    res.status(intent.status === 'FAILED' ? 402 : intent.status === 'PENDING' ? 202 : 201).json({
      paymentId: intent.id, bookingId: intent.booking_id, status: intent.status,
      failureCode: intent.failure_code, amountCents: intent.amount_cents, currency: intent.currency
    });
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    next(error);
  } finally { client.release(); }
});

app.get('/internal/payments/:paymentId', authorize, async (req, res, next) => {
  try {
    const { rows } = await pool.query(
      'SELECT id,status,callback_attempts,callback_delivered_at FROM payment.intents WHERE id=$1', [req.params.paymentId]
    );
    if (!rows[0]) return res.status(404).json({ code: 'PAYMENT_NOT_FOUND' });
    res.json({ paymentId: rows[0].id, status: rows[0].status, callbackAttempts: rows[0].callback_attempts,
      callbackDeliveredAt: rows[0].callback_delivered_at });
  } catch (error) { next(error); }
});

app.post('/internal/payments/:paymentId/refund', authorize, async (req, res, next) => {
  const refundKey = req.get('Idempotency-Key') || '';
  if (!refundKey || refundKey.length > 128) return res.status(400).json({ code: 'IDEMPOTENCY_KEY_REQUIRED' });
  try {
    const { rows } = await pool.query(
      `UPDATE payment.intents SET status='REFUNDED',refund_key=$2,updated_at=now()
       WHERE id=$1 AND status IN ('SUCCEEDED','REFUNDED') AND (refund_key IS NULL OR refund_key=$2)
       RETURNING id,status,refund_key`, [req.params.paymentId, refundKey]
    );
    if (!rows[0]) return res.status(409).json({ code: 'PAYMENT_NOT_REFUNDABLE' });
    res.json({ paymentId: rows[0].id, status: rows[0].status });
  } catch (error) { next(error); }
});

app.use((error, _req, res, _next) => {
  console.error('payment request failed', error.message);
  res.status(500).json({ code: 'INTERNAL_ERROR' });
});

const callbackTimer = process.env.RUN_CALLBACK_WORKER === 'false'
  ? null
  : setInterval(createCallbackDelivery(pool, serviceToken, callbackRetryMs), Number(process.env.PAYMENT_CALLBACK_POLL_MS || 500));
callbackTimer?.unref();

const server = app.listen(port, () => console.log(`payment service listening on ${port}`));
const shutdown = async () => { if (callbackTimer) clearInterval(callbackTimer); server.close(); await pool.end(); };
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
