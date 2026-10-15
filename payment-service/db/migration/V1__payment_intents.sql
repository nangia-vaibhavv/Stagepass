CREATE TABLE payment.intents (
  id uuid PRIMARY KEY,
  booking_id uuid NOT NULL,
  user_id text NOT NULL,
  amount_cents integer NOT NULL CHECK (amount_cents > 0),
  currency char(3) NOT NULL,
  method_token text NOT NULL,
  status text NOT NULL CHECK (status IN ('SUCCEEDED','FAILED','REFUNDED')),
  failure_code text,
  request_hash varchar(64) NOT NULL,
  idempotency_key varchar(128) NOT NULL UNIQUE,
  refund_key varchar(128),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_intents_booking_idx ON payment.intents(booking_id);
