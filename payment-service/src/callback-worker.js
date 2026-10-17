import pg from 'pg';
import { createCallbackDelivery } from './callbackDelivery.js';

const { Pool } = pg;
const serviceToken = process.env.PAYMENT_SERVICE_TOKEN;
if (!serviceToken || serviceToken.length < 24) throw new Error('PAYMENT_SERVICE_TOKEN must contain at least 24 characters');
const callbackRetryMs = Number(process.env.PAYMENT_CALLBACK_RETRY_MS || 2000);
const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 2 });
const deliverDueCallbacks = createCallbackDelivery(pool, serviceToken, callbackRetryMs);
const timer = setInterval(deliverDueCallbacks, Number(process.env.PAYMENT_CALLBACK_POLL_MS || 500));
timer.unref();
console.log('payment callback worker started');

const shutdown = async () => { clearInterval(timer); await pool.end(); process.exit(0); };
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
