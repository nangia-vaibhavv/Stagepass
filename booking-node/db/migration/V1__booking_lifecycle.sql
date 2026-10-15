CREATE TABLE booking.sessions (
  id uuid PRIMARY KEY,
  show_id text NOT NULL,
  user_id text NOT NULL,
  state text NOT NULL CHECK (state IN ('HELD','PAYMENT_PENDING','PAYMENT_FAILED','CONFIRMED','EXPIRED','CANCELLED')),
  hold_token uuid NOT NULL,
  expires_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sessions_user_created_idx ON booking.sessions(user_id, created_at DESC);

CREATE TABLE booking.session_seats (
  booking_id uuid NOT NULL REFERENCES booking.sessions(id) ON DELETE CASCADE,
  show_id text NOT NULL,
  seat_id text NOT NULL,
  price_cents integer NOT NULL CHECK (price_cents >= 0),
  PRIMARY KEY (booking_id, seat_id)
);
CREATE INDEX session_seats_show_idx ON booking.session_seats(show_id, seat_id);

CREATE TABLE booking.confirmed_seats (
  show_id text NOT NULL,
  seat_id text NOT NULL,
  booking_id uuid NOT NULL REFERENCES booking.sessions(id),
  confirmed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (show_id, seat_id)
);
CREATE INDEX confirmed_seats_booking_idx ON booking.confirmed_seats(booking_id);

CREATE TABLE booking.idempotency_records (
  user_id text NOT NULL,
  idempotency_key varchar(128) NOT NULL,
  request_hash varchar(64) NOT NULL,
  status text NOT NULL CHECK (status IN ('PROCESSING','COMPLETED')),
  response_json jsonb,
  http_status integer,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, idempotency_key)
);

CREATE TABLE booking.transitions (
  id bigserial PRIMARY KEY,
  booking_id uuid NOT NULL REFERENCES booking.sessions(id),
  from_state text,
  to_state text NOT NULL,
  reason text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
