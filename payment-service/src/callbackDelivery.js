export function createCallbackDelivery(pool, serviceToken, callbackRetryMs) {
  let callbackTickRunning = false;

  return async function deliverDueCallbacks() {
    if (callbackTickRunning) return;
    callbackTickRunning = true;
    try {
      const { rows } = await pool.query(
        `WITH due AS (
           SELECT id FROM payment.intents
           WHERE callback_url IS NOT NULL AND callback_delivered_at IS NULL AND callback_due_at<=now()
             AND status IN ('PENDING','SUCCEEDED')
           ORDER BY callback_due_at FOR UPDATE SKIP LOCKED LIMIT 10
         )
         UPDATE payment.intents intent SET status='SUCCEEDED',callback_attempts=intent.callback_attempts+1,
           callback_due_at=now()+($1::bigint*interval '1 millisecond')
         FROM due WHERE intent.id=due.id
         RETURNING intent.id,intent.booking_id,intent.callback_url`, [callbackRetryMs]
      );
      for (const intent of rows) {
        try {
          const response = await fetch(intent.callback_url, {
            method: 'POST', headers: { 'content-type': 'application/json', 'X-Service-Token': serviceToken },
            body: JSON.stringify({ paymentId: intent.id, bookingId: intent.booking_id, status: 'SUCCEEDED' }),
            signal: AbortSignal.timeout(2500)
          });
          if (!response.ok) throw new Error(`callback returned HTTP ${response.status}`);
          await pool.query('UPDATE payment.intents SET callback_delivered_at=now() WHERE id=$1', [intent.id]);
        } catch (error) {
          console.error('payment callback delivery failed', { paymentId: intent.id, message: error.message });
        }
      }
    } catch (error) { console.error('payment callback worker failed', error.message); }
    finally { callbackTickRunning = false; }
  };
}
