CREATE TABLE catalog.venues (
  id text PRIMARY KEY,
  name text NOT NULL,
  city text NOT NULL,
  address text NOT NULL
);
CREATE TABLE catalog.shows (
  id text PRIMARY KEY,
  venue_id text NOT NULL REFERENCES catalog.venues(id),
  artist text NOT NULL,
  title text NOT NULL,
  starts_at timestamptz NOT NULL,
  sale_starts_at timestamptz,
  currency char(3) NOT NULL DEFAULT 'USD',
  status text NOT NULL DEFAULT 'scheduled' CHECK (status IN ('scheduled','on_sale','completed','cancelled'))
);
CREATE TABLE catalog.sections (
  id text PRIMARY KEY,
  venue_id text NOT NULL REFERENCES catalog.venues(id),
  name text NOT NULL,
  price_cents integer NOT NULL CHECK (price_cents >= 0)
);
CREATE TABLE catalog.seats (
  id text PRIMARY KEY,
  section_id text NOT NULL REFERENCES catalog.sections(id),
  row_label text NOT NULL,
  seat_number integer NOT NULL CHECK (seat_number > 0),
  UNIQUE(section_id, row_label, seat_number)
);

INSERT INTO catalog.venues (id,name,city,address) VALUES
  ('venue-redwood','Redwood Amphitheatre','San Francisco','100 Music Way'),
  ('venue-foundry','The Foundry Hall','Brooklyn','25 River Street'),
  ('venue-orbit','Orbit Arena','Chicago','800 Lake Shore Drive')
ON CONFLICT DO NOTHING;
INSERT INTO catalog.shows (id,venue_id,artist,title,starts_at,sale_starts_at,currency,status) VALUES
  ('show-aurora','venue-redwood','Aurora Lane','Summer Lights Tour','2027-06-18T19:30:00Z','2026-11-01T17:00:00Z','USD','scheduled'),
  ('show-copper','venue-foundry','The Copper Lines','Live at The Foundry','2027-05-02T20:00:00Z',NULL,'USD','on_sale'),
  ('show-midnight','venue-orbit','Midnight Arcade','After Hours','2027-07-09T20:30:00Z',NULL,'USD','on_sale')
ON CONFLICT DO NOTHING;
INSERT INTO catalog.sections(id,venue_id,name,price_cents) VALUES
  ('sec-redwood-floor','venue-redwood','Floor',12500),('sec-redwood-balcony','venue-redwood','Balcony',7500),
  ('sec-foundry-ga','venue-foundry','General Admission',6500),('sec-foundry-box','venue-foundry','Box Seats',11000),
  ('sec-orbit-floor','venue-orbit','Floor',14000),('sec-orbit-upper','venue-orbit','Upper Level',8000)
ON CONFLICT DO NOTHING;
INSERT INTO catalog.seats(id,section_id,row_label,seat_number)
SELECT section.id || '-' || row_label || '-' || n, section.id, row_label, n
FROM catalog.sections section
CROSS JOIN (VALUES ('A'),('B'),('C')) rows(row_label)
CROSS JOIN generate_series(1,20) n
ON CONFLICT DO NOTHING;
