CREATE TABLE booking.outbox_events (
  event_id uuid PRIMARY KEY,
  event_type text NOT NULL,
  aggregate_id text NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  schema_version integer NOT NULL DEFAULT 1,
  data jsonb NOT NULL,
  published_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbox_events_unpublished_idx ON booking.outbox_events(created_at) WHERE published_at IS NULL;
