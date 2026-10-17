INSERT INTO catalog.venues(id, name, city, address)
VALUES ('venue-boundary', 'Boundary Hall', 'Brooklyn', '1 Queue Street')
ON CONFLICT (id) DO NOTHING;

INSERT INTO catalog.shows(id, venue_id, artist, title, starts_at, sale_starts_at, currency, status)
VALUES ('show-boundary', 'venue-boundary', 'StagePass Demo', 'Boundary Waitlist Demo', '2027-08-15T20:00:00Z', NULL, 'USD', 'on_sale')
ON CONFLICT (id) DO NOTHING;

INSERT INTO catalog.sections(id, venue_id, name, price_cents)
VALUES ('sec-boundary-ga', 'venue-boundary', 'General Admission', 5000)
ON CONFLICT (id) DO NOTHING;

INSERT INTO catalog.seats(id, section_id, row_label, seat_number)
SELECT 'sec-boundary-ga-' || row_label || '-' || seat_number, 'sec-boundary-ga', row_label, seat_number
FROM (VALUES ('A'), ('B'), ('C')) AS rows(row_label)
CROSS JOIN generate_series(1, 20) AS numbers(seat_number)
ON CONFLICT (id) DO NOTHING;
