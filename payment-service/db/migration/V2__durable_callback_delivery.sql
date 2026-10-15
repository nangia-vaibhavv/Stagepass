ALTER TABLE payment.intents DROP CONSTRAINT IF EXISTS intents_status_check;
ALTER TABLE payment.intents ADD CONSTRAINT payment_intents_status_check
  CHECK (status IN ('PENDING','SUCCEEDED','FAILED','REFUNDED'));
ALTER TABLE payment.intents ADD COLUMN callback_url text;
ALTER TABLE payment.intents ADD COLUMN callback_due_at timestamptz;
ALTER TABLE payment.intents ADD COLUMN callback_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE payment.intents ADD COLUMN callback_delivered_at timestamptz;
CREATE INDEX payment_callback_due_idx ON payment.intents(callback_due_at)
  WHERE callback_delivered_at IS NULL AND callback_url IS NOT NULL;
