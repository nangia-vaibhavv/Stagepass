ALTER TABLE booking.sessions ADD COLUMN current_payment_id uuid;
CREATE TABLE booking.payment_attempts (
  payment_id uuid PRIMARY KEY,
  booking_id uuid NOT NULL REFERENCES booking.sessions(id),
  user_id text NOT NULL,
  idempotency_key varchar(128) NOT NULL,
  status text NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED','REFUNDED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_attempts_booking_idx ON booking.payment_attempts(booking_id,created_at DESC);
