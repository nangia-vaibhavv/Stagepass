CREATE TABLE booking.show_ownership (
    show_id text PRIMARY KEY,
    owner_node_id text NOT NULL,
    epoch bigint NOT NULL CHECK (epoch > 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);
